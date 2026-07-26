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
 * 文字列に制御文字（ISO 制御文字＝C0: U+0000〜U+001F・DEL: U+007F・C1: U+0080〜U+009F。
 * ただしタブ・改行・復帰は除く）や、対にならない UTF-16 サロゲート（不正な文字表現）が
 * 含まれていないことを検証する Bean Validation 制約。
 *
 * <p>特に NUL（U+0000）は標準制約（{@code @NotBlank} / {@link MaxCodePoints} 等）を
 * すべて通過してしまう一方、PostgreSQL は text/varchar 列に NUL を保存できず DB 層で
 * 初めてエラーになる。その {@code DataIntegrityViolationException} はサービス層の保存時
 * catch で「参照先カテゴリが消えたレース」等の制約違反と区別できず、誤った 404
 * （「カテゴリが見つかりません」）や 500 として返ってしまうため、DTO の入力検証段階で
 * 400（Bean Validation エラー）として拒否する。
 *
 * <p>NUL 以外の ISO 制御文字（DEL や C1 を含む）も名前・説明の値として意味を持たない
 * （端末制御・改ページ等の制御用途）ため合わせて拒否するが、複数行の説明等で正当に
 * 使われうるタブ（U+0009）・改行（U+000A）・復帰（U+000D）は許容し、拒否範囲を
 * 必要最小限に留める。
 *
 * <p>対にならないサロゲート（例: JSON の {@code "a\ud800b"} のような、高位サロゲートの
 * 直後に低位サロゲートが続かない・低位サロゲートが単独で現れる文字列）も拒否する。
 * これらは UTF-8 へ変換できない不正な文字列であり、既存の制約と Unicode 正規化を通過した
 * 後に PostgreSQL の「invalid byte sequence for encoding UTF8」エラーとして初めて失敗する。
 * その失敗はサービス層の保存時 catch で一意制約・FK のレース由来と誤認され、誤った 409
 * （「既に存在します」）や 404 として返ってしまうため、入力検証段階で 400 として拒否する。
 * 正しい高位＋低位のサロゲートペア（絵文字等）はこれまで通り合格する。
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

        // 実際の検証本体。true を返せば合格、false なら制約違反として扱われる
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            // null は他の制約（@NotBlank 等）に判定を委ねるため、ここでは合格扱いにする
            // （Bean Validation の一般的な作法: 各制約は自分の観点だけを見る）
            if (value == null) {
                return true;
            }
            // 文字列を 1 文字（UTF-16 コード単位）ずつ調べる。制御文字（C0・DEL・C1）はすべて
            // BMP 内の単一コード単位、サロゲートの対不成立もコード単位の並びで判定できるため、
            // コードポイント走査に切り替えなくても取りこぼしは無い
            for (int i = 0; i < value.length(); i++) {
                // 現在位置の文字を取り出す
                char c = value.charAt(i);
                // 高位サロゲート（絵文字等の前半 U+D800〜U+DBFF）が見つかった場合の対チェック
                if (Character.isHighSurrogate(c)) {
                    // 直後に低位サロゲート（後半 U+DC00〜U+DFFF）が続かなければ不正な文字表現とみなす
                    if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                        // 高位サロゲートが単独で存在する（UTF-8 に変換できない）ため不合格として false を返す
                        return false;
                    }
                    // 正しい対（絵文字等）なので、対になっている低位サロゲートを読み飛ばして次へ進む
                    i++;
                    // このコードポイントの判定は完了したので次のループへ進む
                    continue;
                }
                // 低位サロゲートが単独で現れた（対応する高位サロゲートが直前に無い）場合は不合格にする
                if (Character.isLowSurrogate(c)) {
                    // 低位サロゲート単独は UTF-8 に変換できない不正な文字表現のため false を返す
                    return false;
                }
                // ISO 制御文字（C0: U+0000〜U+001F、DEL: U+007F、C1: U+0080〜U+009F）のうち、
                // タブ・改行・復帰以外が見つかったら不合格にする
                if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r') {
                    // 許容外の制御文字（NUL・DEL・C1 等）を含むため制約違反として false を返す
                    return false;
                }
            }
            // 許容外の制御文字・対にならないサロゲートが見つからなかったので合格として true を返す
            return true;
        }
    }
}
