// ドメイン（JPA エンティティ）のパッケージ
package com.izumacha.expensetracker.domain;

// 監査の操作種別（作成／更新／削除／ログイン成功／ログイン失敗）を表す列挙
import com.izumacha.expensetracker.audit.AuditAction;
// 列挙をどの形式で保存するかを指定するアノテーション
import jakarta.persistence.Enumerated;
// 列挙を文字列として保存する指定（EnumType.STRING）
import jakarta.persistence.EnumType;
// JPA の列定義アノテーション
import jakarta.persistence.Column;
// JPA のエンティティ宣言用アノテーション
import jakarta.persistence.Entity;
// 主キーの生成方式指定用アノテーション
import jakarta.persistence.GeneratedValue;
// 主キー生成戦略の列挙
import jakarta.persistence.GenerationType;
// 主キー宣言用アノテーション
import jakarta.persistence.Id;
// テーブルのインデックス定義用アノテーション
import jakarta.persistence.Index;
// テーブル名指定用アノテーション
import jakarta.persistence.Table;
// 日時型（記録時刻に使う）
import java.time.LocalDateTime;
// Lombok のゲッター自動生成
import lombok.Getter;
// Lombok の引数なしコンストラクタ自動生成
import lombok.NoArgsConstructor;

/**
 * 「誰が・いつ・どの行に・どんな操作をしたか」を残す監査ログの 1 行。
 *
 * <p><b>なぜ必要か</b>（docs/issue-analysis.md の追加所見 A.1）<br>
 * これまで変更の追跡手段はコンテナ標準出力のアプリログだけだった。json-file のローテーションは
 * 上限超過分を捨てるディスク保護であり、{@code docker compose down} でコンテナごと消えるため、
 * 金額データの変更履歴を後から説明する用途には足りない。記録先を DB に移し、バックアップ
 * （{@code scripts/backup-db.sh}）の対象に自然に含まれるようにする。
 *
 * <p><b>書き込み経路は 1 つだけ</b><br>
 * 行を作るのは {@code audit.AuditLogWriter} のみで、サービス層やコントローラからは書かない。
 * 変更の検知は永続化フック（{@code audit.EntityAuditListener}、JPA の
 * {@code @PostPersist}/{@code @PostUpdate}/{@code @PostRemove}）に寄せているため、
 * 新しいサービスメソッドを足しても記録の追加を書き忘れることがない。
 *
 * <p><b>何を保存しないか（§9 機密情報・PII を残さない）</b><br>
 * 保存するのは「操作の事実」だけで、支出の金額・説明・カテゴリ名といった<b>値そのものは
 * 保存しない</b>。フィールド単位の差分まで残すと、本体テーブルの家計情報が監査テーブルにも
 * 複製され、保護すべき範囲が二重になる（バックアップ・アクセス権限の管理コストも倍になる）。
 * 「いつ誰がどの支出 ID を触ったか」が追えれば A.1 の目的（変更履歴の説明責任・総当たりの立証）は
 * 満たせるため、値の複製は意図的に持たない。パスワードやトークンは当然一切保存しない。
 *
 * <p><b>更新しない・追記のみ</b><br>
 * このエンティティにセッターは無く、{@code updatable = false} を全列に付けている。監査記録は
 * 追記専用（append-only）で、アプリからの書き換え経路を型のレベルで塞ぐ。
 */
