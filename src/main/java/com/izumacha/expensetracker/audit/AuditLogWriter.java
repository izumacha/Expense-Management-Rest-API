// 監査ログ（誰が・いつ・何をしたか）に関するパッケージ
package com.izumacha.expensetracker.audit;

// 監査ログのエンティティを参照する
import com.izumacha.expensetracker.domain.AuditLog;
// 監査ログの永続化を担うリポジトリ
import com.izumacha.expensetracker.repository.AuditLogRepository;
// Spring に管理させるためのコンポーネント宣言
import org.springframework.stereotype.Component;
// トランザクションの伝播方法を指定する列挙
import org.springframework.transaction.annotation.Propagation;
// メソッドをトランザクション境界にするアノテーション
import org.springframework.transaction.annotation.Transactional;

/**
 * 監査ログ 1 行を<b>独立したトランザクション</b>で DB へ書き込むだけのコンポーネント。
 *
 * <p><b>なぜ {@link AuditRecorder} と分けるのか</b><br>
 * Spring の {@code @Transactional} は<b>プロキシ経由の呼び出しでしか効かない</b>。
 * 同じクラスの中で「例外を握って fail-open にするメソッド」から「トランザクション境界の
 * メソッド」を呼ぶと自己呼び出し（self-invocation）になり、プロキシを通らないため
 * {@code REQUIRES_NEW} が静かに無視される。書き込みだけを別 Bean に切り出すことで
 * 必ずプロキシを経由させる。責務も「書く人」と「いつ書くかを決める人」に分かれ、
 * 1 クラス 1 責務になる（§6）。
 *
 * <p><b>なぜ {@link Propagation#REQUIRES_NEW} なのか</b><br>
 * この書き込みは業務トランザクションの<b>コミット後</b>に呼ばれる（{@link AuditRecorder}）。
 * その時点で外側のトランザクションは既に終了しており、既存トランザクションへ参加する
 * 設定（既定の {@code REQUIRED}）では「トランザクションが無いので毎回新規に開始する」という
 * 曖昧な挙動に依存することになる。新しいトランザクションを開くことを明示して、認証イベント
 * （トランザクション外から呼ばれる）と変更イベント（コミット後に呼ばれる）のどちらの経路でも
 * 同じ挙動になるようにする。
 */
// このクラスを Spring の Bean として登録する
@Component
public class AuditLogWriter {

    // 監査ログを保存するリポジトリ
    private final AuditLogRepository auditLogRepository;

    // 依存 Bean をコンストラクタで受け取る
    public AuditLogWriter(
            // 監査ログのリポジトリ（Spring が注入する）
            AuditLogRepository auditLogRepository) {
        // リポジトリをフィールドに保持する
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 監査ログ 1 行を新しいトランザクションで保存する。
     *
     * <p>失敗（DB 断など）の握りつぶしはここでは行わない。呼び出し元
     * （{@link AuditRecorder}）が業務処理を止めないために例外を捕捉する。ここで捕捉すると
     * 「トランザクションはロールバックされたのに呼び出し元は成功と認識する」ねじれが起きる。
     *
     * @param auditLog 保存する監査ログ（呼び出し元で組み立て済み）
     */
    // 新しいトランザクションを開始してこのメソッドを実行する
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLog auditLog) {
        // 監査ログを 1 行保存する（追記のみ。更新・削除は行わない）
        auditLogRepository.save(auditLog);
    }
}
