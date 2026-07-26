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

// カテゴリ更新リクエストを表す record（作成リクエストと API 契約を明示的に分離する。
// UpdateExpenseRequest と同様、現時点では CreateCategoryRequest と同じフィールド構成だが、
// 将来的に更新でのみ許可・禁止するフィールドを導入するときに独立した型として拡張できる）
public record UpdateCategoryRequest(

        // カテゴリ名（必須・最大50文字・制御文字禁止）
        @NotBlank(message = "must not be blank")
        // 最大文字数までに制限する（上限値はドメイン側の定数を参照し、{max} で文言へ埋め込む）
        @MaxCodePoints(max = Category.NAME_MAX_LENGTH, message = "must be at most {max} characters")
        // NUL 等の制御文字を禁止する（詳細は NoControlCharacters の Javadoc を参照）
        @NoControlCharacters(message = "must not contain control characters")
        String name
) {
    // 正規コンストラクタで @NotBlank / @MaxCodePoints より先に正規化する
    // （CreateCategoryRequest と同じ理由。詳細はそちらのコメントおよび CategoryNameNormalizer の
    // Javadoc を参照）。strip() による Unicode 空白除去と NFC 正規化の両方を行うことで、
    // 実際に永続化される値と同じ文字列を @NotBlank / @MaxCodePoints が検証できるようにする。
    public UpdateCategoryRequest {
        // null はそのまま維持し、非 null なら前後の空白除去 + NFC 正規化を行う
        name = CategoryNameNormalizer.normalize(name);
    }
}
