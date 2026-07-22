// 認証・アクセス制御に関するパッケージ
package com.izumacha.expensetracker.security;

// JSON 応答を書き出すための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;
// ログ出力に使うロガー本体
import org.slf4j.Logger;
// ロガーを生成するファクトリ
import org.slf4j.LoggerFactory;
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
// {status, message} 形式のエラー応答を書き出す共通ユーティリティ
import com.izumacha.expensetracker.web.ApiErrorWriter;
// サーブレットのフィルタ連鎖を表す型
import jakarta.servlet.FilterChain;
// サーブレット例外型
import jakarta.servlet.ServletException;
// HTTP リクエストを表す型
import jakarta.servlet.http.HttpServletRequest;
// HTTP レスポンスを表す型
import jakarta.servlet.http.HttpServletResponse;
// 入出力例外型
import java.io.IOException;
// 同名ヘッダの全行を順に読み取るための列挙型（X-Forwarded-For が複数行あるケースに使う）
import java.util.Enumeration;
// IP 形式検証に使う正規表現パターン
import java.util.regex.Pattern;
// 送信元ごとのカウンタを保持するスレッドセーフなマップ
import java.util.concurrent.ConcurrentHashMap;
// 単位時間内のカウントをスレッドセーフに数えるための整数
import java.util.concurrent.atomic.AtomicInteger;
// 最後に警告・掃除を実行したウィンドウ番号をスレッドセーフに保持するための長整数
import java.util.concurrent.atomic.AtomicLong;
// プロパティ値を注入するアノテーション
import org.springframework.beans.factory.annotation.Value;
// Bean の優先順位（フィルタの適用順）を指定するアノテーション
import org.springframework.core.annotation.Order;
// 「最優先」を表す定数 Ordered.HIGHEST_PRECEDENCE を参照する
import org.springframework.core.Ordered;
// HTTP ステータスを表す列挙
import org.springframework.http.HttpStatus;
// Spring に管理させるためのコンポーネント宣言
import org.springframework.stereotype.Component;
// 1 リクエストにつき一度だけ実行されることを保証するフィルタ基底
import org.springframework.web.filter.OncePerRequestFilter;

