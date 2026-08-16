// データアクセス（Spring Data JPA）のパッケージ
package com.izumacha.expensetracker.repository;

// 監査ログのエンティティを参照する
import com.izumacha.expensetracker.domain.AuditLog;
// 基本的な CRUD とページングを備えたリポジトリの基底インターフェース
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 監査ログ（{@link AuditLog}）の永続化を担うリポジトリ。
 *
 * <p>監査ログは<b>追記専用</b>のため、追加のクエリメソッドは定義していない。
 * 参照は現時点では DB へ直接接続して行う運用とし（README「運用手順」参照）、
 * 参照用の API を作るときは「誰が監査ログを読めるか」の認可設計を先に決める必要がある
 * （監査ログ自体が「いつ誰が何を触ったか」という機微な情報であるため。§9 最小公開）。
 *
 * <p>書き込みの経路は {@link com.izumacha.expensetracker.audit.AuditLogWriter} だけに
 * 限定している。ほかの層から直接 {@code save} を呼ばないこと（記録の一貫性が崩れるため）。
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
