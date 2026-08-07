// セキュリティ関連のテストパッケージ（レート制限の重み付けの検証用）
package com.izumacha.expensetracker.security;

// JSON 応答を書き出すための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;
// トークン発行エンドポイントのパス定数（本番実装と同じ定義元を参照する）
import com.izumacha.expensetracker.config.SecurityConfig;

// 入出力例外型
import java.io.IOException;
// サーブレット例外型
import jakarta.servlet.ServletException;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// フィルタ連鎖の擬似実装（後続へ進んだかどうかの確認に使う）
import org.springframework.mock.web.MockFilterChain;
// 擬似 HTTP リクエスト
import org.springframework.mock.web.MockHttpServletRequest;
// 擬似 HTTP レスポンス
import org.springframework.mock.web.MockHttpServletResponse;

// 検証に使う assertThat を取り込む
import static org.assertj.core.api.Assertions.assertThat;

/**
 * トークン発行エンドポイント（POST /api/auth/token）が通常より多くのレート制限枠を消費することの検証。
 *
 * <p>この 1 本だけは未認証で呼べて中で bcrypt 照合という重い計算を回すため、一般 API と同じ
 * 1 枠で数えると総当たり・CPU 枯渇に一般 API 向けの緩い枠がそのまま使われてしまう。
 * {@link RateLimitFilter#AUTH_ENDPOINT_REQUEST_COST} 枠を消費させることで、通常の CRUD の
 * 流量を変えずにトークン発行だけを絞る。
 */
class RateLimitFilterAuthEndpointCostTest {

    // ウィンドウ内の許可枠数。テスト中にウィンドウが切り替わらないよう十分長い窓と組で使う
    private static final int CAPACITY = 120;

    // 単位時間（秒）。1 時間にしてテスト実行中のウィンドウ切り替わりを避ける
    private static final long WINDOW_SECONDS = 3600;

    // テストで使う送信元 IP（1 つの送信元の枠消費を追跡する）
    private static final String CLIENT_IP = "203.0.113.10";

    // 検証対象のフィルタを既定設定で組み立てるヘルパー
    private RateLimitFilter filter() {
        // X-Forwarded-For は信頼しない設定（getRemoteAddr() をキーにする）で生成して返す
        return new RateLimitFilter(CAPACITY, WINDOW_SECONDS, false, new ObjectMapper());
    }

    // 指定のメソッド・パスでフィルタを 1 回通し、後続へ進めたか（=許可されたか）を返すヘルパー
    private boolean passesThrough(RateLimitFilter filter, String method, String uri)
            throws ServletException, IOException {
        // 擬似リクエストを組み立てる
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        // 送信元 IP を固定して同じレート制限キーに集約させる
        request.setRemoteAddr(CLIENT_IP);
        // 擬似レスポンスを用意する
        MockHttpServletResponse response = new MockHttpServletResponse();
        // 後続へ進んだかどうかを観測するためのフィルタ連鎖を用意する
        MockFilterChain chain = new MockFilterChain();
        // フィルタを 1 回実行する
        filter.doFilter(request, response, chain);
        // 後続のリクエストが設定されていれば通過、されていなければ 429 で打ち切られたと判断する
        return chain.getRequest() != null;
    }

