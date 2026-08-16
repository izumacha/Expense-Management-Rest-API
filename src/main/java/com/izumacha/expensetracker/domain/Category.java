// カテゴリのドメインパッケージ
package com.izumacha.expensetracker.domain;

// 変更を監査ログへ記録するエンティティリスナ
import com.izumacha.expensetracker.audit.EntityAuditListener;
// JPA のエンティティ関連アノテーションを取り込む
import jakarta.persistence.Column;
// エンティティリスナを紐づけるアノテーション
import jakarta.persistence.EntityListeners;
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
// 一意制約用の正規化キー（NFC + 小文字化）の導出ロジックを一元管理するユーティリティ
import com.izumacha.expensetracker.validation.CategoryNameNormalizer;
// Lombok のアクセスレベル指定（セッターを生成しないフィールドの指定に使う）
import lombok.AccessLevel;
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
public class Category implements AuditedEntity {

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

    // カテゴリ名の正規化キー（NFC 正規化 + Locale.ROOT 小文字化。CategoryNameNormalizer.normalizeKey）。
    // 【なぜ必要か】name 列の一意制約は大文字小文字を区別するため、"Travel" と "travel" の
    // 同時 POST がサービス層の check-then-act（existsByNameKey）を両方すり抜けると
    // 双方コミットされてしまい、「大文字小文字を区別しない一意性」という API 契約が恒久的に壊れる。
    // この列の一意制約（プロバイダ非依存の通常の UNIQUE 制約）が DB 側の最終防波堤となり、
    // レース時は片方が DataIntegrityViolationException になって CategoryService が 409 へ変換する。
    // 【length を指定しない理由】小文字化は文字数を増やすことがある（例: U+0130 は Locale.ROOT の
    // 小文字化で 2 コードポイントに増える）ため NAME_MAX_LENGTH(50) では収まらない場合があり、
    // Hibernate 既定の varchar(255) をそのまま使って十分な余裕を持たせる。
    // 【nullable=false を付けない理由】ddl-auto: update の ALTER TABLE は既存行の値を埋め戻せず、
    // NOT NULL を付けるとデータのある既存 DB で列追加が失敗する（version 列のような静的 DEFAULT は
    // 名前から導出する値のため使えない）。新規・更新行は必ず下の setName 経由で値が入り、
    // 既存行に残る NULL は一意インデックス上で衝突とみなされないため制約違反にもならない。
    // 【セッターを生成しない理由】name と独立に書き換えられると同期が壊れるため、
    // 値の設定経路をコンストラクタと setName の 2 箇所（実体は setName 1 箇所）に閉じ込める
    @Setter(AccessLevel.NONE)
    @Column(unique = true)
    private String nameKey;

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
        // 名前の設定（空白除去）と正規化キーの同期をまとめて行う setName へ委譲し、
        // name と nameKey を更新する経路を 1 箇所に保つ（§6 DRY）
        setName(name);
    }

    // カテゴリ名を設定する（Lombok の自動生成に代えて手書きし、正規化キーを常に同期させる）。
    // 同名メソッドを定義すると Lombok は name のセッターを生成しないため、name を変更する
    // すべての経路（コンストラクタ・サービス層の更新）がここを通り、nameKey の同期漏れが起きない
    public void setName(String name) {
        // 前後の空白を取り除いてから設定する（" 食費" と "食費" が別カテゴリとして
        // 重複判定をすり抜けないように、設定経路をこのセッター 1 箇所に統一する）
        this.name = (name == null) ? null : name.strip();
        // 一意制約用の正規化キー（NFC + 小文字化）を名前から導出して同期する（null は null のまま）
        this.nameKey = CategoryNameNormalizer.normalizeKey(this.name);
    }
}
