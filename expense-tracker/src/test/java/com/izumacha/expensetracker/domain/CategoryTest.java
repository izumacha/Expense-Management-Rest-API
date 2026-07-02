// ドメインエンティティのテストパッケージ
package com.izumacha.expensetracker.domain;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// Category エンティティの不変条件（名前の正規化）を検証する
class CategoryTest {

    // コンストラクタ: 前後の空白を取り除いてから名前を保持することを検証する
    @Test
    void コンストラクタ_前後の空白を取り除く() {
        // 前後に空白を含む名前でカテゴリを生成する
        Category category = new Category(" 食費 ");

        // 空白が取り除かれた名前になっていることを検証する
        assertThat(category.getName()).isEqualTo("食費");
    }

    // コンストラクタ: 前後の空白が無い名前はそのまま保持されることを検証する
    @Test
    void コンストラクタ_空白が無ければそのまま保持する() {
        // 空白を含まない名前でカテゴリを生成する
        Category category = new Category("食費");

        // 名前がそのまま保持されていることを検証する
        assertThat(category.getName()).isEqualTo("食費");
    }
}
