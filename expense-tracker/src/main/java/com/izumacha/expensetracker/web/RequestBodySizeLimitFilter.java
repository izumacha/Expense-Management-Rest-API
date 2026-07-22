// Web 横断のユーティリティ・フィルタを置くパッケージ
package com.izumacha.expensetracker.web;

// JSON 応答を書き出すための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
// 本文サイズ超過時に送出する専用例外を参照する
import com.izumacha.expensetracker.exception.RequestBodyTooLargeException;
// サーブレットの入力ストリームの読み取り完了通知を受け取るリスナー型
import jakarta.servlet.ReadListener;
// サーブレットのフィルタ連鎖を表す型
import jakarta.servlet.FilterChain;
// サーブレット例外型
import jakarta.servlet.ServletException;
// サーブレットの入力ストリーム基底クラス
import jakarta.servlet.ServletInputStream;
// HTTP リクエストを表す型
import jakarta.servlet.http.HttpServletRequest;
// HTTP リクエストをラップ（差し替え）するための基底クラス
import jakarta.servlet.http.HttpServletRequestWrapper;
// HTTP レスポンスを表す型
import jakarta.servlet.http.HttpServletResponse;
// 文字入力を1文字ずつでなくまとめて読めるようにするバッファ付きリーダー
import java.io.BufferedReader;
// 入出力例外型
import java.io.IOException;
// バイト列を文字として読むためのブリッジ
import java.io.InputStreamReader;
// 文字コードを表す型
import java.nio.charset.Charset;
// 文字コードを定数で扱うためのクラス（Content-Type に文字コード指定が無いときの既定値に使う）
import java.nio.charset.StandardCharsets;
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

