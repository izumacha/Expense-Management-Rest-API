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
// 関連が初期化済み（ロード済み）かを判定する Hibernate ユーティリティ
import org.hibernate.Hibernate;
// ページ単位の取得結果を表す型
import org.springframework.data.domain.Page;
// ページ指定（ページ番号・件数）を生成する型
import org.springframework.data.domain.PageRequest;

// テスト前処理を宣言するアノテーション
import org.junit.jupiter.api.BeforeEach;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;
// 永続化コンテキストを直接操作するテスト用 EntityManager
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

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

    // 永続化コンテキストを空にして遅延ロードの差を顕在化させるために使う
    @Autowired
    private TestEntityManager entityManager;

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
        // 期間・カテゴリを指定せず（十分大きいページで）検索する
        Page<Expense> result = expenseRepository.search(null, null, null, PageRequest.of(0, 10));

        // 投入した 4 件すべてが返ることを検証する
        assertThat(result.getTotalElements()).isEqualTo(4);
        // 先頭が最新（7/1）であることを検証する（spentOn DESC）
        assertThat(result.getContent().get(0).getSpentOn()).isEqualTo(LocalDate.of(2026, 7, 1));
        // JOIN FETCH によりカテゴリが取得済みであることを検証する
        assertThat(result.getContent().get(0).getCategory().getName()).isNotNull();
    }

    // search: 月で絞り込むとその月の支出だけが返ることを検証する
    @Test
    void search_月で絞ると当月分のみ返る() {
        // 6 月の期間（6/1 以上 7/1 未満）で検索する
        Page<Expense> result = expenseRepository.search(
                // 開始日（月初・含む）
                LocalDate.of(2026, 6, 1),
                // 終了日（翌月初・含まない）
                LocalDate.of(2026, 7, 1),
                // カテゴリ指定なし
                null,
                // 十分大きいページ指定
                PageRequest.of(0, 10));

        // 6 月分の 3 件だけが返ることを検証する
        assertThat(result.getTotalElements()).isEqualTo(3);
        // すべて 6 月であることを検証する
        assertThat(result.getContent()).allMatch(e -> e.getSpentOn().getMonthValue() == 6);
    }

    // search: カテゴリで絞り込むとそのカテゴリの支出だけが返ることを検証する
    @Test
    void search_カテゴリで絞ると当該カテゴリのみ返る() {
        // 交通費カテゴリだけで検索する
        Page<Expense> result = expenseRepository.search(null, null, transport.getId(), PageRequest.of(0, 10));

        // 交通費の 1 件だけが返ることを検証する
        assertThat(result.getTotalElements()).isEqualTo(1);
        // そのカテゴリ名が交通費であることを検証する
        assertThat(result.getContent().get(0).getCategory().getName()).isEqualTo("交通費");
    }

    // search: ページサイズを指定すると件数が上限で制限され、総件数とページ数が正しいことを検証する
    @Test
    void search_ページサイズで件数が制限される() {
        // 1 ページ 2 件で先頭ページを検索する
        Page<Expense> result = expenseRepository.search(null, null, null, PageRequest.of(0, 2));

        // 1 ページの件数が上限の 2 件であることを検証する
        assertThat(result.getContent()).hasSize(2);
        // 総件数は 4 件のままであることを検証する
        assertThat(result.getTotalElements()).isEqualTo(4);
        // 総ページ数が 2 ページであることを検証する
        assertThat(result.getTotalPages()).isEqualTo(2);
    }

    // findByIdWithCategory: JOIN FETCH で支出とカテゴリを1クエリで取得し、
    // 永続化コンテキストを空にした後でもカテゴリが初期化済み（N+1 が起きない）であることを検証する
    @Test
    void findByIdWithCategory_カテゴリを同時に取得しN1を避ける() {
        // 食費の支出を1件保存して採番する
        Expense saved = expenseRepository.save(expense(food, "1234", LocalDate.of(2026, 6, 20)));
        // 変更をDBへ反映し、永続化コンテキストを空にする（以降の取得で遅延ロードの差が出る）
        entityManager.flush();
        // 1次キャッシュを破棄して、取得時に本当にカテゴリまで取れているか確かめられるようにする
        entityManager.clear();

        // 詳細取得用のカテゴリ込みクエリで支出を取得する
        Expense found = expenseRepository.findByIdWithCategory(saved.getId()).orElseThrow();

        // JOIN FETCH により、追加クエリ無しでカテゴリが初期化済みであることを検証する
        assertThat(Hibernate.isInitialized(found.getCategory())).isTrue();
        // 取得したカテゴリ名が正しいことを検証する
        assertThat(found.getCategory().getName()).isEqualTo("食費");
    }

    // findByIdWithCategory: 該当が無ければ空の Optional を返すことを検証する
    @Test
    void findByIdWithCategory_不在なら空を返す() {
        // 存在しない ID（十分大きい値）で取得すると空が返ることを検証する
        assertThat(expenseRepository.findByIdWithCategory(999_999L)).isEmpty();
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

    // summarizeByCategory: 合計金額が同額のカテゴリ間ではカテゴリID昇順で安定した順序になることを検証する。
    // SUM(amount) だけを ORDER BY にすると、同額カテゴリ間の相対順序が実行のたびに変わりうる
    // （ページングや繰り返し呼び出しで表示順が入れ替わる非決定性の原因になる）。
    @Test
    void summarizeByCategory_合計が同額ならカテゴリID昇順で安定する() {
        // food/transport とは別に、8月の合計が完全に同額(300円)になる2カテゴリを用意する
        Category a = categoryRepository.save(new Category("娯楽費"));
        Category b = categoryRepository.save(new Category("日用品"));
        // 両カテゴリとも 8 月に 300 円ずつ支出する
        expenseRepository.save(expense(a, "300", LocalDate.of(2026, 8, 1)));
        expenseRepository.save(expense(b, "300", LocalDate.of(2026, 8, 2)));

        // 8 月の期間でカテゴリ別集計を取得する
        List<CategorySummary> result = expenseRepository.summarizeByCategory(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));

        // カテゴリは娯楽費・日用品の 2 件であることを検証する
        assertThat(result).hasSize(2);
        // 合計は同額(300)だが、カテゴリ ID の小さい方（先に保存した a）が先頭になることを検証する
        assertThat(result.get(0).categoryId()).isEqualTo(a.getId());
        assertThat(result.get(1).categoryId()).isEqualTo(b.getId());
    }

    // existsByCategoryId: 支出から参照されているカテゴリなら true を返すことを検証する
    @Test
    void existsByCategoryId_参照されていればtrue() {
        // setUp で食費カテゴリの支出を投入済みのため true であることを検証する
        assertThat(expenseRepository.existsByCategoryId(food.getId())).isTrue();
    }

    // existsByCategoryId: どの支出からも参照されていないカテゴリなら false を返すことを検証する
    @Test
    void existsByCategoryId_未参照ならfalse() {
        // setUp とは別に、支出を1件も紐づけていない娯楽費カテゴリを保存する
        Category entertainment = categoryRepository.save(new Category("娯楽費"));

        // 支出が無いカテゴリの参照判定が false であることを検証する
        assertThat(expenseRepository.existsByCategoryId(entertainment.getId())).isFalse();
    }
}
