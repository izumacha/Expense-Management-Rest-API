// 例外ハンドラのテストパッケージ
package com.izumacha.expensetracker.exception;

// 対象コントローラ（このコントローラ経由でハンドラの挙動を検証する）
import com.izumacha.expensetracker.controller.ExpenseController;
// コントローラが依存する支出サービス（モックする）
import com.izumacha.expensetracker.service.ExpenseService;

// ログレベル（WARN 等）を表す Logback の列挙
import ch.qos.logback.classic.Level;
// Logback の実体ロガー（テストから appender を付け外しするために使う）
import ch.qos.logback.classic.Logger;
// 1 件のログ出力イベントを表す型
import ch.qos.logback.classic.spi.ILoggingEvent;
// 出力されたログをメモリ上のリストに溜めるテスト用 appender
import ch.qos.logback.core.read.ListAppender;
// ロガーを取得するファクトリ（SLF4J）
import org.slf4j.LoggerFactory;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;
// MockMvc の自動設定（フィルタの有効・無効を制御する）アノテーション
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
// Web スライステストを有効化するアノテーション
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
// サービスをモック Bean として差し込むアノテーション
import org.springframework.boot.test.mock.mockito.MockBean;
// DB アクセス障害を模す具象例外
import org.springframework.dao.DataAccessResourceFailureException;
// HTTP リクエストを擬似実行するクライアント
import org.springframework.test.web.servlet.MockMvc;

// 値の検証に使う assertThat を取り込む（AssertJ。他のテストクラスと同じ static import に統一する）
import static org.assertj.core.api.Assertions.assertThat;
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
// 認証・レート制限フィルタは別テストで検証するため、ここでは無効化して例外整形の挙動に集中する
@AutoConfigureMockMvc(addFilters = false)
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
        when(expenseService.search(any(), any(), any()))
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
        when(expenseService.search(any(), any(), any()))
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
        when(expenseService.search(any(), any(), any()))
                // 内部詳細を含む例外を投げる（外部に漏れてはいけない）
                .thenThrow(new IllegalArgumentException("internal detail: ownerId=42"));

        // 一覧エンドポイントへ GET する
        mockMvc.perform(get("/api/expenses"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の message が汎用の安全文言（内部詳細を含まない）であることを検証する
                .andExpect(jsonPath("$.message").value(ErrorMessages.BAD_REQUEST));
    }

    // 想定外の IllegalArgumentException はサーバログに WARN で記録され、握り潰されない
    // （サーバ側バグの発生を観測できる）ことを検証する。外部応答は上のテストで検証済みのため、
    // ここでは Logback のテスト用 appender でログ出力の有無・レベル・原因例外の連鎖を確認する
    @Test
    void 想定外のIllegalArgumentはWARNログに記録される() throws Exception {
        // 検証対象ハンドラの実体ロガー（Logback）を取得する
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        // ログ出力をメモリ上に溜めるテスト用 appender を生成する
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        // appender を稼働状態にする（start しないとイベントが溜まらない）
        appender.start();
        // ハンドラのロガーへ appender を取り付ける
        handlerLogger.addAppender(appender);
        try {
            // search が内部詳細を含む IllegalArgumentException を投げるようモックする
            when(expenseService.search(any(), any(), any()))
                    // サーバ側バグを模した例外を投げる
                    .thenThrow(new IllegalArgumentException("internal detail: ownerId=42"));

            // 一覧エンドポイントへ GET する（400 になることは前提として軽く確認する）
            mockMvc.perform(get("/api/expenses"))
                    // ステータスが 400 であることを検証する
                    .andExpect(status().isBadRequest());

            // WARN レベルで、原因例外（IllegalArgumentException）を連鎖させたログが 1 件記録されたことを検証する
            assertThat(appender.list)
                    // WARN かつ throwable が IllegalArgumentException のイベントが存在することを確認する
                    .anySatisfy(event -> {
                        // ログレベルが WARN であることを検証する
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        // 原因例外の型名が IllegalArgumentException であることを検証する（追跡可能性の確認）
                        assertThat(event.getThrowableProxy().getClassName())
                                .isEqualTo(IllegalArgumentException.class.getName());
                    });
        } finally {
            // 他のテストへ影響しないよう、取り付けた appender を必ず取り外す
            handlerLogger.detachAppender(appender);
        }
    }

    // 未定義パス（存在しないURL）は 404 と {status,message} 形式になり、
    // Spring Boot 既定の BasicErrorController 形式（timestamp/error/path 等）に化けないことを検証する
    @Test
    void 未定義パスは404で統一形式() throws Exception {
        // どのコントローラにもマッピングされていないパスへ GET する
        mockMvc.perform(get("/api/does-not-exist"))
                // ステータスが 404 であることを検証する
                .andExpect(status().isNotFound())
                // 本体の status が 404 であることを検証する
                .andExpect(jsonPath("$.status").value(404))
                // 本体の message が安全な文言であることを検証する
                .andExpect(jsonPath("$.message").value(ErrorMessages.PATH_NOT_FOUND))
                // Spring Boot 既定のエラー本体が持つ timestamp フィールドが存在しないことを検証する
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                // Spring Boot 既定のエラー本体が持つ path フィールドが存在しないことを検証する
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    // アプリ由来の RequestBodyTooLargeException は 413 と、送出側が指定した安全文言で返ることを検証する
    // （RequestBodySizeLimitFilter が Content-Length を偽る/付けないクライアントに備えて実読み取り
    // バイト数自体を打ち切る際に、コントローラ呼び出し前の JSON パース中に送出する例外の整形を確認する）
    @Test
    void 本文サイズ超過例外は413で安全文言() throws Exception {
        // search が本文サイズ超過の RequestBodyTooLargeException を投げるようモックする
        when(expenseService.search(any(), any(), any()))
                // 外部公開して安全な文言を持つ本文サイズ超過例外を投げる
                .thenThrow(new RequestBodyTooLargeException(ErrorMessages.PAYLOAD_TOO_LARGE));

        // 一覧エンドポイントへ GET する
        mockMvc.perform(get("/api/expenses"))
                // ステータスが 413 であることを検証する
                .andExpect(status().isPayloadTooLarge())
                // 本体の status が 413 であることを検証する
                .andExpect(jsonPath("$.status").value(413))
                // 本体の message が送出側の安全文言であることを検証する
                .andExpect(jsonPath("$.message").value(ErrorMessages.PAYLOAD_TOO_LARGE));
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
