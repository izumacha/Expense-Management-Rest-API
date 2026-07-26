// レスポンス DTO のパッケージ
package com.izumacha.expensetracker.dto.response;

// 10進数の合計値型
import java.math.BigDecimal;

// カテゴリ別集計の1行を表す record（JPQL の SELECT new で直接生成される）
public record CategorySummary(

        // カテゴリ ID
        Long categoryId,

        // カテゴリ名
        String categoryName,

        // そのカテゴリの金額合計
        BigDecimal total
) {
}
