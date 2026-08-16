// 設定クラスのテストパッケージ
package com.izumacha.expensetracker.config;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// アプリ内へイベントを発行するインターフェース（テスト用に捕捉する対象）
import org.springframework.context.ApplicationEventPublisher;
// ユーザー名とパスワードを照合する認証マネージャのインターフェース
import org.springframework.security.authentication.AuthenticationManager;
// 資格情報が不正だったことを表す認証例外
import org.springframework.security.authentication.BadCredentialsException;
// ユーザー名＋パスワードの認証要求を表すトークン
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
// 資格情報不正による認証失敗イベント
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
// 認証成功イベント
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
// bcrypt ハッシュを生成するためのエンコーダ
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
// 捕捉したイベントを溜める入れ物
import java.util.ArrayList;
// 捕捉したイベントを溜める入れ物のインターフェース
import java.util.List;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
// 「例外が投げられること」を検証する AssertJ のヘルパーを取り込む
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ApiUserConfig が組み立てる AuthenticationManager が、認証の成否を<b>アプリ内イベントとして
 * 発行する</b>ことを固定するテスト。
 *
 * <p>【何を守るテストか】監査ログの認証イベント（{@code audit.AuthenticationAuditListener}）は
 * このイベントの購読だけで成り立っている。{@code ProviderManager} の既定の発行器は何も
 * 発行しない実装なので、{@code setAuthenticationEventPublisher(...)} の 1 行が消えると
 * <b>認証の記録だけが静かに止まる</b>（アプリは正常に動き、他のテストも通り、監査ログには
 * 変更イベントだけが残るため「ログインが一度も行われていない」ように見える）。
 * リスナ側のユニットテストではこの配線の欠落を検出できないため、ここで配線そのものを検証する。
 *
 * <p>Spring コンテキストは起動せず、設定クラスのメソッドを直接呼んで組み立てる（共通規約 §11）。
 */
class AuthenticationEventPublishingTest {

    // テストで使う API ユーザー名
    private static final String USERNAME = "api-user";

    // テストで使う正しいパスワード（ハッシュ化して設定に渡す）
    private static final String CORRECT_PASSWORD = "correct-password";

    // 検証対象の設定クラス（正しいユーザー名と bcrypt ハッシュで構成する）
    private final ApiUserConfig config =
            new ApiUserConfig(USERNAME, new BCryptPasswordEncoder().encode(CORRECT_PASSWORD));

    // 発行されたイベントを溜める入れ物
    private final List<Object> publishedEvents = new ArrayList<>();

    // 発行されたイベントをそのまま溜めるだけの発行器（本番では Spring のコンテキストが担う）
    private final ApplicationEventPublisher capturingPublisher = publishedEvents::add;

    // 設定クラスから認証マネージャを組み立てるヘルパー（本番と同じ組み立て手順を通す）
    private AuthenticationManager buildAuthenticationManager() {
        // ユーザーストア・パスワードエンコーダ・イベント発行器を渡して認証マネージャを作る
        return config.authenticationManager(
                // 環境変数由来の単一ユーザーを保持するストア
                config.apiUserDetailsService(),
                // bcrypt のパスワード照合エンコーダ
                config.passwordEncoder(),
                // 発行されたイベントを捕捉する発行器
                capturingPublisher);
    }

    // 認証に成功したときに成功イベントが発行されることを検証する
    @Test
    void 認証成功で成功イベントが発行される() {
        // 認証マネージャを組み立てる
        AuthenticationManager manager = buildAuthenticationManager();
        // 正しいユーザー名とパスワードで認証する
        manager.authenticate(new UsernamePasswordAuthenticationToken(USERNAME, CORRECT_PASSWORD));
        // 監査リスナが購読している成功イベントが発行されたことを検証する
        assertThat(publishedEvents).hasAtLeastOneElementOfType(AuthenticationSuccessEvent.class);
    }

    // 認証に失敗したときに失敗イベントが発行されることを検証する
    @Test
    void 認証失敗で失敗イベントが発行される() {
        // 認証マネージャを組み立てる
        AuthenticationManager manager = buildAuthenticationManager();
        // 誤ったパスワードでの認証が資格情報不正で失敗することを検証する
        assertThatThrownBy(() -> manager.authenticate(
                new UsernamePasswordAuthenticationToken(USERNAME, "wrong-password")))
                // 例外の型が資格情報不正であることを検証する
                .isInstanceOf(BadCredentialsException.class);
        // 監査リスナが購読している失敗イベントが発行されたことを検証する
        assertThat(publishedEvents).hasAtLeastOneElementOfType(AuthenticationFailureBadCredentialsEvent.class);
    }

    // 存在しないユーザー名でも失敗イベントが発行されることを検証する（総当たりの立証に必要）
    @Test
    void 未知のユーザー名でも失敗イベントが発行される() {
        // 認証マネージャを組み立てる
        AuthenticationManager manager = buildAuthenticationManager();
        // 設定されていないユーザー名での認証が失敗することを検証する
        assertThatThrownBy(() -> manager.authenticate(
                new UsernamePasswordAuthenticationToken("unknown-user", CORRECT_PASSWORD)))
                // ユーザー不在も資格情報不正に丸められる（ユーザー列挙防止。§9）ことを併せて検証する
                .isInstanceOf(BadCredentialsException.class);
        // 失敗イベントが発行され、監査ログに「誰の名前で試されたか」が残せることを検証する
        assertThat(publishedEvents).hasAtLeastOneElementOfType(AuthenticationFailureBadCredentialsEvent.class);
    }
}
