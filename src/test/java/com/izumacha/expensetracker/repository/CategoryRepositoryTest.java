// リポジトリのテストパッケージ
package com.izumacha.expensetracker.repository;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// 重複チェック用の正規化キー（NFC + Locale.ROOT 小文字化）を導出するユーティリティを参照する
import com.izumacha.expensetracker.validation.CategoryNameNormalizer;

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

    // サービス層と同じ手順（normalizeKey で導出したキーを渡す）で存在判定を行うヘルパー
    private boolean existsByName(String name) {
        // 名前から正規化キーを導出してリポジトリの存在判定を呼び出す
        return categoryRepository.existsByNameKey(CategoryNameNormalizer.normalizeKey(name));
    }

    // サービス層と同じ手順で「自分以外」の存在判定を行うヘルパー
    private boolean existsByNameExcluding(String name, Long id) {
        // 名前から正規化キーを導出して、自分の ID を除外した存在判定を呼び出す
        return categoryRepository.existsByNameKeyAndIdNot(CategoryNameNormalizer.normalizeKey(name), id);
    }

    // existsByNameKey: 同名が存在すれば true を返すことを検証する
    @Test
    void existsByNameKey_存在すればtrue() {
        // 食費カテゴリを保存する
        categoryRepository.save(new Category("食費"));

        // 同名の存在判定が true であることを検証する
        assertThat(existsByName("食費")).isTrue();
    }

    // existsByNameKey: 同名が無ければ false を返すことを検証する
    @Test
    void existsByNameKey_無ければfalse() {
        // 何も保存していない名前の存在判定が false であることを検証する
        assertThat(existsByName("娯楽費")).isFalse();
    }

    // existsByNameKey: 大文字小文字だけが異なる名前も重複として true を返すことを検証する
    // (レビュー指摘: "Travel" と "travel" が別カテゴリとして重複チェックをすり抜けていた問題の修正確認。
    //  現在は normalizeKey（Locale.ROOT 小文字化）で導出したキー同士の比較で同一視する)
    @Test
    void existsByNameKey_大文字小文字が異なっても重複としてtrue() {
        // 小文字始まりで "travel" カテゴリを保存する
        categoryRepository.save(new Category("travel"));

        // 大文字始まりの "Travel" で問い合わせても、キー比較で同一視され true であることを検証する
        assertThat(existsByName("Travel")).isTrue();
        // 全て大文字の "TRAVEL" でも同様に true であることを検証する
        assertThat(existsByName("TRAVEL")).isTrue();
    }

    // existsByNameKey: "ß"（ドイツ語エスツェット）と "SS" は重複扱いしないことを検証する。
    // 【なぜこのケースが重要か】以前の existsByNameIgnoreCase は SQL の UPPER(name)=UPPER(?) へ
    // 変換され、PostgreSQL では UPPER('ß')='SS' となるため "straße" と "STRASSE" を重複と
    // 判定していた。しかし一意制約の正体である nameKey 列は Java の Locale.ROOT 小文字化
    // （"ß"→"ß"、"SS"→"ss"）で導出されるため両者は別キーであり、事前チェックだけが
    // 「重複」と答えるのは制約との定義の食い違いだった。キー方式ではどちらも Java 基準に
    // 揃うため、重複扱いされない（＝両方登録できる）ことを確認する
    @Test
    void existsByNameKey_エスツェットとSSは重複扱いしない() {
        // エスツェット（ß）を含む "straße" カテゴリを保存する
        categoryRepository.save(new Category("straße"));

        // "STRASSE"（Java の小文字化では "strasse"）は別キーなので false であることを検証する
        assertThat(existsByName("STRASSE")).isFalse();
        // 同じ "straße"（大文字小文字違いの "STRAßE" も同キー）は true であることを検証する
        assertThat(existsByName("STRAßE")).isTrue();
    }

    // existsByNameKeyAndIdNot: 自分自身の ID を除外するので、自分の現在名では false を返すことを検証する
    // （更新時に「名前を変えていない」ケースを誤って重複と判定しないことの確認）
    @Test
    void existsByNameKeyAndIdNot_自分自身は除外されfalse() {
        // 食費カテゴリを保存する
        Category food = categoryRepository.save(new Category("食費"));

        // 自分自身の ID を除外した判定では、自分の名前と一致しても false であることを検証する
        assertThat(existsByNameExcluding("食費", food.getId())).isFalse();
    }

    // existsByNameKeyAndIdNot: 自分以外に同名カテゴリが存在すれば true を返すことを検証する
    @Test
    void existsByNameKeyAndIdNot_他人と同名ならtrue() {
        // 食費カテゴリを保存する
        categoryRepository.save(new Category("食費"));
        // 交通費カテゴリを保存する
        Category transport = categoryRepository.save(new Category("交通費"));

        // 交通費を食費へ改名しようとした場合、自分（transport）以外に食費が存在するため true であることを検証する
        assertThat(existsByNameExcluding("食費", transport.getId())).isTrue();
        // 交通費自身の ID を除外した判定では、自分の名前と一致しても false であることを検証する
        assertThat(existsByNameExcluding("交通費", transport.getId())).isFalse();
    }
}
