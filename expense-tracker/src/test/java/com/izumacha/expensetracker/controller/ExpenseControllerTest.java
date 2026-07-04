// コントローラのテストパッケージ
package com.izumacha.expensetracker.controller;

// 支出返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.ExpenseResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// 月次集計返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.SummaryResponse;
// 未存在例外を参照する
import com.izumacha.expensetracker.exception.NotFoundException;
// 支出サービスを参照する
import com.izumacha.expensetracker.service.ExpenseService;

// 10進数の金額型
import java.math.BigDecimal;
// 日付型
import java.time.LocalDate;
// 日時型
import java.time.LocalDateTime;
// 一覧の戻り型
import java.util.List;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// サービスへ渡る引数（Pageable）を捕捉するためのキャプチャ
import org.mockito.ArgumentCaptor;
// ページ指定（ページ番号・件数・並び順）を表す型
import org.springframework.data.domain.Pageable;
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
// 引数が特定の値と一致することを表す eq マッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.eq;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// モックへの呼び出しを検証する verify を取り込む（Mockito）
import static org.mockito.Mockito.verify;
// 例外送出を設定する doThrow を取り込む（Mockito）
import static org.mockito.Mockito.doThrow;
// 値を検証する assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
// POST リクエストを組み立てる post を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
// GET リクエストを組み立てる get を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
// PUT リクエストを組み立てる put を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
// DELETE リクエストを組み立てる delete を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
// レスポンスのステータスを検証する status を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
// レスポンスヘッダを検証する header を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
// レスポンス本体を JSONPath で検証する jsonPath を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

// ExpenseController を Web スライスでテストする（サービスはモック）
@WebMvcTest(ExpenseController.class)
// 認証・レート制限フィルタは別テストで検証するため、ここでは無効化してコントローラの挙動に集中する
@AutoConfigureMockMvc(addFilters = false)
class ExpenseControllerTest {

    // 擬似 HTTP リクエストを送るクライアント
    @Autowired
    private MockMvc mockMvc;

    // コントローラが依存する支出サービスのモック
    @MockBean
    private ExpenseService expenseService;

    // POST: 正しい入力なら 201 Created と本体が返ることを検証する
    @Test
    void 支出作成_正常時は201() throws Exception {
        // サービスが返す DTO を用意する
        ExpenseResponse dto = new ExpenseResponse(
                // ID
                10L,
                // 金額
                new BigDecimal("1280"),
                // カテゴリ ID
                1L,
                // カテゴリ名
                "食費",
                // 説明
                "ランチ",
                // 支出日
                LocalDate.of(2026, 6, 9),
                // 作成日時
                LocalDateTime.of(2026, 6, 9, 12, 0));
        // サービスの create が上記 DTO を返すようモックする
        when(expenseService.create(any())).thenReturn(dto);

        // 正しい JSON ボディで POST する
        mockMvc.perform(post("/api/expenses")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // リクエスト本体を渡す
                        .content("""
                                {"amount":1280,"categoryId":1,"description":"ランチ","spentOn":"2026-06-09"}
                                """))
                // ステータスが 201 であることを検証する
                .andExpect(status().isCreated())
                // Location ヘッダが作成した支出を指していることを検証する
                .andExpect(header().string("Location", "/api/expenses/10"))
                // 本体の id が 10 であることを検証する
                .andExpect(jsonPath("$.id").value(10))
                // 本体のカテゴリ名が食費であることを検証する
                .andExpect(jsonPath("$.categoryName").value("食費"));
    }

