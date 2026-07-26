// レスポンス DTO のパッケージ
package com.izumacha.expensetracker.dto.response;

// 支出エンティティを参照する
import com.izumacha.expensetracker.domain.Expense;
// 10進数の金額型
import java.math.BigDecimal;
// 日付型
import java.time.LocalDate;
// 日時型
import java.time.LocalDateTime;

// 支出の返却用 DTO を表す record
public record ExpenseResponse(

        // 支出 ID
        Long id,

        // 金額
        BigDecimal amount,

        // カテゴリ ID
        Long categoryId,

        // カテゴリ名
        String categoryName,

        // 説明
        String description,

        // 支出日
        LocalDate spentOn,

        // 作成日時
        LocalDateTime createdAt
) {

    // エンティティから DTO を生成する静的ファクトリ
    public static ExpenseResponse from(Expense expense) {
        // 各フィールドを取り出して DTO を組み立てる
        return new ExpenseResponse(
                // 支出 ID
                expense.getId(),
                // 金額
                expense.getAmount(),
                // 紐づくカテゴリの ID
                expense.getCategory().getId(),
                // 紐づくカテゴリの名前
                expense.getCategory().getName(),
                // 説明
                expense.getDescription(),
                // 支出日
                expense.getSpentOn(),
                // 作成日時
                expense.getCreatedAt()
        );
    }
}
