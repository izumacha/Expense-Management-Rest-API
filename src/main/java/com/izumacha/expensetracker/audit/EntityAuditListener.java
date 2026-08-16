// 監査ログ（誰が・いつ・何をしたか）に関するパッケージ
package com.izumacha.expensetracker.audit;

// 監査対象であることを示すインターフェース
import com.izumacha.expensetracker.domain.AuditedEntity;
// 永続化直後に呼ばれるコールバックを示すアノテーション
import jakarta.persistence.PostPersist;
// 削除直後に呼ばれるコールバックを示すアノテーション
import jakarta.persistence.PostRemove;
// 更新直後に呼ばれるコールバックを示すアノテーション
import jakarta.persistence.PostUpdate;
// ログ出力に使うロガー本体
import org.slf4j.Logger;
// ロガーを生成するファクトリ
import org.slf4j.LoggerFactory;
// 依存 Bean を「必要になった時点で」取り出すためのプロバイダ
import org.springframework.beans.factory.ObjectProvider;

/**
 * エンティティの作成・更新・削除を検知して監査ログへ流す JPA のエンティティリスナ。
 *
 * <p><b>なぜサービス層ではなく永続化フックに置くのか</b><br>
 * サービス層で 1 メソッドずつ記録を書くと、新しい更新経路を足したときに書き忘れる。
 * JPA のライフサイクルコールバックに寄せれば、どの経路から保存されても必ずここを通るため
 * 記録漏れが起きない（docs/issue-analysis.md の追加所見 A.1 の対応案どおり）。
 *
 * <p><b>ここを通らない操作（既知の限界）</b><br>
 * JPQL や SQL の一括更新・一括削除（{@code @Modifying} のクエリなど）は永続化コンテキストを
 * 迂回するためコールバックが発火しない。現在のサービス層はすべて {@code save} / {@code delete}
 * 経由なので該当しないが、<b>一括更新クエリを追加するときは監査記録も同時に設計すること</b>。
 *
 * <p><b>なぜ {@link ObjectProvider} で受け取るのか</b><br>
 * このリスナは EntityManagerFactory の組み立て中に生成される。ここで {@link AuditRecorder} を
 * 直接コンストラクタ注入すると、AuditRecorder → AuditLogRepository → EntityManagerFactory と
 * たどって<b>自分自身の生成待ち</b>になり循環参照で起動に失敗する。{@link ObjectProvider} は
 * 実際に {@code getObject()} を呼ぶまで解決を遅らせるため、最初のコールバックが起きる頃には
 * 依存が揃っている状態で取得できる。
 */
public class EntityAuditListener {

    // このクラス専用のロガー（記録できなかった場合の警告に使う）
    private static final Logger log = LoggerFactory.getLogger(EntityAuditListener.class);

    // 監査ログの記録先（実際に必要になるまで解決を遅らせる）
    private final ObjectProvider<AuditRecorder> auditRecorderProvider;

    /**
     * 一度解決した記録先を覚えておく場所。
     *
     * <p>プロバイダは「生成時点では依存が揃っていない」問題を避けるためだけに必要で、最初の
     * コールバックが起きる頃には対象は生成済みの単一 Bean に定まっている。それでも毎回
     * プロバイダから引くと、保存・更新・削除のたびに DI コンテナの解決処理をやり直すことになる。
     * 複数スレッドが同時に初回を通ると解決が 2 回走ることがあるが、得られるのは同じ Bean なので
     * 実害は無い（そのため二重チェックのロックは置かない）。
     */
    private volatile AuditRecorder cachedAuditRecorder;

    /**
     * 依存を遅延解決するプロバイダを受け取る。
     *
     * <p>このクラスは Spring の {@code @Component} ではなく、JPA の実装（Hibernate）が
     * {@code @EntityListeners} の指定に従って生成する。Spring Boot は Hibernate の
     * Bean コンテナに Spring のファクトリを接続しているため、この形のコンストラクタ注入が働く。
     *
     * @param auditRecorderProvider 監査ログ記録コンポーネントのプロバイダ
     */
    public EntityAuditListener(
            // 監査ログ記録コンポーネントのプロバイダ（Spring が注入する）
            ObjectProvider<AuditRecorder> auditRecorderProvider) {
        // プロバイダをフィールドに保持する
        this.auditRecorderProvider = auditRecorderProvider;
    }

    /**
     * 新しい行が永続化された直後に呼ばれ、作成を記録する。
     *
     * @param entity 永続化されたエンティティ（JPA の仕様上 {@code Object} で受け取る）
     */
    // 永続化直後に呼ばれるコールバックとして登録する
    @PostPersist
    public void onPostPersist(Object entity) {
        // 作成として記録する
        record(entity, AuditAction.CREATE);
    }

    /**
     * 既存の行が更新された直後に呼ばれ、更新を記録する。
     *
     * @param entity 更新されたエンティティ
     */
    // 更新直後に呼ばれるコールバックとして登録する
    @PostUpdate
    public void onPostUpdate(Object entity) {
        // 更新として記録する
        record(entity, AuditAction.UPDATE);
    }

    /**
     * 行が削除された直後に呼ばれ、削除を記録する。
     *
     * @param entity 削除されたエンティティ
     */
    // 削除直後に呼ばれるコールバックとして登録する
    @PostRemove
    public void onPostRemove(Object entity) {
        // 削除として記録する
        record(entity, AuditAction.DELETE);
    }

    /**
     * 記録先を返す。初回だけプロバイダから解決し、以降は覚えておいたものを使う。
     */
    private AuditRecorder auditRecorder() {
        // 覚えてある記録先を取り出す（初回は null）
        AuditRecorder resolved = cachedAuditRecorder;
        // まだ解決していなければプロバイダから取得して覚えておく
        if (resolved == null) {
            // DI コンテナから記録先を解決する
            resolved = auditRecorderProvider.getObject();
            // 次回以降のために覚えておく
            cachedAuditRecorder = resolved;
        }
        // 解決済みの記録先を返す
        return resolved;
    }

    /**
     * 共通の記録処理。3 つのコールバックが同じ手順を踏むためここへまとめる（§6 DRY）。
     *
     * <p>ここで例外を外へ出さないのは、監査の失敗で業務処理（保存そのもの）を巻き添えに
     * しないため（fail-open。方針の理由は {@link AuditRecorder} を参照）。
     */
    private void record(Object entity, AuditAction action) {
        // 監査対象のインターフェースを実装していない型は主キーを取り出せないため記録できない。
        // @EntityListeners を付けたエンティティは必ず実装している想定で、実装漏れは
        // AuditedEntityCoverageTest が検出する。ここは万一すり抜けたときに黙って
        // 記録を落とさないための保険として警告を残す
        if (!(entity instanceof AuditedEntity auditedEntity)) {
            // どの型が記録できなかったかを警告として残す（クラス名は自分たちのコード由来で外部入力ではない）
            log.warn("監査対象として登録されていますが AuditedEntity を実装していないため記録できません: type={}, action={}",
                    entity == null ? "null" : entity.getClass().getName(), action);
            // 記録せずに戻る
            return;
        }
        // 記録を試みる
        try {
            // 監査ログ記録コンポーネントを取得し、変更を記録する（実際の書き込みはコミット後）
            auditRecorder().recordEntityChange(auditedEntity, action);
        } catch (RuntimeException e) {
            // 記録コンポーネントを取得できない等の異常でも保存処理は止めず、警告だけ残す
            log.warn("監査ログの記録を開始できませんでした（処理は継続します）: type={}, action={}",
                    entity.getClass().getSimpleName(), action, e);
        }
    }
}
