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

        // カテゴリ別の集計一覧（上限件数まで。上限は app.summary.max-categories で設定する）
        List<CategorySummary> byCategory,

        // byCategory が上限件数で打ち切られたか（true なら内訳に載っていないカテゴリが存在する）。
        // total は打ち切りに関係なく常にその月のすべての支出の合計なので、打ち切りが起きると
        // 「byCategory[].total の足し上げ < total」になる。この差がバグではなく仕様上の打ち切りで
        // あることをクライアントが判別できるよう、真偽値として明示的に返す（§9 の
        // 「壊れたデータを黙って返さない」＝欠落を隠さず伝える方針）。
        boolean byCategoryTruncated
) {
}
