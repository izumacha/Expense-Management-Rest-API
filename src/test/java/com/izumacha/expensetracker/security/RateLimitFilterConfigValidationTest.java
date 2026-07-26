// セキュリティ関連のテストパッケージ（レート制限フィルタの設定値検証用）
package com.izumacha.expensetracker.security;

// フィルタのコンストラクタが必要とする ObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 「例外が投げられること」「投げられないこと」を検証する AssertJ のヘルパーを取り込む
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * レート制限フィルタのコンストラクタが環境変数由来の設定値を検証し、
 * 不正値では起動を失敗させる（fail-closed）ことを確認するユニットテスト。
 *
 * <p>【何を守るテストか】検証が無いと、window-seconds=0 はウィンドウ計算のゼロ除算で
 * 毎リクエスト ArithmeticException を引き起こし、最優先フィルタ内の例外のため
 * GlobalExceptionHandler を素通りしてコンテナ既定の 500 になる（{status, message} の
 * エラー契約が全エンドポイントで壊れる）。capacity=0 は全リクエストが 429 になる。
 * どちらも「実行時に静かに壊れる」より起動時に落とすべき設定ミスであり、
 * datasource パスワード未設定時と同じ fail-closed 方針（§9）に揃える。
 *
 * <p>Spring コンテキストを使わずコンストラクタを直接呼び出す純粋ユニットテスト（共通規約 §11）。
 */
class RateLimitFilterConfigValidationTest {

    // 正常系で使う許可数（1 以上なら何でもよい代表値）
    private static final int VALID_CAPACITY = 120;

    // 正常系で使う単位時間（秒）（1 以上なら何でもよい代表値）
    private static final long VALID_WINDOW_SECONDS = 60;

    // 指定した設定値でフィルタを生成するヘルパー（trust-x-forwarded-for は検証対象外なので false 固定）
    private static RateLimitFilter createFilter(int capacity, long windowSeconds) {
        // コンストラクタを直接呼び出してフィルタを生成する（検証ロジックはコンストラクタ内で走る）
        return new RateLimitFilter(capacity, windowSeconds, false, new ObjectMapper());
    }

    // 許可数が 0 のときは起動時例外（IllegalStateException）で失敗することを検証する
    @Test
    void capacityが0なら起動時例外() {
        // capacity=0 での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> createFilter(0, VALID_WINDOW_SECONDS))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれ、管理者が直すべき設定を特定できることを検証する
                .hasMessageContaining("app.rate-limit.capacity");
    }

    // 許可数が負のときも起動時例外で失敗することを検証する（境界値の負数側）
    @Test
    void capacityが負なら起動時例外() {
        // capacity=-1 での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> createFilter(-1, VALID_WINDOW_SECONDS))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれることを検証する
                .hasMessageContaining("app.rate-limit.capacity");
    }

    // 単位時間が 0 のとき（ゼロ除算の原因）は起動時例外で失敗することを検証する
    @Test
    void windowSecondsが0なら起動時例外() {
        // windowSeconds=0 での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> createFilter(VALID_CAPACITY, 0))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれることを検証する
                .hasMessageContaining("app.rate-limit.window-seconds");
    }

    // 単位時間が負のときも起動時例外で失敗することを検証する（境界値の負数側）
    @Test
    void windowSecondsが負なら起動時例外() {
        // windowSeconds=-60 での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> createFilter(VALID_CAPACITY, -60))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれることを検証する
                .hasMessageContaining("app.rate-limit.window-seconds");
    }

    // 正常な設定値（両方とも 1 以上）では例外なく生成できることを検証する
    @Test
    void 正常な設定値なら生成できる() {
        // 既定値相当（capacity=120 / window=60 秒）での生成が例外を投げないことを検証する
        assertThatCode(() -> createFilter(VALID_CAPACITY, VALID_WINDOW_SECONDS))
                // どの例外も発生しないことを検証する
                .doesNotThrowAnyException();
    }

    // 境界値ちょうど（どちらも最小の有効値 1）でも生成できることを検証する（§11 境界値の重視）
    @Test
    void 境界値1なら生成できる() {
        // capacity=1 / windowSeconds=1 での生成が例外を投げないことを検証する
        assertThatCode(() -> createFilter(1, 1))
                // どの例外も発生しないことを検証する
                .doesNotThrowAnyException();
    }
}
