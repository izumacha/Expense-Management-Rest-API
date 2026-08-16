// バリデーション関連のテストパッケージ
package com.izumacha.expensetracker.validation;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
// 「例外が投げられること」を検証する AssertJ のヘルパーを取り込む
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TextSanitizer（外部由来の文字列を記録先へ書く前に無害化する共通ユーティリティ）のユニットテスト。
 *
 * <p>【何を守るテストか】この関数はログ（RateLimitFilter）と監査ログ（audit パッケージ）の
 * 両方が依存する共通の砦である。制御文字の置換が壊れれば記録の偽装（CR/LF による偽の行の挿入）が
 * 通り、長さの打ち切りが壊れれば記録先の肥大＝資源枯渇の入口になる（§9）。
 * 一元化した以上、規則そのものはここで境界値まで固定しておく（共通規約 §11 純粋ロジックはユニットテスト）。
 */
class TextSanitizerTest {

    // テストで使う代表的な上限値（実際の上限は呼び出し側が渡すので、ここでは任意の代表値でよい）
    private static final int MAX_LENGTH = 10;

    // 改行（LF）・復帰（CR）が '_' に置換され、偽の記録行を挿入できないことを検証する
    @Test
    void 改行と復帰は置換される() {
        // CR/LF で偽の行を挿入しようとする値を、十分に長い上限で無害化する
        String sanitized = TextSanitizer.sanitize("ab\r\ncd", 100);
        // CR/LF が '_' に置換され、1 行に収まっていることを検証する
        assertThat(sanitized).isEqualTo("ab__cd");
    }

    // タブやエスケープなど他の ISO 制御文字も '_' に置換されることを検証する
    @Test
    void その他の制御文字も置換される() {
        // タブ（\t）とエスケープ（端末表示を乗っ取る ANSI シーケンスの起点）を含む値を無害化する
        String sanitized = TextSanitizer.sanitize("a\tb\u001B[31mc", 100);
        // 制御文字だけが '_' に置換され、それ以外の文字は元のまま残ることを検証する
        assertThat(sanitized).isEqualTo("a_b_[31mc");
    }

    // 上限を超える長さの値は打ち切られることを検証する（記録先の肥大の抑止・境界値の超過側）
    @Test
    void 上限超過の長さは打ち切られる() {
        // 上限より 1 文字長い値を無害化する
        String sanitized = TextSanitizer.sanitize("x".repeat(MAX_LENGTH + 1), MAX_LENGTH);
        // 出力が上限文字数ちょうどに打ち切られていることを検証する
        assertThat(sanitized).hasSize(MAX_LENGTH);
    }

    // 上限ちょうどの通常値はそのまま返ることを検証する（境界値・正常系）
    @Test
    void 上限ちょうどの値はそのまま返る() {
        // 制御文字を含まない上限ちょうどの値を用意する
        String value = "a".repeat(MAX_LENGTH);
        // 無害化しても値が変わらないことを検証する
        assertThat(TextSanitizer.sanitize(value, MAX_LENGTH)).isEqualTo(value);
    }

    // 上限 0 は空文字になることを検証する（境界値の下限。例外にはしない）
    @Test
    void 上限0なら空文字になる() {
        // 上限 0 で無害化すると 1 文字も残らないことを検証する
        assertThat(TextSanitizer.sanitize("abc", 0)).isEmpty();
    }

    // null は文字列 "null" として返り、例外にならないことを検証する（防御的な境界値）
    @Test
    void nullは文字列nullとして返る() {
        // null を無害化しても NPE にならず "null" が返ることを検証する
        assertThat(TextSanitizer.sanitize(null, MAX_LENGTH)).isEqualTo("null");
    }

    // 負の上限は呼び出し側の設定ミスなので、黙って動かさず例外で知らせることを検証する
    @Test
    void 負の上限は例外になる() {
        // 負の上限での呼び出しが IllegalArgumentException を投げることを検証する
        assertThatThrownBy(() -> TextSanitizer.sanitize("abc", -1))
                // 例外の型が IllegalArgumentException であることを検証する
                .isInstanceOf(IllegalArgumentException.class)
                // どの引数が不正かがメッセージから分かることを検証する
                .hasMessageContaining("maxLength");
    }
}
