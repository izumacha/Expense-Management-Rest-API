// サービスのテストパッケージ（ExpenseService の設定値検証用）
package com.izumacha.expensetracker.service;

// カテゴリリポジトリを参照する（コンストラクタ引数のモックに使う）
import com.izumacha.expensetracker.repository.CategoryRepository;
// 支出リポジトリを参照する（コンストラクタ引数のモックに使う）
import com.izumacha.expensetracker.repository.ExpenseRepository;

// JPA のエンティティマネージャ型（コンストラクタ引数のモックに使う）
import jakarta.persistence.EntityManager;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 「例外が投げられること」「投げられないこと」を検証する AssertJ のヘルパーを取り込む
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
// 依存をモックとして生成する mock を取り込む（Mockito）
import static org.mockito.Mockito.mock;

/**
 * ExpenseService のコンストラクタが設定値（summary のカテゴリ内訳上限
 * {@code spring.data.web.pageable.max-page-size}）を検証し、
 * 不正値では起動を失敗させる（fail-closed）ことを確認するユニットテスト。
 *
 * <p>【何を守るテストか】検証が無いと、0 以下の設定でもアプリは起動に成功する一方、
 * summary() の PageRequest.of(0, summaryMaxCategories) が毎回 IllegalArgumentException を投げ、
 * サーバ側の設定ミスなのに 400（クライアント起因）として返る「動いているように見えて壊れた」
 * 状態を招く。RateLimitFilter / RequestBodySizeLimitFilter の設定値検証と同じ fail-closed 方針（§9）に揃える。
 *
 * <p>Spring コンテキストを使わずコンストラクタを直接呼び出す純粋ユニットテスト（共通規約 §11）。
 */
class ExpenseServiceConfigValidationTest {

    // 正常系で使う上限件数（1 以上なら何でもよい代表値。application.yml の既定値と同じ 100）
    private static final int VALID_MAX_CATEGORIES = 100;

    // 指定した上限件数でサービスを生成するヘルパー（依存はモックで埋める。検証対象は上限値のみ）
    private static ExpenseService createService(int summaryMaxCategories) {
        // コンストラクタを直接呼び出してサービスを生成する（検証ロジックはコンストラクタ内で走る）
        return new ExpenseService(
                // 支出リポジトリのモック（コンストラクタでは代入されるだけで呼ばれない）
                mock(ExpenseRepository.class),
                // カテゴリリポジトリのモック（同上）
                mock(CategoryRepository.class),
                // エンティティマネージャのモック（同上）
                mock(EntityManager.class),
                // 検証対象の上限件数
                summaryMaxCategories);
    }

    // 上限が 0 のときは起動時例外（IllegalStateException）で失敗することを検証する
    @Test
    void 上限が0なら起動時例外() {
        // 上限 0 での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> createService(0))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれ、管理者が直すべき設定を特定できることを検証する
                .hasMessageContaining("spring.data.web.pageable.max-page-size");
    }

    // 上限が負のときも起動時例外で失敗することを検証する（境界値の負数側）
    @Test
    void 上限が負なら起動時例外() {
        // 上限 -1 での生成が IllegalStateException を投げることを検証する
        assertThatThrownBy(() -> createService(-1))
                // 例外の型が IllegalStateException であることを検証する
                .isInstanceOf(IllegalStateException.class)
                // メッセージに対象プロパティ名が含まれることを検証する
                .hasMessageContaining("spring.data.web.pageable.max-page-size");
    }

    // 正常な設定値（1 以上）では例外なく生成できることを検証する
    @Test
    void 正常な設定値なら生成できる() {
        // 既定値相当（100 件）での生成が例外を投げないことを検証する
        assertThatCode(() -> createService(VALID_MAX_CATEGORIES))
                // どの例外も発生しないことを検証する
                .doesNotThrowAnyException();
    }

    // 境界値ちょうど（最小の有効値 1）でも生成できることを検証する（§11 境界値の重視）
    @Test
    void 境界値1なら生成できる() {
        // 上限 1 での生成が例外を投げないことを検証する
        assertThatCode(() -> createService(1))
                // どの例外も発生しないことを検証する
                .doesNotThrowAnyException();
    }
}