// リクエスト本文（body）のサイズに上限を設け、巨大なボディによるリソース枯渇を防ぐフィルタ
// （§9 公開エンドポイントを保護する：リクエストサイズの上限）。
//
// 【なぜ必要か】
// server.tomcat.max-http-form-post-size（application.yml）は Tomcat の FORM/multipart パース
// （request.getParameter() 経由）にのみ適用され、本 API の @RequestBody（Jackson が
// request.getInputStream() を直接読む生の JSON ボディ）の読み取りには一切効かない。
// そのため対策なしでは Content-Length・実サイズともに無制限の巨大な JSON ボディを受け付けてしまい、
// パース時のメモリ確保がリクエストごとに際限なく増える DoS の起点になる。
//
// 【二段構えで防ぐ理由】
// (1) 宣言された Content-Length が上限超過なら、ボディを読む前に即座に 413 で拒否する（安価で確実）。
// (2) Content-Length を送らない、または偽って小さく申告するクライアント（chunked 転送等）に備え、
//     実際に読み取れるバイト数そのものを上限で打ち切るストリームへ差し替える（フェイルセーフ、§9）。
//     上限を超えて読み取ろうとした時点で RequestBodyTooLargeException を送出し、
//     GlobalExceptionHandler が捕捉して (1) と同じ 413 契約に整形する（"本文が大きすぎる"のに
//     「不正な JSON」という誤った 400 を返さないようにするため。ちょうど上限と同じサイズの正常な
//     本文まで誤って拒否しないよう、境界では元ストリームが本当に自然終了しているかを都度確認する）。
@Component
// 認証より手前で、ボディを読み取る前にサイズを弾く。RateLimitFilter と同じ理由
// （Spring Boot は Security のフィルタチェーンを既定 order = -100 で登録するため、確実に先に
// 実行させるには最小値付近の @Order を使う必要がある）。
// ただしレート制限（RateLimitFilter＝Ordered.HIGHEST_PRECEDENCE）よりは 1 つ後ろにずらす。
// 同一 @Order 値どうしの相対順序は Spring が保証しないため明示的に差を付けており、これにより
// 「レート制限 → 本文サイズの上限チェック」の順が常に保証される。逆順（本フィルタが先）だと、
// サイズ超過リクエストがレート制限のカウントより先に 413 で打ち切られてカウントされず、
// 巨大な本文を送り続ける攻撃者が一切レート制限されない抜け穴になっていた
// （詳細は RateLimitFilter の @Order コメントを参照）。
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    // 許可するリクエスト本文の最大バイト数（application.yml の app.request.max-body-size-bytes）
    private final long maxBodySizeBytes;
    // JSON 直列化に使う ObjectMapper
    private final ObjectMapper objectMapper;

    // 設定値と ObjectMapper をコンストラクタで受け取る
    public RequestBodySizeLimitFilter(
            // 本文サイズの上限（バイト）
            @Value("${app.request.max-body-size-bytes}") long maxBodySizeBytes,
            // JSON 直列化に使う ObjectMapper
            ObjectMapper objectMapper) {
        // 上限値をフィールドに保持する
        this.maxBodySizeBytes = maxBodySizeBytes;
        // ObjectMapper をフィールドに保持する
        this.objectMapper = objectMapper;
    }

    // 各リクエストで本文サイズを判定する本体
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // クライアントが宣言した本文サイズを取得する（不明なら -1）
        long declaredLength = request.getContentLengthLong();
        // 宣言された時点で上限超過が分かる場合は、ボディを読み込む前に即座に拒否する（安価な早期リジェクト）
        if (declaredLength > maxBodySizeBytes) {
            // 上限超過の安全な文言で 413 応答を書き出す
            ApiErrorWriter.write(response, objectMapper, HttpStatus.PAYLOAD_TOO_LARGE, ErrorMessages.PAYLOAD_TOO_LARGE);
            // 後続のフィルタ・コントローラへは進ませない
            return;
        }
        // Content-Length が無い／実サイズと食い違う場合に備え、実際に読み取れるバイト数自体を
        // 上限で打ち切るリクエストへ差し替えたうえで後続へ進める（§9 fail-safe）。
        // 上限超過は RequestBodyTooLargeException として送出され、コントローラ呼び出し前
        // （引数解決中の JSON パース）で発生するため GlobalExceptionHandler が 413 に整形する。
        filterChain.doFilter(new SizeLimitedRequest(request, maxBodySizeBytes), response);
    }

    // getInputStream() / getReader() が返すストリームを上限付きに差し替える HttpServletRequest ラッパー。
    // 【パッケージプライベートな理由】同一パッケージのテストから境界値（ちょうど上限・上限+1バイト・
    // 0バイト要求等）を直接検証できるようにするため（§11 境界値テストの重視）。
    static final class SizeLimitedRequest extends HttpServletRequestWrapper {

        // 許可する最大バイト数
        private final long maxBodySizeBytes;
        // 生成済みの上限付きストリームをキャッシュするフィールド。
        // 【なぜキャッシュが必要か】キャッシュせず呼び出しごとに新規生成すると、getInputStream() が
        // 複数回呼ばれた場合（将来ロギング用フィルタ等が追加された場合を含む）に bytesRead が
        // 呼び出しのたびに 0 へリセットされ、実質的な上限が「呼び出し回数 × 上限」まで緩んでしまう。
        // 同一ストリームインスタンスを使い回すことで、本文全体を通じて上限が一度だけ効くようにする。
        private SizeLimitedServletInputStream cachedStream;

        // 元のリクエストと上限バイト数を受け取るコンストラクタ
        SizeLimitedRequest(HttpServletRequest request, long maxBodySizeBytes) {
            // 親クラスに元のリクエストを渡す（委譲先として保持される）
            super(request);
            // 上限バイト数をフィールドに保持する
            this.maxBodySizeBytes = maxBodySizeBytes;
        }

        // 元のストリームを上限付きストリームでラップして返す（2回目以降はキャッシュを返す）
        @Override
        public ServletInputStream getInputStream() throws IOException {
            // まだ生成していなければ、元のリクエストの入力ストリームを取得して上限付きストリームへ包む
            if (cachedStream == null) {
                // 生成結果をキャッシュへ保持する（以降の呼び出しは同じインスタンスを再利用する）
                cachedStream = new SizeLimitedServletInputStream(super.getInputStream(), maxBodySizeBytes);
            }
            // キャッシュ済みの上限付きストリームを返す
            return cachedStream;
        }

        // 文字列として本文を読む経路（getReader()）も上限付きストリーム経由にする。
        // 【なぜ必要か】HttpServletRequestWrapper.getReader() の既定実装は素の（上限の掛かっていない）
        // 元リクエストの getReader() へ直接委譲するため、ここを上書きしないと getInputStream() 側の
        // 上限が getReader() 経由の読み取りでは素通りしてしまう（現状 Jackson は getInputStream() しか
        // 使わないため今は到達しないが、将来別のメッセージコンバータ等が getReader() を使った場合に
        // 上限が無意味化する抜け穴になるため、フェイルセーフとして塞いでおく。§9）。
        @Override
        public BufferedReader getReader() throws IOException {
            // Content-Type に文字コード指定があればそれを使い、無ければサーブレット既定の ISO-8859-1 を使う
            // （Servlet 仕様の既定値。本 API は JSON のみを扱うため通常は Content-Type で UTF-8 が指定される）
            String encoding = getCharacterEncoding();
            // 文字コード名を Charset へ変換する（未指定時は ISO-8859-1 をフォールバックにする）
            Charset charset = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.ISO_8859_1;
            // 上限付きストリーム（getInputStream() 経由。キャッシュされたものと同一）を文字読み取りへ包む
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    // 読み取り済みバイト数が上限を超えたら RequestBodyTooLargeException を送出する ServletInputStream
    // ラッパー。EOF（-1）を返して静かに打ち切るのではなく例外を投げる設計により、GlobalExceptionHandler
    // が明確に 413（「不正な JSON」ではなく「本文が大きすぎる」）として応答できるようにする。
    // 【パッケージプライベートな理由】SizeLimitedRequest と同じくテストから直接インスタンス化するため。
    static final class SizeLimitedServletInputStream extends ServletInputStream {

        // 委譲先の元ストリーム
        private final ServletInputStream delegate;
        // 許可する最大バイト数
        private final long maxBodySizeBytes;
        // ここまでに読み取ったバイト数
        private long bytesRead;

        // 元ストリームと上限バイト数を受け取るコンストラクタ
        SizeLimitedServletInputStream(ServletInputStream delegate, long maxBodySizeBytes) {
            // 委譲先ストリームを保持する
            this.delegate = delegate;
            // 上限バイト数を保持する
            this.maxBodySizeBytes = maxBodySizeBytes;
        }

        // 1バイト読み取る（上限を超えて読もうとした場合は例外を送出する）
        @Override
        public int read() throws IOException {
            // 既に上限バイト数まで読み取り済みなら、本当に上限超過か（まだ元ストリームにデータが
            // 残っているか）を確認する。ちょうど上限と同じサイズで本文が終わっている場合は
            // 超過ではないため、通常の EOF として扱う（境界値の誤判定を防ぐ）。
            if (bytesRead >= maxBodySizeBytes) {
                // 元ストリームから1バイト先読みして残りデータの有無を確認する
                int probe = delegate.read();
                // 元ストリームも終わっていれば、上限ちょうどで正常終了した本文としてEOFを返す
                if (probe == -1) {
                    // ストリーム終端を表す -1 を返す
                    return -1;
                }
                // まだ読める1バイトがある＝本当に上限を超えている
                throw new RequestBodyTooLargeException(ErrorMessages.PAYLOAD_TOO_LARGE);
            }
            // 元ストリームから1バイト読み取る
            int b = delegate.read();
            // 実際にバイトを読み取れた場合だけカウントを増やす（EOF の -1 はカウントしない）
            if (b != -1) {
                // 読み取り済みバイト数を1増やす
                bytesRead++;
            }
            // 読み取った値（または EOF）を返す
            return b;
        }

        // 複数バイトをまとめて読み取る（上限を超えて読もうとした場合は例外を送出する）
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            // 要求サイズが0なら、InputStream の契約どおり何も読まずに0を返す
            // （EOF/例外ではなく「0バイト読んだ」という正常応答が仕様上の正解）
            if (len == 0) {
                // 何も読み取らない
                return 0;
            }
            // 既に上限バイト数まで読み取り済みなら、本当に上限超過かを1バイト先読みして確認する
            // （read() と同じ理由。ちょうど上限で終わる正常な本文を誤って拒否しないため）
            if (bytesRead >= maxBodySizeBytes) {
                // 元ストリームから1バイト先読みして残りデータの有無を確認する
                int probe = delegate.read();
                // 元ストリームも終わっていれば、上限ちょうどで正常終了した本文としてEOFを返す
                if (probe == -1) {
                    // ストリーム終端を表す -1 を返す
                    return -1;
                }
                // まだ読める1バイトがある＝本当に上限を超えている
                throw new RequestBodyTooLargeException(ErrorMessages.PAYLOAD_TOO_LARGE);
            }
            // 上限に収まる範囲だけ読み取るよう、要求サイズを残り許容量にクランプする
            int allowed = (int) Math.min(len, maxBodySizeBytes - bytesRead);
            // 元ストリームからクランプ後のサイズで読み取る
            int n = delegate.read(b, off, allowed);
            // 実際に読み取れたバイト数があればカウントへ加算する
            if (n > 0) {
                // 読み取り済みバイト数へ加算する
                bytesRead += n;
            }
            // 読み取ったバイト数（または EOF の -1）を返す
            return n;
        }

        // ストリームが読み終わっているかを返す（元ストリームの終端 or 上限到達のどちらかで真になる）
        @Override
        public boolean isFinished() {
            // 上限に達しているか、元ストリームが終端に達していれば true
            return bytesRead >= maxBodySizeBytes || delegate.isFinished();
        }

        // ノンブロッキング読み取りが可能かを元ストリームへそのまま委譲する
        @Override
        public boolean isReady() {
            // 元ストリームの準備状態をそのまま返す
            return delegate.isReady();
        }

        // ノンブロッキング読み取り用のリスナー登録を元ストリームへそのまま委譲する
        @Override
        public void setReadListener(ReadListener readListener) {
            // 元ストリームへリスナー登録を委譲する
            delegate.setReadListener(readListener);
        }

        // ストリームを閉じる処理を元ストリームへそのまま委譲する
        @Override
        public void close() throws IOException {
            // 元ストリームを閉じる
            delegate.close();
        }
    }
}
