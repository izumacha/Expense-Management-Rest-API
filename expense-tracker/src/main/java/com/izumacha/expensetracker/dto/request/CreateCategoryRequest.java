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

// カテゴリ作成リクエストを表す record
public record CreateCategoryRequest(

        // カテゴリ名（必須・最大50文字）
        @NotBlank(message = "must not be blank")
        // 最大文字数までに制限する（上限値はドメイン側の定数を参照し、{max} で文言へ埋め込む）
        @MaxCodePoints(max = Category.NAME_MAX_LENGTH, message = "must be at most {max} characters")
        String name
) {
    // 正規コンストラクタで @NotBlank より先に正規化する。Bean Validation の @NotBlank は
    // ASCII 空白のみを trim() で除去するため、全角スペース（U+3000）等の Unicode 空白だけの
    // 値は「空白でない」と誤判定されてしまう。ここで strip()（Unicode 対応）しておくことで、
    // 実際に永続化される値と同じ文字列を @NotBlank / @Size が検証できるようにする。
    public CreateCategoryRequest {
        // null はそのまま維持し、非 null なら前後の空白（Unicode 対応）を取り除く
        name = (name == null) ? null : name.strip();
    }
}