// 監査ログを表すエンティティ
@Entity
// テーブル名と、よく絞り込む列のインデックスを指定する。
// 監査ログの典型的な参照は「最近の操作を新しい順に見る」（occurred_at の範囲・並び替え）と
// 「特定の支出 / カテゴリの履歴を追う」（entity_name + entity_id の等価条件）の 2 つなので、
// それぞれに複合インデックスを用意して全件走査（sequential scan）を避ける（共通規約 §8）。
// 監査ログは追記のみで際限なく増えるテーブルであり、行数が増えるほどインデックスの有無が効く。
// 注: スキーマは ddl-auto 管理のため、この定義が自動で反映されるのは新規作成スキーマのみ
// （既存 DB では docs/backup.md の DDL を手動適用する）。
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_logs_occurred_at", columnList = "occurred_at"),
    @Index(name = "idx_audit_logs_entity", columnList = "entity_name, entity_id")
})
// ゲッターを自動生成（セッターは生成しない＝追記専用にするため）
@Getter
// JPA が要求する引数なしコンストラクタを自動生成（protected にして業務コードからの利用を防ぐ）
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AuditLog {

    /**
     * {@code entityName} 列の最大文字数。エンティティの単純クラス名（{@code Expense} 等）か、
     * 認証イベントを表す固定値しか入らないため短くてよい。
     * 呼び出し側が同じ値で切り詰められるよう定数として公開する（§6 一元管理）。
     */
    public static final int ENTITY_NAME_MAX_LENGTH = 64;

    /**
     * {@code entityId} 列の最大文字数。数値の主キーを文字列化した値しか入らないが、
     * 将来 UUID などに変わっても収まる余裕を持たせている。
     */
    public static final int ENTITY_ID_MAX_LENGTH = 64;

    /**
     * {@code actor} 列の最大文字数。
     *
     * <p><b>なぜ上限が要るか</b>: ログイン失敗の actor は<b>外部から送られたユーザー名</b>
     * （＝攻撃者が長さも内容も決められる文字列）である。上限が無いと 1 リクエストあたりの
     * 書き込み量を攻撃者が決められ、監査テーブルの肥大＝資源枯渇の入口になる（§9）。
     * 実在の API ユーザー名は環境変数由来の短い値なので、正規の記録がこの上限で欠けることはない。
     */
    public static final int ACTOR_MAX_LENGTH = 100;

    // 主キー（自動採番）
    @Id
    // DB の ID 列で自動採番する
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 操作対象の種類（エンティティの単純クラス名、または認証イベントを表す固定値）
    @Column(name = "entity_name", nullable = false, updatable = false, length = ENTITY_NAME_MAX_LENGTH)
    private String entityName;

    // 操作対象の主キーを文字列にしたもの。認証イベントのように対象行が無い場合は null になる
    @Column(name = "entity_id", updatable = false, length = ENTITY_ID_MAX_LENGTH)
    private String entityId;

    // 操作の種別（作成／更新／削除／ログイン成功／ログイン失敗）。
    // EnumType.STRING で保存するのは、ORDINAL だと列挙の並び順を変えただけで過去の記録の
    // 意味が変わってしまい、監査ログとして成立しなくなるため
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = AuditAction.NAME_MAX_LENGTH)
    private AuditAction action;

    // 操作した主体（認証済みならユーザー名、未認証なら既定値）。外部由来の値は無害化済みのものが入る
    @Column(nullable = false, updatable = false, length = ACTOR_MAX_LENGTH)
    private String actor;

    // 操作が起きた日時（JVM 既定タイムゾーン＝TimeZoneConfig が JST に固定している）
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    /**
     * 監査ログ 1 行を組み立てる。
     *
     * <p>各引数は呼び出し元（{@code audit.AuditEntry}）で既に無害化・切り詰め済みであることを
     * 前提とする。ここで再度切り詰めないのは、切り詰め規則を 2 箇所に持つと片方だけ変わった
     * ときに列長超過で保存が落ちるためで、規則の所在を呼び出し元 1 箇所に保つ（§6）。
     *
     * @param entityName 操作対象の種類（null 不可）
     * @param entityId   操作対象の主キー文字列（対象行が無いイベントでは null）
     * @param action     操作の種別（null 不可）
     * @param actor      操作した主体（null 不可）
     * @param occurredAt 操作が起きた日時（null 不可）
     */
    public AuditLog(String entityName, String entityId, AuditAction action, String actor, LocalDateTime occurredAt) {
        // 操作対象の種類を保持する
        this.entityName = entityName;
        // 操作対象の主キー文字列を保持する（null 可）
        this.entityId = entityId;
        // 操作の種別を保持する
        this.action = action;
        // 操作した主体を保持する
        this.actor = actor;
        // 操作が起きた日時を保持する
        this.occurredAt = occurredAt;
    }
}
