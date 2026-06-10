// リポジトリのテストパッケージ
package com.izumacha.expensetracker.repository;

// JPA スライステストを有効化するアノテーション
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
// JPA リポジトリ層だけを読み込むスライステスト
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
// Testcontainers のコンテナを Spring の DataSource に自動接続するアノテーション
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
// テスト用のプロパティを上書きするアノテーション
import org.springframework.test.context.TestPropertySource;
// PostgreSQL の使い捨てコンテナ
import org.testcontainers.containers.PostgreSQLContainer;
// コンテナのライフサイクルを JUnit に委ねるアノテーション
import org.testcontainers.junit.jupiter.Container;
// Testcontainers と JUnit 5 を連携させるアノテーション
import org.testcontainers.junit.jupiter.Testcontainers;

// リポジトリ層テストの共通基底クラス（Testcontainers の設定を一元化する）
@DataJpaTest
// 組込 DB への自動置換を無効化し、下記の本物の PostgreSQL を使わせる
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// テスト実行ごとにスキーマを作り直して決定的にする
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
// Testcontainers のライフサイクル管理を有効化する
@Testcontainers
abstract class AbstractRepositoryTest {

    // 本番と同じ PostgreSQL 16 の使い捨てコンテナを起動する
    @Container
    // このコンテナの接続情報を Spring の DataSource へ自動配線する
    @ServiceConnection
    // コンテナはクラス全体で共有するため static にする
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
}
