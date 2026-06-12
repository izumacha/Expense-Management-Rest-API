// セキュリティ関連のテストパッケージ（X-Forwarded-For ヘッダ経路の検証用）
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
// @WebMvcTest のデフォルト認証要求を無効化し、実際の SecurityConfig（permitAll）を読み込むために使う
import org.springframework.context.annotation.Import;
// セキュリティ設定クラス（anyRequest().permitAll() を定義）
import com.izumacha.expensetracker.config.SecurityConfig;

// any() マッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.any;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// GET リクエストを組み立てる get を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
// レスポンスのステータスを検証する status を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * X-Forwarded-For ヘッダを信頼する設定（trust-x-forwarded-for=true）での
 * レート制限フィルタの挙動を検証するテストクラス。
 *
 * <p>検証するシナリオ:
 * <ol>
 *   <li>XFF の末尾 IP でレート制限がかかること（末尾 = プロキシが記録した接続元）</li>
 *   <li>XFF にカンマ区切りで複数 IP が並ぶとき、末尾 IP がレート制限キーになること</li>
 *   <li>XFF の値が IP 形式でない場合は getRemoteAddr()（127.0.0.1）にフォールバックすること</li>
 * </ol>
 */
// テスト対象のコントローラを CategoryController に限定する（スライステスト）
@WebMvcTest(CategoryController.class)
// @WebMvcTest はデフォルトで Spring Security の認証要求を有効化するが、
// 実際のアプリは anyRequest().permitAll() なので SecurityConfig を明示インポートして一致させる
@Import(SecurityConfig.class)
// 単位時間あたりの上限を 2 に、ウィンドウを十分長くしてテスト中に切り替わらないようにする。
// trust-x-forwarded-for=true にして XFF ヘッダを信頼する経路を有効化する
@TestPropertySource(properties = {
        "app.rate-limit.capacity=2",
        "app.rate-limit.window-seconds=3600",
        "app.rate-limit.trust-x-forwarded-for=true"
})
class RateLimitFilterXffTest {

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

    /**
     * X-Forwarded-For の末尾 IP（1.2.3.4）でレート制限がかかることを検証する。
     *
     * <p>末尾トークンはプロキシが記録した接続元であり、クライアントが偽造できない
     * （CLAUDE.md §9、Spring ForwardedHeaderFilter と同一の規約）。
     */
    // XFF の末尾 IP でレート制限が機能することを確認するテスト
    @Test
    void XFF末尾IPでレート制限がかかる() throws Exception {
        // X-Forwarded-For: 1.2.3.4 を 1 回目（上限内）→ 200 を検証する
        mockMvc.perform(get("/api/categories")
                        // クライアントから直接来た単一 IP を XFF に設定する
                        .header("X-Forwarded-For", "1.2.3.4"))
                // 1 回目は制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // X-Forwarded-For: 1.2.3.4 を 2 回目（上限内）→ 200 を検証する
        mockMvc.perform(get("/api/categories")
                        // 同じ IP（1.2.3.4）を再度送る
                        .header("X-Forwarded-For", "1.2.3.4"))
                // 2 回目も制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // X-Forwarded-For: 1.2.3.4 を 3 回目（上限超過）→ 429 を検証する
        mockMvc.perform(get("/api/categories")
                        // 同じ IP（1.2.3.4）を 3 回目送る（上限 2 を超える）
                        .header("X-Forwarded-For", "1.2.3.4"))
                // 3 回目は上限超過なので 429 になることを検証する
                .andExpect(status().isTooManyRequests());
    }

    /**
     * XFF にカンマ区切りで複数の IP が含まれる場合、末尾 IP がレート制限キーになることを検証する。
     *
     * <p>攻撃者がヘッダの先頭に偽の IP（spoofed-ip）を挿入しても、
     * フィルタは末尾のプロキシ記録 IP（5.6.7.8）をキーとして使うため、
     * 先頭の偽 IP を変えても同一クライアントとして正しくレート制限がかかる。
     */
    // 複数 IP を持つ XFF で末尾 IP がキーになることを確認するテスト
    @Test
    void XFF複数IPの末尾でレート制限がかかる() throws Exception {
        // X-Forwarded-For: spoofed-ip, 5.6.7.8 → 末尾 5.6.7.8 がキー、1 回目は 200
        mockMvc.perform(get("/api/categories")
                        // 先頭に攻撃者が挿入した偽 IP、末尾がプロキシ記録の実 IP
                        .header("X-Forwarded-For", "spoofed-ip, 5.6.7.8"))
                // 1 回目は制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // X-Forwarded-For: other-spoofed, 5.6.7.8 → 先頭を変えても末尾 5.6.7.8 は同じ、2 回目は 200
        mockMvc.perform(get("/api/categories")
                        // 先頭の偽 IP を変えても末尾（5.6.7.8）は同じなので同一クライアント扱い
                        .header("X-Forwarded-For", "other-spoofed, 5.6.7.8"))
                // 2 回目も制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // X-Forwarded-For: yet-another-spoofed, 5.6.7.8 → 先頭を変えても末尾 5.6.7.8 は同じ、3 回目は 429
        mockMvc.perform(get("/api/categories")
                        // 先頭の偽 IP をさらに変えても末尾（5.6.7.8）が同じなので上限超過
                        .header("X-Forwarded-For", "yet-another-spoofed, 5.6.7.8"))
                // 3 回目は上限超過なので 429 になることを検証する
                .andExpect(status().isTooManyRequests());
    }

    /**
     * XFF の末尾値が IP 形式でない場合（ホスト名など）、getRemoteAddr()（MockMvc では 127.0.0.1）
     * にフォールバックしてレート制限がかかることを検証する。
     *
     * <p>IP 形式でない値（例: "invalid-hostname"）を looksLikeIp() が拒否し、
     * 安全な getRemoteAddr() に切り替えることで、予期しない文字列がキーになるのを防ぐ。
     */
    // XFF が無効な形式のとき getRemoteAddr() にフォールバックすることを確認するテスト
    @Test
    void XFF不正形式はリモートアドレスにフォールバックする() throws Exception {
        // X-Forwarded-For: invalid-hostname → IP 形式でないため 127.0.0.1 がキーに、1 回目は 200
        mockMvc.perform(get("/api/categories")
                        // IP 形式でないホスト名を XFF に設定する（不正な形式）
                        .header("X-Forwarded-For", "invalid-hostname"))
                // 1 回目は制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // X-Forwarded-For: invalid-hostname → 引き続き 127.0.0.1 がキーに、2 回目は 200
        mockMvc.perform(get("/api/categories")
                        // 同じ無効なホスト名を再度送る（フォールバック先は 127.0.0.1 で同じ）
                        .header("X-Forwarded-For", "invalid-hostname"))
                // 2 回目も制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // X-Forwarded-For: invalid-hostname → 引き続き 127.0.0.1 がキーに、3 回目は 429
        mockMvc.perform(get("/api/categories")
                        // 3 回目も無効なホスト名。フォールバック先 127.0.0.1 の上限を超える
                        .header("X-Forwarded-For", "invalid-hostname"))
                // 3 回目は上限超過なので 429 になることを検証する
                .andExpect(status().isTooManyRequests());
    }
}