// 送信元 IP ごとに固定ウィンドウでリクエスト数を制限する簡易レート制限フィルタ（§9 公開エンドポイントを保護する）。
// 外部ライブラリを足さず、メモリ使用も上限を設けて DoS（リソース枯渇）の起点を抑える。
//
// 【前提: 単一インスタンス限定】カウンタ（下記 counters）は JVM ローカルの ConcurrentHashMap で
// 共有ストア（Redis 等）を持たない。このため複数レプリカを水平スケールで並べて配備すると、
// 実効的なレート上限が「capacity × レプリカ数」まで緩んでしまう（各レプリカが独立にカウントするため）。
// MVP の単一インスタンス運用を前提とした設計であり、水平スケールを導入する際は本フィルタを
// 共有ストア方式へ置き換えること。
@Component
// 認証より手前で先に流量を絞る（無認証の大量アクセスでも認証処理に到達させない）。
// Spring Boot は Spring Security のフィルタチェーンを既定で order = -100（SecurityProperties.Filter の既定値）
// で登録するため、以前の @Order(1) では 1 > -100 となり、実際には Security のフィルタチェーンより
// 「後」に実行されていた（意図と逆）。フィルタは order の昇順に実行されるため、Security より確実に先に
// 実行させるには、指定可能な最小値である Ordered.HIGHEST_PRECEDENCE を使う必要がある。
// 現状は permitAll() のため実害は小さいが、将来 JWT/OAuth2 等の実認証を追加した際に、認証処理（コスト有り）
// より後にしかレート制限がかからず、無認証の大量アクセスから認証処理を守れなくなる（§9 の設計意図を満たせない）。
//
// 【HIGHEST_PRECEDENCE（最優先）にする理由】
// RequestBodySizeLimitFilter（web パッケージ）も同じ「Security より確実に先に実行する」目的を
// 持つが、同一の @Order 値を持つフィルタ同士の相対順序は Spring が保証しない。そこで本フィルタを
// 指定可能な最小値（最優先）にし、サイズ上限フィルタ側を 1 つ後ろ（HIGHEST_PRECEDENCE + 1）へ
// ずらすことで、常に「レート制限 → 本文サイズの上限チェック」の順で実行されるようにする。
// この順序により、サイズ超過のリクエストも必ず送信元のレート制限枠を消費してから 413 で
// 弾かれる。逆順（サイズ上限が先）だと、413 の早期リジェクトがレート制限のカウントより先に
// 行われるため、巨大な本文を送り続ける攻撃者が一切レート制限されない抜け穴になっていた。
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    // このクラスのログ出力に使うロガー
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // 追跡する送信元 IP の最大数（無制限に増やしてメモリを枯渇させないための上限）。
    // 同一パッケージのテストから上限値を参照できるようパッケージプライベートにしている
    static final int MAX_TRACKED_CLIENTS = 10_000;

    // 追跡テーブルが満杯のとき、未追跡の新規送信元をまとめて数えるための共有バケットのキー。
    // IP アドレスには使われない文字（アンダースコア等）で構成し、実クライアントのキーと衝突しないようにする
    private static final String OVERFLOW_KEY = "__overflow__";

    // IPv4 アドレスの形式を確認するパターン（例: 192.168.0.1）。
    // ネストした量指定子を使わないため ReDoS が起きない（§9）。
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");

    // IPv6 アドレスの形式を確認するパターン（例: ::1、2001:db8::1）。
    // 16 進数とコロンのみで構成され、コロンを最低 1 つ含み、最長 39 文字以内に収まる形式を許容する。
    // 【コロンを必須にする理由】旧パターン（^[0-9a-fA-F:]{2,39}$）はコロンを含まない 16 進文字
    // だけの文字列（例: "deadbeef"）まで IPv6 と誤判定していた。IPv6 表記には必ず ':' が現れる
    // ため、先頭の先読み (?=.*:) で「どこかに ':' がある」ことを必須とし、IP でない任意の
    // 16 進風文字列がレート制限キーとして採用されるのを防ぐ。先読みは 1 パスの走査だけで
    // ネストした量指定子を持たず、入力長も looksLikeIp 側で 39 文字に制限済みのため ReDoS は起きない（§9）。
    // 【意図的な寛容設計】完全な IPv6 構文検証（グループ数・二重コロン規則等）をここで行うと
    // 正規表現が複雑になり ReDoS のリスクが高まる（CLAUDE.md §9）。そのため形式チェックを
    // 「16 進数とコロンのみ＋コロン必須」に留めている。`:::` のような非合法な IPv6 風文字列が
    // 通過してもレート制限キーとして格納されるだけであり、攻撃者にとって有利な挙動にはならない
    // （攻撃者は最後のヘッダ行の末尾トークンを偽装できないため）。
    private static final Pattern IPV6_PATTERN =
            Pattern.compile("^(?=.*:)[0-9a-fA-F:]{2,39}$");

    // 1 つの送信元が単位時間内に許可されるリクエスト数の上限
    private final int capacity;
    // カウントの単位時間（秒）
    private final long windowSeconds;
    // X-Forwarded-For ヘッダを信頼するかどうか（リバースプロキシ配下のときのみ true にする）
    // デフォルト false: 直接接続を前提とし、ユーザーが偽装できる X-Forwarded-For を無視する。
    //
    // 【true に設定する場合の必須要件 — 設定ミスは深刻なセキュリティ問題を招く】
    // (1) リバースプロキシは接続元 IP を「最後」に付与するよう設定すること。
    //     既存行への追記型（"X-Forwarded-For: <既存値>, <接続元IP>"）と、
    //     別ヘッダ行の追加型（HAProxy の option forwardfor 等。既存行に連結せず
    //     独立した X-Forwarded-For 行を後ろに追加する）のどちらでもよい。
    //     本フィルタは「最後のヘッダ行の末尾トークン」を採用するため、いずれの方式でも
    //     プロキシが付与した接続元 IP が選ばれ、攻撃者が最初から送り込んだ値
    //     （既存行の先頭トークンや、先行する別ヘッダ行）は選ばれない。
    //     プロキシが接続元 IP を先頭に付与したり、ヘッダをそのままスルーしたりする設定では
    //     攻撃者が採用位置のトークンを制御でき、他クライアントのレート制限枠を消費させられる。
    // (2) プロキシが既存の X-Forwarded-For ヘッダをクライアント側からの値ごと信用して
    //     破棄も追記もせずそのまま転送しないこと（採用位置にクライアントが任意値を挿入できてしまう）。
    // (3) プロキシへの直接アクセスをネットワーク層でブロックし、
    //     外部からアプリに X-Forwarded-For を直送できないようにすること（§9）。
    private final boolean trustXForwardedFor;
    // エラー応答を JSON で書き出すための ObjectMapper
    private final ObjectMapper objectMapper;

    // 送信元 IP ごとの「現在ウィンドウとそのカウント」を保持するマップ
    private final ConcurrentHashMap<String, Window> counters = new ConcurrentHashMap<>();

    // 最後に上限到達の警告ログを出したウィンドウ番号（同一ウィンドウ内での警告ログ連発＝ログ起因の DoS を防ぐ）。
    // 初期値 -1 は「まだ一度も警告を出していない」ことを表す。
    private final AtomicLong lastWarnedWindow = new AtomicLong(-1);

    // 最後に古いエントリを掃除したウィンドウ番号（同一ウィンドウ内での重複掃除を防ぐ）。
    // 初期値 -1 は「まだ一度も掃除していない」ことを表す。
    private final AtomicLong lastCleanedWindow = new AtomicLong(-1);

    // 設定値と ObjectMapper をコンストラクタで受け取る
    public RateLimitFilter(
            // 単位時間あたりの許可数（app.rate-limit.capacity）
            @Value("${app.rate-limit.capacity}") int capacity,
            // 単位時間の長さ（app.rate-limit.window-seconds）
            @Value("${app.rate-limit.window-seconds}") long windowSeconds,
            // X-Forwarded-For を信頼するか（リバースプロキシ配下かどうか）。既定は false（安全側）
            @Value("${app.rate-limit.trust-x-forwarded-for:false}") boolean trustXForwardedFor,
            // JSON 直列化に使う ObjectMapper
            ObjectMapper objectMapper) {
        // 【環境変数由来の設定値を必ず検証する（§9 入力は信用しない・fail-closed）】
        // 検証を怠ると、windowSeconds=0 は isOverLimit() のウィンドウ計算（除算）で
        // ArithmeticException（ゼロ除算）を毎リクエスト引き起こす。本フィルタは最優先
        // （HIGHEST_PRECEDENCE）で実行されるため、この例外は GlobalExceptionHandler に届かず
        // コンテナ既定の 500 となり、{status, message} のエラー契約が全エンドポイントで壊れる。
        // また capacity<=0 はすべてのリクエストが 429 になる「動いているように見えて壊れた」状態を招く。
        // どちらも実行時に静かに壊れるより、datasource パスワード（application.yml）と同じく
        // 起動そのものを失敗させる fail-closed に倒す。
        // 許可数が 0 以下（1 リクエストも通せない設定）は設定ミスなので起動を失敗させる
        if (capacity <= 0) {
            // 設定ミスの内容と直し方が分かる日本語メッセージで起動時例外（アプリは開始しない）を投げる
            throw new IllegalStateException(
                    "app.rate-limit.capacity（APP_RATE_LIMIT_CAPACITY）は 1 以上を指定してください。現在値: " + capacity);
        }
        // 単位時間が 0 以下（ゼロ除算または負のウィンドウ）は設定ミスなので起動を失敗させる
        if (windowSeconds <= 0) {
            // 設定ミスの内容と直し方が分かる日本語メッセージで起動時例外（アプリは開始しない）を投げる
            throw new IllegalStateException(
                    "app.rate-limit.window-seconds（APP_RATE_LIMIT_WINDOW_SECONDS）は 1 以上を指定してください。現在値: "
                            + windowSeconds);
        }
        // 許可数をフィールドに保持する
        this.capacity = capacity;
        // 単位時間をフィールドに保持する
        this.windowSeconds = windowSeconds;
        // X-Forwarded-For の信頼設定をフィールドに保持する
        this.trustXForwardedFor = trustXForwardedFor;
        // ObjectMapper をフィールドに保持する
        this.objectMapper = objectMapper;
    }

    // 各リクエストで送信元ごとの流量を判定する本体
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 送信元を識別するキーを取得する（trust-x-forwarded-for 設定により直接 IP か X-Forwarded-For かを切り替える）
        String clientKey = resolveClientKey(request);
        // 上限超過なら 429 を返して処理を打ち切る
        if (isOverLimit(clientKey)) {
            // 再試行までの目安秒数をヘッダで伝える
            response.setHeader("Retry-After", String.valueOf(windowSeconds));
            // 超過の安全な文言で 429 応答を書き出す
            ApiErrorWriter.write(response, objectMapper, HttpStatus.TOO_MANY_REQUESTS, ErrorMessages.TOO_MANY_REQUESTS);
            // 後続のフィルタ・コントローラへは進ませない
            return;
        }
        // 上限内なので後続の処理へ進める
        filterChain.doFilter(request, response);
    }

    // 送信元を識別するキーを解決する。
    // trust-x-forwarded-for が true（かつリバースプロキシ配下）の場合のみ X-Forwarded-For を参照する。
    // false（デフォルト）の場合は getRemoteAddr() だけを使う。
    // X-Forwarded-For はユーザーが任意の値を送れるヘッダであるため、
    // 信頼できるリバースプロキシが設定・書き換える保証がなければ使ってはいけない。
    //
    // 【最後のヘッダ行・末尾トークンを採用する理由】
    // 複数プロキシを経由すると "client, proxy1, proxy2, ..." の形式になる。
    // 先頭（index 0）はクライアントが任意に偽装できる。
    // 末尾（最後のトークン）は直近の信頼プロキシが接続元として記録した IP であり
    // ユーザーが書き換えられない。Spring の ForwardedHeaderFilter も同じ方針を採用している。
    // さらに、追記型のプロキシ（HAProxy の option forwardfor 等）は既存行へ連結せず
    // 「独立した X-Forwarded-For 行」を後ろに追加するため、同名ヘッダが複数行になりうる。
    // getHeader() は最初の 1 行しか返さず、攻撃者が最初から送り込んだ行が選ばれてしまう
    // （その行の末尾トークンも攻撃者制御下）ので、getHeaders() で全行を読み最後の行
    // （＝直近の信頼プロキシが付与した行）を採用する。
    private String resolveClientKey(HttpServletRequest request) {
        // trust-x-forwarded-for が有効でない場合はヘッダを無視して接続元 IP をそのまま使う
        if (!trustXForwardedFor) {
            return request.getRemoteAddr(); // 直接接続元 IP（スプーフィング不可）
        }
        // X-Forwarded-For の「すべてのヘッダ行」を到着順の列挙として取得する
        // （getHeader() だと最初の 1 行＝攻撃者が偽装できる行しか見えないため）
        Enumeration<String> headerLines = request.getHeaders("X-Forwarded-For");
        // 最後のヘッダ行（＝直近の信頼プロキシが付与した行）を受け取る変数（無ければ null のまま）
        String forwarded = null;
        // 列挙を最後まで読み進め、最後の行だけを残す（サーブレット API に「最後の行だけ取得」は無いため）
        while (headerLines != null && headerLines.hasMoreElements()) {
            // 現在の行で候補を上書きする（ループ終了時には最後の行が残る）
            forwarded = headerLines.nextElement();
        }
        // ヘッダが存在し空でない場合は末尾トークン（信頼プロキシが記録した IP）を使う
        if (forwarded != null && !forwarded.isBlank()) {
            // カンマ区切りで複数の IP が連なる場合は、最後のカンマより後ろ（末尾トークン）だけを取り出してトリムする。
            // 【なぜ split(",") を使わないか】Java の split は末尾の空トークンを捨てるため、
            // ヘッダ値がカンマだけ（例: "," や ",,,"）だと結果が空配列になり、
            // parts[parts.length - 1] が ArrayIndexOutOfBoundsException でフィルタごとクラッシュしていた。
            // lastIndexOf 方式なら末尾トークンが空でも空文字列が得られ、
            // 下の looksLikeIp 判定に失敗して getRemoteAddr() へ安全にフォールバックする（§9 fail-safe）。
            // 【挙動変更の明記】旧 split 実装は末尾カンマ（例: "1.2.3.4,"）で直前の非空トークンを採用していたが、
            // 本実装ではフォールバックに変わる。信頼契約に従うプロキシは末尾に実 IP を追記するため
            // 末尾カンマは正常経路では発生せず、発生時（設定ミス＝ヘッダ全体が攻撃者制御下）は
            // 攻撃者の選んだ値を採用しないフォールバックのほうが安全（意図的な厳格化）。
            String candidate = forwarded.substring(forwarded.lastIndexOf(',') + 1).strip(); // 最後のカンマの次から末尾までを候補 IP として切り出す（カンマが無ければ全体）
            // IP アドレスの形式でない場合は偽装値または設定ミスの可能性があるためフォールバックする（§9 fail-closed）
            if (looksLikeIp(candidate)) {
                // 正常な IP 形式ならそれをレート制限キーとして返す
                return candidate;
            }
            // 不正な形式の場合は警告を出して直接接続元 IP に切り替える
            log.debug("X-Forwarded-For の末尾値 '{}' が IP 形式でないため getRemoteAddr() にフォールバックします", candidate);
        }
        // ヘッダが無い場合または不正な形式の場合は接続元 IP をそのまま使う
        return request.getRemoteAddr();
    }

    // 引数が IPv4 または IPv6 アドレスの形式に見えるかを確認する（DNS 名前解決を行わない）。
    // 外部入力を直接渡すため ReDoS の起きないシンプルなパターンのみ使用する（§9）。
    private static boolean looksLikeIp(String candidate) {
        // null または過剰な長さ（IPV6_PATTERN が許容する最大 39 文字）は IP ではないとみなす
        if (candidate == null || candidate.length() > 39) return false;
        // IPv4 形式（例: 192.168.0.1）または IPv6 形式（例: ::1、2001:db8::1）を許容する
        return IPV4_PATTERN.matcher(candidate).matches()
                || IPV6_PATTERN.matcher(candidate).matches();
    }

    // 送信元のカウントを 1 増やし、単位時間内の上限を超えたかを返す
    private boolean isOverLimit(String clientKey) {
        // 現在時刻が属する固定ウィンドウ番号（秒を単位時間で割った商）を求める
        long currentWindow = System.currentTimeMillis() / 1000 / windowSeconds;
        // マップが肥大化しないよう、上限に達したら古いウィンドウの項目を掃除する。
        // ただし O(N) のスキャンが毎リクエスト走るのを防ぐため、ウィンドウが切り替わったときだけ掃除する（1 ウィンドウ 1 回に抑制）。
        if (counters.size() >= MAX_TRACKED_CLIENTS) {
            // 直近に掃除したウィンドウ番号を読み出す
            long previouslyCleaned = lastCleanedWindow.get();
            // まだこのウィンドウで掃除していない、かつ自分が代表として番号を更新できた場合だけ掃除を実行する
            if (previouslyCleaned != currentWindow
                    && lastCleanedWindow.compareAndSet(previouslyCleaned, currentWindow)) {
                // 現在ウィンドウより古い項目を取り除く（メモリ上限の維持）
                counters.values().removeIf(window -> window.windowId != currentWindow);
            }
        }
        // 掃除後もまだ上限に達しているか（同一ウィンドウに大量の送信元がいる状況）を判定する
        // （compute の中ではマップを変更できないため、ここで一度だけ判定して使い回す）
        boolean atCapacity = counters.size() >= MAX_TRACKED_CLIENTS;
        // 上限に達している場合は警告ログを出力する（管理者が容量設定を見直せるように）
        // ただし isOverLimit はリクエストごとに呼ばれるため、毎回ログを出すと上限到達時（＝大量アクセス時）に
        // 警告が秒間大量に出てログ起因の二次的な資源枯渇を招く。そこで現在ウィンドウで未出力のときだけ出す（1 ウィンドウ 1 回に抑制）。
        if (atCapacity) {
            // 直近に警告したウィンドウ番号を読み出す（他スレッドが同時に更新する可能性がある）
            long previouslyWarned = lastWarnedWindow.get();
            // まだこのウィンドウで警告していない、かつ自分が代表として番号を更新できた場合だけログを出す（compareAndSet で重複出力を防ぐ）
            if (previouslyWarned != currentWindow && lastWarnedWindow.compareAndSet(previouslyWarned, currentWindow)) {
                log.warn("追跡クライアント数が上限({})に達しました。古いエントリの削除を検討してください。", MAX_TRACKED_CLIENTS); // 上限到達時に警告ログを出力（同一ウィンドウ内では 1 回だけ）
            }
        }
        // 実際にカウントするキーを決める。通常は送信元ごとのキーをそのまま使う
        String effectiveKey = clientKey;
        // 追跡テーブルが満杯で、かつまだ追跡されていない新規送信元の場合は共有オーバーフローバケットに振り替える。
        // 【fail-open → fail-safe への変更とそのトレードオフ】旧実装は満杯時の新規送信元を
        // 「追跡せず素通し」にしていたため、攻撃者が送信元アドレス（特に IPv6）を毎ウィンドウ
        // 切り替えてテーブルを埋め尽くすと、以降の新規キーからのアクセスが一切制限されない
        // フェイルオープンの抜け穴になっていた。本実装では満杯時の未追跡送信元を単一の
        // 共有バケット（OVERFLOW_KEY、上限は通常キーと同じ capacity）でまとめて数えるため、
        // 満杯時の流量も全体として必ず頭打ちになる（フェイルセーフ）。代償として、テーブルが
        // 満杯の間だけは正当な新規クライアント同士がこのバケットを共有し、互いの消費で早めに
        // 429 になりうるが、満杯自体が異常（攻撃または容量不足）な状態であり、その間の新規
        // クライアントを無制限に通すよりも安全側に倒す方が §9（fail-safe）に適う。
        // なお containsKey と compute の間の TOCTOU で「直後に追跡され始めた送信元」が
        // オーバーフロー側に数えられることがあるが、制限が緩む方向ではないため許容している。
        if (atCapacity && !counters.containsKey(clientKey)) {
            // 共有オーバーフローバケットのキーに切り替える（テーブルには 1 エントリしか増えない）
            effectiveKey = OVERFLOW_KEY;
        }
        // 決定したキーの現在ウィンドウのカウンタを取得する（無ければ新規ウィンドウで作成）
        Window window = counters.compute(effectiveKey, (key, existing) -> {
            // 同じウィンドウの既存カウンタがあればそれを引き続き使う
            if (existing != null && existing.windowId == currentWindow) {
                // 既存をそのまま返す
                return existing;
            }
            // それ以外（新規／同じキーのウィンドウ切替）は新しいウィンドウ（カウント 0）を作る
            return new Window(currentWindow);
        });
        // カウントを 1 増やし、単位時間の上限を超えたかどうかを返す
        return window.count.incrementAndGet() > capacity;
    }

    // 1 つの送信元の「対象ウィンドウ番号」と「そのウィンドウ内のカウント」を表す内部クラス
    private static final class Window {
        // このカウンタが対象とする固定ウィンドウ番号
        private final long windowId;
        // このウィンドウ内でのリクエスト数（スレッドセーフに加算する）
        private final AtomicInteger count = new AtomicInteger(0);

        // 対象ウィンドウ番号を受け取るコンストラクタ
        private Window(long windowId) {
            // ウィンドウ番号を保持する
            this.windowId = windowId;
        }
    }
}
