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
// Lombok のゲッター自動生成
import lombok.Getter;
// Lombok の引数なしコンストラクタ自動生成
import lombok.NoArgsConstructor;
// Lombok のセッター自動生成
import lombok.Setter;

// カテゴリを表すエンティティ
@Entity
// テーブル名を定義（name 列の一意制約は下の @Column(unique = true) 側で一元管理する。
// 以前はここにも @Table(uniqueConstraints = ...) で同じ制約を重複宣言しており、
// ddl-auto: update 環境で同一列に対する冗長な UNIQUE 制約/インデックスが2つ生成されていた）
@Table(name = "categories")
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
        // 前後の空白を取り除いてから設定する（" 食費" と "食費" が別カテゴリとして
        // 重複判定をすり抜けないように、生成経路をこのコンストラクタ 1 箇所に統一する）
        this.name = (name == null) ? null : name.strip();
    }
}
