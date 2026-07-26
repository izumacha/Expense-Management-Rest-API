// Web 横断ユーティリティのテストパッケージ（本文サイズ上限フィルタの設定値検証用）
package com.izumacha.expensetracker.web;

// フィルタのコンストラクタが必要とする ObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 「例外が投げられること」「投げられないこと」を検証する AssertJ のヘルパーを取り込む
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本文サイズ上限フィルタのコンストラクタが環境変数由来の設定値を検証し、
 * 不正値では起動を失敗させる（fail-closed）ことを確認するユニットテスト。
 *
 * <p>【何を守るテストか】検証が無いと、max-body-size-bytes<=0 は「宣言 Content-Length の
 * 事前チェックは素通りしうる一方、本文を 1 バイト読んだ時点で必ず上限超過になる」ため、
 * 本文を持つすべてのリクエストが 413 になる「起動は成功するのに壊れている」状態を招く。
 * 実行時に静かに壊れるより起動時に落とすべき設定ミスであり、RateLimitFilter の
 * capacity / window-seconds 検証と同じ fail-closed 方針（§9）に揃える。
 *
 * <p>Spring コンテキストを使わずコンストラクタを直接呼び出す純粋ユニットテスト（共通規約 §11）。
 */
class RequestBodySizeLimitFilterConfigValidationTest {

    // 正常系で使う上限バイト数（application.yml の既定値と同じ 1MB）
    private static final long VALID_MAX_BODY_SIZE_BYTES = 1_048_576;

    // 指定した上限値でフィルタを生成するヘルパー（検証ロジックはコンストラクタ内で走る）
    private static RequestBodySizeLimitFilter createFilter(long maxBodySizeBytes) {
        // コンストラクタを直接呼び出してフィルタを生成する
        return new RequestBodySizeLimitFilter(maxBodySizeBytes, new ObjectMapper());
    }

    // 上限が 0 のときは起動時例外（IllegalStateException）で失敗することを検証する
    @Test
    void 上限が0なら起動時例外() {
        // maxBodySizeBytes=0 での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> createFilter(0))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれ、管理者が直すべき設定を特定できることを検証する
                .hasMessageContaining("app.request.max-body-size-bytes");
    }

    // 上限が負のときも起動時例外で失敗することを検証する（境界値の負数側）
    @Test
    void 上限が負なら起動時例外() {
        // maxBodySizeBytes=-1 での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> createFilter(-1))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれることを検証する
                .hasMessageContaining("app.request.max-body-size-bytes");
    }

    // 正常な設定値（1 以上）では例外なく生成できることを検証する
    @Test
    void 正常な設定値なら生成できる() {
        // 既定値相当（1MB）での生成が例外を投げないことを検証する
        assertThatCode(() -> createFilter(VALID_MAX_BODY_SIZE_BYTES))
                // どの例外も発生しないことを検証する
                .doesNotThrowAnyException();
    }

    // 境界値ちょうど（最小の有効値 1）でも生成できることを検証する（§11 境界値の重視）
    @Test
    void 境界値1なら生成できる() {
        // maxBodySizeBytes=1 での生成が例外を投げないことを検証する
        assertThatCode(() -> createFilter(1))
                // どの例外も発生しないことを検証する
                .doesNotThrowAnyException();
    }
}
