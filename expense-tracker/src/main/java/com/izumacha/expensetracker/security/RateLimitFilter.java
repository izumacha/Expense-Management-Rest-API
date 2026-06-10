// 認証・アクセス制御に関するパッケージ
package com.izumacha.expensetracker.security;

// JSON 応答を書き出すための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;
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
// 送信元ごとのカウンタを保持するスレッドセーフなマップ
import java.util.concurrent.ConcurrentHashMap;
// 単位時間内のカウントをスレッドセーフに数えるための整数
import java.util.concurrent.atomic.AtomicInteger;
// プロパティ値を注入するアノテーション
import org.springframework.beans.factory.annotation.Value;
// Bean の優先順位（フィルタの適用順）を指定するアノテーション
import org.springframework.core.annotation.Order;
// HTTP ステータスを表す列挙
import org.springframework.http.HttpStatus;
// Spring に管理させるためのコンポーネント宣言
import org.springframework.stereotype.Component;
// 1 リクエストにつき一度だけ実行されることを保証するフィルタ基底
import org.springframework.web.filter.OncePerRequestFilter;

// 送信元 IP ごとに固定ウィンドウでリクエスト数を制限する簡易レート制限フィルタ（§9 公開エンドポイントを保護する）。
// 外部ライブラリを足さず、メモリ使用も上限を設けて DoS（リソース枯渇）の起点を抑える。
@Component
// 認証より手前で先に流量を絞る（無認証の大量アクセスでも認証処理に到達させない）
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    // 追跡する送信元 IP の最大数（無制限に増やしてメモリを枯渇させないための上限）
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    // 1 つの送信元が単位時間内に許可されるリクエスト数の上限
    private final int capacity;
    // カウントの単位時間（秒）
    private final long windowSeconds;
    // エラー応答を JSON で書き出すための ObjectMapper
    private final ObjectMapper objectMapper;

    // 送信元 IP ごとの「現在ウィンドウとそのカウント」を保持するマップ
    private final ConcurrentHashMap<String, Window> counters = new ConcurrentHashMap<>();

    // 設定値と ObjectMapper をコンストラクタで受け取る
    public RateLimitFilter(
            // 単位時間あたりの許可数（app.rate-limit.capacity）
            @Value("${app.rate-limit.capacity}") int capacity,
            // 単位時間の長さ（app.rate-limit.window-seconds）
            @Value("${app.rate-limit.window-seconds}") long windowSeconds,
            // JSON 直列化に使う ObjectMapper
            ObjectMapper objectMapper) {
        // 許可数をフィールドに保持する
        this.capacity = capacity;
        // 単位時間をフィールドに保持する
        this.windowSeconds = windowSeconds;
        // ObjectMapper をフィールドに保持する
        this.objectMapper = objectMapper;
    }

    // 各リクエストで送信元ごとの流量を判定する本体
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 送信元を識別するキー（接続元 IP アドレス）を取得する
        String clientKey = request.getRemoteAddr();
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

    // 送信元のカウントを 1 増やし、単位時間内の上限を超えたかを返す
    private boolean isOverLimit(String clientKey) {
        // 現在時刻が属する固定ウィンドウ番号（秒を単位時間で割った商）を求める
        long currentWindow = System.currentTimeMillis() / 1000 / windowSeconds;
        // マップが肥大化しないよう、上限に達したら古いウィンドウの項目を掃除する
        if (counters.size() >= MAX_TRACKED_CLIENTS) {
            // 現在ウィンドウより古い項目を取り除く（メモリ上限の維持）
            counters.values().removeIf(window -> window.windowId != currentWindow);
        }
        // 掃除後もまだ上限に達しているか（同一ウィンドウに大量の送信元がいる状況）を判定する
        // （compute の中ではマップを変更できないため、ここで一度だけ判定して使い回す）
        boolean atCapacity = counters.size() >= MAX_TRACKED_CLIENTS;
        // 送信元の現在ウィンドウのカウンタを取得する（無ければ新規ウィンドウで作成）
        Window window = counters.compute(clientKey, (key, existing) -> {
            // 同じウィンドウの既存カウンタがあればそれを引き続き使う
            if (existing != null && existing.windowId == currentWindow) {
                // 既存をそのまま返す
                return existing;
            }
            // 追跡上限に達している新規送信元は、メモリ枯渇を避けるため追跡対象に加えない（安全側に通す）
            if (existing == null && atCapacity) {
                // マッピングを作らない（null を返すと項目は追加されない）
                return null;
            }
            // それ以外（新規で空きがある／同じ送信元のウィンドウ切替）は新しいウィンドウ（カウント 0）を作る
            return new Window(currentWindow);
        });
        // 追跡対象に加えなかった（メモリ上限）場合は通過させ、それ以外はカウントを 1 増やして上限超過を判定する
        return window != null && window.count.incrementAndGet() > capacity;
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
