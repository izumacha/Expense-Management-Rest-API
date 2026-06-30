// サービスのテストパッケージ（本体と同じパッケージ階層に置く）
package com.izumacha.expensetracker.service;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// 支出エンティティを参照する
import com.izumacha.expensetracker.domain.Expense;
// 支出作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateExpenseRequest;
// 支出更新リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.UpdateExpenseRequest;
// カテゴリ別集計 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategorySummary;
// 支出返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.ExpenseResponse;
// 月次集計返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.SummaryResponse;
// クライアント起因の不正リクエスト例外を参照する
import com.izumacha.expensetracker.exception.InvalidRequestException;
// 未存在例外を参照する
import com.izumacha.expensetracker.exception.NotFoundException;
// カテゴリリポジトリを参照する
import com.izumacha.expensetracker.repository.CategoryRepository;
// 支出リポジトリを参照する
import com.izumacha.expensetracker.repository.ExpenseRepository;

// 10進数の金額型
import java.math.BigDecimal;
// 日付型
import java.time.LocalDate;
// 一覧の戻り型
import java.util.List;
// 値が無いことを表す Optional 型
import java.util.Optional;
// ページ単位の取得結果を表す型
import org.springframework.data.domain.Page;
// ページ指定（ページ番号・件数）を生成する型
import org.springframework.data.domain.PageRequest;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// Mockito を JUnit 5 と連携させる拡張
import org.junit.jupiter.api.extension.ExtendWith;
// モック対象を宣言するアノテーション
import org.mockito.InjectMocks;
// モックを生成するアノテーション
import org.mockito.Mock;
// 引数を捕捉するためのクラス
import org.mockito.ArgumentCaptor;
// Mockito の JUnit 5 拡張本体
import org.mockito.junit.jupiter.MockitoExtension;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
// 例外発生を検証する assertThatThrownBy を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThatThrownBy;
// any() マッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.any;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// 呼び出し検証の verify を取り込む（Mockito）
import static org.mockito.Mockito.verify;
// 呼び出されなかったことを検証する never を取り込む（Mockito）
import static org.mockito.Mockito.never;

