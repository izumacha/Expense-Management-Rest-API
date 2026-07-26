// リクエスト DTO のパッケージ
package com.izumacha.expensetracker.dto.request;

// カテゴリ名の最大文字数定数（Category.NAME_MAX_LENGTH）を参照する
import com.izumacha.expensetracker.domain.Category;
// 空文字を禁止するバリデーション
import jakarta.validation.constraints.NotBlank;
// コードポイント数で文字数を制限するバリデーション（標準の @Size は UTF-16 コード単位数で
// 数えるため、絵文字等のサロゲートペア文字が2とカウントされ DB の varchar(n) の実際の基準
// （コードポイント基準）とずれる。MaxCodePoints.java の Javadoc を参照）
import com.izumacha.expensetracker.validation.MaxCodePoints;
// カテゴリ名の正規化（前後空白除去 + NFC正規化）を参照する
import com.izumacha.expensetracker.validation.CategoryNameNormalizer;
// 制御文字（NUL 等）を含まないことを検証するバリデーション（PostgreSQL は NUL を text 列に
// 保存できず DB 層で初めてエラーになり、誤った 404/500 に化けるため入力段階で 400 として弾く）
import com.izumacha.expensetracker.validation.NoControlCharacters;

// カテゴリ作成リクエストを表す record
public record CreateCategoryRequest(

        // カテゴリ名（必須・最大50文字・制御文字禁止）
        @NotBlank(message = "must not be blank")
        // 最大文字数までに制限する（上限値はドメイン側の定数を参照し、{max} で文言へ埋め込む）
        @MaxCodePoints(max = Category.NAME_MAX_LENGTH, message = "must be at most {max} characters")
        // NUL 等の制御文字を禁止する（詳細は NoControlCharacters の Javadoc を参照）
        @NoControlCharacters(message = "must not contain control characters")
        String name
) {
    // 正規コンストラクタで @NotBlank / @MaxCodePoints より先に正規化する。
    // 1. Bean Validation の @NotBlank は ASCII 空白のみを trim() で除去するため、全角スペース
    //    （U+3000）等の Unicode 空白だけの値は「空白でない」と誤判定されてしまう。
    // 2. NFD 分解済み表現（濁点付き仮名等が基底文字＋結合文字の複数コードポイントに分解された
    //    表現）は NFC 合成表現よりコードポイント数が多くなりやすいため、正規化前の生入力のまま
    //    @MaxCodePoints を検証すると、NFC 合成後は上限内に収まるはずの名前を誤って 400 で
    //    拒否してしまう（サービス層の CategoryService.normalizeName() が防いでいたのは
    //    「正規化で伸びて上限を超える」逆方向のケースのみで、この方向は未対応だった）。
    // どちらも CategoryNameNormalizer（strip + NFC 正規化）で解決し、実際に永続化される値と
    // 同じ文字列を @NotBlank / @MaxCodePoints が検証できるようにする。
    public CreateCategoryRequest {
        // null はそのまま維持し、非 null なら前後の空白除去 + NFC 正規化を行う
        name = CategoryNameNormalizer.normalize(name);
    }
}
