// コントローラのテストパッケージ
package com.izumacha.expensetracker.controller;

// ApiUserConfig（環境変数由来の API ユーザー）を実物で読み込むために参照する
import com.izumacha.expensetracker.config.ApiUserConfig;
// JwtConfig（HS256 のエンコーダ・デコーダ）を実物で読み込むために参照する
import com.izumacha.expensetracker.config.JwtConfig;
// SecurityConfig（トークン発行のみ未認証許可）を実物で読み込むために参照する
import com.izumacha.expensetracker.config.SecurityConfig;
// 外部向けエラーメッセージ定数を参照する（認証失敗時の安全な文言の検証に使う）
import com.izumacha.expensetracker.exception.ErrorMessages;
// トークン発行のビジネスロジックを実物で読み込むために参照する
import com.izumacha.expensetracker.service.AuthTokenService;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;
// Web スライステストを有効化するアノテーション
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
// 監査ログの記録先（AuthTokenService の依存。モックとして差し込む）
import com.izumacha.expensetracker.audit.AuditRecorder;
// 依存をモック Bean として差し込むアノテーション
import org.springframework.boot.test.mock.mockito.MockBean;
// 実物の設定クラス・サービスをスライスへ読み込むアノテーション
import org.springframework.context.annotation.Import;
// テスト実行時に動的な値でプロパティを与える仕組み（bcrypt ハッシュを実行時生成するために使う）
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
// bcrypt ハッシュの生成に使うエンコーダ
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
// 発行されたトークンを検証・解読するデコーダ（発行内容の確認に使う）
import org.springframework.security.oauth2.jwt.JwtDecoder;
// HTTP リクエストを擬似実行するクライアント
import org.springframework.test.web.servlet.MockMvc;
// レスポンス全体を受け取って中身を検証するための結果型
import org.springframework.test.web.servlet.MvcResult;

// レスポンス本体の JSON を検証用に読み戻すための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;
// レスポンス本体のバイト列を UTF-8 として読み戻すための文字コード定数
// （MockHttpServletResponse の getContentAsString() は既定で ISO-8859-1 解釈になり日本語が化けるため）
import java.nio.charset.StandardCharsets;

// 値を検証する assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
// POST リクエストを組み立てる post を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
// レスポンスのステータスを検証する status を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
// レスポンス本体を JSONPath で検証する jsonPath を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * トークン発行エンドポイント（POST /api/auth/token）を、実物のセキュリティ設定・
 * 認証マネージャ・JWT エンコーダで検証するテスト。
 *
 * <p>API ユーザーは src/test/resources/application.properties のユーザー名
 * （test-api-user）と、本クラスの &#64;DynamicPropertySource が実行時に生成する
 * bcrypt ハッシュで構成する（平文とハッシュの対応を固定文字列でコミットしない）。
 */
// テスト対象のコントローラを AuthController に限定する（スライステスト）
@WebMvcTest(AuthController.class)
// 実物のセキュリティ設定一式（フィルタチェーン・JWT・API ユーザー）とトークン発行サービスを読み込む
@Import({SecurityConfig.class, JwtConfig.class, ApiUserConfig.class, AuthTokenService.class})
class AuthControllerTest {

    // テストで使う API ユーザーのユーザー名（application.properties の security.api-user.name と一致させる）
    private static final String USERNAME = "test-api-user";

    // テストで使う API ユーザーの平文パスワード（下の @DynamicPropertySource でハッシュ化して与える）
    private static final String PASSWORD = "test-password-123";

    // API ユーザーのパスワードハッシュをテスト実行時に生成してプロパティとして与える。
    // 固定のハッシュ文字列をコミットすると平文（PASSWORD）との対応がリポジトリに残るため、
    // 実行のたびに bcrypt で生成する（ソルトが毎回変わるので同じ平文でもハッシュは毎回異なる）
    @DynamicPropertySource
    static void apiUserPasswordHash(DynamicPropertyRegistry registry) {
        // 平文パスワードから bcrypt ハッシュを生成してプロパティに登録する
        registry.add("security.api-user.password-hash", () -> new BCryptPasswordEncoder().encode(PASSWORD));
    }

