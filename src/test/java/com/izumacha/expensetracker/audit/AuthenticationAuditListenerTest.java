// 監査ログ関連のテストパッケージ
package com.izumacha.expensetracker.audit;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 資格情報が不正だったことを表す認証例外
import org.springframework.security.authentication.BadCredentialsException;
// 認証済み・未認証のユーザー名＋資格情報を表すトークン
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
// 資格情報不正による認証失敗イベント
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
// 認証成功イベント
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;

// 呼び出しの検証に使うヘルパーを取り込む
import static org.mockito.Mockito.verify;
// モックを生成するヘルパーを取り込む
import static org.mockito.Mockito.mock;
// これ以上の呼び出しが無かったことを検証するヘルパーを取り込む
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * AuthenticationAuditListener（認証イベントを監査ログへ流すリスナ）のユニットテスト。
 *
 * <p>【何を守るテストか】
 * <ul>
 *   <li><b>成功と失敗の両方が記録されること</b>: 片方だけ記録していると「失敗が無い＝安全」
 *       なのか「失敗を記録していないだけ」なのかを後から区別できず、総当たりの立証に使えない
 *       （docs/issue-analysis.md 追加所見 A.1 の目的そのもの）。</li>
 *   <li><b>パスワードを記録しないこと</b>: 認証イベントは資格情報を保持している。記録先へ
 *       渡すのがユーザー名だけであることを、引数の完全一致で機械的に固定する（§9）。</li>
 * </ul>
 *
 * <p>Spring コンテキストは起動せず、イベントオブジェクトを直接渡す純粋ユニットテスト（共通規約 §11）。
 */
class AuthenticationAuditListenerTest {

    // テストで使うユーザー名（認証の主体）
    private static final String USERNAME = "api-user";

    // テストで使うパスワード（記録先へ渡ってはいけない値）
    private static final String PASSWORD = "must-not-be-recorded";

    // 監査ログの記録先のモック
    private final AuditRecorder recorder = mock(AuditRecorder.class);

    // 検証対象
    private final AuthenticationAuditListener listener = new AuthenticationAuditListener(recorder);

    // 認証成功イベントがログイン成功として記録されることを検証する
    @Test
    void 認証成功はログイン成功として記録される() {
        // ユーザー名とパスワードを保持する認証トークンから成功イベントを組み立てる
        AuthenticationSuccessEvent event = new AuthenticationSuccessEvent(
                new UsernamePasswordAuthenticationToken(USERNAME, PASSWORD));
        // 成功イベントを処理させる
        listener.onAuthenticationSuccess(event);
        // ユーザー名だけを添えてログイン成功として記録されたことを検証する
        verify(recorder).recordAuthentication(AuditAction.LOGIN_SUCCESS, USERNAME);
        // パスワードを含む他の呼び出しが無かったことを検証する（記録先へ渡るのはユーザー名だけ。§9）
        verifyNoMoreInteractions(recorder);
    }

    // 認証失敗イベントがログイン失敗として記録されることを検証する
    @Test
    void 認証失敗はログイン失敗として記録される() {
        // ユーザー名とパスワードを保持する認証トークンから失敗イベントを組み立てる
        AuthenticationFailureBadCredentialsEvent event = new AuthenticationFailureBadCredentialsEvent(
                new UsernamePasswordAuthenticationToken(USERNAME, PASSWORD),
                new BadCredentialsException("資格情報が不正です"));
        // 失敗イベントを処理させる
        listener.onAuthenticationFailure(event);
        // ユーザー名だけを添えてログイン失敗として記録されたことを検証する
        verify(recorder).recordAuthentication(AuditAction.LOGIN_FAILURE, USERNAME);
        // パスワードを含む他の呼び出しが無かったことを検証する（§9）
        verifyNoMoreInteractions(recorder);
    }
}
