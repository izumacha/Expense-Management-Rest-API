// Web 横断ユーティリティのテストパッケージ
package com.izumacha.expensetracker.web;

// テスト対象の経路に使うカテゴリコントローラを参照する
import com.izumacha.expensetracker.controller.CategoryController;
// セキュリティ設定クラス（anyRequest().permitAll() を定義）を参照する
import com.izumacha.expensetracker.config.SecurityConfig;
// カテゴリ作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateCategoryRequest;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// コントローラが依存するカテゴリサービス（モックする）
import com.izumacha.expensetracker.service.CategoryService;

// JSON へ直列化するための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;

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
// @WebMvcTest のデフォルト認証要求を無効化し、実際の SecurityConfig（permitAll）を読み込むために使う
import org.springframework.context.annotation.Import;
// HTTP メディアタイプを表す型
import org.springframework.http.MediaType;

// any() マッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.any;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// POST リクエストを組み立てる post を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
// レスポンスのステータスを検証する status を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
// レスポンス本体を JSONPath で検証する jsonPath を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

// リクエスト本文サイズ上限フィルタの挙動を検証する。
// 上限（200バイト）を「通常サイズの JSON は通す・明らかに超過したボディは拒否する」の
// 境目として使えるよう、カテゴリ作成の通常ペイロード（数十バイト）より十分大きく、
// 意図的に肥大化させたペイロード（数百バイト超）より小さい値に設定する。
@WebMvcTest(CategoryController.class)
// @WebMvcTest はデフォルトで Spring Security の認証要求を有効化するが、
// 実際のアプリは anyRequest().permitAll() なので SecurityConfig を明示インポートして一致させる
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"app.request.max-body-size-bytes=200"})
class RequestBodySizeLimitFilterTest {

    // 擬似 HTTP リクエストを送るクライアント
    @Autowired
    private MockMvc mockMvc;

    // コントローラが依存するカテゴリサービスのモック
    @MockBean
    private CategoryService categoryService;

    // JSON 本文を組み立てるためのマッパー
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Content-Length が上限（200バイト）を超えるリクエストは、ボディを読む前に 413 で拒否されることを検証する
    @Test
    void 宣言サイズが上限超過なら413() throws Exception {
        // 上限を大きく超える（200文字超の）カテゴリ名を持つ JSON ボディを組み立てる
        String oversizedName = "あ".repeat(300);
        // POST リクエストを送信し、413 が返ることを検証する
        mockMvc.perform(post("/api/categories")
                        // JSON として送る
                        .contentType(MediaType.APPLICATION_JSON)
                        // 上限超過のボディを設定する（MockHttpServletRequest は content から Content-Length を導出する）
                        .content(objectMapper.writeValueAsString(new CreateCategoryRequest(oversizedName))))
                // ステータスが 413 (Payload Too Large) であることを検証する
                .andExpect(status().isPayloadTooLarge())
                // 契約どおり status フィールドが 413 であることを検証する
                .andExpect(jsonPath("$.status").value(413))
                // 安全な文言（入力値を含まない）が返ることを検証する
                .andExpect(jsonPath("$.message").value("リクエスト本文が大きすぎます"));
    }

    // 上限（200バイト）を超えない通常サイズのリクエストは、フィルタを通過してコントローラまで到達することを検証する
    @Test
    void 上限内なら通常どおり処理される() throws Exception {
        // サービスが正常に応答するようモックする
        when(categoryService.create(any()))
                // 作成結果を返す
                .thenReturn(new CategoryResponse(1L, "食費"));
        // 上限を超えない通常サイズのカテゴリ作成リクエストを送信する
        mockMvc.perform(post("/api/categories")
                        // JSON として送る
                        .contentType(MediaType.APPLICATION_JSON)
                        // 通常サイズのボディを設定する
                        .content(objectMapper.writeValueAsString(new CreateCategoryRequest("食費"))))
                // ステータスが 201 (Created) であることを検証する（上限付きストリームが正常な読み取りを妨げない）
                .andExpect(status().isCreated())
                // レスポンス本体がサービスの戻り値どおりであることを検証する
                .andExpect(jsonPath("$.name").value("食費"));
    }
}
