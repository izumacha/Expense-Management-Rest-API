// バリデーション制約のテストパッケージ（対象クラスと同じパッケージに置く）
package com.izumacha.expensetracker.validation;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// アノテーションインスタンスをフィールドから取得するためのリフレクション API
import java.lang.reflect.Field;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// MaxCodePoints.Validator の境界値・サロゲートペア（絵文字等）の扱いを検証する。
// Bean Validation の実行時コンテナ（Hibernate Validator）を経由せず、
// isValid() を直接呼び出すことで DB/Spring コンテキスト無しの純粋ユニットテストにする（共通規約 §11）。
class MaxCodePointsTest {

    // 実際に @MaxCodePoints を付与したフィールドを持つだけのテスト専用クラス。
    // 手書きでアノテーションインタフェースを模倣するより、リフレクションで実行時に生成される
    // 本物のプロキシインスタンスを使うほうが Validator.initialize() の実挙動を確実に再現できる
    private static final class Holder {
        // 上限3文字のテスト用フィールド
        @MaxCodePoints(max = 3)
        String maxThree;

        // 上限0文字のテスト用フィールド
        @MaxCodePoints(max = 0)
        String maxZero;
    }

    // 指定フィールドに付与された @MaxCodePoints で初期化済みの Validator を返すヘルパー
    private MaxCodePoints.Validator validatorFor(String fieldName) throws NoSuchFieldException {
        // Holder クラスから該当フィールドの Field オブジェクトを取得する
        Field field = Holder.class.getDeclaredField(fieldName);
        // フィールドに付与された実際の @MaxCodePoints インスタンスを取得する
        MaxCodePoints annotation = field.getAnnotation(MaxCodePoints.class);
        // 新しい Validator を作り、取得したアノテーションで初期化する
        MaxCodePoints.Validator validator = new MaxCodePoints.Validator();
        validator.initialize(annotation);
        // 初期化済みの Validator を返す
        return validator;
    }

    // null は他の制約（@NotBlank 等）に判定を委ねるため合格扱いになることを検証する
    @Test
    void isValid_nullは合格扱い() throws NoSuchFieldException {
        // 上限3文字の Validator で null を検証すると true(合格)になることを確認する
        assertThat(validatorFor("maxThree").isValid(null, null)).isTrue();
    }

    // ちょうど上限と同じコードポイント数の文字列は合格することを検証する（境界値）
    @Test
    void isValid_上限ちょうどのコードポイント数は合格() throws NoSuchFieldException {
        // ちょうど3コードポイントの文字列を検証すると true(合格)になることを確認する
        assertThat(validatorFor("maxThree").isValid("食費交", null)).isTrue();
    }

    // 上限を1つでも超えるコードポイント数の文字列は不合格になることを検証する（境界値）
    @Test
    void isValid_上限を1つ超えると不合格() throws NoSuchFieldException {
        // 4コードポイントの文字列を検証すると false(不合格)になることを確認する
        assertThat(validatorFor("maxThree").isValid("食費交通", null)).isFalse();
    }

    // 空文字列は0コードポイントなので、上限が0より大きければ常に合格することを検証する
    @Test
    void isValid_空文字列は合格() throws NoSuchFieldException {
        // 上限3文字の Validator で空文字列を検証すると true(合格)になることを確認する
        assertThat(validatorFor("maxThree").isValid("", null)).isTrue();
    }

    // 上限0のときは空文字列だけが合格し、1文字でも不合格になることを検証する（境界値）
    @Test
    void isValid_上限0では空文字列のみ合格() throws NoSuchFieldException {
        // 上限0の Validator を取得する
        MaxCodePoints.Validator validator = validatorFor("maxZero");

        // 空文字列(0コードポイント)は合格する
        assertThat(validator.isValid("", null)).isTrue();
        // 1文字でも上限0を超えるため不合格になる
        assertThat(validator.isValid("a", null)).isFalse();
    }

    // サロゲートペア（基本多言語面外の絵文字）1文字が String.length() では2とカウントされる一方、
    // コードポイント数では1とカウントされることを検証する。標準の @Size(UTF-16基準)と異なり、
    // この制約が本当にコードポイント基準で数えていることのピン留め（クラス Javadoc の主張の検証）。
    @Test
    void isValid_サロゲートペアの絵文字は1コードポイントとして数える() throws NoSuchFieldException {
        // U+1F600 (😀) はサロゲートペアで表現され String.length() は 2 を返す
        String singleEmoji = "😀";
        // 前提確認: UTF-16 コード単位数は2であること(このテストの意味を保証する)
        assertThat(singleEmoji.length()).isEqualTo(2);

        // 上限1文字の Validator が無いため、上限0(=1コードポイントでも不合格)と
        // 上限3(=1コードポイントなら余裕で合格)の両方で挙動を確認する
        assertThat(validatorFor("maxZero").isValid(singleEmoji, null))
                .as("1コードポイントとして数えられるため上限0は超過し不合格になる")
                .isFalse();
        assertThat(validatorFor("maxThree").isValid(singleEmoji, null))
                .as("1コードポイントとして数えられるため上限3の範囲内で合格する")
                .isTrue();
    }
}