    /**
     * トークン発行は {@code CAPACITY / AUTH_ENDPOINT_REQUEST_COST} 回で枠を使い切り、
     * それ以降は 429 になること（＝1 枠ずつ数えていた頃の CAPACITY 回よりずっと早く止まる）。
     */
    @Test
    void tokenEndpointExhaustsBudgetFasterThanOrdinaryRequests() throws Exception {
        // 検証対象のフィルタを用意する
        RateLimitFilter filter = filter();
        // 重み付けから逆算した「許可されるはずのトークン発行回数」を求める
        int allowedAttempts = CAPACITY / RateLimitFilter.AUTH_ENDPOINT_REQUEST_COST;
        // 許可されるはずの回数までは通過することを確認する
        for (int i = 0; i < allowedAttempts; i++) {
            // i 回目のトークン発行が通過することを確認する
            assertThat(passesThrough(filter, "POST", SecurityConfig.TOKEN_ENDPOINT))
                    .as("%d 回目のトークン発行は許可されるはず", i + 1)
                    .isTrue();
        }
        // 枠を使い切った次のトークン発行は 429 で打ち切られることを確認する
        assertThat(passesThrough(filter, "POST", SecurityConfig.TOKEN_ENDPOINT))
                .as("枠を使い切った後のトークン発行は 429 で拒否されるはず")
                .isFalse();
        // 1 枠ずつ数えていたら CAPACITY 回通せていたはずで、実際にはその手前で止まっていることを明示する
        assertThat(allowedAttempts).isLessThan(CAPACITY);
    }

    /**
     * 通常のエンドポイントは従来どおり 1 リクエスト 1 枠のままであること
     * （重み付けの導入が一般 API の流量に影響していないことの回帰防止）。
     */
    @Test
    void ordinaryEndpointStillCostsOneUnitPerRequest() throws Exception {
        // 検証対象のフィルタを用意する
        RateLimitFilter filter = filter();
        // 上限ちょうどまでの通常リクエストがすべて通過することを確認する
        for (int i = 0; i < CAPACITY; i++) {
            // i 回目の通常リクエストが通過することを確認する
            assertThat(passesThrough(filter, "GET", "/api/categories"))
                    .as("%d 回目の通常リクエストは許可されるはず", i + 1)
                    .isTrue();
        }
        // 上限を超えた次の通常リクエストは 429 で打ち切られることを確認する
        assertThat(passesThrough(filter, "GET", "/api/categories"))
                .as("上限を超えた通常リクエストは 429 で拒否されるはず")
                .isFalse();
    }

    /**
     * 同じパスでも GET は重い扱いにしないこと（重み付けの条件がメソッドとパスの両方であることの確認）。
     * トークン発行は POST のみ許可されているため、GET は認証処理に到達せず bcrypt も走らない。
     */
    @Test
    void getOnTokenPathIsNotTreatedAsExpensive() throws Exception {
        // 検証対象のフィルタを用意する
        RateLimitFilter filter = filter();
        // POST なら枠を使い切っているはずの回数だけ GET を送っても、まだ通過できることを確認する
        for (int i = 0; i <= CAPACITY / RateLimitFilter.AUTH_ENDPOINT_REQUEST_COST; i++) {
            // i 回目の GET が通過することを確認する
            assertThat(passesThrough(filter, "GET", SecurityConfig.TOKEN_ENDPOINT))
                    .as("%d 回目の GET は 1 枠しか消費しないので許可されるはず", i + 1)
                    .isTrue();
        }
    }

    /**
     * 境界値: capacity が重み（AUTH_ENDPOINT_REQUEST_COST）より小さい設定でも、
     * 1 回目のトークン発行は必ず通ること（「動いているのに一度も認証できない」壊れ方を避ける）。
     */
    @Test
    void firstTokenRequestIsAllowedEvenWhenCapacityIsSmallerThanCost() throws Exception {
        // 重みより小さい許可枠（1）でフィルタを組み立てる
        RateLimitFilter tinyFilter = new RateLimitFilter(1, WINDOW_SECONDS, false, new ObjectMapper());
        // 1 回目のトークン発行は通過することを確認する（消費量が capacity で頭打ちになるため）
        assertThat(passesThrough(tinyFilter, "POST", SecurityConfig.TOKEN_ENDPOINT))
                .as("capacity が重みより小さくても 1 回目は試せるはず")
                .isTrue();
        // 2 回目は枠を使い切っているので 429 になることを確認する
        assertThat(passesThrough(tinyFilter, "POST", SecurityConfig.TOKEN_ENDPOINT))
                .as("2 回目は枠を使い切っているので拒否されるはず")
                .isFalse();
    }
}
