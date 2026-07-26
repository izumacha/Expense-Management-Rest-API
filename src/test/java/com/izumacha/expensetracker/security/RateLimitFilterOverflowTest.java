// セキュリティ関連のテストパッケージ（追跡テーブル満杯時のオーバーフローバケット検証用）
package com.izumacha.expensetracker.security;

// 429 応答の JSON を書き出すために filter が必要とする ObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 擬似 HTTP リクエストを表すモック（Spring テストユーティリティ）
import org.springframework.mock.web.MockHttpServletRequest;
// 擬似 HTTP レスポンスを表すモック（書き込まれたステータスを検証できる）
import org.springframework.mock.web.MockHttpServletResponse;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追跡テーブルが満杯（MAX_TRACKED_CLIENTS 件）になったときの
 * 共有オーバーフローバケットの挙動を検証するユニットテスト。
 *
 * <p>【何を守るテストか】旧実装は満杯時の未追跡送信元を「追跡せず素通し」にしていたため、
 * 攻撃者が送信元アドレス（特に IPv6）を切り替えてテーブルを埋め尽くすと、以降の新規キーからの
 * アクセスが一切レート制限されないフェイルオープンの抜け穴になっていた。修正後は満杯時の
 * 未追跡送信元が単一の共有バケット（通常キーと同じ上限）でまとめて数えられ、全体として
 * 必ず頭打ちになる（フェイルセーフ）ことを確認する。
 *
 * <p>10,000 件のテーブルを埋める必要があるため、Spring コンテキストや MockMvc を使わず
 * フィルタを直接生成してモックのリクエスト/レスポンスで駆動する（DB・PostgreSQL 不要の
 * 純粋ユニットテスト。共通規約 §11）。
 */
class RateLimitFilterOverflowTest {

    // 単位時間あたりの許可数（オーバーフローバケットにも同じ上限が適用される）
    private static final int CAPACITY = 2;

    // 単位時間の長さ（秒）。テスト実行中にウィンドウが切り替わらないよう十分長くする
    private static final long WINDOW_SECONDS = 3600;

    // テスト対象のフィルタ（Spring を介さずコンストラクタで直接設定値を渡す）
    private final RateLimitFilter filter =
            // capacity=2 / window=3600 秒 / XFF 不信頼（remoteAddr をキーにする）で生成する
            new RateLimitFilter(CAPACITY, WINDOW_SECONDS, false, new ObjectMapper());

    // 指定した送信元 IP から 1 リクエストをフィルタに通し、レスポンスのステータスコードを返すヘルパー
    private int performRequest(String remoteAddr) throws Exception {
        // 擬似の GET リクエストを作る
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/categories");
        // 送信元 IP（レート制限のキー）を指定値に設定する
        request.setRemoteAddr(remoteAddr);
        // 書き込まれるステータスを観測するための擬似レスポンスを作る
        MockHttpServletResponse response = new MockHttpServletResponse();
        // フィルタを実行する（通過時は後続チェーンへ進むが、ここでは何もしないチェーンを渡す）
        filter.doFilter(request, response, (req, res) -> { /* 通過確認のみなので後続処理は不要 */ });
        // フィルタが書き込んだ（または既定の 200 のままの）ステータスコードを返す
        return response.getStatus();
    }

    // i 番目の埋め草クライアント用の一意な IP を生成するヘルパー（10.0.x.y 形式で最大 65,536 通り）
    private static String fillerIp(int i) {
        // 第 3 オクテットと第 4 オクテットに連番を分配して一意な IPv4 文字列を組み立てる
        return "10.0." + (i / 256) + "." + (i % 256);
    }

    // 満杯時の未追跡送信元が共有バケットで集合的に制限され、既存の追跡済み送信元は
    // 引き続き自分専用のカウンタで制限されることを検証する
    @Test
    void 満杯時の新規送信元は共有オーバーフローバケットで制限される() throws Exception {
        // 追跡テーブルを上限（MAX_TRACKED_CLIENTS 件）までユニークな送信元で埋める
        for (int i = 0; i < RateLimitFilter.MAX_TRACKED_CLIENTS; i++) {
            // 各送信元の 1 回目（上限 2 の範囲内）なので 200 で通過することを確認する
            assertThat(performRequest(fillerIp(i))).isEqualTo(200);
        }

        // 満杯後の新規送信元 1 人目: オーバーフローバケットの 1 カウント目なので 200 で通過する
        assertThat(performRequest("192.0.2.1")).isEqualTo(200);
        // 満杯後の新規送信元 2 人目（別 IP）: 同じ共有バケットの 2 カウント目なので 200 で通過する
        assertThat(performRequest("192.0.2.2")).isEqualTo(200);
        // 満杯後の新規送信元 3 人目（さらに別 IP）: 共有バケットが上限（2）を超えるため 429 になる。
        // 旧実装（素通し）ではここが 200 になっており、無制限アクセスの抜け穴だった
        assertThat(performRequest("192.0.2.3")).isEqualTo(429);

        // 追跡済みの既存送信元（埋め草の 1 件目）はオーバーフローバケットの影響を受けず、
        // 自分専用カウンタの 2 回目（上限内）として 200 で通過することを確認する
        assertThat(performRequest(fillerIp(0))).isEqualTo(200);
        // 同じ既存送信元の 3 回目は自分専用カウンタの上限（2）を超えるため 429 になることを確認する
        assertThat(performRequest(fillerIp(0))).isEqualTo(429);
    }
}
