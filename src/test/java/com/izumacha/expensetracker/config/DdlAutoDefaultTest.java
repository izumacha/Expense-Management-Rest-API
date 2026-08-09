// 設定クラスのテストパッケージ（application.yml の既定値検証用）
package com.izumacha.expensetracker.config;

// application.yml を読み込むための例外型（リソース読み込み失敗時に投げられる）
import java.io.IOException;
// 読み込んだ YAML を Spring の設定ソースとして扱うための型
import java.util.List;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// YAML ファイルを Spring の PropertySource へ変換するローダー
import org.springframework.boot.env.YamlPropertySourceLoader;
// クラスパス上のファイルを指すリソース型（src/main/resources/application.yml を読む）
import org.springframework.core.io.ClassPathResource;
// 設定値の集合（PropertySource）を表す型
import org.springframework.core.env.PropertySource;
// プレースホルダ（${...:既定値}）を解決できる環境オブジェクト
import org.springframework.core.env.StandardEnvironment;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同梱の {@code application.yml} が持つ Hibernate スキーマ反映方針
 * （{@code spring.jpa.hibernate.ddl-auto}）の**既定値**が {@code validate} であることを固定するテスト。
 *
 * <p>【何を守るテストか】既定値が {@code update} だと、環境変数 {@code SPRING_JPA_HIBERNATE_DDL_AUTO}
 * を設定し忘れた環境（＝本番でいちばん起きやすい設定漏れ）で Hibernate が起動時に本番 DB へ
 * ALTER TABLE を自動発行してしまう。「設定を忘れた側が危険側に倒れる」のは fail-open であり、
 * 共通規約 §9 の fail-safe 原則に反する。既定を {@code validate}（DDL を出さず、実スキーマと
 * エンティティが食い違えば起動を失敗させる）に固定し、スキーマを作りたい環境だけが明示的に
 * 上書きする形を、リグレッションとして機械的に守る。
 *
 * <p>Spring アプリ本体は起動せず、YAML を読んでプレースホルダを解決するだけの単体テスト（§11）。
 * 実行環境に {@code SPRING_JPA_HIBERNATE_DDL_AUTO} が設定されていても結果が揺れないよう、
 * OS 環境変数とシステムプロパティの設定ソースは明示的に取り除いて評価する。
 */
class DdlAutoDefaultTest {

    // 検証対象の設定キー（Spring Boot が Hibernate へ渡すスキーマ反映方針）
    private static final String DDL_AUTO_KEY = "spring.jpa.hibernate.ddl-auto";

    // ddl-auto の既定値が validate であることを検証する
    @Test
    void application_ymlのddlAuto既定値はvalidate() throws IOException {
        // src/main/resources/application.yml をクラスパスから読み込む
        List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        // YAML が 1 つ以上の設定ソースとして読めていることを確認する（読めていないと以降の検証が空振りする）
        assertThat(loaded).isNotEmpty();

        // プレースホルダ（${...:既定値}）を解決できる環境オブジェクトを用意する
        StandardEnvironment environment = new StandardEnvironment();
        // OS 環境変数の設定ソースを外す（実行環境に同名の変数があっても既定値の検証結果を揺らさないため）
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        // システムプロパティの設定ソースも同じ理由で外す
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        // 読み込んだ application.yml を唯一の設定ソースとして登録する
        loaded.forEach(source -> environment.getPropertySources().addLast(source));

        // プレースホルダを解決した結果の ddl-auto を取り出す（環境変数が無いので既定値が使われる）
        String resolved = environment.getProperty(DDL_AUTO_KEY);

        // 既定値が validate（DDL を発行しない fail-safe な値）であることを検証する
        assertThat(resolved).isEqualTo("validate");
    }
}
