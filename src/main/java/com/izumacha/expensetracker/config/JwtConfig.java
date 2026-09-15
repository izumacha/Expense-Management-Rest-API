// 設定クラスのパッケージ
package com.izumacha.expensetracker.config;

// 文字列をバイト列へ変換するときの文字コード定数
import java.nio.charset.StandardCharsets;
// HMAC 鍵を表す標準ライブラリのインターフェース
import javax.crypto.SecretKey;
// バイト列から HMAC 鍵を生成する標準ライブラリの実装
import javax.crypto.spec.SecretKeySpec;

// Nimbus（JOSE ライブラリ）の共有シークレット供給源（JwtEncoder の鍵として使う）
import com.nimbusds.jose.jwk.source.ImmutableSecret;

// プロパティ値を注入するアノテーション
import org.springframework.beans.factory.annotation.Value;
// Bean を宣言するアノテーション
import org.springframework.context.annotation.Bean;
// このクラス自体を Spring に設定クラスとして登録するアノテーション
import org.springframework.context.annotation.Configuration;
// JWT の署名アルゴリズム（HS256 等の MAC 系）を表す列挙
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
// 受信した JWT を検証・解読するインターフェース
import org.springframework.security.oauth2.jwt.JwtDecoder;
// JWT を生成・署名するインターフェース
import org.springframework.security.oauth2.jwt.JwtEncoder;
// JwtDecoder の Nimbus 実装（ビルダー付き）
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
// JwtEncoder の Nimbus 実装
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
// iss の検証規則（未設定も失敗）を Javadoc から参照するためのバリデータ型
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
// 既定の検証に iss の一致検証を足したバリデータを組み立てるファクトリ
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * JWT の署名・検証に使う共有シークレット（HS256）を構成する設定クラス。
 *
 * <p>共有シークレットは環境変数 {@code JWT_SECRET}（プロパティ {@code security.jwt.secret}）から
 * 受け取り、トークン発行（{@link JwtEncoder}）と検証（{@link JwtDecoder}）の両方で同じ鍵を使う。
 *
 * <p><b>fail-closed（未設定・弱い鍵では起動させない）</b><br>
 * シークレットが未設定のまま起動を許すと「認証が動いているように見えて署名鍵が空」という
 * 危険な状態になりうるため、未設定または {@value #MIN_SECRET_BYTES} バイト未満（HS256 の
 * 強度要件 RFC 7518 §3.2: 鍵長はハッシュ長 256 ビット以上）の場合は、
 * 何をどう直せばよいかが分かるメッセージで起動そのものを失敗させる（CLAUDE.md §9）。
 */
// このクラスが Spring の設定クラスであることを示す
@Configuration
public class JwtConfig {

    // このアプリが iss クレームとして名乗る識別子。**発行側と検証側の唯一の参照元**。
    // 発行側（AuthTokenService）はこの値を載せ、検証側（下の jwtDecoder）はこの値と一致することを
    // 要求する。どちらか一方に写しを置くと、片方だけ変えたときに「名乗りと検査が食い違い、
    // 自分で発行したトークンを自分で拒む」か、逆に「検査が名乗りを追わず素通りする」になる（§6）。
    public static final String TOKEN_ISSUER = "expense-tracker";

    // HS256 の共有シークレットに要求する最小バイト数（256 ビット = 32 バイト。RFC 7518 §3.2）
    // 同一パッケージのテストから境界値を参照できるようパッケージプライベートにしている
    static final int MIN_SECRET_BYTES = 32;

    // HMAC-SHA256 の鍵アルゴリズム名（SecretKeySpec に渡す JCA 標準名）
    private static final String HMAC_SHA256 = "HmacSHA256";

    // 署名・検証の両方で使う共有シークレット鍵（起動時の検証を通ったもの）
    private final SecretKey secretKey;

    // 共有シークレットをプロパティから受け取り、起動時に検証するコンストラクタ
    public JwtConfig(
            // HS256 署名用の共有シークレット（security.jwt.secret / 環境変数 JWT_SECRET。未設定なら空文字）
            @Value("${security.jwt.secret:}") String secret) {
        // 【環境変数由来の設定値を必ず検証する（§9 入力は信用しない・fail-closed）】
        // 未設定（null または空白のみ）は署名鍵が無い状態なので起動を失敗させる
        if (secret == null || secret.isBlank()) {
            // 設定ミスの内容と直し方が分かる日本語メッセージで起動時例外（アプリは開始しない）を投げる
            throw new IllegalStateException(
                    "security.jwt.secret（JWT_SECRET）が未設定です。HS256 署名用に "
                            + MIN_SECRET_BYTES + " バイト以上のシークレットを設定してください");
        }
        // シークレットを UTF-8 のバイト列へ変換する（鍵長の判定と鍵生成の両方に使う）
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        // 32 バイト未満の鍵は HS256 の強度要件（RFC 7518 §3.2）を満たさないため起動を失敗させる
        if (secretBytes.length < MIN_SECRET_BYTES) {
            // 現在の長さは伝えるがシークレットの値そのものはメッセージに含めない（§9 秘密情報を漏らさない）
            throw new IllegalStateException(
                    "security.jwt.secret（JWT_SECRET）が短すぎます。HS256 には "
                            + MIN_SECRET_BYTES + " バイト以上が必要です。現在: " + secretBytes.length + " バイト");
        }
        // 検証を通ったバイト列から HMAC-SHA256 の共有鍵を生成して保持する
        this.secretKey = new SecretKeySpec(secretBytes, HMAC_SHA256);
    }

    /**
     * 受信した Bearer トークン（JWT）の署名・クレームを検証するデコーダを登録する。
     *
     * <p>検証するもの: HS256 の署名、{@code exp} / {@code nbf}（既定の時刻検証。時刻ずれ許容 60 秒）、
     * 証明書バインド（{@code cnf.x5t#S256}。本アプリはこのクレームを載せないので実質 no-op）、
     * そして {@code iss} が {@link #TOKEN_ISSUER} と一致すること。
     *
     * <p><b>iss を検証する理由</b><br>
     * 署名鍵は {@code JWT_SECRET} 1 つなので、iss を見ないと「その鍵を知っている全員」が通る。
     * 同じ値を別サービスと共用してしまう運用ミスが、そのままこの API への完全なアクセスになる。
     * iss 未設定のトークンも {@link JwtIssuerValidator} の既定どおり失敗させる（不一致だけを弾くと
     * iss を省くだけで迂回できる）。
     *
     * <p><b>組み立てに {@code createDefaultWithIssuer} を使う理由</b><br>
     * {@code setJwtValidator} は既定のバリデータを**置き換える**ため、自分で
     * {@code DelegatingOAuth2TokenValidator} を組むと「既定を束ね忘れて期限切れトークンが通る」
     * 事故を書けてしまう。このファクトリは既定を必ず内側へ入れ直すので、その誤りが表現できない。
     *
     * @return HS256 の共有シークレットと iss の一致まで検証する JwtDecoder
     */
    // このメソッドが返す JwtDecoder を Spring の Bean として登録する
    @Bean
    public JwtDecoder jwtDecoder() {
        // 共有シークレット鍵と HS256 アルゴリズムを指定してデコーダを組み立てる
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
        // 既定の検証（exp / nbf / 証明書バインド）に iss の一致検証を足したものへ差し替える
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TOKEN_ISSUER));
        // iss まで検証するデコーダを返す
        return decoder;
    }

    /**
     * トークン発行エンドポイントが JWT を生成・署名するためのエンコーダを登録する。
     *
     * @return HS256 の共有シークレットで署名する JwtEncoder
     */
    // このメソッドが返す JwtEncoder を Spring の Bean として登録する
    @Bean
    public JwtEncoder jwtEncoder() {
        // デコーダと同じ共有シークレット鍵を供給源としてエンコーダを生成して返す
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }
}
