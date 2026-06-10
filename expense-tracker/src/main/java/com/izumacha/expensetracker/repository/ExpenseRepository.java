// リポジトリパッケージ
package com.izumacha.expensetracker.repository;

// 支出エンティティを参照する
import com.izumacha.expensetracker.domain.Expense;
// カテゴリ別集計 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategorySummary;
// 日付型
import java.time.LocalDate;
// 一覧の戻り型
import java.util.List;
// CRUD を提供する基底インタフェース
import org.springframework.data.jpa.repository.JpaRepository;
// JPQL を記述するためのアノテーション
import org.springframework.data.jpa.repository.Query;
// クエリパラメータをバインドするアノテーション
import org.springframework.data.repository.query.Param;

// 支出の永続化を担うリポジトリ
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    // 期間とカテゴリ（いずれも任意）で支出を絞り込む一覧クエリ
    // CAST(... AS 型) は IS NULL 側のパラメータに明示的な型を与えるための対策。
    // PostgreSQL では「IS NULL でしか使われないバインド変数」は型を推論できず
    // "could not determine data type" エラーになるため、エンティティ属性の型でキャストする。
    @Query("""
            SELECT e FROM Expense e
            JOIN FETCH e.category
            WHERE (CAST(:start AS LocalDate) IS NULL OR e.spentOn >= :start)
              AND (CAST(:end AS LocalDate) IS NULL OR e.spentOn < :end)
              AND (CAST(:categoryId AS Long) IS NULL OR e.category.id = :categoryId)
            ORDER BY e.spentOn DESC, e.id DESC
            """)
    List<Expense> search(
            // 期間の開始日（月初・含む）
            @Param("start") LocalDate start,
            // 期間の終了日（翌月初・含まない）
            @Param("end") LocalDate end,
            // 絞り込み対象のカテゴリ ID
            @Param("categoryId") Long categoryId
    );

    // 月次のカテゴリ別合計を GROUP BY で集計するクエリ
    @Query("""
            SELECT new com.izumacha.expensetracker.dto.response.CategorySummary(
                e.category.id, e.category.name, SUM(e.amount))
            FROM Expense e
            WHERE e.spentOn >= :start AND e.spentOn < :end
            GROUP BY e.category.id, e.category.name
            ORDER BY SUM(e.amount) DESC
            """)
    List<CategorySummary> summarizeByCategory(
            // 期間の開始日（月初・含む）
            @Param("start") LocalDate start,
            // 期間の終了日（翌月初・含まない）
            @Param("end") LocalDate end
    );
}
