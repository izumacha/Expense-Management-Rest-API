// バリデーション制約のテストパッケージ（対象クラスと同じパッケージに置く）
package com.izumacha.expensetracker.validation;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// NoControlCharacters.Validator の制御文字判定（NUL・その他 C0・許容するタブ/改行/復帰）を検証する。
// Bean Validation の実行時コンテナ（Hibernate Validator）を経由せず、
// isValid() を直接呼び出すことで DB/Spring コンテキスト無しの純粋ユニットテストにする（共通規約 §11）。
// この制約は属性を持たない（initialize で取り込む値が無い）ため、
// MaxCodePointsTest のようなアノテーション取得用の Holder は不要で直接生成できる。
// 制御文字はエディタ上で不可視のため、コード中はエスケープ表記（"\0" や (char) 0x1F）で明示し、
// テストが本当に制御文字入力を使っていることをソースコード上で保証する。
class NoControlCharactersTest {

    // テスト対象の Validator（状態を持たないため 1 インスタンスを全テストで共有する）
    private final NoControlCharacters.Validator validator = new NoControlCharacters.Validator();

    // null は他の制約（@NotBlank 等）に判定を委ねるため合格扱いになることを検証する
    @Test
    void isValid_nullは合格扱い() {
        // null を検証すると true(合格)になることを確認する
        assertThat(validator.isValid(null, null)).isTrue();
    }

    // 制御文字を含まない通常の文字列（日本語・絵文字含む）は合格することを検証する
    @Test
    void isValid_通常の文字列は合格() {
        // 日本語の名前を検証すると true(合格)になることを確認する
        assertThat(validator.isValid("食費", null)).isTrue();
        // 絵文字（サロゲートペア）混じりの文字列も true(合格)になることを確認する
        assertThat(validator.isValid("ランチ😀", null)).isTrue();
        // 半角スペース（U+0020。C0 範囲のすぐ外側の境界値）を含む文字列も true(合格)になることを確認する
        assertThat(validator.isValid("food expense", null)).isTrue();
    }

    // 空文字列は制御文字を含まないため合格することを検証する（境界値）
    @Test
    void isValid_空文字列は合格() {
        // 空文字列を検証すると true(合格)になることを確認する
        assertThat(validator.isValid("", null)).isTrue();
    }

    // NUL（U+0000）を含む文字列は不合格になることを検証する。
    // NUL は @NotBlank / @MaxCodePoints をすべて通過する一方、PostgreSQL の text/varchar 列には
    // 保存できず DB 層で初めてエラーになるため、この制約で入力段階に弾くことが本質（クラス Javadoc 参照）
    @Test
    void isValid_NULを含む文字列は不合格() {
        // 文字列の途中に NUL（"\0"）を含むケースが false(不合格)になることを確認する
        assertThat(validator.isValid("食\0費", null)).isFalse();
        // NUL（"\0"）1 文字だけのケースも false(不合格)になることを確認する
        assertThat(validator.isValid("\0", null)).isFalse();
        // 末尾に NUL（"\0"）があるケースも false(不合格)になることを確認する
        assertThat(validator.isValid("食費\0", null)).isFalse();
    }

    // NUL 以外の C0 制御文字（U+0001〜U+001F のうちタブ/改行/復帰以外）も不合格になることを検証する（境界値）
    @Test
    void isValid_その他のC0制御文字も不合格() {
        // C0 範囲の先頭付近（U+0001）を含むケースが false(不合格)になることを確認する
        assertThat(validator.isValid("a" + (char) 0x01 + "b", null)).isFalse();
        // C0 範囲の末尾（U+001F。境界値）を含むケースが false(不合格)になることを確認する
        assertThat(validator.isValid("a" + (char) 0x1F + "b", null)).isFalse();
        // エスケープ文字（U+001B）を含むケースが false(不合格)になることを確認する
        assertThat(validator.isValid("a" + (char) 0x1B + "b", null)).isFalse();
    }

    // DEL（U+007F）を含む文字列は不合格になることを検証する（C0 の外側にある単独の制御文字）
    @Test
    void isValid_DELを含む文字列は不合格() {
        // 文字列の途中に DEL（U+007F）を含むケースが false(不合格)になることを確認する
        assertThat(validator.isValid("a" + (char) 0x7F + "b", null)).isFalse();
        // DEL（U+007F）1 文字だけのケースも false(不合格)になることを確認する
        assertThat(validator.isValid(String.valueOf((char) 0x7F), null)).isFalse();
    }

