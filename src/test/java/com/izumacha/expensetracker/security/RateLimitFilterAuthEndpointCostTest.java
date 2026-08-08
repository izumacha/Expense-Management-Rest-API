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
// 例外が投げられないことを検証する assertThatCode を取り込む
import static org.assertj.core.api.Assertions.assertThatCode;

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
        // コンテキストパス無し（ルート配備）として同名ヘルパーへ委譲する
        return passesThrough(filter, method, uri, "");
    }

    // コンテキストパス付きの配備も再現できるヘルパー（contextPath は "" ならルート配備）
    private boolean passesThrough(RateLimitFilter filter, String method, String uri, String contextPath)
            throws ServletException, IOException {
        // 擬似リクエストを組み立てる
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        // 生の requestURI を明示指定する（MockHttpServletRequest はコンストラクタ引数を
        // そのまま requestURI に入れるが、エンコード済みパスを扱う意図をテスト側で明示しておく）
        request.setRequestURI(uri);
        // コンテキストパス（例: "/expense"）を設定する。UrlPathHelper がこの分を取り除く
        request.setContextPath(contextPath);
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

    // 指定パス・コンテキストパスへ POST し続け、429 で止まるまでに何回通ったかを返すヘルパー。
    // 「重み付けが効いているか」は通過回数（10 回 vs 120 回）で判別できる
    private int allowedTokenRequests(String uri, String contextPath) throws ServletException, IOException {
        // 検証対象のフィルタを用意する
        RateLimitFilter filter = filter();
        // 通過した回数を数えるカウンタ
        int allowed = 0;
        // 重み付けが外れていれば CAPACITY 回通ってしまうので、その上限まで試行する
        for (int i = 0; i < CAPACITY; i++) {
            // 429 で打ち切られたらそこで数え終わる
            if (!passesThrough(filter, "POST", uri, contextPath)) {
                // ループを抜けて現在のカウントを確定させる
                break;
            }
            // 通過したので回数を 1 増やす
            allowed++;
        }
        // 通過できた回数を返す
        return allowed;
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
     * パーセントエンコードでパスを偽装しても重み付けが外れないこと（レート制限バイパスの回帰防止）。
     *
     * <p>"%74" は 't' のパーセントエンコードで、StrictHttpFirewall はこれを拒否せず、
     * Spring Security も Spring MVC もデコードして {@code /api/auth/token} として扱う
     * （実測: この URI への POST は 200 でトークンが発行される）。生の {@code getRequestURI()} と
     * 定数を比較していた頃は、この 1 文字の細工だけで消費枠が 12 → 1 に落ち、既定値なら
     * 総当たり試行が 10 回/分から 120 回/分へ 12 倍に緩んでいた。
     */
    @Test
    void percentEncodedTokenPathStillCostsFullWeight() throws Exception {
        // 正規のパスで通過できる回数（＝重み付けが効いているときの基準値）を求める
        int viaCanonicalPath = allowedTokenRequests(SecurityConfig.TOKEN_ENDPOINT, "");
        // 'token' の 't' をパーセントエンコードした偽装パスで通過できる回数を求める
        int viaEncodedPath = allowedTokenRequests("/api/auth/%74oken", "");
        // 偽装パスでも正規のパスと同じ回数しか通らない（＝重み付けが外れない）ことを確認する
        assertThat(viaEncodedPath)
                .as("パーセントエンコードでパスを偽装しても重み付けは外れないはず")
                .isEqualTo(viaCanonicalPath);
        // 念のため、そもそも重み付けが効いている（CAPACITY 回も通らない）ことも確認する
        assertThat(viaEncodedPath)
                .as("重み付けが効いていれば CAPACITY 回より前に 429 になるはず")
                .isEqualTo(CAPACITY / RateLimitFilter.AUTH_ENDPOINT_REQUEST_COST);
    }

    /**
     * コンテキストパス付きで配備しても重み付けが外れないこと（設定変更で静かに壊れることの回帰防止）。
     *
     * <p>{@code server.servlet.context-path} を設定すると {@code getRequestURI()} は
     * {@code "<contextPath>/api/auth/token"} になる。生の値と定数を比較していた頃は、
     * アプリ側のコード変更なしに（設定を足しただけで）総当たり対策が無効化されていた。
     */
    @Test
    void tokenPathUnderContextPathStillCostsFullWeight() throws Exception {
        // コンテキストパス "/expense" 配下のトークン発行パスで通過できる回数を求める
        int viaContextPath = allowedTokenRequests("/expense" + SecurityConfig.TOKEN_ENDPOINT, "/expense");
        // ルート配備のときと同じ回数しか通らない（＝重み付けが外れない）ことを確認する
        assertThat(viaContextPath)
                .as("コンテキストパス配下でも重み付けは外れないはず")
                .isEqualTo(CAPACITY / RateLimitFilter.AUTH_ENDPOINT_REQUEST_COST);
    }

    /**
     * デコードできないパスでもフィルタから例外が漏れないこと（コンテナ既定 500 の回帰防止）。
     *
     * <p>パス正規化に使う {@code UrlPathHelper} は不正なパーセントエンコード（{@code "%zz"} や
     * 末尾の裸の {@code "%"}）に対して {@code IllegalArgumentException} を投げる。本フィルタは
     * {@code @Order(HIGHEST_PRECEDENCE)} で Spring Security よりも DispatcherServlet よりも先に
     * 走るため、ここで例外が漏れると GlobalExceptionHandler に届かず、コンテナ既定の 500 となって
     * {@code {status, message}} のエラー契約が壊れる。フィルタ内で捕捉して安全側の枠数で数える。
     */
    @Test
    void malformedPercentEncodingDoesNotEscapeTheFilter() {
        // 検証対象のフィルタを用意する
        RateLimitFilter filter = filter();
        // 代表的な不正エンコードのパターンを順に流す
        for (String malformedUri : new String[] {"/api/%zz", "/api/auth/token%", "/api/%"}) {
            // フィルタを 1 回通しても例外が外へ漏れないことを確認する（429 になるかどうかは問わない）
            assertThatCode(() -> passesThrough(filter, "POST", malformedUri))
                    .as("デコードできないパス %s でも例外を外へ出さないはず", malformedUri)
                    .doesNotThrowAnyException();
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
