// バリデーション関連のテストパッケージ（対象クラスと同じパッケージに置く）
package com.izumacha.expensetracker.validation;

// Unicode 正規化フォームを直接扱うため（NFD 分解済み入力を組み立てるのに使う）
import java.text.Normalizer;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// CategoryNameNormalizer.normalize() の空白除去・Unicode 正規化(NFD→NFC)を検証する。
// CategoryService/CreateCategoryRequest 経由の間接テストはあるが、この正規化ロジック自体を
// 直接ピン留めするテストが無かったため追加する（共通規約 §11）。
class CategoryNameNormalizerTest {

    // null はそのまま null で返し、@NotBlank 側の判定に委ねることを検証する
    @Test
    void normalize_nullはそのままnullを返す() {
        // null を渡すと null が返ることを確認する
        assertThat(CategoryNameNormalizer.normalize(null)).isNull();
    }

    // 前後の空白（半角）を取り除くことを検証する
    @Test
    void normalize_前後の半角空白を除去する() {
        // 前後に半角空白を含む名前を正規化すると、空白が取り除かれることを確認する
        assertThat(CategoryNameNormalizer.normalize(" 食費 ")).isEqualTo("食費");
    }

    // 前後の空白（Unicode の全角スペース等）も strip() により取り除かれることを検証する
    @Test
    void normalize_前後の全角空白も除去する() {
        // 全角スペース(U+3000)を前後に含む名前を正規化すると、空白が取り除かれることを確認する
        assertThat(CategoryNameNormalizer.normalize("　食費　")).isEqualTo("食費");
    }

    // NFD（基底文字+結合文字に分解された表現）の入力が NFC（合成済み表現）へ正規化され、
    // 見た目が同じ NFC 入力と同一の文字列になることを検証する。CategoryService の重複チェックが
    // "見た目が同じ名前は同一視する" 前提に立っているため、この正規化が実際に効いていることの
    // ピン留め（クラス Javadoc が説明する動機の直接検証）
    @Test
    void normalize_NFD分解表現をNFC合成表現へ変換する() {
        // "が"（濁点付きかな）の NFC 合成済み表現
        String nfc = "が";
        // 同じ文字の NFD 分解済み表現（基底文字「か」+結合文字「濁点」の2コードポイント）を
        // NFC からあえて作り直して用意する
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        // 前提確認: NFD表現はNFC表現よりコードポイント数が多いこと(このテストの意味を保証する)
        assertThat(nfd.codePointCount(0, nfd.length()))
                .isGreaterThan(nfc.codePointCount(0, nfc.length()));

        // NFD分解表現を正規化すると、見た目が同じNFC合成表現と文字列として完全一致することを確認する
        assertThat(CategoryNameNormalizer.normalize(nfd)).isEqualTo(nfc);
    }

    // 空白除去と NFC 正規化が両方同時に効くことを検証する（実際の入力経路に近い複合ケース）
    @Test
    void normalize_空白除去とNFC正規化を同時に行う() {
        // "が"のNFD分解表現の前後に空白を付けた入力を用意する
        String nfd = Normalizer.normalize("が", Normalizer.Form.NFD);
        String rawInput = "  " + nfd + "  ";

        // 空白除去とNFC正規化の両方が適用され、"が"(NFC合成表現)のみが残ることを確認する
        assertThat(CategoryNameNormalizer.normalize(rawInput)).isEqualTo("が");
    }
}
