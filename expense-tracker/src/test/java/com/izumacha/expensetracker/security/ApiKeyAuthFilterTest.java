// セキュリティ関連のテストパッケージ
package com.izumacha.expensetracker.security;

// テスト対象の経路に使うカテゴリコントローラ
import com.izumacha.expensetracker.controller.CategoryController;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// コントローラが依存するカテゴリサービス（モックする）
import com.izumacha.expensetracker.service.CategoryService;

// 一覧の戻り型
import java.util.List;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;
// Web スライステストを有効化するアノテーション
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
// サービスをモック Bean として差し込むアノテーション
import org.springframework.boot.test.mock.mockito.MockBean;
// HTTP リクエストを擬似実行するクライアント
import org.springframework.test.web.servlet.MockMvc;

// any() マッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.any;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// GET リクエストを組み立てる get を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
// レスポンス本体を JSONPath で検証する jsonPath を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
// レスポンスのステータスを検証する status を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// API キー認証フィルタの挙動を検証する（フィルタは有効のまま動かす）
@WebMvcTest(CategoryController.class)
class ApiKeyAuthFilterTest {

    // 擬似 HTTP リクエストを送るクライアント
    @Autowired
    private MockMvc mockMvc;

    // コントローラが依存するカテゴリサービスのモック
    @MockBean
    private CategoryService categoryService;

    // API キーが無いリクエストは 401 と {status,message} 形式になることを検証する
    @Test
    void APIキー無しは401() throws Exception {
        // ヘッダを付けずに一覧へ GET する
        mockMvc.perform(get("/api/categories"))
                // ステータスが 401 であることを検証する
                .andExpect(status().isUnauthorized())
                // 本体の status フィールドが 401 であることを検証する
                .andExpect(jsonPath("$.status").value(401))
                // 本体に message フィールドが存在することを検証する
                .andExpect(jsonPath("$.message").exists());
    }

    // 誤った API キーのリクエストは 401 になることを検証する
    @Test
    void 誤ったAPIキーは401() throws Exception {
        // 誤ったキーを付けて一覧へ GET する
        mockMvc.perform(get("/api/categories").header("X-API-Key", "wrong-key"))
                // ステータスが 401 であることを検証する
                .andExpect(status().isUnauthorized());
    }

    // 正しい API キーのリクエストはコントローラに到達して 200 になることを検証する
    @Test
    void 正しいAPIキーは200() throws Exception {
        // サービスが空のページを返すようモックする
        when(categoryService.findAll(any()))
                // 0 件・既定サイズ 20 の空ページを返す
                .thenReturn(new PageResponse<CategoryResponse>(List.of(), 0, 20, 0, 0));

        // テスト用の正しいキー（test/resources の application.properties で設定）を付けて GET する
        mockMvc.perform(get("/api/categories").header("X-API-Key", "test-api-key"))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk());
    }
}
