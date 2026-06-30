// リクエスト DTO のパッケージ
package com.izumacha.expensetracker.dto.request;

// 0より大きいことを検証するバリデーション
import jakarta.validation.constraints.DecimalMin;
// 整数部・小数部の桁数を制限するバリデーション
import jakarta.validation.constraints.Digits;
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

// 支出更新リクエストを表す record（作成リクエストと API 契約を明示的に分離する）
// 現時点では CreateExpenseRequest と同じフィールド構成だが、
// 将来的に更新で許可しないフィールド（例: カテゴリ固定）や
// 部分更新（null = 変更なし）を導入するときに独立した型として拡張できる
public record UpdateExpenseRequest(

        // 金額（必須・0より大きい・桁数は DB 列 precision=19/scale=2 に合わせる）
        @NotNull(message = "must not be null")
        // 0 を含まず 0 より大きい値のみ許可する
        @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        // 整数部 17 桁・小数部 2 桁までに制限する（DB 列精度との不整合や巨大値を防ぐ）
        @Digits(integer = 17, fraction = 2, message = "must have at most 17 integer digits and 2 fraction digits")
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
