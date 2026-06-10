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

// 各テスト前に共通準備を行うアノテーション
import org.junit.jupiter.api.BeforeEach;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;
// Web スライステストを有効化するアノテーション
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
// サービスをモック Bean として差し込むアノテーション
import org.springframework.boot.test.mock.mockito.MockBean;
// テスト用にプロパティを上書きするアノテーション
import org.springframework.test.context.TestPropertySource;
// HTTP リクエストを擬似実行するクライアント
import org.springframework.test.web.servlet.MockMvc;

// any() マッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.any;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// GET リクエストを組み立てる get を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
// レスポンスのステータスを検証する status を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// レート制限フィルタの挙動を検証する（上限を 2 に下げて超過を起こす）
@WebMvcTest(CategoryController.class)
// 単位時間あたりの上限を 2 に、ウィンドウを十分長くしてテスト中に切り替わらないようにする
@TestPropertySource(properties = {"app.rate-limit.capacity=2", "app.rate-limit.window-seconds=3600"})
class RateLimitFilterTest {

    // 擬似 HTTP リクエストを送るクライアント
    @Autowired
    private MockMvc mockMvc;

    // コントローラが依存するカテゴリサービスのモック
    @MockBean
    private CategoryService categoryService;

    // 各テスト前にサービスのモック応答を用意する
    @BeforeEach
    void setUp() {
        // サービスが空のページを返すようモックする（上限内のリクエストは 200 になる）
        when(categoryService.findAll(any()))
                // 0 件・既定サイズ 20 の空ページを返す
                .thenReturn(new PageResponse<CategoryResponse>(List.of(), 0, 20, 0, 0));
    }

    // 上限（2）を超える 3 回目のリクエストは 429 になることを検証する
    @Test
    void 上限超過は429() throws Exception {
        // 1 回目（上限内）は 200 になることを検証する
        mockMvc.perform(get("/api/categories"))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk());
        // 2 回目（上限内）は 200 になることを検証する
        mockMvc.perform(get("/api/categories"))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk());
        // 3 回目（上限超過）は 429 になることを検証する
        mockMvc.perform(get("/api/categories"))
                // ステータスが 429 であることを検証する
                .andExpect(status().isTooManyRequests());
    }
}
