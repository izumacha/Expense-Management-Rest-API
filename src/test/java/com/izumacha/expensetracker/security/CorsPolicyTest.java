// セキュリティ関連のテストパッケージ（CORS 許可オリジン制限の検証用）
package com.izumacha.expensetracker.security;

// テスト対象の経路に使うカテゴリコントローラ
import com.izumacha.expensetracker.controller.CategoryController;
// JwtConfig（SecurityConfig が必要とする JwtDecoder の供給元）を参照する
import com.izumacha.expensetracker.config.JwtConfig;
// セキュリティ設定クラス（CORS 許可オリジンの明示リストを構成する）
import com.izumacha.expensetracker.config.SecurityConfig;
// コントローラが依存するカテゴリサービス（モックする。CORS 判定はコントローラ到達前なので応答設定は不要）
import com.izumacha.expensetracker.service.CategoryService;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;
// Web スライステストを有効化するアノテーション
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
// サービスをモック Bean として差し込むアノテーション
import org.springframework.boot.test.mock.mockito.MockBean;
// 実物の設定クラスをスライスへ読み込むアノテーション
import org.springframework.context.annotation.Import;
// テスト用にプロパティを上書きするアノテーション
import org.springframework.test.context.TestPropertySource;
// HTTP リクエストを擬似実行するクライアント
import org.springframework.test.web.servlet.MockMvc;

// OPTIONS リクエスト（プリフライト）を組み立てる options を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
// レスポンスのステータスを検証する status を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
// レスポンスヘッダを検証する header を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * CORS の許可オリジン制限（security.cors.allowed-origins）を検証するテスト。
 *
 * <p>許可オリジンを 1 つだけ設定した状態で、
 * 許可オリジンからのプリフライトは通り（Access-Control-Allow-Origin が返り）、
 * 許可外オリジンからのプリフライトは 403 で拒否されることを確認する。
 * 未設定（既定の全拒否）の挙動は JwtAuthorizationTest 側で検証している。
 */
// テスト対象のコントローラを CategoryController に限定する（スライステスト）
@WebMvcTest(CategoryController.class)
// 実物のセキュリティ設定（CORS 設定源を含む）とその依存（JwtDecoder を提供する JwtConfig）を読み込む
@Import({SecurityConfig.class, JwtConfig.class})
// 許可オリジンを 1 つだけ設定する（これ以外のオリジンは拒否されるはず）
@TestPropertySource(properties = {"security.cors.allowed-origins=https://allowed.example.com"})
class CorsPolicyTest {

    // テストで許可するオリジン（@TestPropertySource の設定値と一致させる）
    private static final String ALLOWED_ORIGIN = "https://allowed.example.com";

    // 擬似 HTTP リクエストを送るクライアント
    @Autowired
    private MockMvc mockMvc;

    // コントローラが依存するカテゴリサービスのモック（CORS 判定はコントローラ到達前に行われる）
    @MockBean
    private CategoryService categoryService;

    // 許可オリジンからのプリフライトは 200 になり、許可オリジンがヘッダで返ることを検証する
    @Test
    void 許可オリジンのプリフライトは許可される() throws Exception {
        // 許可オリジンからのプリフライト（OPTIONS + Origin + Access-Control-Request-Method）を送る
        mockMvc.perform(options("/api/categories")
                        // 許可リストに載っているオリジンを設定する
                        .header("Origin", ALLOWED_ORIGIN)
                        // プリフライトで問い合わせる実リクエストのメソッドを設定する
                        .header("Access-Control-Request-Method", "GET"))
                // プリフライトが成功（200）することを検証する（認証不要で応答される）
                .andExpect(status().isOk())
                // 応答の許可オリジンヘッダが要求元オリジンと一致することを検証する
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    // 許可外オリジンからのプリフライトは 403 で拒否されることを検証する
    @Test
    void 許可外オリジンのプリフライトは拒否される() throws Exception {
        // 許可リストに無いオリジンからのプリフライトを送る
        mockMvc.perform(options("/api/categories")
                        // 許可リストに載っていないオリジンを設定する
                        .header("Origin", "https://evil.example.com")
                        // プリフライトで問い合わせる実リクエストのメソッドを設定する
                        .header("Access-Control-Request-Method", "GET"))
                // プリフライトが 403 で拒否されることを検証する（fail-closed）
                .andExpect(status().isForbidden());
    }

    // 許可オリジンでも、許可リスト外の HTTP メソッド（PATCH）を求めるプリフライトは拒否されることを検証する
    @Test
    void 許可外メソッドのプリフライトは拒否される() throws Exception {
        // 許可オリジンから、許可していないメソッド（PATCH）を求めるプリフライトを送る
        mockMvc.perform(options("/api/categories")
                        // 許可リストに載っているオリジンを設定する
                        .header("Origin", ALLOWED_ORIGIN)
                        // 許可メソッド（GET/POST/PUT/DELETE）に含まれない PATCH を問い合わせる
                        .header("Access-Control-Request-Method", "PATCH"))
                // プリフライトが 403 で拒否されることを検証する（許可メソッドの最小化）
                .andExpect(status().isForbidden());
    }
}
