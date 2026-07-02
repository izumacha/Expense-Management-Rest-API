// コントローラのテストパッケージ
package com.izumacha.expensetracker.controller;

// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// 重複例外を参照する
import com.izumacha.expensetracker.exception.DuplicateException;
// カテゴリサービスを参照する
import com.izumacha.expensetracker.service.CategoryService;

// 一覧の戻り型
import java.util.List;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// MockMvc の自動設定（フィルタの有効・無効を制御する）アノテーション
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
// Web スライステストを有効化するアノテーション
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
// サービスをモック Bean として差し込むアノテーション
import org.springframework.boot.test.mock.mockito.MockBean;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;
// HTTP リクエストを擬似実行するクライアント
import org.springframework.test.web.servlet.MockMvc;

// any() マッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.any;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// POST リクエストを組み立てる post を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
// GET リクエストを組み立てる get を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
// レスポンスのステータスを検証する status を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
// レスポンスヘッダを検証する header を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
// レスポンス本体を JSONPath で検証する jsonPath を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

// CategoryController を Web スライスでテストする（サービスはモック）
@WebMvcTest(CategoryController.class)
// レート制限フィルタは別テストで検証するため、ここでは無効化してコントローラの挙動に集中する
@AutoConfigureMockMvc(addFilters = false)
class CategoryControllerTest {

    // 擬似 HTTP リクエストを送るクライアント
    @Autowired
    private MockMvc mockMvc;

    // コントローラが依存するカテゴリサービスのモック
    @MockBean
    private CategoryService categoryService;

    // POST: 正しい入力なら 201 Created と本体が返ることを検証する
    @Test
    void カテゴリ作成_正常時は201() throws Exception {
        // サービスが返す DTO を用意する
        when(categoryService.create(any()))
                // ID 採番済みのカテゴリ DTO を返す
                .thenReturn(new CategoryResponse(1L, "食費"));

        // 正しい JSON ボディで POST する
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 名前を持つ本体を渡す
                        .content("""
                                {"name":"食費"}
                                """))
                // ステータスが 201 であることを検証する
                .andExpect(status().isCreated())
                // Location ヘッダが作成したカテゴリを指していることを検証する
                .andExpect(header().string("Location", "/api/categories/1"))
                // 本体の id が 1 であることを検証する
                .andExpect(jsonPath("$.id").value(1))
                // 本体の name が食費であることを検証する
                .andExpect(jsonPath("$.name").value("食費"));
    }

    // POST: 名前が空白なら検証で 400 になることを検証する
    @Test
    void カテゴリ作成_空白名は400() throws Exception {
        // 空文字の名前で POST する
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 空の名前を持つ本体を渡す
                        .content("""
                                {"name":""}
                                """))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status フィールドが 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400));
    }

    // POST: 名前が 50 文字超なら検証で 400 になることを検証する
    @Test
    void カテゴリ作成_長すぎる名前は400() throws Exception {
        // 51 文字の名前を作る（"あ" を 51 回繰り返す）
        String longName = "あ".repeat(51);
        // 長すぎる名前で POST する
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 51 文字の名前を持つ本体を渡す
                        .content("{\"name\":\"" + longName + "\"}"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest());
    }

    // POST: サービスが重複例外を投げると 409 になることを検証する
    @Test
    void カテゴリ作成_重複は409() throws Exception {
        // サービスの create が DuplicateException を投げるようモックする
        when(categoryService.create(any()))
                // 重複例外を投げる
                .thenThrow(new DuplicateException("category name already exists: 食費"));

        // 既存と同名で POST する
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 名前を持つ本体を渡す
                        .content("""
                                {"name":"食費"}
                                """))
                // ステータスが 409 であることを検証する
                .andExpect(status().isConflict())
                // 本体の status フィールドが 409 であることを検証する
                .andExpect(jsonPath("$.status").value(409));
    }

    // GET: 一覧取得は 200 とページ形式のサービス結果を返すことを検証する
    @Test
    void カテゴリ一覧_200で返る() throws Exception {
        // サービスが 1 件のカテゴリを含むページを返すようモックする
        when(categoryService.findAll(any()))
                // 食費 1 件・全 1 件のページを返す
                .thenReturn(new PageResponse<>(List.of(new CategoryResponse(1L, "食費")), 0, 20, 1, 1));

        // 一覧エンドポイントへ GET する
        mockMvc.perform(get("/api/categories"))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk())
                // 本体 content 先頭の name が食費であることを検証する
                .andExpect(jsonPath("$.content[0].name").value("食費"));
    }
}
