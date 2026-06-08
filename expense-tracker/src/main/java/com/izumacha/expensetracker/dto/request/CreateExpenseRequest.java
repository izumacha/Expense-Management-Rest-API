// リクエスト DTO のパッケージ
package com.izumacha.expensetracker.dto.request;

// 0より大きいことを検証するバリデーション
import jakarta.validation.constraints.DecimalMin;
// null を禁止するバリデーション
import jakarta.validation.constraints.NotNull;
// 過去または当日であることを検証するバリデーション
import jakarta.validation.constraints.PastOrPresent;
// 文字数を制限するバリデーション
import jakarta.validation.constraints.Size;
// 10進数の金額型
import java.math.BigDecimal;
// 日付型
import java.time.LocalDate;

// 支出作成・更新リクエストを表す record
public record CreateExpenseRequest(

        // 金額（必須・0より大きい）
        @NotNull(message = "must not be null")
        // 0 を含まず 0 より大きい値のみ許可する
        @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        BigDecimal amount,

        // カテゴリ ID（必須・存在しなければサービス層で404）
        @NotNull(message = "must not be null")
        Long categoryId,

        // 説明（任意・最大255文字）
        @Size(max = 255, message = "must be at most 255 characters")
        String description,

        // 支出日（必須・未来日不可）
        @NotNull(message = "must not be null")
        // 過去または当日のみ許可する
        @PastOrPresent(message = "must not be a future date")
        LocalDate spentOn
) {
}
