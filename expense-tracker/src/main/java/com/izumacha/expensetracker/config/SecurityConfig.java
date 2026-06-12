// セキュリティ設定クラスのパッケージ
package com.izumacha.expensetracker.config;

// Spring Security のフィルタチェーン定義に使う型
import org.springframework.security.web.SecurityFilterChain;
// HTTP セキュリティ設定（認証・認可・CSRF 等）を組み立てるビルダ
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
// Web セキュリティを有効化するアノテーション
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
// Lambda DSL で CSRF 設定を操作するための設定クラス
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
// Bean を宣言するアノテーション
import org.springframework.context.annotation.Bean;
// このクラス自体を Spring に設定クラスとして登録するアノテーション
import org.springframework.context.annotation.Configuration;

/**
 * Spring Security の設定クラス。
 *
 * <p><b>MVP フェーズにおける設計判断（認証を無効化している理由）</b><br>
 * 本 API は個人・小規模利用を前提とした家計簿バックエンドの MVP（Minimum Viable Product）です。
 * MVP フェーズでは認証・認可はスコープ外とし、全エンドポイントへの匿名アクセスを意図的に許可しています。
 * この設計は「未実装・見落とし」ではなく、要件定義に基づく明示的な選択です。
 *
 * <p><b>本番環境への移行時に追加すべき対応</b><br>
 * 本番導入・マルチユーザー化の際は以下を実装すること:
 * <ul>
 *   <li>JWT または OAuth2 による認証フローの実装（{@code http.oauth2ResourceServer()} 等）</li>
 *   <li>エンドポイントごとのロールベース認可（{@code .hasRole("USER")} 等）の追加</li>
 *   <li>CSRF 保護の再評価（ステートレス Bearer 認証〔JWT / OAuth2〕を使う場合に限り無効化が正当化される。
 *       Cookie 認証を追加する場合は必ず {@code csrf().enable()} で CSRF 保護を再有効化すること。
 *       SameSite Cookie は CSRF 保護の代替ではなく多層防御として併用するものであり、
 *       登録ドメインを他サービスと共有する場合は SameSite だけでは防げない〔CLAUDE.md §9〕）</li>
 *   <li>CORS 設定（{@code http.cors()} で許可オリジンを明示的に制限すること）</li>
 * </ul>
 */
// このクラスが Spring の設定クラスであることを示す
@Configuration
// Spring Security の Web セキュリティ機能を有効化する
@EnableWebSecurity
public class SecurityConfig {

    /**
     * HTTP セキュリティの設定を行い、フィルタチェーンを Bean として登録する。
     *
     * <p>MVP フェーズでは認証なしで全エンドポイントにアクセスを許可する。
     * 本番移行時はここに認証フロー・認可ルールを追加すること。
     *
     * @param http HttpSecurity ビルダー（Spring によって注入される）
     * @return 設定済みの SecurityFilterChain
     * @throws Exception HttpSecurity のビルドに失敗した場合
     */
    // このメソッドが返す SecurityFilterChain を Spring の Bean として登録する
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        // -------------------------------------------------------------------
        // CSRF（クロスサイトリクエストフォージェリ）保護の設定
        // -------------------------------------------------------------------
        // MVP のため CSRF 保護を無効化している（意図的な設計）。
        // ステートレス Bearer 認証（JWT / OAuth2）を採用する場合に限り、この無効化は正当化される
        // （CLAUDE.md §9）。現時点では Cookie セッション認証を使用していないため CSRF リスクはないが、
        // Cookie 認証を追加する際は必ず csrf().enable() で CSRF 保護を再有効化すること。
        // SameSite Cookie は CSRF 保護の代替ではなく多層防御として併用するものである。
        http.csrf(CsrfConfigurer::disable);
        // -------------------------------------------------------------------

        // -------------------------------------------------------------------
        // エンドポイントの認可設定
        // -------------------------------------------------------------------
        // MVP のため全エンドポイントへの匿名アクセスを許可している（意図的な設計）。
        // 本番導入・マルチユーザー化の際は .authenticated() や .hasRole("USER") 等で
        // 適切な認可ルールを追加すること。
        http.authorizeHttpRequests(
                // すべてのリクエストを認証なしで許可する（MVP フェーズのみ。本番では変更すること）
                auth -> auth.anyRequest().permitAll()
        );
        // -------------------------------------------------------------------

        // 設定を確定してフィルタチェーンを生成し返す
        return http.build();
    }
}
