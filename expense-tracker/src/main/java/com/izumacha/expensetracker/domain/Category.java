// カテゴリのドメインパッケージ
package com.izumacha.expensetracker.domain;

// JPA のエンティティ関連アノテーションを取り込む
import jakarta.persistence.Column;
// JPA のエンティティ宣言用アノテーション
import jakarta.persistence.Entity;
// 主キーの生成方式指定用アノテーション
import jakarta.persistence.GeneratedValue;
// 主キー生成戦略の列挙
import jakarta.persistence.GenerationType;
// 主キー宣言用アノテーション
import jakarta.persistence.Id;
// テーブル名・制約指定用アノテーション
import jakarta.persistence.Table;
// 一意制約指定用アノテーション
import jakarta.persistence.UniqueConstraint;
// Lombok のゲッター自動生成
import lombok.Getter;
// Lombok の引数なしコンストラクタ自動生成
import lombok.NoArgsConstructor;
// Lombok のセッター自動生成
import lombok.Setter;

// カテゴリを表すエンティティ
@Entity
// テーブル名と name 列の一意制約を定義
@Table(name = "categories", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
// ゲッターを自動生成
@Getter
// セッターを自動生成
@Setter
// JPA が要求する引数なしコンストラクタを自動生成
@NoArgsConstructor
public class Category {

    // 主キー（自動採番）
    @Id
    // DB の ID 列で自動採番する
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // カテゴリ名（必須・一意・最大50文字）
    @Column(nullable = false, unique = true, length = 50)
    private String name;

    // カテゴリ名を受け取るコンストラクタ
    public Category(String name) {
        // 渡された名前をフィールドに設定する
        this.name = name;
    }
}
