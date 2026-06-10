// リポジトリのテストパッケージ
package com.izumacha.expensetracker.repository;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// 支出エンティティを参照する
import com.izumacha.expensetracker.domain.Expense;
// カテゴリ別集計 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategorySummary;

// 10進数の金額型
import java.math.BigDecimal;
// 日付型
import java.time.LocalDate;
// 一覧の戻り型
import java.util.List;

// テスト前処理を宣言するアノテーション
import org.junit.jupiter.api.BeforeEach;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// ExpenseRepository のカスタムクエリを本物の PostgreSQL で検証する
class ExpenseRepositoryTest extends AbstractRepositoryTest {

    // テスト対象の支出リポジトリ
    @Autowired
    private ExpenseRepository expenseRepository;

    // 準備に使うカテゴリリポジトリ
    @Autowired
    private CategoryRepository categoryRepository;

    // 食費カテゴリ（各テストで採番される）
    private Category food;
    // 交通費カテゴリ（各テストで採番される）
    private Category transport;

    // 各テストの前にカテゴリと支出を投入する
    @BeforeEach
    void setUp() {
        // 食費カテゴリを保存して採番する
        food = categoryRepository.save(new Category("食費"));
        // 交通費カテゴリを保存して採番する
        transport = categoryRepository.save(new Category("交通費"));

        // 6/3 食費 1000 円を保存する
        expenseRepository.save(expense(food, "1000", LocalDate.of(2026, 6, 3)));
        // 6/10 食費 500 円を保存する
        expenseRepository.save(expense(food, "500", LocalDate.of(2026, 6, 10)));
        // 6/5 交通費 500 円を保存する
        expenseRepository.save(expense(transport, "500", LocalDate.of(2026, 6, 5)));
        // 7/1 食費 9999 円を保存する（6月の絞り込みに含まれない確認用）
        expenseRepository.save(expense(food, "9999", LocalDate.of(2026, 7, 1)));
    }

    // 支出エンティティを組み立てるヘルパー
    private Expense expense(Category category, String amount, LocalDate spentOn) {
        // 空の支出を生成する
        Expense e = new Expense();
        // カテゴリを設定する
        e.setCategory(category);
        // 金額を設定する
        e.setAmount(new BigDecimal(amount));
        // 支出日を設定する
        e.setSpentOn(spentOn);
        // 組み立てた支出を返す
        return e;
    }

    // search: 期間もカテゴリも無指定なら全件を新しい順で返すことを検証する
    @Test
    void search_無指定なら全件を日付降順で返す() {
        // 期間・カテゴリを指定せず検索する
        List<Expense> result = expenseRepository.search(null, null, null);

        // 投入した 4 件すべてが返ることを検証する
        assertThat(result).hasSize(4);
        // 先頭が最新（7/1）であることを検証する（spentOn DESC）
        assertThat(result.get(0).getSpentOn()).isEqualTo(LocalDate.of(2026, 7, 1));
        // JOIN FETCH によりカテゴリが取得済みであることを検証する
        assertThat(result.get(0).getCategory().getName()).isNotNull();
    }

    // search: 月で絞り込むとその月の支出だけが返ることを検証する
    @Test
    void search_月で絞ると当月分のみ返る() {
        // 6 月の期間（6/1 以上 7/1 未満）で検索する
        List<Expense> result = expenseRepository.search(
                // 開始日（月初・含む）
                LocalDate.of(2026, 6, 1),
                // 終了日（翌月初・含まない）
                LocalDate.of(2026, 7, 1),
                // カテゴリ指定なし
                null);

        // 6 月分の 3 件だけが返ることを検証する
        assertThat(result).hasSize(3);
        // すべて 6 月であることを検証する
        assertThat(result).allMatch(e -> e.getSpentOn().getMonthValue() == 6);
    }

    // search: カテゴリで絞り込むとそのカテゴリの支出だけが返ることを検証する
    @Test
    void search_カテゴリで絞ると当該カテゴリのみ返る() {
        // 交通費カテゴリだけで検索する
        List<Expense> result = expenseRepository.search(null, null, transport.getId());

        // 交通費の 1 件だけが返ることを検証する
        assertThat(result).hasSize(1);
        // そのカテゴリ名が交通費であることを検証する
        assertThat(result.get(0).getCategory().getName()).isEqualTo("交通費");
    }

    // summarizeByCategory: カテゴリ別合計を金額降順で返すことを検証する
    @Test
    void summarizeByCategory_カテゴリ別合計を金額降順で返す() {
        // 6 月の期間でカテゴリ別集計を取得する
        List<CategorySummary> result = expenseRepository.summarizeByCategory(
                // 開始日（月初・含む）
                LocalDate.of(2026, 6, 1),
                // 終了日（翌月初・含まない）
                LocalDate.of(2026, 7, 1));

        // カテゴリは食費・交通費の 2 件であることを検証する
        assertThat(result).hasSize(2);
        // 先頭は合計が大きい食費（1000+500=1500）であることを検証する
        assertThat(result.get(0).categoryName()).isEqualTo("食費");
        // 食費の合計が 1500 であることを検証する
        assertThat(result.get(0).total()).isEqualByComparingTo("1500");
        // 2 番目は交通費（500）であることを検証する
        assertThat(result.get(1).categoryName()).isEqualTo("交通費");
        // 交通費の合計が 500 であることを検証する
        assertThat(result.get(1).total()).isEqualByComparingTo("500");
    }
}
