// リポジトリのテストパッケージ
package com.izumacha.expensetracker.repository;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;

// 値が無いことを表す Optional 型
import java.util.Optional;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// CategoryRepository の検索・重複判定を本物の PostgreSQL で検証する
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

    // findByName: 名前で 1 件取得できることを検証する
    @Test
    void findByName_一致すれば取得できる() {
        // 交通費カテゴリを保存する
        categoryRepository.save(new Category("交通費"));

        // 名前で検索する
        Optional<Category> found = categoryRepository.findByName("交通費");

        // 値が存在することを検証する
        assertThat(found).isPresent();
        // 取得したカテゴリ名が交通費であることを検証する
        assertThat(found.get().getName()).isEqualTo("交通費");
    }

    // findByName: 一致しなければ空を返すことを検証する
    @Test
    void findByName_不一致なら空() {
        // 存在しない名前での検索が空であることを検証する
        assertThat(categoryRepository.findByName("未登録")).isEmpty();
    }
}
