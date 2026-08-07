// ビジネスロジックのパッケージ
package com.izumacha.expensetracker.service;

// トークン発行リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.TokenRequest;
// トークン発行レスポンス DTO を参照する
import com.izumacha.expensetracker.dto.response.TokenResponse;

// 現在時刻を表す型（トークンの発行時刻・有効期限の計算に使う）
import java.time.Instant;

// ユーザー名とパスワードを照合する認証マネージャ
import org.springframework.security.authentication.AuthenticationManager;
// ユーザー名＋パスワードの認証要求を表すトークンクラス
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
// JWT の署名アルゴリズム（HS256 等の MAC 系）を表す列挙
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
// JWT のクレーム（発行者・主体・有効期限などの中身）を組み立てるクラス
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
// JWT を生成・署名するインターフェース（JwtConfig の HS256 エンコーダが注入される）
import org.springframework.security.oauth2.jwt.JwtEncoder;
// エンコーダへクレームとヘッダを渡すためのパラメータクラス
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
// JWT の署名ヘッダ（アルゴリズム指定）を組み立てるクラス
import org.springframework.security.oauth2.jwt.JwsHeader;
// Spring に管理させるためのサービス宣言
import org.springframework.stereotype.Service;

/**
 * トークン発行（POST /api/auth/token）のビジネスロジック。
 *
 * <p>ユーザー名とパスワードを {@link AuthenticationManager}（bcrypt 照合）で検証し、
 * 成功したら有効期限付きの JWT（HS256 署名）を発行する。認証失敗時は
 * {@code AuthenticationException} がそのまま送出され、GlobalExceptionHandler が
 * 401 の {@code {status, message}} 応答へ整形する。
 */
// このクラスをビジネスロジックの Bean として登録する
@Service
public class AuthTokenService {

    // 発行するトークンの有効期間（秒）。1 時間に固定する（§6 マジックナンバーの一元管理）。
    // 長くするほど漏洩時に悪用できる時間が延びるため、MVP では短めの 1 時間とする（§9）
    public static final long TOKEN_TTL_SECONDS = 3600;

    // JWT の発行者（iss クレーム）として名乗る識別子（本アプリを表す固定値）
    static final String TOKEN_ISSUER = "expense-tracker";

    // ユーザー名・パスワードの照合を行う認証マネージャ（ApiUserConfig で構成）
    private final AuthenticationManager authenticationManager;

    // JWT の生成・署名を行うエンコーダ（JwtConfig の HS256 共有シークレット）
    private final JwtEncoder jwtEncoder;

    // 依存 Bean をコンストラクタで受け取る
    public AuthTokenService(
            // 認証マネージャ（Spring が注入する）
            AuthenticationManager authenticationManager,
            // JWT エンコーダ（Spring が注入する）
            JwtEncoder jwtEncoder) {
        // 認証マネージャをフィールドに保持する
        this.authenticationManager = authenticationManager;
        // エンコーダをフィールドに保持する
        this.jwtEncoder = jwtEncoder;
    }

    /**
     * ユーザー名とパスワードを検証し、有効期限付きのアクセストークン（JWT）を発行する。
     *
     * @param request ユーザー名とパスワードを持つトークン発行リクエスト
     * @return 発行したトークンと有効期間（秒）
     * @throws org.springframework.security.core.AuthenticationException 認証に失敗した場合（401 へ整形される）
     */
    public TokenResponse issueToken(TokenRequest request) {
        // ユーザー名とパスワードを認証マネージャで照合する（失敗時は AuthenticationException が投げられる。
        // パスワードはここで照合に使うだけで、保存・ログ出力はしない。§9）
        var authentication = authenticationManager.authenticate(
                // 未認証状態のユーザー名＋パスワードの組を認証要求として渡す
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        // トークンの発行時刻として現在時刻を取得する
        Instant issuedAt = Instant.now();
        // JWT のクレーム（トークンの中身）を組み立てる
        JwtClaimsSet claims = JwtClaimsSet.builder()
                // 発行者（このアプリ）を設定する
                .issuer(TOKEN_ISSUER)
                // 主体（認証に成功したユーザー名）を設定する
                .subject(authentication.getName())
                // 発行時刻を設定する
                .issuedAt(issuedAt)
                // 有効期限（発行時刻 + 有効期間）を設定する。期限切れトークンは JwtDecoder が拒否する
                .expiresAt(issuedAt.plusSeconds(TOKEN_TTL_SECONDS))
                // クレームを確定する
                .build();
        // 署名ヘッダ（HS256）を組み立てる（JwtConfig の共有シークレット鍵と対応するアルゴリズム）
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        // ヘッダとクレームを署名して JWT 文字列を生成する
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        // トークンと有効期間（秒）を DTO に詰めて返す
        return new TokenResponse(token, TOKEN_TTL_SECONDS);
    }
}
