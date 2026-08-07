// 設定クラスのテストパッケージ
package com.izumacha.expensetracker.config;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 「例外が投げられること」「投げられないこと」を検証する AssertJ のヘルパーを取り込む
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
// 値を検証する assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtConfig のコンストラクタが環境変数由来のシークレットを検証し、
 * 不正値では起動を失敗させる（fail-closed）ことを確認するユニットテスト。
 *
 * <p>【何を守るテストか】検証が無いと「シークレット未設定・弱い鍵のまま認証が動いているように
 * 見える」危険な状態で起動できてしまう。datasource パスワードやレート制限の設定値と同じく、
 * 実行時に静かに壊れるより起動時に落とす fail-closed 方針（§9）に揃える。
 *
 * <p>Spring コンテキストを使わずコンストラクタを直接呼び出す純粋ユニットテスト（共通規約 §11）。
 */
class JwtConfigValidationTest {

    // 32 バイト（HS256 の最小鍵長）ちょうどの有効なシークレット（ASCII のみなので 1 文字 = 1 バイト）
    private static final String VALID_SECRET_32_BYTES = "0123456789abcdef0123456789abcdef";

    // シークレットが空文字（未設定相当）のときは起動時例外（IllegalStateException）で失敗することを検証する
    @Test
    void シークレットが空なら起動時例外() {
        // 空文字での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> new JwtConfig(""))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれ、管理者が直すべき設定を特定できることを検証する
                .hasMessageContaining("security.jwt.secret");
    }

    // シークレットが null のときも起動時例外で失敗することを検証する（プロパティ解決不能の境界）
    @Test
    void シークレットがnullなら起動時例外() {
        // null での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> new JwtConfig(null))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれることを検証する
                .hasMessageContaining("security.jwt.secret");
    }

    // シークレットが 31 バイト（最小鍵長未満）のときは起動時例外で失敗することを検証する（境界値の下側）
    @Test
    void シークレットが31バイトなら起動時例外() {
        // 31 文字（= 31 バイト）のシークレットを用意する
        String tooShort = VALID_SECRET_32_BYTES.substring(0, 31);
        // 31 バイトでの生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> new JwtConfig(tooShort))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれることを検証する
                .hasMessageContaining("security.jwt.secret");
    }

    // シークレットの値そのものが例外メッセージに漏れないことを検証する（§9 秘密情報を漏らさない）
    @Test
    void 例外メッセージにシークレット値を含めない() {
        // 短すぎる（それでも秘密の）シークレットを用意する
        String secretValue = "my-secret-value";
        // 生成時の例外メッセージにシークレット値そのものが含まれないことを検証する
        assertThatThrownBy(() -> new JwtConfig(secretValue))
                // メッセージに設定値そのものが含まれないことを検証する
                .hasMessageNotContaining(secretValue);
    }

    // 境界値ちょうど（32 バイト）のシークレットでは生成でき、Bean も組み立てられることを検証する（§11 境界値）
    @Test
    void シークレットが32バイトなら生成できる() {
        // 32 バイトちょうどのシークレットでの生成が例外を投げないことを検証する
        assertThatCode(() -> new JwtConfig(VALID_SECRET_32_BYTES))
                // どの例外も発生しないことを検証する
                .doesNotThrowAnyException();
        // 生成した設定からデコーダ・エンコーダの Bean が実際に組み立てられることを検証する
        JwtConfig config = new JwtConfig(VALID_SECRET_32_BYTES);
        // デコーダが null でなく生成されることを検証する
        assertThat(config.jwtDecoder()).isNotNull();
        // エンコーダが null でなく生成されることを検証する
        assertThat(config.jwtEncoder()).isNotNull();
    }

    // マルチバイト文字のシークレットはバイト数（文字数ではない）で判定されることを検証する。
    // 日本語 11 文字（UTF-8 で 33 バイト）は文字数こそ少ないが鍵長要件を満たすため生成できる
    @Test
    void マルチバイト文字はバイト数で判定される() {
        // 日本語 11 文字（1 文字 3 バイト × 11 = 33 バイト ≧ 32 バイト）のシークレットを用意する
        String multibyteSecret = "あ".repeat(11);
        // バイト数が要件を満たすため生成が例外を投げないことを検証する
        assertThatCode(() -> new JwtConfig(multibyteSecret))
                // どの例外も発生しないことを検証する
                .doesNotThrowAnyException();
    }
}
