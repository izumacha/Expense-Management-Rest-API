// 監査ログ関連のテストパッケージ
package com.izumacha.expensetracker.audit;

// 監査ログのエンティティ（actor 列の長さの定義元）を参照する
import com.izumacha.expensetracker.domain.AuditLog;
// 各テストの後始末を宣言するアノテーション
import org.junit.jupiter.api.AfterEach;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 未認証状態を表す匿名認証トークン
import org.springframework.security.authentication.AnonymousAuthenticationToken;
// 認証済み状態を表すユーザー名＋資格情報のトークン
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
// 権限（ロール）を表す標準実装
import org.springframework.security.core.authority.SimpleGrantedAuthority;
// 認証情報を出し入れする静的ホルダ
import org.springframework.security.core.context.SecurityContextHolder;
// 権限のリストを組み立てるために使う
import java.util.List;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuditActorResolver（監査ログの「誰が」を決めるコンポーネント）のユニットテスト。
 *
 * <p>【何を守るテストか】actor 列は {@code NOT NULL} なので、未認証の経路で {@code null} を
 * 返すと監査ログの保存そのものが落ちる。逆に Spring Security の匿名ユーザー
 * （{@code anonymousUser}）を実在のユーザー名として記録してしまうと、監査ログを読む人が
 * 「anonymousUser というアカウントが操作した」と誤読する。どちらも監査の信頼性を壊すため、
 * 認証情報の各状態でどの値になるかをここで固定する（共通規約 §11 境界値）。
 *
 * <p>Spring コンテキストは起動せず、{@link SecurityContextHolder} に直接値を差し込む純粋ユニットテスト。
 */
class AuditActorResolverTest {

    // 検証対象（依存を持たないので直接生成できる）
    private final AuditActorResolver resolver = new AuditActorResolver();

    // テストごとに認証情報を消して、後続テストへ状態が漏れないようにする
    @AfterEach
    void clearSecurityContext() {
        // スレッドローカルに残った認証情報を消す
        SecurityContextHolder.clearContext();
    }

    // 認証情報が無い場合は未認証を表す固定値になることを検証する
    @Test
    void 認証情報が無ければ匿名になる() {
        // 認証情報を入れないまま（クリア状態のまま）解決する
        assertThat(resolver.currentActor()).isEqualTo(AuditActorResolver.ANONYMOUS_ACTOR);
    }

    // 認証済みならユーザー名がそのまま actor になることを検証する（正常系）
    @Test
    void 認証済みならユーザー名になる() {
        // 認証済みのトークン（権限を渡すと isAuthenticated() が true になる）を差し込む
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("api-user", null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
        // ユーザー名がそのまま actor になることを検証する
        assertThat(resolver.currentActor()).isEqualTo("api-user");
    }

    // 匿名認証トークン（未認証扱い）は実在ユーザー名として記録されないことを検証する
    @Test
    void 匿名認証トークンは匿名になる() {
        // Spring Security の匿名ユーザーを表すトークンを差し込む
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        // 匿名認証の主体名（anonymousUser）ではなく、未認証を表す固定値になることを検証する
        assertThat(resolver.currentActor()).isEqualTo(AuditActorResolver.ANONYMOUS_ACTOR);
    }

    // ユーザー名が空白のみの場合は記録として意味を成さないため未認証扱いになることを検証する（境界値）
    @Test
    void ユーザー名が空白のみなら匿名になる() {
        // 空白だけのユーザー名を持つ認証済みトークンを差し込む
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("   ", null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
        // 未認証を表す固定値になることを検証する
        assertThat(resolver.currentActor()).isEqualTo(AuditActorResolver.ANONYMOUS_ACTOR);
    }

    // 想定外に長いユーザー名でも列長へ切り詰められることを検証する（多層防御・境界値の超過側）
    @Test
    void 長すぎるユーザー名は列長に切り詰められる() {
        // 列長より 1 文字長いユーザー名を持つ認証済みトークンを差し込む
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "u".repeat(AuditLog.ACTOR_MAX_LENGTH + 1), null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
        // actor が列長ちょうどに収まることを検証する（保存時の列長超過エラーを防ぐ）
        assertThat(resolver.currentActor()).hasSize(AuditLog.ACTOR_MAX_LENGTH);
    }

    // ユーザー名に制御文字が混ざっても記録の偽装ができないことを検証する（多層防御）
    @Test
    void ユーザー名の制御文字は置換される() {
        // 改行を含むユーザー名を持つ認証済みトークンを差し込む
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "api\nuser", null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
        // 制御文字が '_' に置換されていることを検証する
        assertThat(resolver.currentActor()).isEqualTo("api_user");
    }
}
