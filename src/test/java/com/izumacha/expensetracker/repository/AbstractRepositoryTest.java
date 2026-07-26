// リポジトリのテストパッケージ
package com.izumacha.expensetracker.repository;

// 組込 DB への自動置換を制御するアノテーション
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
// JPA リポジトリ層だけを読み込むスライステスト
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
// テスト実行時にプロパティを動的登録するためのレジストリ
import org.springframework.test.context.DynamicPropertyRegistry;
// 動的プロパティ登録メソッドを示すアノテーション
import org.springframework.test.context.DynamicPropertySource;
// PostgreSQL の使い捨てコンテナ
import org.testcontainers.containers.PostgreSQLContainer;

// リポジトリ層テストの共通基底クラス（Testcontainers の設定を一元化する）
@DataJpaTest
// 組込 DB への自動置換を無効化し、下記の本物の PostgreSQL を使わせる
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
abstract class AbstractRepositoryTest {

    // 全テストクラスで共有する単一の PostgreSQL 16 コンテナ（シングルトン・パターン）
    static final PostgreSQLContainer<?> POSTGRES =
            // 本番と同じ PostgreSQL 16 を使う
            new PostgreSQLContainer<>("postgres:16-alpine");

    // クラス初期化時に一度だけコンテナを起動する（JVM 終了まで生存し Ryuk が後始末する）
    static {
        // コンテナを起動する（@Container を使わないので途中で停止されない）
        POSTGRES.start();
    }

    // 起動済みコンテナの接続情報を Spring の DataSource プロパティへ動的に登録する
    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        // 接続 URL をコンテナの値で上書きする
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        // 接続ユーザー名をコンテナの値で上書きする
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        // 接続パスワードをコンテナの値で上書きする
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // テスト実行ごとにスキーマを作り直して決定的にする
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
}
