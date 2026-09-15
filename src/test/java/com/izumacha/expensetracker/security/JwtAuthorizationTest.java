// セキュリティ関連のテストパッケージ（JWT 認証必須化の検証用）
package com.izumacha.expensetracker.security;

// テスト対象の経路に使うカテゴリコントローラ
import com.izumacha.expensetracker.controller.CategoryController;
// JwtConfig（HS256 のエンコーダ・デコーダ）を実物で読み込むために参照する
import com.izumacha.expensetracker.config.JwtConfig;
// セキュリティ設定クラス（トークン発行以外を認証必須にする）
import com.izumacha.expensetracker.config.SecurityConfig;
// 外部向けエラーメッセージ定数を参照する（401 の安全な文言の検証に使う）
import com.izumacha.expensetracker.exception.ErrorMessages;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// コントローラが依存するカテゴリサービス（モックする）
import com.izumacha.expensetracker.service.CategoryService;

// 一覧の戻り型
import java.util.List;
// 時刻を扱う型（有効・期限切れトークンの発行時刻の組み立てに使う）
import java.time.Instant;

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
// 実物の設定クラスをスライスへ読み込むアノテーション
import org.springframework.context.annotation.Import;
// JWT の署名アルゴリズム（HS256）を表す列挙
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
// JWT のクレームを組み立てるクラス
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
// JWT を生成・署名するインターフェース（実物のトークンを作るために使う）
import org.springframework.security.oauth2.jwt.JwtEncoder;
// エンコーダへクレームとヘッダを渡すためのパラメータクラス
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
// JWT の署名ヘッダを組み立てるクラス
import org.springframework.security.oauth2.jwt.JwsHeader;
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
// レスポンス本体を JSONPath で検証する jsonPath を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * JWT 認証必須化（SecurityConfig の anyRequest().authenticated()）を検証するテスト。
 *
 * <p>検証するシナリオ:
 * <ol>
 *   <li>トークン無しのアクセスは 401 になり、{status, message} 契約の JSON が返ること</li>
 *   <li>署名を検証できない出鱈目なトークンは 401 になること</li>
 *   <li>期限切れトークンは 401 になること</li>
 *   <li>実物のエンコーダ（同じ共有シークレット）で発行した有効なトークンなら 200 になること</li>
 *   <li>CORS 許可オリジン未設定（既定）では、どのオリジンからのプリフライトも拒否されること</li>
 * </ol>
 */
// テスト対象のコントローラを CategoryController に限定する（スライステスト）
@WebMvcTest(CategoryController.class)
// 実物のセキュリティ設定（JWT 認証必須）と JWT 設定（HS256 エンコーダ・デコーダ）を読み込む。
// テスト用シークレットは src/test/resources/application.properties の security.jwt.secret が供給する
@Import({SecurityConfig.class, JwtConfig.class})
class JwtAuthorizationTest {

    // 擬似 HTTP リクエストを送るクライアント
    @Autowired
    private MockMvc mockMvc;

    // 実物のトークンを発行するためのエンコーダ（JwtConfig の HS256 共有シークレット）
    @Autowired
    private JwtEncoder jwtEncoder;

    // コントローラが依存するカテゴリサービスのモック
    @MockBean
    private CategoryService categoryService;

    // 各テスト前にサービスのモック応答を用意する
    @BeforeEach
    void setUp() {
        // サービスが空のページを返すようモックする（認証を通過すれば 200 になる）
        when(categoryService.findAll(any()))
                // 0 件・既定サイズ 20 の空ページを返す
                .thenReturn(new PageResponse<CategoryResponse>(List.of(), 0, 20, 0, 0));
    }

    // 指定した発行時刻・有効期限で実物の JWT を発行するヘルパー（iss は本アプリの識別子）
    private String mintToken(Instant issuedAt, Instant expiresAt) {
        // 本番の発行経路（AuthTokenService）と同じ iss を載せて発行する
        return mintToken(JwtConfig.TOKEN_ISSUER, issuedAt, expiresAt);
    }

