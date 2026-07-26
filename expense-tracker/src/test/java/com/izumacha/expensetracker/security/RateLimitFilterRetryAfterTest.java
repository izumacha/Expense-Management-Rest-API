// セキュリティ関連のテストパッケージ（429 応答の Retry-After ヘッダ検証用）
package com.izumacha.expensetracker.security;

// フィルタのコンストラクタが必要とする ObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// テスト用のモックフィルタチェーン（後続処理の代わり）
import org.springframework.mock.web.MockFilterChain;
// テスト用のモック HTTP リクエスト
import org.springframework.mock.web.MockHttpServletRequest;
// テスト用のモック HTTP レスポンス
import org.springframework.mock.web.MockHttpServletResponse;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 429 応答の Retry-After ヘッダが「現在の固定ウィンドウの残り秒数」になることを検証するユニットテスト。
 *
 * <p>【何を守るテストか】旧実装は常に windowSeconds（ウィンドウ全長）を返しており、
 * ウィンドウ終了間際に 429 を受けたクライアントまでまるまる 1 ウィンドウ分待たされていた。
 * 本テストは値が固定長ではなく「実際に制限が解けるまでの残り秒数」であることをピン留めする。
 *
 * <p>Spring コンテキストを使わずフィルタを直接呼び出す純粋ユニットテスト（共通規約 §11）。
 */
class RateLimitFilterRetryAfterTest {

    // テスト中に確実に上限超過を起こすための許可数（1 回で枠を使い切る）
    private static final int CAPACITY = 1;

    // テスト実行中にウィンドウが切り替わりにくい十分長い単位時間（秒）
    private static final long WINDOW_SECONDS = 3600;

    // 指定リクエストをフィルタへ通し、書き出されたレスポンスを返すヘルパー
    private static MockHttpServletResponse doFilter(RateLimitFilter filter) throws Exception {
        // モックのレスポンスを生成する
        MockHttpServletResponse response = new MockHttpServletResponse();
        // モックのリクエスト・後続チェーンとともにフィルタを実行する
        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());
        // 書き出されたレスポンスを返す
        return response;
    }

    // 上限超過時の Retry-After が固定の windowSeconds ではなく、現在ウィンドウの残り秒数になることを検証する
    @Test
    void 上限超過時のRetryAfterは現在ウィンドウの残り秒数() throws Exception {
        // 上限 1・ウィンドウ 3600 秒のフィルタを生成する（同一送信元の 2 回目が必ず 429 になる）
        RateLimitFilter filter = new RateLimitFilter(CAPACITY, WINDOW_SECONDS, false, new ObjectMapper());
        // 1 回目のリクエストで枠を使い切る（この時点では 429 ではない）
        doFilter(filter);

        // 期待値レンジの計算用に、2 回目のリクエスト直前の現在時刻（秒）を取得する
        long beforeSeconds = System.currentTimeMillis() / 1000;
        // 2 回目のリクエストを送り、上限超過の応答を受け取る
        MockHttpServletResponse response = doFilter(filter);
        // 期待値レンジの計算用に、リクエスト直後の現在時刻（秒）を取得する
        long afterSeconds = System.currentTimeMillis() / 1000;

        // ステータスが 429 であることを検証する
        assertThat(response.getStatus()).isEqualTo(429);
        // Retry-After ヘッダの値を数値として読み取る
        long retryAfter = Long.parseLong(response.getHeader("Retry-After"));
        // 値が有効範囲（1 以上・ウィンドウ全長以下）に収まることを検証する
        assertThat(retryAfter).isBetween(1L, WINDOW_SECONDS);
        // リクエスト直前・直後それぞれの時点での「ウィンドウ残り秒数」の期待値を同じ式で計算する
        long remainingAtBefore = WINDOW_SECONDS - (beforeSeconds % WINDOW_SECONDS);
        long remainingAtAfter = WINDOW_SECONDS - (afterSeconds % WINDOW_SECONDS);
        // 直前と直後の間でウィンドウが切り替わっていない通常ケースでは、応答値が両者の間に
        // 収まるはず（残り秒数は時間経過で単調に減るため）。旧実装の固定値 windowSeconds は
        // 境界ちょうどの瞬間を除きこの範囲に入らないため、退行をここで検知できる。
        // まれにミリ秒の間にウィンドウ境界をまたいだ場合（remainingAtAfter が跳ね上がる）だけは
        // レンジ比較が成立しないため、上の有効範囲チェックのみでフレークを防ぐ
        if (remainingAtAfter <= remainingAtBefore) {
            // 応答値が「直後時点の残り秒数」以上「直前時点の残り秒数」以下であることを検証する
            assertThat(retryAfter).isBetween(remainingAtAfter, remainingAtBefore);
        }
    }
}
