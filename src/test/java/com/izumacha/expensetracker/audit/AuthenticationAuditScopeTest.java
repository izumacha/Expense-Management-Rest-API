// 監査ログ関連のテストパッケージ
package com.izumacha.expensetracker.audit;

// API ユーザー設定（トークン発行で照合する単一ユーザー）を実物で読み込むために参照する
import com.izumacha.expensetracker.config.ApiUserConfig;
// JwtConfig（HS256 のエンコーダ・デコーダ）を実物で読み込むために参照する
import com.izumacha.expensetracker.config.JwtConfig;
// セキュリティ設定クラス（トークン発行以外を認証必須にする）
import com.izumacha.expensetracker.config.SecurityConfig;
// トークン発行のエンドポイント
import com.izumacha.expensetracker.controller.AuthController;
// 認証必須の通常 API の代表として使うコントローラ
import com.izumacha.expensetracker.controller.CategoryController;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// トークン発行のビジネスロジック（実物を読み込む）
import com.izumacha.expensetracker.service.AuthTokenService;
// コントローラが依存するカテゴリサービス（モックする）
import com.izumacha.expensetracker.service.CategoryService;
// 時刻を扱う型（トークンの発行時刻・有効期限の組み立てに使う）
import java.time.Instant;
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
// 依存をモック Bean として差し込むアノテーション
import org.springframework.boot.test.mock.mockito.MockBean;
// 実物の設定クラス・Bean をスライスへ読み込むアノテーション
import org.springframework.context.annotation.Import;
// bcrypt ハッシュを実行時に生成するためのエンコーダ
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
// テスト実行時にプロパティを動的登録するためのレジストリ
import org.springframework.test.context.DynamicPropertyRegistry;
// 動的プロパティ登録メソッドを示すアノテーション
import org.springframework.test.context.DynamicPropertySource;
// HTTP リクエストを擬似実行するクライアント
import org.springframework.test.web.servlet.MockMvc;
// 擬似リクエストの結果に対して検証を連ねるための型
import org.springframework.test.web.servlet.ResultActions;

// any() マッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.any;
// 呼び出しの検証に使うヘルパーを取り込む
import static org.mockito.Mockito.verify;
// 呼び出しが 1 回も無かったことを検証するヘルパーを取り込む
import static org.mockito.Mockito.verifyNoInteractions;
// これ以上の呼び出しが無かったことを検証するヘルパーを取り込む
import static org.mockito.Mockito.verifyNoMoreInteractions;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// GET リクエストを組み立てる get を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
// POST リクエストを組み立てる post を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
// レスポンスのステータスを検証する status を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認証イベントの監査記録が<b>トークン発行だけ</b>を対象にしていることを、実物のフィルタチェーン上で
 * 両方向から固定するテスト。
 *
 * <p>【この設計になっている理由】当初は Spring Security の認証イベント
 * （{@code AuthenticationSuccessEvent} 等）を購読して記録していたが、それでは
 * <b>Bearer トークンを検証するリソースサーバの認証も同じイベントに乗る</b>ため、
 * 通常の API 呼び出し 1 回ごとに「ログイン成功」が記録されることが本テストで判明した。
 * 実害は 2 つある。
 * <ul>
 *   <li>保存期間を持たない監査テーブルがリクエスト数に比例して膨れ、ログイン成功の記録が
 *       「トークンが発行された証拠」として使えなくなる（総当たりの立証という A.1 の目的が果たせない）。</li>
 *   <li>Bearer トークンの検証失敗では認証の主体名が<b>トークン文字列そのもの</b>になり、
 *       それを actor として保存すると資格情報を追記専用テーブルへ書き込むことになる（§9）。</li>
 * </ul>
 * そのため記録はイベント購読をやめ、経路が 1 つしかない {@link AuthTokenService} の中で行う。
 *
 * <p>【何を守るテストか】上の事故は「記録されているので動いて見える」種類の壊れ方なので、
 * 記録の<b>過不足の両方</b>をここで固定する。
 * <ul>
 *   <li>通常の API 呼び出し（成功・失敗とも）では 1 件も記録しないこと</li>
 *   <li>トークン発行では成功・失敗とも記録し、渡すのはユーザー名だけでパスワードは渡さないこと</li>
 * </ul>
 */
// トークン発行と、認証必須の通常 API の両方を対象にする（スライステスト）
@WebMvcTest({AuthController.class, CategoryController.class})
// 実物のセキュリティ設定一式（フィルタチェーン・JWT・API ユーザー）とトークン発行サービスを読み込む
@Import({SecurityConfig.class, JwtConfig.class, ApiUserConfig.class, AuthTokenService.class})
class AuthenticationAuditScopeTest {

    // テストで使う API ユーザーのユーザー名（application.properties の security.api-user.name と一致させる）
    private static final String USERNAME = "test-api-user";

    // テストで使う API ユーザーの平文パスワード（下の @DynamicPropertySource でハッシュ化して与える）
    private static final String PASSWORD = "test-password-123";

    // API ユーザーのパスワードハッシュをテスト実行時に生成してプロパティとして与える
    // （固定のハッシュ文字列をコミットすると平文との対応がリポジトリに残るため。AuthControllerTest と同じ方針）
    @DynamicPropertySource
    static void apiUserPasswordHash(DynamicPropertyRegistry registry) {
        // 平文パスワードから bcrypt ハッシュを生成してプロパティに登録する
        registry.add("security.api-user.password-hash", () -> new BCryptPasswordEncoder().encode(PASSWORD));
    }