    // 擬似 HTTP リクエストを送るクライアント
    @Autowired
    private MockMvc mockMvc;

    // AuthTokenService が認証の成否を記録するために依存する監査ログの入口。
    // 本テストの関心はトークン発行の応答契約なので、記録先はモックにして DB を持ち込まない
    // （記録の内容そのものは AuthenticationAuditScopeTest が検証する）
    @MockBean
    private AuditRecorder auditRecorder;

    // 発行されたトークンの中身（署名・主体）を検証するためのデコーダ（JwtConfig の実物）
    @Autowired
    private JwtDecoder jwtDecoder;

    // レスポンス本体の JSON を読み戻すためのマッパー
    @Autowired
    private ObjectMapper objectMapper;

    // 指定のユーザー名・パスワードでトークン発行エンドポイントへ POST するヘルパー
    private MvcResult postToken(String username, String password) throws Exception {
        // JSON ボディを組み立てて POST し、結果を返す（ステータス検証は呼び出し側で行う）
        return mockMvc.perform(post("/api/auth/token")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // ユーザー名とパスワードを持つ本体を渡す
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                // 結果オブジェクトを取り出す
                .andReturn();
    }

    // 正しい資格情報なら 200 と、実際に検証可能な JWT・有効期間が返ることを検証する
    @Test
    void 正しい資格情報なら200とトークンが返る() throws Exception {
        // 正しいユーザー名・パスワードで POST する
        MvcResult result = mockMvc.perform(post("/api/auth/token")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 正しい資格情報を持つ本体を渡す
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk())
                // 有効期間が定数どおり（3600 秒）であることを検証する
                .andExpect(jsonPath("$.expiresIn").value(AuthTokenService.TOKEN_TTL_SECONDS))
                // 結果オブジェクトを取り出す
                .andReturn();

        // レスポンス本体からアクセストークンを読み出す
        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                // accessToken フィールドの文字列値を取り出す
                .get("accessToken").asText();
        // トークンが空でないことを検証する
        assertThat(accessToken).isNotBlank();
        // 発行されたトークンが実物のデコーダ（同じ共有シークレット）で検証・解読でき、
        // 主体（sub クレーム）が認証したユーザー名であることを検証する
        assertThat(jwtDecoder.decode(accessToken).getSubject()).isEqualTo(USERNAME);
    }

    // 誤ったパスワードなら 401 と安全な文言（どちらが誤りかを区別しない）が返ることを検証する
    @Test
    void 誤ったパスワードは401() throws Exception {
        // 誤ったパスワードで POST した結果を取得する
        MvcResult result = postToken(USERNAME, "wrong-password");
        // ステータスが 401 であることを検証する
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        // 本体を JSON として読み戻す
        var body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        // 本体の status フィールドが 401 であることを検証する
        assertThat(body.get("status").asInt()).isEqualTo(401);
        // 本体の message が一元管理された安全な文言であることを検証する
        assertThat(body.get("message").asText()).isEqualTo(ErrorMessages.AUTH_FAILED);
    }

    // 存在しないユーザー名でも 401 になり、パスワード誤りと同じ文言（ユーザー列挙防止）であることを検証する
    @Test
    void 存在しないユーザー名は401() throws Exception {
        // 存在しないユーザー名で POST した結果を取得する
        MvcResult result = postToken("no-such-user", PASSWORD);
        // ステータスが 401 であることを検証する
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        // 本体の message がパスワード誤りと同じ文言（存在有無を推測させない）であることを検証する
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("message").asText())
                .isEqualTo(ErrorMessages.AUTH_FAILED);
    }

    // ユーザー名が空欄なら入力検証で 400 になることを検証する（認証処理まで進ませない）
    @Test
    void ユーザー名が空欄は400() throws Exception {
        // 空のユーザー名で POST する
        mockMvc.perform(post("/api/auth/token")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // ユーザー名が空の本体を渡す
                        .content("{\"username\":\"\",\"password\":\"" + PASSWORD + "\"}"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status フィールドが 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400));
    }
}
