// データアクセス（Spring Data JPA）のパッケージ
package com.izumacha.expensetracker.repository;

// 監査ログのエンティティを参照する
import com.izumacha.expensetracker.domain.AuditLog;
// 一括保存・一覧取得で扱う型
import java.util.List;
// 必要なメソッドだけを宣言できる最小のリポジトリ基底インターフェース
import org.springframework.data.repository.Repository;

/**
 * 監査ログ（{@link AuditLog}）の永続化を担うリポジトリ。
 *
 * <p><b>なぜ {@code JpaRepository} を継承しないのか</b><br>
 * {@code JpaRepository} を継承すると {@code delete} 系や {@code deleteAll} まで公開され、
 * このリポジトリを注入したどのコンポーネントからでも監査記録を消せてしまう。
 * 「監査ログは追記専用」という約束を README・{@link AuditLog} の javadoc・CLAUDE.md で
 * 宣言している以上、<b>約束は型で守る</b>べきで、規約とレビューだけに頼るべきではない。
 * そこで最小の {@link Repository} を継承し、必要な操作（保存と参照）だけを明示的に宣言する。
 *
 * <p>更新経路も同時に塞いである: {@link AuditLog} にセッターは無く、全列が
 * {@code updatable = false} なので、既存行を読み出して書き換えることもできない。
 *
 * <p>書き込みの入口は {@link com.izumacha.expensetracker.audit.AuditLogWriter} だけに
 * 限定している。ほかの層から直接 {@code saveAll} を呼ばないこと（記録の一貫性が崩れるため）。
 *
 * <p>参照は現時点では DB へ直接接続して行う運用とし（README「監査ログの確認」参照）、
 * 参照用の API を作るときは「誰が監査ログを読めるか」の認可設計を先に決める必要がある
 * （監査ログ自体が「いつ誰が何を触ったか」という機微な情報であるため。§9 最小公開）。
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    /**
     * 監査ログをまとめて保存する（追記のみ）。
     *
     * <p>型引数の形は Spring Data の基底実装（{@code SimpleJpaRepository}）の宣言に合わせてある。
     * 名前と引数が一致することで、独自クエリを書かずに基底実装がそのまま使われる。
     *
     * @param auditLogs 保存する監査ログの一覧
     * @param <S>       保存する監査ログの型（{@link AuditLog} かその派生型）
     * @return 保存された監査ログ（採番済みの主キーを持つ）
     */
    <S extends AuditLog> List<S> saveAll(Iterable<S> auditLogs);

    /**
     * 保存済みの監査ログをすべて返す。
     *
     * <p>現状の利用者はテストだけ（実運用の参照は DB へ直接接続する）。件数が増えるテーブルの
     * ため、参照 API を作る際は必ずページングを伴う取得メソッドを別途用意すること（§8）。
     *
     * @return 監査ログの一覧
     */
    List<AuditLog> findAll();
}
