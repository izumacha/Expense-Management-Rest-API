// 例外ハンドラのテストパッケージ
package com.izumacha.expensetracker.exception;

// 対象コントローラ（このコントローラ経由でハンドラの挙動を検証する）
import com.izumacha.expensetracker.controller.ExpenseController;
// コントローラが依存する支出サービス（モックする）
import com.izumacha.expensetracker.service.ExpenseService;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;
// Web スライステストを有効化するアノテーション
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
// サービスをモック Bean として差し込むアノテーション
import org.springframework.boot.test.mock.mockito.MockBean;
// DB アクセス障害を模す具象例外
import org.springframework.dao.DataAccessResourceFailureException;
// HTTP リクエストを擬似実行するクライアント
import org.springframework.test.web.servlet.MockMvc;

// any() マッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.any;
// 戻り値・例外を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// GET リクエストを組み立てる get を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
// POST リクエストを組み立てる post を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
// レスポンス本体を JSONPath で検証する jsonPath を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
// レスポンスのステータスを検証する status を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GlobalExceptionHandler の網羅した挙動を ExpenseController 経由で検証する
@WebMvcTest(ExpenseController.class)
class GlobalExceptionHandlerTest {

    // 擬似 HTTP リクエストを送るクライアント
    @Autowired
    private MockMvc mockMvc;

    // コントローラが依存する支出サービスのモック
    @MockBean
    private ExpenseService expenseService;

    // パス変数の型不一致（/api/expenses/abc）は 400 と {status,message} 形式になることを検証する
    @Test
    void パス変数の型不一致は400で統一形式() throws Exception {
        // 数値であるべき id に文字列を渡して GET する
        mockMvc.perform(get("/api/expenses/abc"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status が 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400))
                // 本体に message が存在することを検証する
                .andExpect(jsonPath("$.message").exists());
    }

    // クエリパラメータの型不一致（categoryId=abc）は 400 になることを検証する
    @Test
    void クエリの型不一致は400() throws Exception {
        // 数値であるべき categoryId に文字列を渡して GET する
        mockMvc.perform(get("/api/expenses").param("categoryId", "abc"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status が 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400));
    }

    // 必須パラメータ欠落（summary の month 欠落）は 400 と {status,message} 形式になることを検証する
    @Test
    void 必須パラメータ欠落は400で統一形式() throws Exception {
        // month を付けずに集計エンドポイントへ GET する
        mockMvc.perform(get("/api/expenses/summary"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status が 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400))
                // 本体に message が存在することを検証する
                .andExpect(jsonPath("$.message").exists());
    }

    // DB アクセス障害は 500 と汎用の安全文言になり、内部詳細を漏らさないことを検証する
    @Test
    void DBアクセス障害は500で汎用文言() throws Exception {
        // search が DB 障害例外を投げるようモックする
        when(expenseService.search(any(), any()))
                // 内部詳細（接続先など）を含む例外を投げる
                .thenThrow(new DataAccessResourceFailureException("db down at 10.0.0.1:5432"));

        // 一覧エンドポイントへ GET する
        mockMvc.perform(get("/api/expenses"))
                // ステータスが 500 であることを検証する
                .andExpect(status().isInternalServerError())
                // 本体の status が 500 であることを検証する
                .andExpect(jsonPath("$.status").value(500))
                // 本体の message が汎用の安全文言（内部詳細を含まない）であることを検証する
                .andExpect(jsonPath("$.message").value(ErrorMessages.INTERNAL_ERROR));
    }

    // 想定外の実行時例外は 500 と汎用の安全文言になることを検証する
    @Test
    void 想定外例外は500で汎用文言() throws Exception {
        // search が想定外の実行時例外を投げるようモックする
        when(expenseService.search(any(), any()))
                // 内部メッセージを含む例外を投げる
                .thenThrow(new RuntimeException("boom internal detail"));

        // 一覧エンドポイントへ GET する
        mockMvc.perform(get("/api/expenses"))
                // ステータスが 500 であることを検証する
                .andExpect(status().isInternalServerError())
                // 本体の message が汎用の安全文言（内部詳細を含まない）であることを検証する
                .andExpect(jsonPath("$.message").value(ErrorMessages.INTERNAL_ERROR));
    }

    // アプリ由来の InvalidRequestException は 400 と、送出側が指定した安全文言で返ることを検証する
    @Test
    void 不正リクエスト例外は400で安全文言() throws Exception {
        // summary が月形式不正の InvalidRequestException を投げるようモックする
        when(expenseService.summary("bad"))
                // 外部公開して安全な文言を持つ不正リクエスト例外を投げる
                .thenThrow(new InvalidRequestException(ErrorMessages.INVALID_MONTH_FORMAT));

        // 不正な月を付けて集計エンドポイントへ GET する
        mockMvc.perform(get("/api/expenses/summary").param("month", "bad"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の message が送出側の安全文言であることを検証する
                .andExpect(jsonPath("$.message").value(ErrorMessages.INVALID_MONTH_FORMAT));
    }

    // 想定外の IllegalArgumentException は 400 だが内部メッセージを返さず汎用文言になることを検証する
    @Test
    void 想定外のIllegalArgumentは400で汎用文言() throws Exception {
        // search が内部詳細を含む IllegalArgumentException を投げるようモックする
        when(expenseService.search(any(), any()))
                // 内部詳細を含む例外を投げる（外部に漏れてはいけない）
                .thenThrow(new IllegalArgumentException("internal detail: ownerId=42"));

        // 一覧エンドポイントへ GET する
        mockMvc.perform(get("/api/expenses"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の message が汎用の安全文言（内部詳細を含まない）であることを検証する
                .andExpect(jsonPath("$.message").value(ErrorMessages.BAD_REQUEST));
    }

    // 不正な JSON ボディは 400 のまま（catch-all で 500 に退行しない）であることを検証する
    @Test
    void 不正なJSONは400のまま() throws Exception {
        // 壊れた JSON を本体にして POST する
        mockMvc.perform(post("/api/expenses")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 閉じ括弧のない壊れた JSON を渡す
                        .content("{"))
                // ステータスが 400 であることを検証する（500 ではない）
                .andExpect(status().isBadRequest())
                // 本体の status が 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400));
    }
}
