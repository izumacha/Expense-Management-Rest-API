// 設定クラスのテストパッケージ
package com.izumacha.expensetracker.config;

// SecurityConfig のコンストラクタが必要とする ObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 「例外が投げられること」「投げられないこと」を検証する AssertJ のヘルパーを取り込む
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SecurityConfig のコンストラクタが CORS 許可オリジン設定を検証し、
 * ワイルドカード（*）指定では起動を失敗させることを確認するユニットテスト。
 *
 * <p>【何を守るテストか】許可オリジンに * を許すと「許可リストで制限する」という
 * 設定の意味自体が失われ、全オリジンからのクロスオリジン呼び出しに開放されてしまう。
 * 気付きにくい設定ミスなので起動時に落として管理者に知らせる（§9 fail-closed）。
 *
 * <p>Spring コンテキストを使わずコンストラクタを直接呼び出す純粋ユニットテスト（共通規約 §11）。
 */
class SecurityConfigValidationTest {

    // コンストラクタに渡す JSON マッパー（検証対象ではないため素の ObjectMapper でよい）
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 許可オリジンがワイルドカード単体のときは起動時例外（IllegalStateException）で失敗することを検証する
    @Test
    void ワイルドカード単体なら起動時例外() {
        // "*" での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> new SecurityConfig("*", objectMapper))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれ、管理者が直すべき設定を特定できることを検証する
                .hasMessageContaining("security.cors.allowed-origins");
    }

    // 個別オリジンに混ぜてワイルドカードを指定した場合も起動時例外で失敗することを検証する
    @Test
    void ワイルドカード混在でも起動時例外() {
        // 正しいオリジンと "*" が混在する CSV での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> new SecurityConfig("https://app.example.com, *", objectMapper))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれることを検証する
                .hasMessageContaining("security.cors.allowed-origins");
    }

    // 未設定（空文字）は「全オリジン拒否」の正常な既定として例外なく生成できることを検証する
    @Test
    void 未設定なら全拒否の既定として生成できる() {
        // 空文字での生成が例外を投げないことを検証する（fail-closed の既定であって設定ミスではない）
        assertThatCode(() -> new SecurityConfig("", objectMapper))
                // どの例外も発生しないことを検証する
                .doesNotThrowAnyException();
    }

    // 個別オリジンの列挙（空白・末尾カンマの揺れを含む）は例外なく生成できることを検証する
    @Test
    void 個別オリジンの列挙なら生成できる() {
        // 空白入り・末尾カンマ付きの CSV での生成が例外を投げないことを検証する
        assertThatCode(() -> new SecurityConfig("https://app.example.com, https://admin.example.com,", objectMapper))
                // どの例外も発生しないことを検証する
                .doesNotThrowAnyException();
    }
}