    // 擬似 HTTP リクエストを送るクライアント
    @Autowired
    private MockMvc mockMvc;

    // 実物のトークンを発行するためのエンコーダ（JwtConfig の HS256 共有シークレット）
    @Autowired
    private JwtEncoder jwtEncoder;

    // カテゴリコントローラが依存するサービスのモック
    @MockBean
    private CategoryService categoryService;

    // 監査ログ記録の入口のモック（呼ばれる／呼ばれないを検証する対象）
    @MockBean
    private AuditRecorder auditRecorder;

    // 各テスト前にサービスのモック応答を用意する
    @BeforeEach
    void setUp() {
        // サービスが空のページを返すようモックする（認証を通過すれば 200 になる）
        when(categoryService.findAll(any()))
                // 0 件・既定サイズ 20 の空ページを返す
                .thenReturn(new PageResponse<CategoryResponse>(List.of(), 0, 20, 0, 0));
    }

    // 有効な JWT を発行するヘルパー
    private String mintValidToken() {
        // 主体・発行時刻・有効期限を持つクレームを組み立てる
        JwtClaimsSet claims = JwtClaimsSet.builder()
                // 主体（テスト用のユーザー名）を設定する
                .subject(USERNAME)
                // 発行時刻を現在にする
                .issuedAt(Instant.now())
                // 有効期限を 1 時間後にする
                .expiresAt(Instant.now().plusSeconds(3600))
                // クレームを確定する
                .build();
        // HS256 の署名ヘッダとクレームを署名して JWT 文字列を返す
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                // 署名済みトークンの文字列表現を取り出す
                .getTokenValue();
    }

    // 指定のユーザー名・パスワードでトークン発行エンドポイントへ POST するヘルパー
    private ResultActions postToken(String username, String password) throws Exception {
        // JSON ボディを組み立てて POST する
        return mockMvc.perform(post("/api/auth/token")
                // JSON 形式であることを宣言する
                .contentType("application/json")
                // ユーザー名とパスワードを持つ本体を渡す
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    // 発行済みトークンでの通常 API 呼び出しが監査ログを増やさないことを検証する
    @Test
    void 有効なトークンでのAPI呼び出しは監査ログを増やさない() throws Exception {
        // 有効なトークンを付けてカテゴリ一覧を取得する（認証を通過して 200 になる）
        mockMvc.perform(get("/api/categories").header("Authorization", "Bearer " + mintValidToken()))
                // 認証が通り 200 が返ることを確認する（前提の確認）
                .andExpect(status().isOk());

        // 監査ログの記録が 1 回も呼ばれていないことを検証する
        // （ここが落ちるなら、API 呼び出しのたびに監査ログが積み上がっている）
        verifyNoInteractions(auditRecorder);
    }

    // 無効なトークンでの呼び出しも監査ログを増やさないことを検証する
    @Test
    void 無効なトークンでのAPI呼び出しは監査ログを増やさない() throws Exception {
        // 署名を検証できない出鱈目なトークンを付けて呼び出す
        mockMvc.perform(get("/api/categories").header("Authorization", "Bearer not-a-valid-token"))
                // 認証に失敗して 401 が返ることを確認する（前提の確認）
                .andExpect(status().isUnauthorized());

        // 監査ログの記録が 1 回も呼ばれていないことを検証する
        // （ここが落ちるなら、トークン文字列が actor として保存されうる。§9）
        verifyNoInteractions(auditRecorder);
    }

    // トークン発行の成功がログイン成功として 1 件だけ記録されることを検証する
    @Test
    void トークン発行の成功はログイン成功として記録される() throws Exception {
        // 正しい資格情報でトークンを発行する
        postToken(USERNAME, PASSWORD)
                // 発行に成功して 200 が返ることを確認する（前提の確認）
                .andExpect(status().isOk());

        // ユーザー名だけを添えてログイン成功が記録されたことを検証する
        verify(auditRecorder).recordAuthentication(AuditAction.LOGIN_SUCCESS, USERNAME);
        // それ以外の記録が起きていないことを検証する（パスワードを含む呼び出しが無いことも兼ねる。§9）
        verifyNoMoreInteractions(auditRecorder);
    }

    // トークン発行の失敗がログイン失敗として 1 件だけ記録されることを検証する
    @Test
    void トークン発行の失敗はログイン失敗として記録される() throws Exception {
        // 誤ったパスワードでトークン発行を試みる
        postToken(USERNAME, "wrong-password")
                // 認証に失敗して 401 が返ることを確認する（記録を足しても応答契約が変わらないことの確認）
                .andExpect(status().isUnauthorized());

        // 試みられたユーザー名を添えてログイン失敗が記録されたことを検証する（総当たりの立証に使う）
        verify(auditRecorder).recordAuthentication(AuditAction.LOGIN_FAILURE, USERNAME);
        // それ以外の記録が起きていないことを検証する（パスワードが記録先へ渡らないことも兼ねる。§9）
        verifyNoMoreInteractions(auditRecorder);
    }
}
