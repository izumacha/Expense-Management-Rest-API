// ビジネスロジックのパッケージ
package com.izumacha.expensetracker.service;

// 監査ログの操作種別（ログイン成功／失敗）を参照する
import com.izumacha.expensetracker.audit.AuditAction;
// 監査ログの記録先を参照する
import com.izumacha.expensetracker.audit.AuditRecorder;
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
// 認証済みの主体を表す型（照合結果の戻り型）
import org.springframework.security.core.Authentication;
// 認証失敗を表す例外の基底クラス（記録してから送出し直すために捕捉する）
import org.springframework.security.core.AuthenticationException;
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
 *
 * <h2>認証イベントの監査記録はここで行う</h2>
 * ログイン成功・失敗の記録は<b>このメソッドの中で</b>行う。Spring Security の認証イベント
 * （{@code AuthenticationSuccessEvent} 等）を購読する方式は<b>採らない</b>。
 *
 * <p>理由: Bearer トークンを検証するリソースサーバも内部では認証マネージャを通るため、
 * イベントを購読すると<b>通常の API 呼び出し 1 回ごとに「ログイン成功」が記録されてしまう</b>。
 * 実際、その方式では次の 2 つが起きることを確認している（{@code AuthenticationAuditScopeTest}）。
 * <ul>
 *   <li>保存期間を持たない監査テーブルがリクエスト数に比例して膨れ、ログイン成功の記録が
 *       「トークンが発行された証拠」として使えなくなる。</li>
 *   <li>Bearer トークンの検証失敗では認証の主体名が<b>トークン文字列そのもの</b>になり、
 *       それを actor として保存すると資格情報を追記専用テーブルに書き込むことになる（§9）。</li>
 * </ul>
 *
 * <p>トークン発行の経路はこのメソッド 1 つだけなので、ここで成功・失敗の両方を記録すれば
 * 取りこぼしは起きない。「記録し忘れ」を避けるためにイベントへ寄せる利点より、
 * 「記録しすぎ」を防ぐために記録元を自分たちが制御する 1 箇所に閉じる利点が上回る。
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

    // 認証の成否を監査ログへ記録する入口
    private final AuditRecorder auditRecorder;

    // 依存 Bean をコンストラクタで受け取る
    public AuthTokenService(
            // 認証マネージャ（Spring が注入する）
            AuthenticationManager authenticationManager,
            // JWT エンコーダ（Spring が注入する）
            JwtEncoder jwtEncoder,
            // 監査ログの記録先（Spring が注入する）
            AuditRecorder auditRecorder) {
        // 認証マネージャをフィールドに保持する
        this.authenticationManager = authenticationManager;
        // エンコーダをフィールドに保持する
        this.jwtEncoder = jwtEncoder;
        // 監査ログの記録先をフィールドに保持する
        this.auditRecorder = auditRecorder;
    }

    /**
     * ユーザー名とパスワードを検証し、有効期限付きのアクセストークン（JWT）を発行する。
     *
     * @param request ユーザー名とパスワードを持つトークン発行リクエスト
     * @return 発行したトークンと有効期間（秒）
     * @throws org.springframework.security.core.AuthenticationException 認証に失敗した場合（401 へ整形される）
     */
    public TokenResponse issueToken(TokenRequest request) {
        // 認証結果を受け取る変数を宣言する（成功時のみ値が入る）
        var authentication = authenticate(request);
        // 認証に成功したのでログイン成功を監査ログへ記録する。
        // 記録するのは認証済みの主体名だけで、パスワードは渡さない（§9）
        auditRecorder.recordAuthentication(AuditAction.LOGIN_SUCCESS, authentication.getName());
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

    /**
     * ユーザー名とパスワードを照合し、失敗した場合はログイン失敗を記録してから例外を送出し直す。
     *
     * <p>照合そのものと「失敗を記録する」責務をこのメソッドに閉じ込め、
     * {@link #issueToken(TokenRequest)} 側は「成功したあとの流れ」だけを読めばよい形にしている。
     *
     * <p>例外は握り潰さず必ず送出し直す（§6 エラーを握り潰さない）。呼び出し元へ返る形は
     * 記録の有無で変わらないため、監査を足したことで 401 の応答契約が変わることはない。
     *
     * @param request ユーザー名とパスワードを持つトークン発行リクエスト
     * @return 認証に成功した主体
     * @throws AuthenticationException 認証に失敗した場合（記録したうえでそのまま送出する）
     */
    private Authentication authenticate(TokenRequest request) {
        // 照合を試みる
        try {
            // ユーザー名とパスワードを認証マネージャで照合する。
            // パスワードはここで照合に使うだけで、保存・ログ出力はしない（§9）
            return authenticationManager.authenticate(
                    // 未認証状態のユーザー名＋パスワードの組を認証要求として渡す
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException e) {
            // 照合に失敗したので、試みられたユーザー名を添えてログイン失敗を記録する。
            // ここが総当たり攻撃の検知・立証に使える唯一の記録になる（docs/issue-analysis.md A.1）。
            // ユーザー名は外部入力なので、AuditRecorder 側で制御文字の除去と列長への切り詰めを行う。
            // パスワードは渡さない（§9）
            auditRecorder.recordAuthentication(AuditAction.LOGIN_FAILURE, request.username());
            // 記録した後、元の例外をそのまま送出して 401 の応答契約を保つ
            throw e;
        }
    }
}
