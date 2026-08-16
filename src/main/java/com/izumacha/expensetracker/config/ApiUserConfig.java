// 設定クラスのパッケージ
package com.izumacha.expensetracker.config;

// プロパティ値を注入するアノテーション
import org.springframework.beans.factory.annotation.Value;
// アプリ内へイベントを発行するインターフェース（@EventListener が受け取る先）
import org.springframework.context.ApplicationEventPublisher;
// Bean を宣言するアノテーション
import org.springframework.context.annotation.Bean;
// このクラス自体を Spring に設定クラスとして登録するアノテーション
import org.springframework.context.annotation.Configuration;
// ユーザー名とパスワードを照合する認証マネージャのインターフェース
import org.springframework.security.authentication.AuthenticationManager;
// 認証の成功・失敗を Spring のイベントとして発行する標準実装
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
// UserDetailsService と PasswordEncoder を組み合わせる標準の認証プロバイダ
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
// 複数の認証プロバイダを束ねる AuthenticationManager の標準実装
import org.springframework.security.authentication.ProviderManager;
// ユーザー情報（ユーザー名・パスワードハッシュ・ロール）を組み立てるビルダー付きクラス
import org.springframework.security.core.userdetails.User;
// ユーザー名からユーザー情報を引くインターフェース
import org.springframework.security.core.userdetails.UserDetailsService;
// メモリ上にユーザーを保持する UserDetailsService の実装
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
// bcrypt でパスワードを照合するエンコーダ実装
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
// パスワード照合のインターフェース
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * トークン発行（POST /api/auth/token）で照合する API ユーザーを構成する設定クラス。
 *
 * <p>MVP のため DB のユーザーテーブルは持たず、環境変数 {@code API_USER_NAME} /
 * {@code API_USER_PASSWORD_HASH}（bcrypt ハッシュ）から<b>単一の API ユーザー</b>を
 * メモリ上（{@link InMemoryUserDetailsManager}）に構成する。
 *
 * <p><b>fail-closed（未設定・不正な形式では起動させない）</b><br>
 * ユーザー名またはパスワードハッシュが未設定のまま起動を許すと「誰もログインできない」か
 * 「空文字で認証が通る」のどちらかの壊れた状態になるため、起動そのものを失敗させる（CLAUDE.md §9）。
 * また、ハッシュ欄に誤って<b>平文パスワード</b>を設定するミスを検出するため、bcrypt 形式
 * （{@code $2a$} / {@code $2b$} / {@code $2y$} で始まる）でない値も起動失敗にする。
 */
// このクラスが Spring の設定クラスであることを示す
@Configuration
public class ApiUserConfig {

    // bcrypt ハッシュ文字列に共通する接頭辞（$2a$・$2b$・$2y$ のいずれも "$2" で始まる）
    private static final String BCRYPT_PREFIX = "$2";

    // API ユーザーに与えるロール名（現状は単一ロール。将来ロール分割する際の基点として定数化）
    // 同一パッケージのテストから参照できるようパッケージプライベートにしている
    static final String API_ROLE = "API";

    // トークン発行時に照合する API ユーザーのユーザー名（起動時の検証を通ったもの）
    private final String apiUserName;

    // API ユーザーのパスワードの bcrypt ハッシュ（起動時の検証を通ったもの。平文は保持しない）
    private final String apiUserPasswordHash;

    // ユーザー名とパスワードハッシュをプロパティから受け取り、起動時に検証するコンストラクタ
    public ApiUserConfig(
            // API ユーザーのユーザー名（security.api-user.name / 環境変数 API_USER_NAME。未設定なら空文字）
            @Value("${security.api-user.name:}") String apiUserName,
            // パスワードの bcrypt ハッシュ（security.api-user.password-hash / 環境変数 API_USER_PASSWORD_HASH）
            @Value("${security.api-user.password-hash:}") String apiUserPasswordHash) {
        // 【環境変数由来の設定値を必ず検証する（§9 入力は信用しない・fail-closed）】
        // ユーザー名が未設定（null または空白のみ）なら起動を失敗させる
        if (apiUserName == null || apiUserName.isBlank()) {
            // 設定ミスの内容と直し方が分かる日本語メッセージで起動時例外（アプリは開始しない）を投げる
            throw new IllegalStateException(
                    "security.api-user.name（API_USER_NAME）が未設定です。API ユーザーのユーザー名を設定してください");
        }
        // パスワードハッシュが未設定（null または空白のみ）なら起動を失敗させる
        if (apiUserPasswordHash == null || apiUserPasswordHash.isBlank()) {
            // 設定ミスの内容と直し方が分かる日本語メッセージで起動時例外を投げる
            throw new IllegalStateException(
                    "security.api-user.password-hash（API_USER_PASSWORD_HASH）が未設定です。"
                            + "パスワードの bcrypt ハッシュを設定してください");
        }
        // bcrypt 形式（"$2" で始まる）でない値は「誤って平文パスワードを設定した」可能性が高いため起動を失敗させる。
        // 平文のまま受理すると照合が常に失敗するうえ、環境変数に平文の秘密が置かれ続けてしまう（§9）
        if (!apiUserPasswordHash.startsWith(BCRYPT_PREFIX)) {
            // 設定値そのもの（平文パスワードかもしれない値）はメッセージに含めない（§9 秘密情報を漏らさない）
            throw new IllegalStateException(
                    "security.api-user.password-hash（API_USER_PASSWORD_HASH）が bcrypt 形式ではありません。"
                            + "平文パスワードではなく bcrypt ハッシュ（$2a$／$2b$／$2y$ で始まる文字列）を設定してください");
        }
        // 検証を通ったユーザー名を保持する
        this.apiUserName = apiUserName;
        // 検証を通ったパスワードハッシュを保持する
        this.apiUserPasswordHash = apiUserPasswordHash;
    }

