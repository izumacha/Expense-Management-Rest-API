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
// リクエストの接続元 IP（remoteAddr）をテスト用に差し替える後処理インターフェース
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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
 *   <li>XFF ヘッダが複数行あるとき、最後の行（追記型プロキシが付与した行）がキーになること</li>
 *   <li>XFF の値が IP 形式でない場合は getRemoteAddr()（127.0.0.1）にフォールバックすること</li>
 *   <li>コロンを含まない 16 進のみの値（"deadbeef"）が IPv6 として誤採用されないこと</li>
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

    // 擬似リクエストの接続元 IP（remoteAddr）を指定した値に差し替える後処理を作るヘルパー。
    // 【なぜ必要か】フィルタのカウンタはテストメソッド間で共有される（Spring コンテキストが同一のため）。
    // フォールバック検証テストが複数あると既定の 127.0.0.1 のカウンタを取り合ってしまうので、
    // テストごとに固有の接続元 IP を与えてカウンタの衝突を防ぐ。
    private static RequestPostProcessor remoteAddr(String ip) {
        // リクエストの接続元 IP を指定値に書き換えてからそのまま返す後処理を返す
        return request -> {
            // MockMvc が生成した擬似リクエストの接続元 IP を差し替える
            request.setRemoteAddr(ip);
            // 差し替え済みのリクエストを返して処理を続行させる
            return request;
        };
    }

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

    /**
     * X-Forwarded-For ヘッダが「複数行」ある場合、最後のヘッダ行がレート制限キーの元になることを検証する。
     *
     * <p>HAProxy の option forwardfor など追記型のプロキシは、既存の X-Forwarded-For 行へ連結せず
     * 独立したヘッダ行を「後ろ」に追加する。getHeader() は最初の 1 行しか返さないため、旧実装では
     * 攻撃者が最初から送り込んだ行（1 行目）が選ばれ、その行を変えるだけでレート制限キーを
     * 偽装できてしまっていた。最後の行（プロキシが付与した行）を採用すれば、1 行目に何を
     * 送っても同一クライアントとして正しくレート制限がかかる。
     */
    // 複数ヘッダ行の XFF で最後の行がキーになることを確認するテスト
    @Test
    void XFF複数ヘッダ行では最後の行でレート制限がかかる() throws Exception {
        // 1 行目=攻撃者偽装（203.0.113.1）、2 行目=プロキシ付与（198.51.100.7）→ 最後の行がキー、1 回目は 200
        mockMvc.perform(get("/api/categories")
                        // 攻撃者がリクエストに最初から含めた偽の XFF 行（正しい IP 形式で偽装している点が重要）
                        .header("X-Forwarded-For", "203.0.113.1")
                        // 追記型プロキシが後ろに追加した実接続元の XFF 行
                        .header("X-Forwarded-For", "198.51.100.7"))
                // 1 回目は制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // 1 行目の偽装 IP を変えても、最後の行（198.51.100.7）が同じなら同一クライアント扱い、2 回目は 200
        mockMvc.perform(get("/api/categories")
                        // 攻撃者が 1 行目の偽装 IP を変える（キー偽装の試み）
                        .header("X-Forwarded-For", "203.0.113.2")
                        // プロキシが付与する実接続元の行は変わらない
                        .header("X-Forwarded-For", "198.51.100.7"))
                // 2 回目も制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // 1 行目をさらに変えても最後の行が同じなので上限超過、3 回目は 429
        mockMvc.perform(get("/api/categories")
                        // 攻撃者が 1 行目の偽装 IP をさらに変える
                        .header("X-Forwarded-For", "203.0.113.3")
                        // プロキシが付与する実接続元の行は変わらない
                        .header("X-Forwarded-For", "198.51.100.7"))
                // 3 回目は上限超過なので 429 になることを検証する（1 行目の偽装ではキーを変えられない）
                .andExpect(status().isTooManyRequests());
    }

    /**
     * コロンを含まない 16 進文字のみの文字列（例: "deadbeef"）は IPv6 として誤採用されず、
     * getRemoteAddr() にフォールバックすることを検証する。
     *
     * <p>旧パターン（^[0-9a-fA-F:]{2,39}$）は "deadbeef" を IPv6 と誤判定し、接続元 IP と
     * 無関係な任意キーとしてカウンタを汚染できた。修正後はコロン必須のため拒否される。
     * 同じ "deadbeef" を送っても接続元 IP が異なれば別カウンタになる（＝ヘッダ値がキーに
     * なっていない）ことで、フォールバックを確認する。
     */
    // 16 進のみ（コロン無し）の XFF が IPv6 として採用されないことを確認するテスト
    @Test
    void XFFがコロン無しの16進文字列なら採用せずリモートアドレスにフォールバックする() throws Exception {
        // XFF: deadbeef → IPv6 形式でないため接続元 IP（10.99.1.1）がキーに、1 回目は 200
        mockMvc.perform(get("/api/categories")
                        // コロンを含まない 16 進文字のみの値を送る（旧パターンでは IPv6 と誤判定されていた）
                        .header("X-Forwarded-For", "deadbeef")
                        // このリクエスト固有の接続元 IP を設定する
                        .with(remoteAddr("10.99.1.1")))
                // 1 回目（このキーでは初回）なので 200 になることを検証する
                .andExpect(status().isOk());

        // 同じ XFF: deadbeef でも接続元 IP が違えば別カウンタ、こちらも 200
        mockMvc.perform(get("/api/categories")
                        // 同じ 16 進のみの値を送る
                        .header("X-Forwarded-For", "deadbeef")
                        // 別の接続元 IP を設定する（"deadbeef" がキーなら同一カウンタになってしまう）
                        .with(remoteAddr("10.99.1.2")))
                // 別カウンタ扱いなので 200 になることを検証する
                .andExpect(status().isOk());

        // さらに別の接続元 IP でも 200（3 回とも別カウンタ＝ヘッダ値はキーとして採用されていない）
        mockMvc.perform(get("/api/categories")
                        // 同じ 16 進のみの値を送る
                        .header("X-Forwarded-For", "deadbeef")
                        // さらに別の接続元 IP を設定する
                        .with(remoteAddr("10.99.1.3")))
                // "deadbeef" がキーなら 3 回目で 429 になるはずだが、拒否されているため 200 になることを検証する
                .andExpect(status().isOk());
    }

    /**
     * XFF の値がカンマ 1 つだけ（","）の場合に、例外を出さず getRemoteAddr()（このテストでは 10.99.0.1）
     * にフォールバックしてレート制限がかかることを検証する境界値テスト。
     *
     * <p>【なぜこのケースが重要か】Java の split(",") は末尾の空トークンを捨てるため
     * "," は空配列になり、修正前は parts[parts.length - 1] が
     * ArrayIndexOutOfBoundsException を投げてフィルタごとクラッシュしていた
     * （DispatcherServlet より手前なので GlobalExceptionHandler でも整形できない）。
     */
    // XFF がカンマのみ（","）でも例外にならず getRemoteAddr() にフォールバックすることを確認するテスト
    @Test
    void XFFがカンマのみでもクラッシュせずリモートアドレスにフォールバックする() throws Exception {
        // X-Forwarded-For: "," → 末尾トークンが空文字列のため接続元 IP（10.99.0.1）がキーに、1 回目は 200
        mockMvc.perform(get("/api/categories")
                        // カンマ 1 つだけのヘッダ値を送る（末尾トークンが空になる境界値）
                        .header("X-Forwarded-For", ",")
                        // このテスト固有の接続元 IP を設定する（他テストとのカウンタ衝突防止）
                        .with(remoteAddr("10.99.0.1")))
                // 例外にならず 1 回目は制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // X-Forwarded-For: "," → 引き続き 10.99.0.1 がキーに、2 回目は 200
        mockMvc.perform(get("/api/categories")
                        // 同じカンマのみの値を再度送る（フォールバック先は同じ接続元 IP）
                        .header("X-Forwarded-For", ",")
                        // 同じ接続元 IP を設定する
                        .with(remoteAddr("10.99.0.1")))
                // 2 回目も制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // X-Forwarded-For: "," → 引き続き 10.99.0.1 がキーに、3 回目は 429
        mockMvc.perform(get("/api/categories")
                        // 3 回目もカンマのみの値。フォールバック先（10.99.0.1）の上限を超える
                        .header("X-Forwarded-For", ",")
                        // 同じ接続元 IP を設定する
                        .with(remoteAddr("10.99.0.1")))
                // 3 回目は上限超過なので 429 になることを検証する（フォールバック後も制限が機能する）
                .andExpect(status().isTooManyRequests());
    }

    /**
     * XFF の値がカンマの連続（",,,"）の場合に、例外を出さず getRemoteAddr()（このテストでは 10.99.0.2）
     * にフォールバックしてレート制限がかかることを検証する境界値テスト。
     *
     * <p>"," と同様に split(",") では空配列になるケース。カンマが複数連続しても
     * lastIndexOf 方式なら末尾の空トークン（空文字列）が得られ、looksLikeIp が拒否して
     * 安全に getRemoteAddr() へフォールバックする（§9 fail-safe）。
     */
    // XFF がカンマの連続（",,,"）でも例外にならず getRemoteAddr() にフォールバックすることを確認するテスト
    @Test
    void XFFがカンマ連続でもクラッシュせずリモートアドレスにフォールバックする() throws Exception {
        // X-Forwarded-For: ",,," → 末尾トークンが空文字列のため接続元 IP（10.99.0.2）がキーに、1 回目は 200
        mockMvc.perform(get("/api/categories")
                        // カンマだけが連続するヘッダ値を送る（すべてのトークンが空になる境界値）
                        .header("X-Forwarded-For", ",,,")
                        // このテスト固有の接続元 IP を設定する（他テストとのカウンタ衝突防止）
                        .with(remoteAddr("10.99.0.2")))
                // 例外にならず 1 回目は制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // X-Forwarded-For: ",,," → 引き続き 10.99.0.2 がキーに、2 回目は 200
        mockMvc.perform(get("/api/categories")
                        // 同じカンマ連続の値を再度送る（フォールバック先は同じ接続元 IP）
                        .header("X-Forwarded-For", ",,,")
                        // 同じ接続元 IP を設定する
                        .with(remoteAddr("10.99.0.2")))
                // 2 回目も制限内なので 200 になることを検証する
                .andExpect(status().isOk());

        // X-Forwarded-For: ",,," → 引き続き 10.99.0.2 がキーに、3 回目は 429
        mockMvc.perform(get("/api/categories")
                        // 3 回目もカンマ連続の値。フォールバック先（10.99.0.2）の上限を超える
                        .header("X-Forwarded-For", ",,,")
                        // 同じ接続元 IP を設定する
                        .with(remoteAddr("10.99.0.2")))
                // 3 回目は上限超過なので 429 になることを検証する（フォールバック後も制限が機能する）
                .andExpect(status().isTooManyRequests());
    }
}
