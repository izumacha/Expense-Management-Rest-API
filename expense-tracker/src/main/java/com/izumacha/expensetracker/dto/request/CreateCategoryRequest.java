// リクエスト DTO のパッケージ
package com.izumacha.expensetracker.dto.request;

// 空文字を禁止するバリデーション
import jakarta.validation.constraints.NotBlank;
// 文字数を制限するバリデーション
import jakarta.validation.constraints.Size;

// カテゴリ作成リクエストを表す record
public record CreateCategoryRequest(

        // カテゴリ名（必須・最大50文字）
        @NotBlank(message = "must not be blank")
        // 最大50文字までに制限する
        @Size(max = 50, message = "must be at most 50 characters")
        String name
) {
}
