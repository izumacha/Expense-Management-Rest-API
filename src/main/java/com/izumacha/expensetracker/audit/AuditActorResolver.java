// 監査ログ（誰が・いつ・何をしたか）に関するパッケージ
package com.izumacha.expensetracker.audit;

// 監査ログの列長（actor の上限）を参照する
import com.izumacha.expensetracker.domain.AuditLog;
// 外部由来の文字列を記録先へ書く前に無害化する共通ユーティリティ
import com.izumacha.expensetracker.validation.TextSanitizer;
// 認証情報が匿名／記憶ログインかを判定する標準インターフェース
import org.springframework.security.authentication.AuthenticationTrustResolver;
// 上記インターフェースの標準実装
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
// 現在のリクエストに紐づく認証情報を保持するホルダ
import org.springframework.security.core.Authentication;
// 認証情報を取り出す静的ホルダ（スレッドローカル）
import org.springframework.security.core.context.SecurityContextHolder;
// Spring に管理させるためのコンポーネント宣言
import org.springframework.stereotype.Component;

/**
 * 監査ログの「誰が（actor）」を、現在のリクエストの認証情報から決めるコンポーネント。
 *
 * <p><b>どこから取るか</b>: {@link SecurityContextHolder} に入っている
 * {@link Authentication}（JWT 認証済みなら {@code sub} クレーム＝トークン発行時に照合した
 * API ユーザー名）を使う。JPA のライフサイクルコールバックはリクエストを処理している
 * スレッド上で呼ばれるため、ここから認証情報を参照できる。
 *
 * <p><b>認証情報が無い場合</b>: 未認証のまま到達しうる経路（アプリ起動時の初期化処理、
 * テストからの直接呼び出しなど）では {@link #ANONYMOUS_ACTOR} を返す。actor 列は
 * {@code NOT NULL} なので、ここで必ず値を確定させて「記録できずに落ちる」ことを防ぐ
 * （fail-open。監査の失敗で業務処理を止めない方針。{@link AuditRecorder} の説明を参照）。
 *
 * <p><b>なぜ無害化するか</b>: 値は自前で署名した JWT 由来なので通常は安全だが、
 * actor 列には認証<b>失敗</b>時に外部から送られたユーザー名も入る
 * （{@code service.AuthTokenService} → {@link AuditRecorder#recordAuthentication}）。
 * 「actor に入る値は必ず無害化・切り詰め済み」という不変条件を経路ごとに揺らさないため、
 * こちらの経路でも同じ処理を通す（多層防御 ＋ 読み手が経路を追わなくても列の性質が分かる）。
 */
// このクラスを Spring の Bean として登録する
@Component
public class AuditActorResolver {

    /**
     * 認証情報が無いときに actor として記録する値。
     *
     * <p>空文字や {@code null} ではなく明示的な語を入れるのは、後から監査ログを読む人が
     * 「記録漏れ」と「本当に未認証で行われた操作」を区別できるようにするため。
     */
    public static final String ANONYMOUS_ACTOR = "anonymous";

    /**
     * 認証情報が「匿名ユーザー」かどうかを判定する標準実装。
     *
     * <p><b>なぜ {@code isAuthenticated()} では足りないのか</b>: Spring Security の
     * 匿名ユーザーを表す {@code AnonymousAuthenticationToken} は
     * <b>{@code isAuthenticated()} が {@code true} を返す</b>（「匿名として認証済み」という
     * 扱いのため）。この判定だけに頼ると、匿名アクセスの操作主体が {@code anonymousUser} という
     * 実在のアカウント名であるかのように監査ログへ記録されてしまう。判定を自前で書かず
     * 標準のトラストリゾルバに委ねる（記憶ログイン等が加わっても判定が追随する）。
     */
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    /**
     * 現在のリクエストの操作主体を返す。
     *
     * @return 認証済みならユーザー名（無害化・{@link AuditLog#ACTOR_MAX_LENGTH} で切り詰め済み）、
     *         未認証なら {@link #ANONYMOUS_ACTOR}
     */
    public String currentActor() {
        // 現在のスレッドに紐づく認証情報を取り出す（未認証なら null が入っていることがある）
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 認証情報そのものが無ければ未認証として扱う
        if (authentication == null) {
            // 未認証を表す固定値を返す
            return ANONYMOUS_ACTOR;
        }
        // 認証が完了していないトークンは未認証として扱う
        if (!authentication.isAuthenticated()) {
            // 未認証を表す固定値を返す
            return ANONYMOUS_ACTOR;
        }
        // 匿名ユーザーのトークンも未認証として扱う（isAuthenticated() だけでは弾けないため。
        // 上のフィールド trustResolver の説明を参照）
        if (trustResolver.isAnonymous(authentication)) {
            // 未認証を表す固定値を返す
            return ANONYMOUS_ACTOR;
        }
        // 認証情報から主体の名前（JWT の sub クレーム＝API ユーザー名）を取り出す
        String name = authentication.getName();
        // 名前が空（null または空白のみ）なら、記録として意味を成さないので未認証扱いにする
        if (name == null || name.isBlank()) {
            // 未認証を表す固定値を返す
            return ANONYMOUS_ACTOR;
        }
        // 制御文字を除去し列長に切り詰めてから返す（actor 列の不変条件をこの 1 箇所で保証する）
        return TextSanitizer.sanitize(name, AuditLog.ACTOR_MAX_LENGTH);
    }
}