    // C1 制御文字（U+0080〜U+009F）を含む文字列は不合格になることを検証する（境界値含む）
    @Test
    void isValid_C1制御文字も不合格() {
        // C1 範囲の先頭（U+0080。境界値）を含むケースが false(不合格)になることを確認する
        assertThat(validator.isValid("a" + (char) 0x80 + "b", null)).isFalse();
        // CSI（U+009B。端末のエスケープシーケンス導入文字）を含むケースが false(不合格)になることを確認する
        assertThat(validator.isValid("a" + (char) 0x9B + "b", null)).isFalse();
        // C1 範囲の末尾（U+009F。境界値）を含むケースが false(不合格)になることを確認する
        assertThat(validator.isValid("a" + (char) 0x9F + "b", null)).isFalse();
        // C1 範囲のすぐ外側（U+00A0。ノーブレークスペース）は制御文字でないため true(合格)になることを確認する
        assertThat(validator.isValid("a" + (char) 0xA0 + "b", null)).isTrue();
    }

    // 対にならない高位サロゲート（例: JSON の "a\ud800b"）は不合格になることを検証する。
    // 単独の高位サロゲートは UTF-8 へ変換できず、既存の全制約と Unicode 正規化を通過した後に
    // PostgreSQL の「invalid byte sequence for encoding UTF8」で初めて失敗し、サービス層で
    // 一意制約・FK レースと誤認されて誤った 409/404 になるため、入力段階で 400 として弾くことが本質
    @Test
    void isValid_単独の高位サロゲートは不合格() {
        // 高位サロゲートの直後に通常文字が続くケース（"a\ud800b"）が false(不合格)になることを確認する
        assertThat(validator.isValid("a\uD800b", null)).isFalse();
        // 高位サロゲートが文字列末尾で終わるケース（後続文字なし）も false(不合格)になることを確認する
        assertThat(validator.isValid("食費\uD800", null)).isFalse();
        // 高位サロゲート 1 文字だけのケースも false(不合格)になることを確認する
        assertThat(validator.isValid("\uDBFF", null)).isFalse();
        // 高位サロゲートが 2 連続する（対が成立しない）ケースも false(不合格)になることを確認する
        assertThat(validator.isValid("\uD800\uD800", null)).isFalse();
    }

    // 対にならない低位サロゲート（直前に高位サロゲートが無い）は不合格になることを検証する
    @Test
    void isValid_単独の低位サロゲートは不合格() {
        // 低位サロゲートが単独で文字列の途中に現れるケースが false(不合格)になることを確認する
        assertThat(validator.isValid("a\uDC00b", null)).isFalse();
        // 低位サロゲートが文字列の先頭に現れるケースも false(不合格)になることを確認する
        assertThat(validator.isValid("\uDFFF食費", null)).isFalse();
        // 低位→高位の順（正しい対の逆順）のケースも false(不合格)になることを確認する
        assertThat(validator.isValid("\uDC00\uD800", null)).isFalse();
    }

    // 正しい高位＋低位のサロゲートペア（絵文字等）は合格することを検証する
    @Test
    void isValid_正しいサロゲートペアは合格() {
        // 絵文字（😀 = U+1F600。高位 U+D83D + 低位 U+DE00 の正しい対）が true(合格)になることを確認する
        assertThat(validator.isValid("ランチ😀", null)).isTrue();
        // サロゲートペアだけの文字列（絵文字 1 文字）も true(合格)になることを確認する
        assertThat(validator.isValid("😀", null)).isTrue();
        // サロゲートペアが連続する文字列（絵文字 2 文字）も true(合格)になることを確認する
        assertThat(validator.isValid("😀😁", null)).isTrue();
    }

    // 正当に使われうるタブ（U+0009）・改行（U+000A）・復帰（U+000D）は許容されることを検証する
    @Test
    void isValid_タブと改行と復帰は合格() {
        // タブを含む文字列が true(合格)になることを確認する
        assertThat(validator.isValid("メモ\tタブ区切り", null)).isTrue();
        // 改行を含む文字列が true(合格)になることを確認する
        assertThat(validator.isValid("1行目\n2行目", null)).isTrue();
        // 復帰＋改行（Windows 形式の改行）を含む文字列が true(合格)になることを確認する
        assertThat(validator.isValid("1行目\r\n2行目", null)).isTrue();
    }
}
