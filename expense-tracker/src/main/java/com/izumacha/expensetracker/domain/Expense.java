// 支出のドメインパッケージ
package com.izumacha.expensetracker.domain;

// JPA の列定義アノテーション
import jakarta.persistence.Column;
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
// category_id は外部キーだが PostgreSQL は FK に自動インデックスを張らないため明示する。
// いずれも全件走査（sequential scan）を避けるためのもの（共通規約 §8）。
@Table(name = "expenses", indexes = {
    @Index(name = "idx_expenses_spent_on", columnList = "spent_on"),
    @Index(name = "idx_expenses_category_id", columnList = "category_id")
})
// ゲッターを自動生成
@Getter
// セッターを自動生成
@Setter
// JPA が要求する引数なしコンストラクタを自動生成
@NoArgsConstructor
public class Expense {

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

    // 説明（任意・最大255文字）
    @Column(length = 255)
    private String description;

    // 支出日（必須）
    @Column(nullable = false)
    private LocalDate spentOn;

    // 作成日時（必須・作成時に自動セット）
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

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
