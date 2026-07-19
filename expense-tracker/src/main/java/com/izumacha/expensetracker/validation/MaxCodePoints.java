// カスタム Bean Validation 制約のパッケージ
package com.izumacha.expensetracker.validation;

// この制約がどこに付けられるか（フィールド・record コンポーネント等）を表す型
import jakarta.validation.Constraint;
// 制約の実処理（Validator）を実装するためのインタフェース
import jakarta.validation.ConstraintValidator;
// Validator に渡される検証コンテキスト（本実装では未使用だが isValid のシグネチャ上必須）
import jakarta.validation.ConstraintValidatorContext;
// バリデーショングループ分けに使う型（本アプリでは未使用だが標準の制約アノテーションが要求する定型項目）
import jakarta.validation.Payload;
// アノテーションを付けられる対象（フィールド・メソッド引数）を列挙する型
import java.lang.annotation.ElementType;
// アノテーションをどこまで保持するか（実行時にリフレクションで読めるようにする）を指定する型
import java.lang.annotation.Retention;
// 実行時までアノテーション情報を保持するポリシー
import java.lang.annotation.RetentionPolicy;
// アノテーションを付けられる対象の集合を指定する型
import java.lang.annotation.Target;

/**
 * 文字列の長さを Unicode コードポイント数で検証する Bean Validation 制約。
 *
 * <p>標準の {@code @Size} は {@link String#length()}（UTF-16 コード単位数）で長さを数える。
 * これは基本多言語面（BMP）外の文字（一部の絵文字や CJK 拡張漢字など）を含む文字列では、
 * サロゲートペア 1 文字が 2 とカウントされてしまう。一方 PostgreSQL の {@code varchar(n)} は
 * 文字（≒コードポイント）単位で長さを数えるため、両者の基準がずれる。
 *
 * <p>この制約は {@link String#codePointCount(int, int)} を使い、DB 列長の実際の基準に合わせて
 * 検証する（{@code Category.NAME_MAX_LENGTH} 文字ちょうどの絵文字混じりの名前が、DB には収まる
 * にもかかわらず DTO 検証だけ先に 400 で弾かれてしまう不整合を避けるため）。
 */
// フィールド・record コンポーネント・メソッド引数に付けられる制約であることを宣言する
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
// 実行時にリフレクションで読み取れるように保持する
@Retention(RetentionPolicy.RUNTIME)
// この制約の実処理を MaxCodePoints.Validator に委譲することを宣言する
@Constraint(validatedBy = MaxCodePoints.Validator.class)
public @interface MaxCodePoints {

    // 検証失敗時のデフォルトメッセージ（既存の @Size と表記を揃え、{max} で上限値を埋め込む）
    String message() default "must be at most {max} characters";

    // 許容する最大コードポイント数（呼び出し側が必須で指定する）
    int max();

    // Bean Validation の規約上必須の項目（本アプリでは未使用。グループ分けを行わない）
    Class<?>[] groups() default {};

    // Bean Validation の規約上必須の項目（本アプリでは未使用。ペイロード付与を行わない）
    Class<? extends Payload>[] payload() default {};

    // 実際の検証ロジックを持つ Validator 実装
    class Validator implements ConstraintValidator<MaxCodePoints, String> {

        // アノテーションの max() から受け取る許容コードポイント数
        private int max;

        // アノテーションの属性値をこの Validator インスタンスへ取り込む初期化処理
        @Override
        public void initialize(MaxCodePoints constraintAnnotation) {
            // アノテーションで指定された上限値を保持する
            this.max = constraintAnnotation.max();
        }

        // 実際の検証本体。true を返せば合格、false なら制約違反として扱われる
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            // null は他の制約（@NotBlank 等）に判定を委ねるため、ここでは合格扱いにする
            // （Bean Validation の一般的な作法: 各制約は自分の観点だけを見る）
            if (value == null) {
                return true;
            }
            // 文字列全体のコードポイント数を数え、上限以下かどうかを判定する
            return value.codePointCount(0, value.length()) <= max;
        }
    }
}