    // iss を明示して実物の JWT を発行するヘルパー（iss の検証を確かめるテストが使う）
    private String mintToken(String issuer, Instant issuedAt, Instant expiresAt) {
        // 発行者・主体・発行時刻・有効期限を持つクレームを組み立てる
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                // 主体（テスト用のユーザー名）を設定する
                .subject("test-api-user")
                // 発行時刻を設定する
                .issuedAt(issuedAt)
                // 有効期限を設定する
                .expiresAt(expiresAt);
        // issuer が null のときは iss クレームを載せない（「iss 無しのトークン」を作るため）
        if (issuer != null) {
            // 指定された発行者を iss クレームとして設定する
            builder.issuer(issuer);
        }
        // HS256 の署名ヘッダとクレームを署名して JWT 文字列を返す
        return jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), builder.build()))
                // 署名済みトークンの文字列表現を取り出す
                .getTokenValue();
    }

    // トークン無しのアクセスは 401 になり、{status, message} 契約の JSON が返ることを検証する
    @Test
    void トークン無しは401() throws Exception {
        // Authorization ヘッダ無しで一覧へ GET する
        mockMvc.perform(get("/api/categories"))
                // ステータスが 401 であることを検証する
                .andExpect(status().isUnauthorized())
                // 本体の status フィールドが 401 であることを検証する（エラー契約の維持）
                .andExpect(jsonPath("$.status").value(401))
                // 本体の message が一元管理された安全な文言であることを検証する
                .andExpect(jsonPath("$.message").value(ErrorMessages.UNAUTHORIZED));
    }

    // 署名を検証できない出鱈目なトークンは 401 になることを検証する
    @Test
    void 不正なトークンは401() throws Exception {
        // JWT の形をしていない出鱈目な Bearer トークンを付けて GET する
        mockMvc.perform(get("/api/categories")
                        // 出鱈目なトークンを Authorization ヘッダに載せる
                        .header("Authorization", "Bearer not-a-valid-token"))
                // ステータスが 401 であることを検証する
                .andExpect(status().isUnauthorized())
                // 本体の status フィールドが 401 であることを検証する
                .andExpect(jsonPath("$.status").value(401))
                // トークン不正の詳細を漏らさず、トークン無しと同じ安全な文言であることを検証する
                .andExpect(jsonPath("$.message").value(ErrorMessages.UNAUTHORIZED));
    }

    // 期限切れトークンは 401 になることを検証する（デコーダの既定の時刻ずれ許容 60 秒を超えて失効させる）
    @Test
    void 期限切れトークンは401() throws Exception {
        // 2 時間前に発行され 1 時間前に失効したトークンを発行する（許容ずれ 60 秒を大きく超過）
        String expiredToken = mintToken(Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        // 期限切れトークンを付けて GET する
        mockMvc.perform(get("/api/categories")
                        // 期限切れトークンを Authorization ヘッダに載せる
                        .header("Authorization", "Bearer " + expiredToken))
                // ステータスが 401 であることを検証する
                .andExpect(status().isUnauthorized())
                // 本体の status フィールドが 401 であることを検証する
                .andExpect(jsonPath("$.status").value(401));
    }

    // 実物のエンコーダで発行した有効なトークンなら 200 になることを検証する（署名検証の end-to-end）
    @Test
    void 有効なトークンなら200() throws Exception {
        // 現在時刻発行・1 時間後に失効する有効なトークンを発行する
        String validToken = mintToken(Instant.now(), Instant.now().plusSeconds(3600));
        // 有効なトークンを付けて GET する
        mockMvc.perform(get("/api/categories")
                        // 有効なトークンを Authorization ヘッダに載せる
                        .header("Authorization", "Bearer " + validToken))
                // ステータスが 200 であることを検証する（署名・期限の検証を通過した）
                .andExpect(status().isOk());
    }

    // CORS 許可オリジン未設定（既定の fail-closed）では、どのオリジンからのプリフライトも拒否されることを検証する
    @Test
    void CORS未設定ではどのオリジンのプリフライトも拒否される() throws Exception {
        // 任意のオリジンからのプリフライト（OPTIONS + Origin + Access-Control-Request-Method）を送る
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/categories")
                        // クロスオリジン呼び出し元のオリジンを設定する
                        .header("Origin", "https://any.example.com")
                        // プリフライトで問い合わせる実リクエストのメソッドを設定する
                        .header("Access-Control-Request-Method", "GET"))
                // 許可オリジンが空（全拒否）のため 403 で拒否されることを検証する
                .andExpect(status().isForbidden());
    }

    // 署名は正しいが iss が別サービスのトークンは 401 になることを検証する。
    //
    // この検査が無いと「共有シークレットを知っている全員」が通ってしまう。署名鍵を
    // 他サービスと共用してしまった運用ミス（同じ JWT_SECRET を使い回す）が、そのまま
    // この API への完全なアクセスになる。**修正前は実測で 200 が返っていた**（§9）。
    @Test
    void 別サービスが発行したトークンは401() throws Exception {
        // 署名鍵は同じだが iss だけが別のトークンを発行する
        String foreignToken = mintToken("some-other-service", Instant.now(), Instant.now().plusSeconds(3600));
        // 発行者違いのトークンを付けて GET する
        mockMvc.perform(get("/api/categories")
                        // 発行者違いのトークンを Authorization ヘッダに載せる
                        .header("Authorization", "Bearer " + foreignToken))
                // ステータスが 401 であることを検証する
                .andExpect(status().isUnauthorized())
                // 本体の status フィールドが 401 であることを検証する
                .andExpect(jsonPath("$.status").value(401))
                // 発行者違いの詳細を漏らさず、トークン無しと同じ安全な文言であることを検証する
                .andExpect(jsonPath("$.message").value(ErrorMessages.UNAUTHORIZED));
    }

    // iss クレームを持たないトークンも 401 になることを検証する（未設定は「一致しない」側へ倒す）。
    //
    // 「不一致だけを弾き、未設定は通す」形にすると、iss を省くだけで検査を迂回できてしまい、
    // 上のテストが守っているはずの境界がそのまま開く（fail-closed を保つための対の検査）。
    @Test
    void iss無しのトークンは401() throws Exception {
        // iss クレームを載せずにトークンを発行する（null を渡すと iss を付けない）
        String issuerlessToken = mintToken(null, Instant.now(), Instant.now().plusSeconds(3600));
        // iss 無しのトークンを付けて GET する
        mockMvc.perform(get("/api/categories")
                        // iss 無しのトークンを Authorization ヘッダに載せる
                        .header("Authorization", "Bearer " + issuerlessToken))
                // ステータスが 401 であることを検証する
                .andExpect(status().isUnauthorized())
                // 本体の status フィールドが 401 であることを検証する
                .andExpect(jsonPath("$.status").value(401))
                // iss 未設定の詳細を漏らさず、トークン無しと同じ安全な文言であることを検証する
                // （発行者違いの検査と対称にしておく。片方だけ文言を見ていると、独自の
                //   AuthenticationEntryPoint が外れて "The iss claim is not valid" が
                //   漏れ出したときに、この経路だけ赤くならず検出が弱くなる）
                .andExpect(jsonPath("$.message").value(ErrorMessages.UNAUTHORIZED));
    }
}
