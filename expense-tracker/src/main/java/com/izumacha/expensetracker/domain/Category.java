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
// 楽観ロックの版番号を宣言するアノテーション
import jakarta.persistence.Version;
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

    // カテゴリ名の最大文字数（DB の列長・DTO の @MaxCodePoints・サービス層の正規化後再検証で共有する唯一の定義。
    // 裸の 50 を各所に散らさないよう、列長を持つドメイン側にまとめて置く。共通規約 §6 一元管理）
    public static final int NAME_MAX_LENGTH = 50;

    // 主キー（自動採番）
    @Id
    // DB の ID 列で自動採番する
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // カテゴリ名（必須・一意・最大50文字。長さは上の定数を参照して一元管理する）
    @Column(nullable = false, unique = true, length = NAME_MAX_LENGTH)
    private String name;

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

    // カテゴリ名を受け取るコンストラクタ
    public Category(String name) {
        // 前後の空白を取り除いてから設定する（" 食費" と "食費" が別カテゴリとして
        // 重複判定をすり抜けないように、生成経路をこのコンストラクタ 1 箇所に統一する）
        this.name = (name == null) ? null : name.strip();
    }
}
