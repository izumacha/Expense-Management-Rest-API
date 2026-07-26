// ドメインエンティティのテストパッケージ
package com.izumacha.expensetracker.domain;

// Unicode 正規化フォームを直接扱うため（NFD 分解済み入力を組み立てるのに使う）
import java.text.Normalizer;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// Category エンティティの不変条件（名前の正規化・一意制約用キー nameKey の同期）を検証する
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

    // コンストラクタ: 一意制約用キー（nameKey）が小文字化された値で同期されることを検証する。
    // "Travel"/"travel" の同時作成レースを DB の一意制約で弾くための最終防波堤の前提となる同期
    @Test
    void コンストラクタ_正規化キーを小文字で同期する() {
        // 大文字を含む名前でカテゴリを生成する
        Category category = new Category("Travel");

        // 表示名は入力どおり（大文字小文字を保持）であることを検証する
        assertThat(category.getName()).isEqualTo("Travel");
        // 一意制約用キーは小文字化されていることを検証する
        assertThat(category.getNameKey()).isEqualTo("travel");
    }

    // setName: 名前の変更時も一意制約用キーが同期して更新されることを検証する（同期漏れの防止）
    @Test
    void setName_名前変更時も正規化キーを同期する() {
        // 初期名でカテゴリを生成する
        Category category = new Category("食費");

        // 名前を大文字を含む別の名前へ変更する
        category.setName("TRAVEL");

        // 表示名は変更後の値（大文字小文字を保持）であることを検証する
        assertThat(category.getName()).isEqualTo("TRAVEL");
        // 一意制約用キーが変更後の名前の小文字化された値へ同期していることを検証する
        assertThat(category.getNameKey()).isEqualTo("travel");
    }

    // setName: NFD（分解済み表現）の名前でも一意制約用キーは NFC（合成済み表現）へ揃えられることを
    // 検証する。見た目が同じで符号化だけが異なる名前が別キーとして一意制約をすり抜けないための同期
    @Test
    void setName_NFD分解表現でも正規化キーはNFC合成表現になる() {
        // "が"（濁点付きかな）の NFD 分解済み表現（基底文字+結合文字の2コードポイント）を用意する
        String nfd = Normalizer.normalize("が", Normalizer.Form.NFD);
        // NFD 表現の名前でカテゴリを生成する
        Category category = new Category(nfd);

        // 一意制約用キーが NFC 合成済み表現の "が" と完全一致することを検証する
        assertThat(category.getNameKey()).isEqualTo("が");
    }

    // コンストラクタ: null の名前では一意制約用キーも null のまま（NullPointerException を起こさない）
    // ことを検証する（@NotBlank による検証は DTO 層の責務のため、ここでは落ちないことだけを保証する）
    @Test
    void コンストラクタ_nullの名前は正規化キーもnull() {
        // null の名前でカテゴリを生成する
        Category category = new Category(null);

        // 名前が null のままであることを検証する
        assertThat(category.getName()).isNull();
        // 一意制約用キーも null のままであることを検証する
        assertThat(category.getNameKey()).isNull();
    }
}
