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
 * 文字列に制御文字（C0 制御文字 U+0000〜U+001F。ただしタブ・改行・復帰は除く）が
 * 含まれていないことを検証する Bean Validation 制約。
 *
 * <p>特に NUL（U+0000）は標準制約（{@code @NotBlank} / {@link MaxCodePoints} 等）を
 * すべて通過してしまう一方、PostgreSQL は text/varchar 列に NUL を保存できず DB 層で
 * 初めてエラーになる。その {@code DataIntegrityViolationException} はサービス層の保存時
 * catch で「参照先カテゴリが消えたレース」等の制約違反と区別できず、誤った 404
 * （「カテゴリが見つかりません」）や 500 として返ってしまうため、DTO の入力検証段階で
 * 400（Bean Validation エラー）として拒否する。
 *
 * <p>NUL 以外の C0 制御文字も名前・説明の値として意味を持たない（端末制御・改ページ等の
 * 制御用途）ため合わせて拒否するが、複数行の説明等で正当に使われうるタブ（U+0009）・
 * 改行（U+000A）・復帰（U+000D）は許容し、拒否範囲を必要最小限に留める。
 */
// フィールド・record コンポーネント・メソッド引数に付けられる制約であることを宣言する
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
// 実行時にリフレクションで読み取れるように保持する
@Retention(RetentionPolicy.RUNTIME)
// この制約の実処理を NoControlCharacters.Validator に委譲することを宣言する
@Constraint(validatedBy = NoControlCharacters.Validator.class)
public @interface NoControlCharacters {

    // 検証失敗時のデフォルトメッセージ（既存制約と同じ英語表記に揃える）
    String message() default "must not contain control characters";

    // Bean Validation の規約上必須の項目（本アプリでは未使用。グループ分けを行わない）
    Class<?>[] groups() default {};

    // Bean Validation の規約上必須の項目（本アプリでは未使用。ペイロード付与を行わない）
    Class<? extends Payload>[] payload() default {};

    // 実際の検証ロジックを持つ Validator 実装
    class Validator implements ConstraintValidator<NoControlCharacters, String> {

        // 許容する C0 制御文字の上限境界（U+0020 未満＝C0 制御文字の範囲）を表す定数
        private static final char C0_CONTROL_UPPER_BOUND = 0x20;

        // 実際の検証本体。true を返せば合格、false なら制約違反として扱われる
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            // null は他の制約（@NotBlank 等）に判定を委ねるため、ここでは合格扱いにする
            // （Bean Validation の一般的な作法: 各制約は自分の観点だけを見る）
            if (value == null) {
                return true;
            }
            // 文字列を 1 文字（UTF-16 コード単位）ずつ調べる。C0 制御文字はすべて BMP 内の
            // 単一コード単位なので、コードポイント走査に切り替えなくても取りこぼしは無い
            for (int i = 0; i < value.length(); i++) {
                // 現在位置の文字を取り出す
                char c = value.charAt(i);
                // C0 制御文字（U+0000〜U+001F）のうち、タブ・改行・復帰以外が見つかったら不合格にする
                if (c < C0_CONTROL_UPPER_BOUND && c != '\t' && c != '\n' && c != '\r') {
                    // 許容外の制御文字（NUL 等）を含むため制約違反として false を返す
                    return false;
                }
            }
            // 許容外の制御文字が見つからなかったので合格として true を返す
            return true;
        }
    }
}