    /**
     * パスワード照合に使うエンコーダ（bcrypt）を登録する。
     *
     * @return bcrypt でハッシュ照合を行う PasswordEncoder
     */
    // このメソッドが返す PasswordEncoder を Spring の Bean として登録する
    @Bean
    public PasswordEncoder passwordEncoder() {
        // bcrypt 実装（標準ライブラリ）を返す。暗号を自前実装しない（§9）
        return new BCryptPasswordEncoder();
    }

    /**
     * 環境変数から構成した単一の API ユーザーを保持するユーザーストアを登録する。
     *
     * @return メモリ上に API ユーザー 1 名を持つ UserDetailsService
     */
    // このメソッドが返す UserDetailsService を Spring の Bean として登録する
    @Bean
    public UserDetailsService apiUserDetailsService() {
        // ユーザー名・パスワードハッシュ・ロールを持つユーザー情報を組み立て、メモリ上のストアに登録して返す
        return new InMemoryUserDetailsManager(
                // ビルダーでユーザー情報を構築する
                User.withUsername(apiUserName)
                        // 保存済みの bcrypt ハッシュをそのまま設定する（平文は扱わない）
                        .password(apiUserPasswordHash)
                        // API ユーザーのロールを付与する
                        .roles(API_ROLE)
                        // ユーザー情報を確定する
                        .build());
    }

    /**
     * トークン発行時にユーザー名とパスワードを照合する認証マネージャを登録する。
     *
     * @param userDetailsService      ユーザー名からユーザー情報を引くストア
     * @param passwordEncoder         bcrypt のパスワード照合エンコーダ
     * @param applicationEventPublisher 認証の成否をアプリ内イベントとして流す発行器
     * @return DaoAuthenticationProvider を束ねた AuthenticationManager
     */
    // このメソッドが返す AuthenticationManager を Spring の Bean として登録する
    @Bean
    public AuthenticationManager authenticationManager(
            // 上で登録したユーザーストア（Spring が注入する）
            UserDetailsService userDetailsService,
            // 上で登録した bcrypt エンコーダ（Spring が注入する）
            PasswordEncoder passwordEncoder,
            // アプリ内イベントの発行器（Spring が注入する）
            ApplicationEventPublisher applicationEventPublisher) {
        // ユーザーストアとエンコーダを組み合わせる標準の認証プロバイダを生成する
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        // ユーザー名の解決先としてユーザーストアを設定する
        provider.setUserDetailsService(userDetailsService);
        // パスワード照合に bcrypt エンコーダを設定する
        provider.setPasswordEncoder(passwordEncoder);
        // プロバイダを 1 つ束ねた認証マネージャを生成する（ユーザー不在も既定で「資格情報不正」に丸められ、
        // ユーザー名の存在有無が応答から推測できない＝ユーザー列挙攻撃を防ぐ。§9）
        ProviderManager providerManager = new ProviderManager(provider);
        // 認証の成功・失敗をアプリ内イベントとして流すよう発行器を設定する。
        // 【なぜ必要か】ProviderManager の既定の発行器は何も発行しない実装のため、これを設定しないと
        // audit.AuthenticationAuditListener が静かに何も記録しなくなる（＝監査ログに認証イベントが
        // 一切残らない状態が、テストを書かない限り気づかれずに成立してしまう）。
        // 【なぜ自前で生成するか】Bean として注入すると、Spring Boot の自動設定が
        // AuthenticationEventPublisher を提供するかどうかという外部条件に監査の成立が依存する。
        // ここで明示的に生成すれば、依存するのは常に存在する ApplicationEventPublisher だけになる
        providerManager.setAuthenticationEventPublisher(
                // 標準実装（例外の種類に応じた失敗イベントへの振り分けを持つ）を使う。§9 認証まわりを自前実装しない
                new DefaultAuthenticationEventPublisher(applicationEventPublisher));
        // 発行器を設定済みの認証マネージャを返す
        return providerManager;
    }
}