    // POST: 金額が 0 以下なら検証で 400 になり {status,message} 形式が返ることを検証する
    @Test
    void 支出作成_金額0以下は400() throws Exception {
        // 金額 0 の不正な JSON で POST する
        mockMvc.perform(post("/api/expenses")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // amount=0 の本体を渡す
                        .content("""
                                {"amount":0,"categoryId":1,"spentOn":"2026-06-09"}
                                """))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status フィールドが 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400))
                // 本体に message フィールドが存在することを検証する
                .andExpect(jsonPath("$.message").exists());
    }

    // POST: 支出日が未来なら検証で 400 になることを検証する
    @Test
    void 支出作成_未来日は400() throws Exception {
        // 未来日（2999年）の JSON で POST する
        mockMvc.perform(post("/api/expenses")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 未来日の本体を渡す
                        .content("""
                                {"amount":1000,"categoryId":1,"spentOn":"2999-01-01"}
                                """))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest());
    }

    // POST: カテゴリ ID が未指定なら検証で 400 になることを検証する
    @Test
    void 支出作成_カテゴリID未指定は400() throws Exception {
        // categoryId を欠いた JSON で POST する
        mockMvc.perform(post("/api/expenses")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // categoryId 無しの本体を渡す
                        .content("""
                                {"amount":1000,"spentOn":"2026-06-09"}
                                """))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest());
    }

    // GET: 一覧取得は 200 とページ形式のサービス結果を返すことを検証する
    @Test
    void 支出一覧_200で返る() throws Exception {
        // サービスが空のページを返すようモックする
        when(expenseService.search(any(), any(), any()))
                // 0 件・既定サイズ 20 の空ページを返す
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        // 一覧エンドポイントへ GET する
        mockMvc.perform(get("/api/expenses"))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk())
                // 本体に content 配列が存在することを検証する
                .andExpect(jsonPath("$.content").exists())
                // 全件数が 0 であることを検証する
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // GET: クライアントが sort クエリを付けても無視され、サービスへは並び順なし（クエリ側 ORDER BY 任せ）の
    // Pageable が渡ることを検証する。未検証の並び順が下位クエリへ届いて 500 を招くのを防ぐ（§9）。
    @Test
    void 支出一覧_sortクエリは無視されページ番号と件数だけが引き継がれる() throws Exception {
        // サービスが空のページを返すようモックする
        when(expenseService.search(any(), any(), any()))
                // 2 ページ目・サイズ 5 の空ページを返す（ページ指定が引き継がれることを見るため）
                .thenReturn(new PageResponse<>(List.of(), 1, 5, 0, 0));

        // 存在しない列を含む sort を付けて一覧を GET する（無視されるので 200 で返るはず）
        mockMvc.perform(get("/api/expenses").param("sort", "amount,desc").param("page", "1").param("size", "5"))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk());

        // サービスへ渡された Pageable を捕捉するキャプチャを用意する
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        // search 呼び出しの Pageable 引数を捕捉する（month / categoryId は任意一致）
        verify(expenseService).search(any(), any(), pageableCaptor.capture());
        // 捕捉した Pageable を取り出す
        Pageable passed = pageableCaptor.getValue();
        // 並び順は無指定（クライアントの sort が捨てられている）であることを検証する
        assertThat(passed.getSort().isSorted()).isFalse();
        // ページ番号はクライアント指定（2 ページ目＝index 1）が引き継がれることを検証する
        assertThat(passed.getPageNumber()).isEqualTo(1);
        // 件数もクライアント指定（5 件）が引き継がれることを検証する
        assertThat(passed.getPageSize()).isEqualTo(5);
    }

    // GET /summary: month 指定で 200 と集計が返ることを検証する
    @Test
    void 月次集計_200で返る() throws Exception {
        // サービスが集計結果を返すようモックする
        when(expenseService.summary("2026-06"))
                // 総合計 2000 円・内訳なしの集計を返す
                .thenReturn(new SummaryResponse("2026-06", new BigDecimal("2000"), List.of()));

        // month を付けて集計エンドポイントへ GET する
        mockMvc.perform(get("/api/expenses/summary").param("month", "2026-06"))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk())
                // 本体の total が 2000 であることを検証する
                .andExpect(jsonPath("$.total").value(2000));
    }

    // GET /summary: month 欠落は 400 になり {status,message} 形式が返ることを検証する
    @Test
    void 月次集計_month欠落は400() throws Exception {
        // month を付けずに集計エンドポイントへ GET する
        mockMvc.perform(get("/api/expenses/summary"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status フィールドが 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400))
                // 本体に message フィールドが存在することを検証する
                .andExpect(jsonPath("$.message").exists());
    }

    // GET /{id}: 対象が無ければサービスの例外で 404 になることを検証する
    @Test
    void 支出詳細_不在時は404() throws Exception {
        // サービスの findById が NotFoundException を投げるようモックする
        when(expenseService.findById(404L))
                // 未存在例外を投げる
                .thenThrow(new NotFoundException("expense not found: id=404"));

        // 存在しない ID で詳細を GET する
        mockMvc.perform(get("/api/expenses/404"))
                // ステータスが 404 であることを検証する
                .andExpect(status().isNotFound())
                // 本体の status フィールドが 404 であることを検証する
                .andExpect(jsonPath("$.status").value(404));
    }

    // PUT: 正しい入力なら 200 OK と更新後の本体が返ることを検証する
    @Test
    void 支出更新_正常時は200() throws Exception {
        // サービスが返す更新後の DTO を用意する
        ExpenseResponse dto = new ExpenseResponse(
                // ID（更新対象と同じ）
                5L,
                // 更新後の金額
                new BigDecimal("500"),
                // カテゴリ ID
                1L,
                // カテゴリ名
                "食費",
                // 更新後の説明
                "修正後",
                // 支出日
                LocalDate.of(2026, 6, 10),
                // 作成日時
                LocalDateTime.of(2026, 6, 10, 9, 0));
        // サービスの update が ID 5 の更新で上記 DTO を返すようモックする
        when(expenseService.update(eq(5L), any())).thenReturn(dto);

        // 正しい JSON ボディで PUT する
        mockMvc.perform(put("/api/expenses/5")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // リクエスト本体を渡す
                        .content("""
                                {"amount":500,"categoryId":1,"description":"修正後","spentOn":"2026-06-10"}
                                """))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk())
                // 本体の id が 5 であることを検証する
                .andExpect(jsonPath("$.id").value(5))
                // 本体のカテゴリ名が食費であることを検証する
                .andExpect(jsonPath("$.categoryName").value("食費"));
    }

    // PUT: 金額が 0 以下なら検証で 400 になることを検証する（更新経路でも @Valid が効くことを確認）
    @Test
    void 支出更新_金額0以下は400() throws Exception {
        // 金額 0 の不正な JSON で PUT する
        mockMvc.perform(put("/api/expenses/5")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // amount=0 の本体を渡す
                        .content("""
                                {"amount":0,"categoryId":1,"spentOn":"2026-06-10"}
                                """))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status フィールドが 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400))
                // 本体に message フィールドが存在することを検証する
                .andExpect(jsonPath("$.message").exists());
    }

    // PUT: 更新対象が無ければサービスの例外で 404 になることを検証する
    @Test
    void 支出更新_対象不在時は404() throws Exception {
        // サービスの update が NotFoundException を投げるようモックする
        when(expenseService.update(eq(99L), any()))
                // 未存在例外を投げる
                .thenThrow(new NotFoundException("expense not found: id=99"));

        // 存在しない ID で PUT する
        mockMvc.perform(put("/api/expenses/99")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 正しい形式の本体を渡す（対象が無いことだけを検証する）
                        .content("""
                                {"amount":500,"categoryId":1,"spentOn":"2026-06-10"}
                                """))
                // ステータスが 404 であることを検証する
                .andExpect(status().isNotFound())
                // 本体の status フィールドが 404 であることを検証する
                .andExpect(jsonPath("$.status").value(404));
    }

    // DELETE: 正常時は 204 No Content になることを検証する
    @Test
    void 支出削除_正常時は204() throws Exception {
        // サービスの delete は何もしない（戻り値なし）
        // 既定で何もしないため明示のスタブは不要

        // 削除エンドポイントへ DELETE する
        mockMvc.perform(delete("/api/expenses/5"))
                // ステータスが 204 であることを検証する
                .andExpect(status().isNoContent());
    }

    // DELETE: 対象が無ければサービスの例外で 404 になることを検証する
    @Test
    void 支出削除_不在時は404() throws Exception {
        // delete が NotFoundException を投げるようモックする
        doThrow(new NotFoundException("expense not found: id=5"))
                // 対象のサービスメソッドを指定する
                .when(expenseService).delete(5L);

        // 存在しない ID で DELETE する
        mockMvc.perform(delete("/api/expenses/5"))
                // ステータスが 404 であることを検証する
                .andExpect(status().isNotFound());
    }
}
