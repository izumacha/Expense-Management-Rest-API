// セキュリティ関連のテストパッケージ（ログ出力前の無害化ヘルパーの検証用）
package com.izumacha.expensetracker.security;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimitFilter.sanitizeForLog（攻撃者制御下のヘッダ値をログへ出す前に無害化するヘルパー）の
 * ユニットテスト。
 *
 * <p>【何を守るテストか】X-Forwarded-For の末尾値は IP 検証に落ちた任意文字列であり、
 * CR/LF をそのままログに出すと偽のログ行を挿入できてしまう（ログ偽装。§9）。
 * 制御文字の置換と長さの打ち切りが機能し続けることを、フィルタ本体を起動せずに直接検証する
 * （共通規約 §11 純粋ロジックはユニットテスト）。
 */
class RateLimitFilterLogSanitizeTest {

    // 改行（LF）・復帰（CR）が '_' に置換され、偽のログ行を挿入できないことを検証する
    @Test
    void 改行と復帰は置換される() {
        // CR/LF で偽のログ行を挿入しようとする攻撃値を無害化する
        String sanitized = RateLimitFilter.sanitizeForLog("1.2.3.4\r\nFAKE-LOG-LINE");
        // CR/LF が '_' に置換され、1 行に収まっていることを検証する
        assertThat(sanitized).isEqualTo("1.2.3.4__FAKE-LOG-LINE");
    }

    // タブやエスケープなど他の ISO 制御文字も '_' に置換されることを検証する
    @Test
    void その他の制御文字も置換される() {
        // タブ（\t）とエスケープ（\u001B。端末の表示制御を乗っ取る ANSI シーケンスの起点）を含む値を無害化する
        String sanitized = RateLimitFilter.sanitizeForLog("a\tb\u001B[31mc");
        // 制御文字だけが '_' に置換されていることを検証する
        assertThat(sanitized).isEqualTo("a_b_[31mc");
    }

    // 上限を超える長さの値は打ち切られることを検証する（ログ肥大の抑止）
    @Test
    void 上限超過の長さは打ち切られる() {
        // 上限より 1 文字長い値を無害化する（境界値の超過側）
        String sanitized = RateLimitFilter.sanitizeForLog("x".repeat(RateLimitFilter.LOG_VALUE_MAX_LENGTH + 1));
        // 出力が上限文字数ちょうどに打ち切られていることを検証する
        assertThat(sanitized).hasSize(RateLimitFilter.LOG_VALUE_MAX_LENGTH);
    }

    // 上限ちょうどの長さの通常値はそのまま返ることを検証する（境界値・正常系）
    @Test
    void 上限ちょうどの通常値はそのまま返る() {
        // 制御文字を含まない上限ちょうどの値を用意する
        String value = "a".repeat(RateLimitFilter.LOG_VALUE_MAX_LENGTH);
        // 無害化しても値が変わらないことを検証する
        assertThat(RateLimitFilter.sanitizeForLog(value)).isEqualTo(value);
    }

    // null は文字列 "null" として返り、例外にならないことを検証する（防御的な境界値）
    @Test
    void nullは文字列nullとして返る() {
        // null を無害化しても NPE にならず "null" が返ることを検証する
        assertThat(RateLimitFilter.sanitizeForLog(null)).isEqualTo("null");
    }
}
