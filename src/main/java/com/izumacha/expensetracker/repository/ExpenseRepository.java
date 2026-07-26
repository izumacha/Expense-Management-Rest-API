// リポジトリパッケージ
package com.izumacha.expensetracker.repository;

// 支出エンティティを参照する
import com.izumacha.expensetracker.domain.Expense;
// カテゴリ別集計 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategorySummary;
// 合計金額の戻り型（10進数の金額型）
import java.math.BigDecimal;
// 日付型
import java.time.LocalDate;
// 一覧の戻り型
import java.util.List;
// 値が無いことを表す Optional 型
import java.util.Optional;
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

    // 1件の詳細取得用に、カテゴリを JOIN FETCH で同時取得するクエリ。
    // Expense.category は遅延ロード（FetchType.LAZY）のため、既定の findById だけでは
    // ExpenseResponse 生成時のカテゴリ参照で追加クエリが走り N+1 になる。
    // ここで多対1のカテゴリを一緒に取得し、詳細取得を1クエリに収める（共通規約 §8）。
    @Query("""
            SELECT e FROM Expense e
            JOIN FETCH e.category
            WHERE e.id = :id
            """)
    Optional<Expense> findByIdWithCategory(@Param("id") Long id);

    // 指定カテゴリを参照する支出が1件でも存在するか判定する（カテゴリ削除時の使用中チェック用）
    boolean existsByCategoryId(Long categoryId);

    // 月次のカテゴリ別合計を GROUP BY で集計するクエリ。
    // GET /api/expenses/summary は唯一「一覧形状なのに件数上限が無い」応答だったため、
    // Pageable で上限を掛けて無制限取得を防ぐ（共通規約 §8/§9。上限値は ExpenseService 側の定数）。
    // 並び順は JPQL の ORDER BY（合計降順→カテゴリID昇順）で固定するため、渡す Pageable は
    // 並び順なし（先頭ページ＋件数のみ）とする。LIMIT は Spring Data JPA が Pageable から
    // プロバイダ非依存に適用するので、JPQL に DB 固有の構文は持ち込まない。
    @Query("""
            SELECT new com.izumacha.expensetracker.dto.response.CategorySummary(
                e.category.id, e.category.name, SUM(e.amount))
            FROM Expense e
            WHERE e.spentOn >= :start AND e.spentOn < :end
            GROUP BY e.category.id, e.category.name
            ORDER BY SUM(e.amount) DESC, e.category.id ASC
            """)
    List<CategorySummary> summarizeByCategory(
            // 期間の開始日（月初・含む）
            @Param("start") LocalDate start,
            // 期間の終了日（翌月初・含まない）
            @Param("end") LocalDate end,
            // 返す最大件数を制限するページ指定（先頭ページ＋上限件数のみを想定）
            Pageable pageable
    );

    // 期間内の支出の総合計を求めるクエリ（期間内に支出が無い場合は SUM の仕様どおり null が返る）。
    // summary の total は byCategory（上限件数で打ち切られうる）の足し上げではなくこの月全体の
    // SUM を使うことで、打ち切りが起きても常に「その月の総合計」であり続ける。
    @Query("""
            SELECT SUM(e.amount) FROM Expense e
            WHERE e.spentOn >= :start AND e.spentOn < :end
            """)
    BigDecimal sumAmount(
            // 期間の開始日（月初・含む）
            @Param("start") LocalDate start,
            // 期間の終了日（翌月初・含まない）
            @Param("end") LocalDate end
    );
}