// ExpenseService をモック依存だけでテストする（DB には接続しない）
@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    // 支出リポジトリのモック
    @Mock
    private ExpenseRepository expenseRepository;

    // カテゴリリポジトリのモック
    @Mock
    private CategoryRepository categoryRepository;

    // 上記モックを注入したテスト対象のサービス
    @InjectMocks
    private ExpenseService expenseService;

    // ID を持つカテゴリを組み立てるヘルパー
    private Category category(long id, String name) {
        // 名前を指定してカテゴリを生成する
        Category c = new Category(name);
        // 採番済みを模すため ID を設定する
        c.setId(id);
        // 組み立てたカテゴリを返す
        return c;
    }

    // ID・属性を持つ支出を組み立てるヘルパー
    private Expense expense(long id, Category category, String amount, LocalDate spentOn) {
        // 空の支出を生成する
        Expense e = new Expense();
        // ID を設定する
        e.setId(id);
        // カテゴリを設定する
        e.setCategory(category);
        // 金額を設定する（文字列から BigDecimal を生成）
        e.setAmount(new BigDecimal(amount));
        // 支出日を設定する
        e.setSpentOn(spentOn);
        // 組み立てた支出を返す
        return e;
    }

    // create: カテゴリが存在すれば保存され DTO が返ることを検証する
    @Test
    void create_カテゴリ存在時は保存して返す() {
        // 既存カテゴリ（食費）を用意する
        Category food = category(1L, "食費");
        // 作成リクエスト（1280円・食費・6/9）を用意する
        CreateExpenseRequest request = new CreateExpenseRequest(
                // 金額
                new BigDecimal("1280"),
                // カテゴリ ID
                1L,
                // 説明
                "ランチ",
                // 支出日
                LocalDate.of(2026, 6, 9));
        // カテゴリ検索が食費を返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(food));
        // 保存時は ID 採番済みの支出を返すようモックする
        when(expenseRepository.save(any(Expense.class)))
                // 採番後の支出（id=10）を返す
                .thenReturn(expense(10L, food, "1280", LocalDate.of(2026, 6, 9)));

        // テスト対象の create を呼び出す
        ExpenseResponse response = expenseService.create(request);

        // 返却 DTO の ID が採番値であることを検証する
        assertThat(response.id()).isEqualTo(10L);
        // 金額が一致することを検証する
        assertThat(response.amount()).isEqualByComparingTo("1280");
        // カテゴリ ID が一致することを検証する
        assertThat(response.categoryId()).isEqualTo(1L);
        // カテゴリ名が一致することを検証する
        assertThat(response.categoryName()).isEqualTo("食費");
    }

    // create: カテゴリが存在しなければ NotFoundException になることを検証する
    @Test
    void create_カテゴリ不在時は404例外() {
        // 作成リクエスト（存在しないカテゴリ 99）を用意する
        CreateExpenseRequest request = new CreateExpenseRequest(
                // 金額
                new BigDecimal("500"),
                // 存在しないカテゴリ ID
                99L,
                // 説明
                null,
                // 支出日
                LocalDate.of(2026, 6, 9));
        // カテゴリ検索が空を返すようモックする
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        // create 呼び出しで NotFoundException が投げられることを検証する
        assertThatThrownBy(() -> expenseService.create(request))
                // 例外型が NotFoundException であることを確認する
                .isInstanceOf(NotFoundException.class);
        // 保存が一度も呼ばれていないことを検証する
        verify(expenseRepository, never()).save(any());
    }

    // search: month=null のとき期間は null のままリポジトリへ渡されることを検証する
    @Test
    void search_月未指定なら期間nullで全件問い合わせ() {
        // リポジトリが空ページを返すようモックする
        when(expenseRepository.search(any(), any(), any(), any())).thenReturn(Page.empty());

        // month も categoryId も指定せず（既定ページで）検索する
        expenseService.search(null, null, PageRequest.of(0, 20));

        // 開始日を捕捉するキャプチャを用意する
        ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);
        // 終了日を捕捉するキャプチャを用意する
        ArgumentCaptor<LocalDate> endCaptor = ArgumentCaptor.forClass(LocalDate.class);
        // search 呼び出しの引数を捕捉する（カテゴリ ID とページ指定は任意一致）
        verify(expenseRepository).search(startCaptor.capture(), endCaptor.capture(), any(), any());
        // 開始日が null（無指定）であることを検証する
        assertThat(startCaptor.getValue()).isNull();
        // 終了日が null（無指定）であることを検証する
        assertThat(endCaptor.getValue()).isNull();
    }

    // search: month 指定時は月初〜翌月初の期間に変換されることを検証する
    @Test
    void search_月指定なら月初から翌月初の期間に変換() {
        // リポジトリが空ページを返すようモックする
        when(expenseRepository.search(any(), any(), any(), any())).thenReturn(Page.empty());

        // 2026-06 を指定して（既定ページで）検索する
        expenseService.search("2026-06", 1L, PageRequest.of(0, 20));

        // 開始日を捕捉するキャプチャを用意する
        ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);
        // 終了日を捕捉するキャプチャを用意する
        ArgumentCaptor<LocalDate> endCaptor = ArgumentCaptor.forClass(LocalDate.class);
        // search 呼び出しの引数を捕捉する（カテゴリ ID とページ指定は任意一致）
        verify(expenseRepository).search(startCaptor.capture(), endCaptor.capture(), any(), any());
        // 開始日が月初（6/1）であることを検証する
        assertThat(startCaptor.getValue()).isEqualTo(LocalDate.of(2026, 6, 1));
        // 終了日が翌月初（7/1・含まない）であることを検証する
        assertThat(endCaptor.getValue()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    // search: 不正な月形式は InvalidRequestException（400 相当）になることを検証する
    @Test
    void search_不正な月形式は400例外() {
        // 不正な月でsearchを呼ぶと例外になることを検証する
        assertThatThrownBy(() -> expenseService.search("2026/06", null, PageRequest.of(0, 20)))
                // 例外型が InvalidRequestException であることを確認する
                .isInstanceOf(InvalidRequestException.class);
    }

    // findById: 存在しない ID は NotFoundException になることを検証する
    @Test
    void findById_不在時は404例外() {
        // 詳細取得はカテゴリ込みクエリを使うため、そちらが空を返すようモックする
        when(expenseRepository.findByIdWithCategory(123L)).thenReturn(Optional.empty());

        // findById 呼び出しで例外になることを検証する
        assertThatThrownBy(() -> expenseService.findById(123L))
                // 例外型が NotFoundException であることを確認する
                .isInstanceOf(NotFoundException.class);
    }

    // findById: 存在する ID はカテゴリ込みクエリで取得され DTO が返ることを検証する（N+1 回避の経路）
    @Test
    void findById_存在時はカテゴリ込みで取得して返す() {
        // 既存カテゴリ（食費）を用意する
        Category food = category(1L, "食費");
        // 詳細取得はカテゴリ込みクエリを使うため、そちらが既存支出を返すようモックする
        when(expenseRepository.findByIdWithCategory(10L))
                // 採番済みの支出（id=10・食費・1280円）を返す
                .thenReturn(Optional.of(expense(10L, food, "1280", LocalDate.of(2026, 6, 9))));

        // テスト対象の findById を呼び出す
        ExpenseResponse response = expenseService.findById(10L);

        // 返却 DTO の ID が一致することを検証する
        assertThat(response.id()).isEqualTo(10L);
        // カテゴリ名が取得できていることを検証する（カテゴリ込み取得の確認）
        assertThat(response.categoryName()).isEqualTo("食費");
    }

    // update: 支出もカテゴリも存在すれば更新され DTO が返ることを検証する
    @Test
    void update_対象とカテゴリ存在時は更新して返す() {
        // 既存カテゴリ（交通費）を用意する
        Category transport = category(2L, "交通費");
        // 既存の支出（id=5）を用意する
        Expense existing = expense(5L, transport, "300", LocalDate.of(2026, 6, 1));
        // 更新リクエスト（金額と日付を変更）を用意する
        UpdateExpenseRequest request = new UpdateExpenseRequest(
                // 新しい金額
                new BigDecimal("480"),
                // カテゴリ ID
                2L,
                // 説明
                "バス",
                // 新しい支出日
                LocalDate.of(2026, 6, 5));
        // 支出検索が既存支出を返すようモックする
        when(expenseRepository.findById(5L)).thenReturn(Optional.of(existing));
        // カテゴリ検索が交通費を返すようモックする
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(transport));
        // 保存は渡された支出をそのまま返すようモックする
        when(expenseRepository.save(any(Expense.class)))
                // save に渡された引数（更新後の支出）を返す
                .thenAnswer(invocation -> invocation.getArgument(0));

        // テスト対象の update を呼び出す
        ExpenseResponse response = expenseService.update(5L, request);

        // 金額が新しい値に更新されたことを検証する
        assertThat(response.amount()).isEqualByComparingTo("480");
        // 説明が更新されたことを検証する
        assertThat(response.description()).isEqualTo("バス");
        // 支出日が更新されたことを検証する
        assertThat(response.spentOn()).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    // update: 対象支出が存在しなければ NotFoundException になることを検証する
    @Test
    void update_対象不在時は404例外() {
        // 更新リクエストを用意する
        UpdateExpenseRequest request = new UpdateExpenseRequest(
                // 金額
                new BigDecimal("480"),
                // カテゴリ ID
                2L,
                // 説明
                null,
                // 支出日
                LocalDate.of(2026, 6, 5));
        // 支出検索が空を返すようモックする
        when(expenseRepository.findById(5L)).thenReturn(Optional.empty());

        // update 呼び出しで例外になることを検証する
        assertThatThrownBy(() -> expenseService.update(5L, request))
                // 例外型が NotFoundException であることを確認する
                .isInstanceOf(NotFoundException.class);
    }

    // delete: 存在すれば repository.delete が呼ばれることを検証する
    @Test
    void delete_存在時は削除を呼ぶ() {
        // 既存カテゴリを用意する
        Category food = category(1L, "食費");
        // 既存支出（id=7）を用意する
        Expense existing = expense(7L, food, "1000", LocalDate.of(2026, 6, 2));
        // 支出検索が既存支出を返すようモックする
        when(expenseRepository.findById(7L)).thenReturn(Optional.of(existing));

        // テスト対象の delete を呼び出す
        expenseService.delete(7L);

        // 取得した支出で delete が呼ばれたことを検証する
        verify(expenseRepository).delete(existing);
    }

    // delete: 存在しなければ NotFoundException になることを検証する
    @Test
    void delete_不在時は404例外() {
        // 支出検索が空を返すようモックする
        when(expenseRepository.findById(7L)).thenReturn(Optional.empty());

        // delete 呼び出しで例外になることを検証する
        assertThatThrownBy(() -> expenseService.delete(7L))
                // 例外型が NotFoundException であることを確認する
                .isInstanceOf(NotFoundException.class);
    }

    // summary: カテゴリ別合計を足し上げて総合計が算出されることを検証する
    @Test
    void summary_カテゴリ別合計の総和を返す() {
        // カテゴリ別集計（食費1500・交通費500）をモック結果として用意する
        List<CategorySummary> byCategory = List.of(
                // 食費の合計行
                new CategorySummary(1L, "食費", new BigDecimal("1500")),
                // 交通費の合計行
                new CategorySummary(2L, "交通費", new BigDecimal("500")));
        // 集計クエリが上記結果を返すようモックする
        when(expenseRepository.summarizeByCategory(any(), any())).thenReturn(byCategory);

        // 2026-06 の集計を取得する
        SummaryResponse response = expenseService.summary("2026-06");

        // 月がそのまま返ることを検証する
        assertThat(response.month()).isEqualTo("2026-06");
        // 総合計が 1500+500=2000 であることを検証する
        assertThat(response.total()).isEqualByComparingTo("2000");
        // カテゴリ別の件数が 2 件であることを検証する
        assertThat(response.byCategory()).hasSize(2);
    }

    // summary: 不正な月形式は InvalidRequestException（400 相当）になることを検証する
    @Test
    void summary_不正な月形式は400例外() {
        // 不正な月で summary を呼ぶと例外になることを検証する
        assertThatThrownBy(() -> expenseService.summary("June"))
                // 例外型が InvalidRequestException であることを確認する
                .isInstanceOf(InvalidRequestException.class);
    }
}
