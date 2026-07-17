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

    // existsByNameIgnoreCase: 同名が存在すれば true を返すことを検証する
    @Test
    void existsByNameIgnoreCase_存在すればtrue() {
        // 食費カテゴリを保存する
        categoryRepository.save(new Category("食費"));

        // 同名の存在判定が true であることを検証する
        assertThat(categoryRepository.existsByNameIgnoreCase("食費")).isTrue();
    }

    // existsByNameIgnoreCase: 同名が無ければ false を返すことを検証する
    @Test
    void existsByNameIgnoreCase_無ければfalse() {
        // 何も保存していない名前の存在判定が false であることを検証する
        assertThat(categoryRepository.existsByNameIgnoreCase("娯楽費")).isFalse();
    }

    // existsByNameIgnoreCase: 大文字小文字だけが異なる名前も重複として true を返すことを検証する
    // (レビュー指摘: "Travel" と "travel" が別カテゴリとして重複チェックをすり抜けていた問題の修正確認)
    @Test
    void existsByNameIgnoreCase_大文字小文字が異なっても重複としてtrue() {
        // 小文字始まりで "travel" カテゴリを保存する
        categoryRepository.save(new Category("travel"));

        // 大文字始まりの "Travel" で問い合わせても、大文字小文字を無視して同一視され true であることを検証する
        assertThat(categoryRepository.existsByNameIgnoreCase("Travel")).isTrue();
        // 全て大文字の "TRAVEL" でも同様に true であることを検証する
        assertThat(categoryRepository.existsByNameIgnoreCase("TRAVEL")).isTrue();
    }

    // existsByNameIgnoreCaseAndIdNot: 自分自身の ID を除外するので、自分の現在名では false を返すことを検証する
    // （更新時に「名前を変えていない」ケースを誤って重複と判定しないことの確認）
    @Test
    void existsByNameIgnoreCaseAndIdNot_自分自身は除外されfalse() {
        // 食費カテゴリを保存する
        Category food = categoryRepository.save(new Category("食費"));

        // 自分自身の ID を除外した判定では、自分の名前と一致しても false であることを検証する
        assertThat(categoryRepository.existsByNameIgnoreCaseAndIdNot("食費", food.getId())).isFalse();
    }

    // existsByNameIgnoreCaseAndIdNot: 自分以外に同名カテゴリが存在すれば true を返すことを検証する
    @Test
    void existsByNameIgnoreCaseAndIdNot_他人と同名ならtrue() {
        // 食費カテゴリを保存する
        categoryRepository.save(new Category("食費"));
        // 交通費カテゴリを保存する
        Category transport = categoryRepository.save(new Category("交通費"));

        // 交通費を食費へ改名しようとした場合、自分（transport）以外に食費が存在するため true であることを検証する
        assertThat(categoryRepository.existsByNameIgnoreCaseAndIdNot("食費", transport.getId())).isTrue();
        // 交通費自身の ID を除外した判定では、自分の名前と一致しても false であることを検証する
        assertThat(categoryRepository.existsByNameIgnoreCaseAndIdNot("交通費", transport.getId())).isFalse();
    }
}
