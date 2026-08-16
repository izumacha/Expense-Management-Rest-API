// 設定クラスのテストパッケージ
package com.izumacha.expensetracker.config;

// テスト内で有効な bcrypt ハッシュを生成するためのエンコーダ
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 「例外が投げられること」「投げられないこと」を検証する AssertJ のヘルパーを取り込む
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ApiUserConfig のコンストラクタが環境変数由来の API ユーザー設定を検証し、
 * 不正値では起動を失敗させる（fail-closed）ことを確認するユニットテスト。
 *
 * <p>【何を守るテストか】ユーザー名・パスワードハッシュが未設定のまま起動を許すと
 * 「トークン発行が誰にも成功しない」壊れた状態になり、bcrypt でない値（誤って設定した
 * 平文パスワード）を受理すると照合が常に失敗するうえ環境変数に平文の秘密が残り続ける。
 * どちらも起動時に落として管理者に設定ミスを知らせる（§9 fail-closed）。
 *
 * <p>Spring コンテキストを使わずコンストラクタを直接呼び出す純粋ユニットテスト（共通規約 §11）。
 */
class ApiUserConfigValidationTest {

    // 正常系で使うユーザー名（空白でなければ何でもよい代表値）
    private static final String VALID_NAME = "api-user";

    // 正常系で使う有効な bcrypt ハッシュ（値は毎回変わるが必ず "$2..." で始まる）
    private static final String VALID_HASH = new BCryptPasswordEncoder().encode("dummy-password");

    // ユーザー名が空文字（未設定相当）のときは起動時例外（IllegalStateException）で失敗することを検証する
    @Test
    void ユーザー名が空なら起動時例外() {
        // 空のユーザー名での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> new ApiUserConfig("", VALID_HASH))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれ、管理者が直すべき設定を特定できることを検証する
                .hasMessageContaining("security.api-user.name");
    }

    // パスワードハッシュが空文字（未設定相当）のときは起動時例外で失敗することを検証する
    @Test
    void パスワードハッシュが空なら起動時例外() {
        // 空のハッシュでの生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> new ApiUserConfig(VALID_NAME, ""))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれることを検証する
                .hasMessageContaining("security.api-user.password-hash");
    }

    // bcrypt 形式でない値（誤って設定された平文パスワード）は起動時例外で失敗することを検証する
    @Test
    void パスワードハッシュが平文なら起動時例外() {
        // 平文パスワードのつもりの値を用意する
        String plainPassword = "plain-text-password";
        // 平文での生成が IllegalStateException を投げ、かつ平文の値が例外メッセージに漏れないことを検証する
        assertThatThrownBy(() -> new ApiUserConfig(VALID_NAME, plainPassword))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに bcrypt 形式が必要である旨が含まれることを検証する
                .hasMessageContaining("bcrypt")
                // 設定値そのもの（平文パスワードかもしれない値）が漏れないことを検証する（§9）
                .hasMessageNotContaining(plainPassword);
    }

    // 正常な設定値（ユーザー名 + bcrypt ハッシュ）では生成でき、Bean も組み立てられることを検証する
    @Test
    void 正常な設定値なら生成できる() {
        // 有効なユーザー名と bcrypt ハッシュでの生成が例外を投げないことを検証する
        assertThatCode(() -> {
            // 設定クラスを生成する（コンストラクタ内の検証が走る）
            ApiUserConfig config = new ApiUserConfig(VALID_NAME, VALID_HASH);
            // ユーザーストア Bean が組み立てられることを確認する（ユーザー名でユーザーを引ける）
            config.apiUserDetailsService().loadUserByUsername(VALID_NAME);
            // 認証マネージャ Bean が組み立てられることを確認する
            config.authenticationManager(config.apiUserDetailsService(), config.passwordEncoder());
        })
                // どの例外も発生しないことを検証する
                .doesNotThrowAnyException();
    }
}
