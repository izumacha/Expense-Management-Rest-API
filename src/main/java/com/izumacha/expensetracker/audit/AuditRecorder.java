// 監査ログ（誰が・いつ・何をしたか）に関するパッケージ
package com.izumacha.expensetracker.audit;

// 監査ログのエンティティを参照する
import com.izumacha.expensetracker.domain.AuditLog;
// 監査対象であることを示すインターフェース（主キーを型安全に取り出すために使う）
import com.izumacha.expensetracker.domain.AuditedEntity;
// 外部由来の文字列を記録先へ書く前に無害化する共通ユーティリティ
import com.izumacha.expensetracker.validation.TextSanitizer;
// 記録時刻に使う日時型
import java.time.LocalDateTime;
// トランザクションごとの記録候補を溜める入れ物
import java.util.ArrayList;
// 記録候補の一覧を扱うインターフェース
import java.util.List;
// ログ出力に使うロガー本体
import org.slf4j.Logger;
// ロガーを生成するファクトリ
import org.slf4j.LoggerFactory;
// Spring に管理させるためのコンポーネント宣言
import org.springframework.stereotype.Component;
// トランザクションの完了（コミット / ロールバック）を受け取るためのコールバック
import org.springframework.transaction.support.TransactionSynchronization;
// 現在のトランザクションへコールバックを登録する静的ホルダ
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 監査ログを「いつ書くか」を決めて {@link AuditLogWriter} に渡す入口。
 *
 * <p>アプリ内で監査ログを作るのはこのクラスだけで、行の組み立て（列に何を入れるか・
 * 外部由来の値をどう無害化するか）もここに集約する。サービス層やコントローラから
 * 直接記録しないことで、記録の書き忘れと書式のばらつきを防ぐ（§6 一元管理）。
 *
 * <h2>データ変更はコミット後に書く</h2>
 * 変更の記録は業務トランザクションの<b>コミット後</b>（{@link TransactionSynchronization#afterCommit()}）
 * に書く。同じトランザクションの中で書くと、ロールバックされた変更まで「起きたこと」として
 * 記録に残ってしまい、監査ログが実態と食い違う。コミット後に限定すれば、記録に載っている
 * 操作は必ず DB に反映された操作になる。
 *
 * <h2>監査の失敗で業務処理を止めない（fail-open）</h2>
 * 書き込みが失敗しても例外を外へ出さず、WARN ログを残して処理を続ける。理由は 2 つ。
 * <ul>
 *   <li>データ変更の記録は<b>コミット後</b>に走るため、ここで例外を投げても業務トランザクションは
 *       もう巻き戻せない。呼び出し元にエラーを返すと「データは保存されたのにクライアントには
 *       失敗が返る」という、実態と応答が食い違う最悪の壊れ方になる。</li>
 *   <li>認証イベントの記録が落ちたことを理由にトークン発行を拒否すると、監査テーブルの不調が
 *       そのまま全 API の停止に直結する。</li>
 * </ul>
 * この方針は<b>監査記録の欠落を許容する</b>という取引でもある。監査が必須要件になった場合は
 * fail-closed（記録できなければ操作も失敗させる）へ切り替える必要があり、そのときは
 * データ変更側を「同一トランザクション内で書く」設計に戻すところから見直すこと
 * （docs/issue-analysis.md の追加所見 A.1 に判断の経緯を記載）。
 */
// このクラスを Spring の Bean として登録する
@Component
public class AuditRecorder {

    // このクラス専用のロガー（監査書き込みの失敗を WARN で残す）
    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    /**
     * 認証イベントを記録するときに {@code entityName} 列へ入れる固定値。
     *
     * <p>認証は特定の行に対する操作ではないため対象行の主キーを持たない。エンティティ名の
     * 位置にこの値を入れることで、変更イベントと同じ 1 テーブルで時系列に並べられる。
     */
    public static final String AUTHENTICATION_ENTITY_NAME = "Authentication";

    /**
     * 操作主体を特定できないときに {@code actor} 列へ入れる値。
     *
     * <p>主にユーザー名を空で送られたログイン失敗で使う。{@link AuditActorResolver#ANONYMOUS_ACTOR}
     * （＝認証情報が無い状態で行われた操作）とは意味が違うため別の値にしている。前者は
     * 「認証を試みたが誰かは分からない」、後者は「そもそも認証していない」を表す。
     * なお、この文字列と同じユーザー名を送られた場合は区別できないが、実在の API ユーザー名は
     * 環境変数で運用者が決める値なので、その名前を避ければ実務上の曖昧さは生じない。
     */
    public static final String UNKNOWN_ACTOR = "(unknown)";

    /**
     * トランザクションごとの記録候補（まだ書いていない監査ログ）を結び付けるときの鍵。
     *
     * <p>他の用途と衝突しないよう、この場でしか作られないオブジェクトを鍵に使う。
     */
    private static final Object PENDING_AUDIT_LOGS_KEY = new Object();

    // 監査ログを独立トランザクションで書き込むコンポーネント
    private final AuditLogWriter auditLogWriter;

    // 現在のリクエストの操作主体を解決するコンポーネント
    private final AuditActorResolver actorResolver;

    // 依存 Bean をコンストラクタで受け取る
    public AuditRecorder(
            // 監査ログの書き込み担当（Spring が注入する）
            AuditLogWriter auditLogWriter,
            // 操作主体の解決担当（Spring が注入する）
            AuditActorResolver actorResolver) {
        // 書き込み担当をフィールドに保持する
        this.auditLogWriter = auditLogWriter;
        // 解決担当をフィールドに保持する
        this.actorResolver = actorResolver;
    }

    /**
     * エンティティの変更（作成・更新・削除）を記録する。実際の書き込みはコミット後に行う。
     *
     * @param entity 変更されたエンティティ（{@code null} 不可）
     * @param action 変更の種別（{@link AuditAction#CREATE} / {@link AuditAction#UPDATE} /
     *               {@link AuditAction#DELETE}）
     */
    public void recordEntityChange(AuditedEntity entity, AuditAction action) {
        // 主キーを文字列にする（採番前などで null のときは列も null のままにする）
        String entityId = (entity.getId() == null) ? null : String.valueOf(entity.getId());
        // 監査ログ 1 行を組み立てる。エンティティ名は自分たちのクラス名（外部入力ではない）なので
        // 無害化は不要。JPA のライフサイクルコールバックには実体のインスタンスが渡るため、
        // 遅延ロード用のプロキシ名（Xxx$HibernateProxy$...）が入ることはない
        AuditLog auditLog = new AuditLog(
                // 操作対象の種類（エンティティの単純クラス名）
                entity.getClass().getSimpleName(),
                // 操作対象の主キー
                entityId,
                // 操作の種別
                action,
                // 操作した主体（無害化・切り詰め済み）
                actorResolver.currentActor(),
                // 操作が起きた日時（TimeZoneConfig が JVM 既定 TZ を JST に固定している）
                LocalDateTime.now());
        // コミット後に書き込むよう予約する
        scheduleAfterCommit(auditLog);
    }

    /**
     * 認証イベント（トークン発行の成功・失敗）を記録する。こちらは業務トランザクションの
     * 外で起きるため、その場で書き込む。
     *
     * @param action            {@link AuditAction#LOGIN_SUCCESS} または {@link AuditAction#LOGIN_FAILURE}
     * @param attemptedUsername 認証に使われたユーザー名（<b>外部入力</b>。{@code null} 可）。
     *                          パスワードは受け取らないし記録もしない（§9）
     */
    public void recordAuthentication(AuditAction action, String attemptedUsername) {
        // 監査ログ 1 行を組み立てる
        AuditLog auditLog = new AuditLog(
                // 認証イベントであることを示す固定値
                AUTHENTICATION_ENTITY_NAME,
                // 認証は特定の行に対する操作ではないので主キーは持たない
                null,
                // 操作の種別（成功 / 失敗）
                action,
                // 外部から送られたユーザー名を無害化・切り詰めて actor にする
                sanitizeAttemptedUsername(attemptedUsername),
                // 操作が起きた日時
                LocalDateTime.now());
        // 認証はトランザクションの外で起きるため、予約せずその場で書き込む
        writeQuietly(List.of(auditLog));
    }

    /**
     * 認証に使われたユーザー名を actor 列へ入れられる形に整える。
     *
     * <p>外部から任意の文字列が届く唯一の経路なので、(1) 空なら特定不能を表す固定値に置き換え、
     * (2) 制御文字を除去して記録の偽装を防ぎ、(3) 列長へ切り詰めて監査テーブルの肥大を防ぐ。
     * 同一パッケージのテストから直接検証できるようパッケージプライベートにしている。
     */
    static String sanitizeAttemptedUsername(String attemptedUsername) {
        // 未指定・空白のみのユーザー名は「誰か特定できない」ものとして固定値に置き換える
        if (attemptedUsername == null || attemptedUsername.isBlank()) {
            // 特定不能を表す固定値を返す
            return UNKNOWN_ACTOR;
        }
        // 制御文字を除去し列長に切り詰めて返す
        return TextSanitizer.sanitize(attemptedUsername, AuditLog.ACTOR_MAX_LENGTH);
    }

    /**
     * トランザクションのコミット後に書き込むよう予約する。トランザクションが無い場合は
     * 待つ相手がいないのでその場で書き込む（記録を落とさないため）。
     *
     * <p><b>1 トランザクションにつき書き込みは 1 回にまとめる。</b> 記録を予約するたびに
     * コールバックを登録すると、N 件の行を触った処理で N 回の独立トランザクションが走り、
     * そのぶん DB 接続の借用も増える（{@link AuditLogWriter} の説明を参照）。
     * 最初の予約のときだけ入れ物をトランザクションへ結び付けてコールバックを 1 つ登録し、
     * 2 件目以降は同じ入れ物へ積むだけにする。
     */
    private void scheduleAfterCommit(AuditLog auditLog) {
        // トランザクション同期が有効でなければ、コミットを待つ手段が無いのでその場で書く
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // その場で 1 件だけ書き込む
            writeQuietly(List.of(auditLog));
            // 予約は不要なのでここで終える
            return;
        }
        // このトランザクションに既に結び付けてある入れ物を取り出す（初回は null）
        List<AuditLog> pending = pendingAuditLogs();
        // まだ無ければ、入れ物を作ってトランザクションへ結び付け、コールバックを 1 つだけ登録する
        if (pending == null) {
            // このトランザクションぶんの記録候補を溜める入れ物を作る
            pending = new ArrayList<>();
            // 同じトランザクションの後続の予約から見つけられるよう結び付ける
            TransactionSynchronizationManager.bindResource(PENDING_AUDIT_LOGS_KEY, pending);
            // コールバックから参照するために実効的に final な参照を用意する
            List<AuditLog> batch = pending;
            // コミット後に呼ばれるコールバックを現在のトランザクションへ登録する。
            // ロールバックされた場合 afterCommit は呼ばれないため、記録は自動的に見送られる
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                // トランザクションのコミットが完了した後に呼ばれる
                @Override
                public void afterCommit() {
                    // 溜まった監査ログをまとめて書き込む（失敗しても業務処理には影響させない）
                    writeQuietly(batch);
                }

                // コミット・ロールバックのどちらでも最後に呼ばれる
                @Override
                public void afterCompletion(int status) {
                    // 入れ物の結び付けを解く。スレッドは使い回されるため、ここで外さないと
                    // 次にこのスレッドが処理するトランザクションへ前回の入れ物が残ってしまう
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_AUDIT_LOGS_KEY);
                }
            });
        }
        // 記録候補を入れ物へ積む（実際の書き込みはコミット後にまとめて行う）
        pending.add(auditLog);
    }

    /**
     * 現在のトランザクションに結び付けてある記録候補の入れ物を返す（無ければ {@code null}）。
     *
     * <p>結び付けた型は自分たちしか触らないため、取り出し時の型変換は安全であることが分かっている。
     */
    @SuppressWarnings("unchecked")
    private static List<AuditLog> pendingAuditLogs() {
        // トランザクションへ結び付けた入れ物を取り出して返す
        return (List<AuditLog>) TransactionSynchronizationManager.getResource(PENDING_AUDIT_LOGS_KEY);
    }

    /**
     * 監査ログを書き込み、失敗しても例外を外へ出さない（fail-open）。
     *
     * <p>WARN で残すのは、記録が落ちたこと自体を運用者が検知できるようにするため
     * （黙って捨てない。§6 エラーを握り潰さない）。メッセージには操作の種類までしか含めず、
     * 例外はスタックトレース付きで<b>サーバ内ログにのみ</b>出す（§9）。
     */
    private void writeQuietly(List<AuditLog> auditLogs) {
        // 書き込みを試みる
        try {
            // 独立したトランザクションでまとめて保存する
            auditLogWriter.write(auditLogs);
        } catch (RuntimeException e) {
            // 失敗しても業務処理は続行し、記録が落ちたことだけを警告として残す。
            // 件数と種別までを出し、値そのもの（actor 等）は出さない（§9）
            log.warn("監査ログの記録に失敗しました（処理は継続します）: 件数={}, 対象={}",
                    auditLogs.size(), describeForLog(auditLogs), e);
        }
    }

    /**
     * 警告ログ用に「どの種類の・どの操作が落ちたか」だけを組み立てる。
     *
     * <p>actor（外部由来の値を含みうる）は出さない。運用者が知りたいのは「何の記録が落ちたか」で
     * あって主体名ではなく、ログへ余計な外部入力を運ばないほうが安全なため（§9）。
     */
    private static String describeForLog(List<AuditLog> auditLogs) {
        // 「種類:操作」の組をカンマ区切りで並べた文字列を組み立てて返す
        return auditLogs.stream()
                // 1 件ごとに「種類:操作」の形へ変換する
                .map(auditLog -> auditLog.getEntityName() + ":" + auditLog.getAction())
                // カンマ区切りで 1 つの文字列にまとめる
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
