// セキュリティ設定クラスのパッケージ
package com.izumacha.expensetracker.config;

// JSON 応答を書き出すための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
// {status, message} 形式のエラー応答を書き出す共通ユーティリティ
import com.izumacha.expensetracker.web.ApiErrorWriter;

// ディスパッチ種別（通常のリクエスト／エラー転送など）を表すサーブレット API の列挙
import jakarta.servlet.DispatcherType;

// カンマ区切り文字列の分解結果を保持するリスト
import java.util.Arrays;
import java.util.List;

// Spring Security のフィルタチェーン定義に使う型
import org.springframework.security.web.SecurityFilterChain;
// 認証されていないアクセスへの応答（401）を組み立てるインターフェース
import org.springframework.security.web.AuthenticationEntryPoint;
// 認証済みだが権限が足りないアクセスへの応答（403）を組み立てるインターフェース
import org.springframework.security.web.access.AccessDeniedHandler;
// HTTP セキュリティ設定（認証・認可・CSRF 等）を組み立てるビルダ
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
// Web セキュリティを有効化するアノテーション
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
// Lambda DSL で CSRF 設定を操作するための設定クラス
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
// セッション生成ポリシー（STATELESS を指定して JSESSIONID Cookie を排除するために使う）
import org.springframework.security.config.http.SessionCreationPolicy;
// 「既定設定のまま」を表すカスタマイザ（cors() / jwt() の有効化に使う）
import org.springframework.security.config.Customizer;
// プロパティ値を注入するアノテーション
import org.springframework.beans.factory.annotation.Value;
// Bean を宣言するアノテーション
import org.springframework.context.annotation.Bean;
// このクラス自体を Spring に設定クラスとして登録するアノテーション
import org.springframework.context.annotation.Configuration;
// HTTP メソッド（GET/POST 等）を表す列挙
import org.springframework.http.HttpMethod;
// HTTP ステータスを表す列挙
import org.springframework.http.HttpStatus;
// HTTP ヘッダ名の定数（Authorization / Content-Type）
import org.springframework.http.HttpHeaders;
// CORS の許可オリジン・メソッド・ヘッダを保持する設定クラス
import org.springframework.web.cors.CorsConfiguration;
// リクエストパスごとの CORS 設定の供給源インターフェース
import org.springframework.web.cors.CorsConfigurationSource;
// パスパターンで CORS 設定を登録する標準実装
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security の設定クラス。
 *
 * <p><b>認証方式: JWT（Resource Server 方式）</b><br>
 * {@code POST /api/auth/token} が発行した HS256 署名の JWT を Bearer トークンとして受け取り、
 * 各リクエストで署名・有効期限を検証する。トークン発行エンドポイント以外の全 {@code /api/**} は
 * 認証必須（{@code anyRequest().authenticated()}）。未認証は 401、権限不足は 403 を、
 * 既存のエラー契約 {@code {status, message}}（{@link ApiErrorWriter}）で返す。
 *
 * <p><b>CORS: 許可オリジンの明示制限（fail-closed）</b><br>
 * 許可オリジンは環境変数 {@code CORS_ALLOWED_ORIGINS}（カンマ区切り）で与える。
 * 未設定なら<b>どのオリジンも許可しない</b>。ワイルドカード（{@code *}）は Bearer トークンを
 * 扱う API で全オリジンに開くことになるため、指定されても起動失敗にする（§9）。
 */
// このクラスが Spring の設定クラスであることを示す
@Configuration
// Spring Security の Web セキュリティ機能を有効化する
@EnableWebSecurity
public class SecurityConfig {

    // すべてのパスを表す CORS 登録用のパスパターン
    private static final String ALL_PATHS = "/**";

    // すべてのオリジンを表すワイルドカード（指定を拒否するための比較用定数）
    private static final String ORIGIN_WILDCARD = "*";

    // トークン発行エンドポイントのパス（唯一の認証不要 API。AuthController のマッピングと一致させる）。
    // 「どのパスが認証情報を検証する入り口か」の定義はここ 1 箇所に保つ（§6 定数の一元管理）。
    // 別パッケージの RateLimitFilter（security）も同じ判断に使うため public にしている
    public static final String TOKEN_ENDPOINT = "/api/auth/token";

    // CORS で許可するオリジンの一覧（環境変数由来。空リストなら全オリジン拒否＝fail-closed）
    private final List<String> allowedOrigins;

    // 401/403 のエラー応答を JSON で書き出すための ObjectMapper
    private final ObjectMapper objectMapper;

    // 許可オリジン設定と ObjectMapper をコンストラクタで受け取る
    public SecurityConfig(
            // 許可オリジンのカンマ区切り文字列（security.cors.allowed-origins / 環境変数 CORS_ALLOWED_ORIGINS）
            @Value("${security.cors.allowed-origins:}") String allowedOriginsCsv,
            // JSON 直列化に使う ObjectMapper
            ObjectMapper objectMapper) {
        // カンマ区切り文字列を空白除去しつつ分解し、空要素を除いたリストにする
        // （"a, b" のような空白入りや末尾カンマを許容し、未設定（空文字）は空リストになる）
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                // 各要素の前後空白を取り除く
                .map(String::strip)
                // 空要素（未設定・連続カンマ由来）を除外する
                .filter(origin -> !origin.isEmpty())
                // 結果をリストに集める
                .toList();
        // 【環境変数由来の設定値を必ず検証する（§9 入力は信用しない）】
        // ワイルドカード指定は「全オリジンへ開放」であり許可リスト制限の意味を失うため起動を失敗させる
        if (this.allowedOrigins.contains(ORIGIN_WILDCARD)) {
            // 設定ミスの内容と直し方が分かる日本語メッセージで起動時例外（アプリは開始しない）を投げる
            throw new IllegalStateException(
                    "security.cors.allowed-origins（CORS_ALLOWED_ORIGINS）にワイルドカード（*）は指定できません。"
                            + "許可するオリジンを個別に列挙してください");
        }
        // ObjectMapper をフィールドに保持する
        this.objectMapper = objectMapper;
    }

    /**
     * HTTP セキュリティの設定を行い、フィルタチェーンを Bean として登録する。
     *
     * @param http HttpSecurity ビルダー（Spring によって注入される）
     * @return 設定済みの SecurityFilterChain
     * @throws Exception HttpSecurity のビルドに失敗した場合
     */
    // このメソッドが返す SecurityFilterChain を Spring の Bean として登録する
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        // 401（未認証）応答を既存エラー契約 {status, message} で書き出すエントリポイントを用意する
        AuthenticationEntryPoint unauthorizedEntryPoint =
                // トークン無し・署名不正・期限切れなどの未認証アクセスに安全な文言で 401 を返す
                (request, response, authException) ->
                        ApiErrorWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED, ErrorMessages.UNAUTHORIZED);

        // 403（権限不足）応答を既存エラー契約 {status, message} で書き出すハンドラを用意する
        AccessDeniedHandler forbiddenHandler =
                // 認証済みだが必要な権限が無いアクセスに安全な文言で 403 を返す
                (request, response, accessDeniedException) ->
                        ApiErrorWriter.write(response, objectMapper, HttpStatus.FORBIDDEN, ErrorMessages.FORBIDDEN);

        // -------------------------------------------------------------------
        // CSRF（クロスサイトリクエストフォージェリ）保護の設定
        // -------------------------------------------------------------------
        // CSRF 保護は無効のままとする（意図的な設計）。CSRF はブラウザが Cookie を自動送信する
        // 性質を悪用する攻撃であり、本 API の認証はステートレスな Bearer トークン（JWT）のみで
        // Cookie を一切使わない（下の STATELESS 設定で JSESSIONID も発行しない）ため、
        // 攻撃者のサイトから被害者の資格情報を勝手に載せたリクエストを送らせることができない。
        // この「ステートレス Bearer 認証に限り無効化が正当化される」判断は CLAUDE.md §9 に基づく。
        // 将来 Cookie セッション認証を追加する場合は必ず CSRF 保護を再有効化すること。
        http.csrf(CsrfConfigurer::disable);
        // -------------------------------------------------------------------

        // -------------------------------------------------------------------
        // CORS（クロスオリジン呼び出し許可）の設定
        // -------------------------------------------------------------------
        // 下の corsConfigurationSource() Bean（許可オリジンの明示リスト・未設定なら全拒否）を有効化する。
        // プリフライト（OPTIONS）は Security の認可判定より前に CORS フィルタが処理するため、
        // 許可外オリジンからのプリフライトは 403 で拒否され、許可オリジンのプリフライトは認証不要で応答される
        http.cors(Customizer.withDefaults());
        // -------------------------------------------------------------------

        // -------------------------------------------------------------------
        // エンドポイントの認可設定
        // -------------------------------------------------------------------
        http.authorizeHttpRequests(auth -> auth
                // エラー転送（ERROR ディスパッチ。フィルタ層から漏れた例外の /error 転送など）は認可を求めない。
                // これを許可しないと、エラー整形（web/ApiErrorController）への内部転送が 401 に化けて
                // {status, message} 契約が壊れる（Spring Security 6 は ERROR ディスパッチも既定で認可対象）
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // トークン発行エンドポイントだけは未認証で呼べるようにする（ここで認証してトークンを得る）
                .requestMatchers(HttpMethod.POST, TOKEN_ENDPOINT).permitAll()
                // 上記以外のすべてのリクエストは認証（有効な Bearer トークン）を必須にする（§9 認可はサーバー側で強制）
                .anyRequest().authenticated()
        );
        // -------------------------------------------------------------------

        // -------------------------------------------------------------------
        // JWT リソースサーバ（Bearer トークン検証）の設定
        // -------------------------------------------------------------------
        http.oauth2ResourceServer(oauth2 -> oauth2
                // JwtConfig の JwtDecoder（HS256 共有シークレット）で Bearer トークンを検証する
                .jwt(Customizer.withDefaults())
                // トークン不正（署名不一致・期限切れ等）の 401 応答を既存エラー契約で書き出す
                .authenticationEntryPoint(unauthorizedEntryPoint)
                // 権限不足の 403 応答を既存エラー契約で書き出す
                .accessDeniedHandler(forbiddenHandler)
        );
        // -------------------------------------------------------------------

        // -------------------------------------------------------------------
        // 認証・認可エラーの応答整形（Bearer トークン検証以外の経路）
        // -------------------------------------------------------------------
        // トークンを一切付けないアクセス（BearerTokenAuthenticationFilter を素通りする経路）でも
        // 同じ {status, message} 契約で 401/403 を返すため、共通の例外ハンドリングにも同じ実装を設定する
        http.exceptionHandling(exceptions -> exceptions
                // 未認証アクセス（401）の応答を既存エラー契約で書き出す
                .authenticationEntryPoint(unauthorizedEntryPoint)
                // 権限不足（403）の応答を既存エラー契約で書き出す
                .accessDeniedHandler(forbiddenHandler)
        );
        // -------------------------------------------------------------------

        // -------------------------------------------------------------------
        // セッション管理の設定
        // -------------------------------------------------------------------
        // REST API はステートレス（状態を持たない）設計のため、セッションを生成しない。
        // STATELESS を指定することで Spring Security が HttpSession を生成しなくなり、
        // レスポンスに JSESSIONID Cookie が Set-Cookie されなくなる。
        // Bearer トークン認証と組み合わせることで Cookie を完全に排除でき、
        // 上記の CSRF 無効化の前提（Cookie を使わない）も維持される（CLAUDE.md §9）。
        http.sessionManagement(
                // セッション生成ポリシーを「一切作らない」に設定する
                sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        );
        // -------------------------------------------------------------------

        // 設定を確定してフィルタチェーンを生成し返す
        return http.build();
    }

    /**
     * CORS の許可オリジン・メソッド・ヘッダを定義する設定源を登録する。
     *
     * <p>許可オリジンが未設定（空リスト）の場合はどのオリジンも許可しない（fail-closed）。
     * 許可メソッド・ヘッダは本 API が実際に使う最小限（GET/POST/PUT/DELETE、
     * Authorization/Content-Type）に絞る（§9 最小権限・最小公開）。
     *
     * @return 全パス共通の CORS 設定源
     */
    // このメソッドが返す CorsConfigurationSource を Spring の Bean として登録する
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // CORS の許可内容を保持する設定オブジェクトを生成する
        CorsConfiguration configuration = new CorsConfiguration();
        // 許可するオリジンを環境変数由来のリストに限定する（空リストなら全オリジン拒否）
        configuration.setAllowedOrigins(allowedOrigins);
        // 許可する HTTP メソッドを本 API が提供する最小限に絞る
        configuration.setAllowedMethods(List.of(
                // 一覧・詳細取得
                HttpMethod.GET.name(),
                // 登録・トークン発行
                HttpMethod.POST.name(),
                // 更新
                HttpMethod.PUT.name(),
                // 削除
                HttpMethod.DELETE.name()));
        // 許可するリクエストヘッダを Bearer トークンと JSON 本文に必要な 2 つだけに絞る
        configuration.setAllowedHeaders(List.of(
                // Bearer トークンを載せるヘッダ
                HttpHeaders.AUTHORIZATION,
                // JSON 本文の形式宣言ヘッダ
                HttpHeaders.CONTENT_TYPE));
        // Cookie 等の資格情報付きクロスオリジンは使わない（Bearer トークンはヘッダで送るため不要。§9 最小公開）
        configuration.setAllowCredentials(false);
        // パスパターンごとに CORS 設定を登録する供給源を生成する
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // すべてのパスに同じ CORS 設定を適用する
        source.registerCorsConfiguration(ALL_PATHS, configuration);
        // 設定済みの供給源を返す
        return source;
    }
}
