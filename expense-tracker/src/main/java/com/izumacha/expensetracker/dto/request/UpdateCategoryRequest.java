// リクエスト DTO のパッケージ
package com.izumacha.expensetracker.dto.request;

// 空文字を禁止するバリデーション
import jakarta.validation.constraints.NotBlank;
// 文字数を制限するバリデーション
import jakarta.validation.constraints.Size;

// カテゴリ更新リクエストを表す record（作成リクエストと API 契約を明示的に分離する。
// UpdateExpenseRequest と同様、現時点では CreateCategoryRequest と同じフィールド構成だが、
// 将来的に更新でのみ許可・禁止するフィールドを導入するときに独立した型として拡張できる）
public record UpdateCategoryRequest(

        // カテゴリ名（必須・最大50文字）
        @NotBlank(message = "must not be blank")
        // 最大50文字までに制限する
        @Size(max = 50, message = "must be at most 50 characters")
        String name
) {
}
