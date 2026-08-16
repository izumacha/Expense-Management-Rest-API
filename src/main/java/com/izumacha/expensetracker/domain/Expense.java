// 支出のドメインパッケージ
package com.izumacha.expensetracker.domain;

// 変更を監査ログへ記録するエンティティリスナ
import com.izumacha.expensetracker.audit.EntityAuditListener;
// JPA の列定義アノテーション
import jakarta.persistence.Column;
// エンティティリスナを紐づけるアノテーション
import jakarta.persistence.EntityListeners;
// JPA のエンティティ宣言用アノテーション
import jakarta.persistence.Entity;
// フェッチ方式の列挙
import jakarta.persistence.FetchType;
// テーブルのインデックス定義用アノテーション
import jakarta.persistence.Index;
// 主キーの生成方式指定用アノテーション
import jakarta.persistence.GeneratedValue;
// 主キー生成戦略の列挙
import jakarta.persistence.GenerationType;
// 主キー宣言用アノテーション
import jakarta.persistence.Id;
// 外部キーの結合列指定用アノテーション
import jakarta.persistence.JoinColumn;
// 多対1リレーション宣言用アノテーション
import jakarta.persistence.ManyToOne;
// テーブル名指定用アノテーション
import jakarta.persistence.Table;
// 楽観ロックの版番号を宣言するアノテーション
import jakarta.persistence.Version;
// 10進数の金額型
import java.math.BigDecimal;
// 日付型
import java.time.LocalDate;
// 日時型
import java.time.LocalDateTime;
// Lombok のゲッター自動生成
import lombok.Getter;
// Lombok の引数なしコンストラクタ自動生成
import lombok.NoArgsConstructor;
// Lombok のセッター自動生成
import lombok.Setter;

// 支出を表すエンティティ
@Entity
// テーブル名と、よく絞り込む列のインデックスを指定する。
// 一覧・月次集計は spent_on の範囲条件／並び替え／GROUP BY で常に使うため先頭に置く。
// 【なぜ (spent_on, id) の複合インデックスか】一覧クエリ（ExpenseRepository.search）は
// ORDER BY spentOn DESC, id DESC ＋ LIMIT（ページング）で取得する。spent_on 単独の
// インデックスだと同一日付の行の並びが id で定まらず、DB はページごとに追加のソートを
// 要する。並び順と同じ列順（spent_on, id）の複合インデックスなら、範囲絞り込みと
// 並び替えの両方をインデックス走査だけでまかなえる（複合インデックスの列順の意識。§8）。
// spent_on 単独の絞り込み（月次集計の範囲条件）も複合インデックスの先頭列で引けるため、
// 旧 idx_expenses_spent_on（単独列）は冗長になり、これを置き換える。
// category_id は外部キーだが PostgreSQL は FK に自動インデックスを張らないため明示する。
// いずれも全件走査（sequential scan）を避けるためのもの（共通規約 §8）。
// 注: スキーマは ddl-auto 管理のため、この定義変更が自動で反映されるのは新規作成スキーマのみ
// （既存 DB では旧インデックスの削除・新インデックスの作成は自動では行われない）。
@Table(name = "expenses", indexes = {
    @Index(name = "idx_expenses_spent_on_id", columnList = "spent_on, id"),
    @Index(name = "idx_expenses_category_id", columnList = "category_id")
})
// 作成・更新・削除を監査ログ（audit_logs）へ記録する（docs/issue-analysis.md 追加所見 A.1）。
// サービス層ではなく永続化フックに置くことで、保存経路が増えても記録漏れが起きない
@EntityListeners(EntityAuditListener.class)
// ゲッターを自動生成
@Getter
// セッターを自動生成
@Setter
// JPA が要求する引数なしコンストラクタを自動生成
@NoArgsConstructor
// AuditedEntity を実装して、監査リスナが主キーを型安全に取り出せるようにする
// （getId() は Lombok の @Getter が生成するゲッターがそのまま実装になる）
public class Expense implements AuditedEntity {

    // 説明（description）に許可する最大文字数（コードポイント数）。
    // 【なぜ定数にするか】この上限は「DB 列長（@Column(length)）」と「DTO の入力検証
    // （CreateExpenseRequest / UpdateExpenseRequest の @MaxCodePoints）」の両方で使う。
    // 裸の数値を各所に書くと、片方だけ変更したときに「検証は通るが DB には入らない」状態になり、
    // 保存時の DataIntegrityViolationException が RaceGuard で「参照先カテゴリが消えたレース」と
    // 誤認され、実際には長すぎる説明が原因なのに 404「カテゴリが見つかりません」を返してしまう。
    // 単一の参照元にまとめて両者を必ず同じ値に保つ（共通規約 §6 一元管理／マジックナンバー回避）。
    // Category.NAME_MAX_LENGTH と同じ方針に揃えている。
    public static final int DESCRIPTION_MAX_LENGTH = 255;

    // 主キー（自動採番）
    @Id
    // DB の ID 列で自動採番する
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 金額（必須・0より大きい・通貨換算なしの10進数）
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // 紐づくカテゴリ（多対1・必須・遅延ロード）
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // 外部キー列名を category_id とし NOT NULL に設定
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    // 説明（任意・上限は DESCRIPTION_MAX_LENGTH 文字。DTO の入力検証と同じ定数を参照する）
    @Column(length = DESCRIPTION_MAX_LENGTH)
    private String description;

    // 支出日（必須）
    @Column(nullable = false)
    private LocalDate spentOn;

    // 作成日時（必須・作成時に自動セット）
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 楽観ロック用の版番号。@Version が無いと Hibernate は UPDATE/DELETE の影響行数を
    // 検証せず、対象行が同時実行で既に削除されていても例外を投げずに0行更新のまま正常終了して
    // しまう（RaceGuard.guarded() の onGone 分岐が実質デッドコードになる）。この列があって
    // 初めて Hibernate は UPDATE/DELETE 文に WHERE version=? を付与し、影響行数0件を
    // OptimisticLockingFailureException として検知できる（service/RaceGuard.java 参照）。
    // columnDefinition で NOT NULL DEFAULT 0 を明示するのは、この列を追加する ALTER TABLE の
    // 対象になる「マイグレーション前から存在する行」を NULL のまま残さないため。Hibernate が
    // 生成する UPDATE/DELETE の WHERE 句は version カラムが NULL でも常に `version = ?`
    // という等価比較になり（`version IS NULL` には自動的に切り替わらない）、SQL の NULL 比較は
    // 常に UNKNOWN（true にならない）ため、DEFAULT を与えず NULL のまま残る行が1件でもあると
    // その行への更新・削除は同時実行の有無に関わらず恒久的に影響行数0件＝404 になってしまう。
    // プリミティブ型 long にしているのも、エンティティが一度も DB を経由せず新規構築された
    // 場合（Java 側の初期値 0）に version フィールド自体が null になり得る余地を無くすため。
    @Version
    @Column(nullable = false, columnDefinition = "bigint not null default 0")
    private long version;

    // 永続化の直前に作成日時を補完するコールバック
    @jakarta.persistence.PrePersist
    public void onCreate() {
        // 作成日時が未設定なら現在時刻を設定する
        if (this.createdAt == null) {
            // 現在日時を作成日時に代入する
            this.createdAt = LocalDateTime.now();
        }
    }
}
