// レスポンス DTO のパッケージ
package com.izumacha.expensetracker.dto.response;

// 10進数の合計値型
import java.math.BigDecimal;
// カテゴリ別集計のリストを保持する
import java.util.List;

// 月次集計の返却用 DTO を表す record
public record SummaryResponse(

        // 対象月（YYYY-MM 形式）
        String month,

        // 総合計
        BigDecimal total,

        // カテゴリ別の集計一覧
        List<CategorySummary> byCategory
) {
}
