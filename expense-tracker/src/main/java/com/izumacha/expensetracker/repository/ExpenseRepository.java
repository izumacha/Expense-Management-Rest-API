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
// ページ単位の取得結果を表す型
import org.springframework.data.domain.Page;
// ページ指定（ページ番号・件数）を表す型
import org.springframework.data.domain.Pageable;
// CRUD を提供する基底インタフェース
import org.springframework.data.jpa.repository.JpaRepository;
// JPQL を記述するためのアノテーション
import org.springframework.data.jpa.repository.Query;
// クエリパラメータをバインドするアノテーション
import org.springframework.data.repository.query.Param;

// 支出の永続化を担うリポジトリ
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    // 期間とカテゴリ（いずれも任意）で支出を絞り込み、ページ単位で取得する一覧クエリ
    // CAST(... AS 型) は IS NULL 側のパラメータに明示的な型を与えるための対策。
    // PostgreSQL では「IS NULL でしか使われないバインド変数」は型を推論できず
    // "could not determine data type" エラーになるため、エンティティ属性の型でキャストする。
    // JOIN FETCH e.category は多対1（to-one）のためページングで行が重複せず安全に LIMIT を適用できる。
    // 総件数は JOIN FETCH を含めない明示の countQuery で数える（FETCH を含む件数クエリは不正なため）。
    @Query(value = """
            SELECT e FROM Expense e
            JOIN FETCH e.category
            WHERE (CAST(:start AS LocalDate) IS NULL OR e.spentOn >= :start)
              AND (CAST(:end AS LocalDate) IS NULL OR e.spentOn < :end)
              AND (CAST(:categoryId AS Long) IS NULL OR e.category.id = :categoryId)
            ORDER BY e.spentOn DESC, e.id DESC
            """,
            countQuery = """
            SELECT COUNT(e) FROM Expense e
            WHERE (CAST(:start AS LocalDate) IS NULL OR e.spentOn >= :start)
              AND (CAST(:end AS LocalDate) IS NULL OR e.spentOn < :end)
              AND (CAST(:categoryId AS Long) IS NULL OR e.category.id = :categoryId)
            """)
    Page<Expense> search(
            // 期間の開始日（月初・含む）
            @Param("start") LocalDate start,
            // 期間の終了日（翌月初・含まない）
            @Param("end") LocalDate end,
            // 絞り込み対象のカテゴリ ID
            @Param("categoryId") Long categoryId,
            // ページ指定（ページ番号・件数）
            Pageable pageable
    );

    // 指定カテゴリに紐づく支出が存在するかを判定する（カテゴリ削除可否の判断に使う）
    boolean existsByCategoryId(Long categoryId);

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
