// リポジトリのテストパッケージ
package com.izumacha.expensetracker.repository;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// CategoryRepository の重複判定を本物の PostgreSQL で検証する
class CategoryRepositoryTest extends AbstractRepositoryTest {

    // テスト対象のカテゴリリポジトリ
    @Autowired
    private CategoryRepository categoryRepository;

    // existsByName: 同名が存在すれば true を返すことを検証する
    @Test
    void existsByName_存在すればtrue() {
        // 食費カテゴリを保存する
        categoryRepository.save(new Category("食費"));

        // 同名の存在判定が true であることを検証する
        assertThat(categoryRepository.existsByName("食費")).isTrue();
    }

    // existsByName: 同名が無ければ false を返すことを検証する
    @Test
    void existsByName_無ければfalse() {
        // 何も保存していない名前の存在判定が false であることを検証する
        assertThat(categoryRepository.existsByName("娯楽費")).isFalse();
    }
}
