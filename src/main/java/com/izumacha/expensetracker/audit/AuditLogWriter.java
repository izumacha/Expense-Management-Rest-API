// 監査ログ（誰が・いつ・何をしたか）に関するパッケージ
package com.izumacha.expensetracker.audit;

// 監査ログのエンティティを参照する
import com.izumacha.expensetracker.domain.AuditLog;
// 監査ログの永続化を担うリポジトリ
import com.izumacha.expensetracker.repository.AuditLogRepository;
// まとめて書き込む監査ログの一覧を受け取るために使う
import java.util.List;
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
     * 監査ログをまとめて新しいトランザクションで保存する。
     *
     * <p><b>なぜ 1 行ずつではなく一括なのか</b>: 1 件ごとにトランザクションを開くと、
     * 1 つの業務トランザクションで N 件の行を触ったときに N 回の開始・挿入・コミットが走る。
     * さらに、この書き込みは業務トランザクションの後始末が終わる前に呼ばれるため、
     * 業務側の DB 接続を掴んだまま<b>2 本目の接続</b>を借りることになる。接続プールの上限が
     * 小さい環境では実効的な同時書き込み数が半減し、極端な設定では枯渇しうる。
     * 1 トランザクション ＝ 1 回の書き込みにまとめて、借りる接続を 1 本・1 回で済ませる。
     *
     * <p>失敗（DB 断など）の握りつぶしはここでは行わない。呼び出し元
     * （{@link AuditRecorder}）が業務処理を止めないために例外を捕捉する。ここで捕捉すると
     * 「トランザクションはロールバックされたのに呼び出し元は成功と認識する」ねじれが起きる。
     *
     * @param auditLogs 保存する監査ログの一覧（呼び出し元で組み立て済み。空なら何もしない）
     */
    // 新しいトランザクションを開始してこのメソッドを実行する
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(List<AuditLog> auditLogs) {
        // 書くものが無ければトランザクションを開くだけ無駄なので何もしない
        if (auditLogs.isEmpty()) {
            // 何も保存せず戻る
            return;
        }
        // 監査ログをまとめて保存する（追記のみ。更新・削除は行わない）
        auditLogRepository.saveAll(auditLogs);
    }
}
