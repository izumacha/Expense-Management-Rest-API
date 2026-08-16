// 監査ログ（誰が・いつ・何をしたか）に関するパッケージ
package com.izumacha.expensetracker.audit;

// アプリ内で発行されたイベントを受け取るメソッドを示すアノテーション
import org.springframework.context.event.EventListener;
// 認証失敗イベントの共通の親クラス（資格情報不正・アカウント無効などを束ねる）
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
// 認証成功イベント
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
// Spring に管理させるためのコンポーネント宣言
import org.springframework.stereotype.Component;

/**
 * トークン発行（{@code POST /api/auth/token}）の認証結果を監査ログへ記録するリスナ。
 *
 * <p><b>なぜイベントを購読するのか</b><br>
 * {@code AuthTokenService} の中に記録処理を書くこともできるが、認証の成否を知っているのは
 * Spring Security 自身であり、そこが発行するイベントを購読すれば「成功・失敗のどちらかだけ
 * 記録し忘れる」という取りこぼしが起きない。認証の実装を差し替えても購読側は変わらない
 * （docs/issue-analysis.md の追加所見 A.1 の対応案どおり）。
 *
 * <p><b>イベントが飛ぶ条件</b><br>
 * イベントを発行するのは {@code ProviderManager} に発行器を設定した場合だけである。
 * 本アプリでは {@code config.ApiUserConfig} が {@code DefaultAuthenticationEventPublisher} を
 * 明示的に設定している。<b>この設定を外すと本クラスは静かに何も記録しなくなる</b>ため、
 * 認証まわりを触るときは {@code AuthenticationAuditListenerTest} の期待も併せて確認すること。
 *
 * <p><b>記録されるのはトークン発行だけ</b><br>
 * JWT で認証される通常の API 呼び出しは {@code ProviderManager} を通らないため、
 * リクエストのたびにログイン成功が積み上がることはない。
 *
 * <p><b>何を記録し、何を記録しないか（§9）</b><br>
 * 記録するのは「いつ・どのユーザー名で・成功したか失敗したか」だけ。<b>パスワードは
 * 受け取りも記録もしない</b>。失敗時のユーザー名は外部入力なので、{@link AuditRecorder} 側で
 * 制御文字を除去し列長へ切り詰めてから保存する。
 *
 * <p><b>書き込み量の上限</b><br>
 * トークン発行は未認証で叩ける経路のため、失敗を無制限に記録できると監査テーブルの肥大
 * （資源枯渇）に使われうる。これは既存の {@code security.RateLimitFilter} が担保している
 * （トークン発行は 1 回で複数枠を消費する重み付けがあり、既定では 1 分あたり 10 回程度に
 * 絞られる）。監査側に二重の絞りを持たせると上限が 2 箇所に分かれて実効値が読めなくなるため、
 * 意図的にレート制限へ一本化している。
 */
// このクラスを Spring の Bean として登録する
@Component
public class AuthenticationAuditListener {

    // 監査ログの記録先
    private final AuditRecorder auditRecorder;

    // 依存 Bean をコンストラクタで受け取る
    public AuthenticationAuditListener(
            // 監査ログ記録コンポーネント（Spring が注入する）
            AuditRecorder auditRecorder) {
        // 記録コンポーネントをフィールドに保持する
        this.auditRecorder = auditRecorder;
    }

    /**
     * ユーザー名とパスワードの照合に成功したときに呼ばれ、ログイン成功を記録する。
     *
     * @param event Spring Security が発行した認証成功イベント
     */
    // 認証成功イベントを購読する
    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        // 認証されたユーザー名を添えてログイン成功として記録する
        auditRecorder.recordAuthentication(AuditAction.LOGIN_SUCCESS, event.getAuthentication().getName());
    }

    /**
     * ユーザー名とパスワードの照合に失敗したときに呼ばれ、ログイン失敗を記録する。
     *
     * <p>資格情報不正だけでなく、アカウント無効・ロックなど他の失敗も同じ親クラスで
     * 受け取る。監査としては「認証を試みて失敗した」事実が重要で、失敗の細目まで残すと
     * 「どの条件で弾かれたか」が漏れてユーザー列挙の手がかりになりうるため記録しない（§9）。
     *
     * @param event Spring Security が発行した認証失敗イベント
     */
    // すべての認証失敗イベント（資格情報不正など）を購読する
    @EventListener
    public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        // 認証に使われたユーザー名（外部入力）を添えてログイン失敗として記録する
        auditRecorder.recordAuthentication(AuditAction.LOGIN_FAILURE, event.getAuthentication().getName());
    }
}
