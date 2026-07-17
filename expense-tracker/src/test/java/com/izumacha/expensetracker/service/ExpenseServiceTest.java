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
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
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
// DB 制約違反例外（保存時のカテゴリ消失レースを模擬するために使う）
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
// ページ単位の取得結果を表す型
import org.springframework.data.domain.Page;
// ページ指定（ページ番号・件数）を生成する型
import org.springframework.data.domain.PageRequest;
// ページ指定（ページ番号・件数）を表す型（summary の上限指定を捕捉する検証に使う）
import org.springframework.data.domain.Pageable;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
// Mockito を JUnit 5 と連携させる拡張
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.doThrow;

// ExpenseService をモック依存だけでテストする（DB には接続しない）
@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    // 支出リポジトリのモック
    @Mock
    private ExpenseRepository expenseRepository;

    // カテゴリリポジトリのモック
    @Mock
    private CategoryRepository categoryRepository;

    // テスト対象のサービス（summaryMaxCategories はプリミティブ int のため
    // Mockito の @InjectMocks ではモック注入できず、明示的にコンストラクタで組み立てる）
    private ExpenseService expenseService;

    // 各テスト前に、application.yml の既定値(100)と同じ上限でサービスを組み立てる
    @BeforeEach
    void setUp() {
        expenseService = new ExpenseService(expenseRepository, categoryRepository, 100);
    }

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
        // 保存時は ID 採番済みの支出を返すようモックする（saveOrThrowIfCategoryVanished は saveAndFlush を使う）
        when(expenseRepository.saveAndFlush(any(Expense.class)))
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

    // create: 小数桁不足の金額(10.5)を保存前に scale=2 (10.50)へ揃えることを検証する。
    // 揃えないと、作成直後のレスポンスだけ scale がずれ（"10.5"）、後続の GET
    // （DB の numeric(19,2) 由来）は "10.50" になり、同一リソースの表現が変わってしまう。
    @Test
    void create_小数桁不足の金額はscale2に揃えてから保存する() {
        // 既存カテゴリ（食費）を用意する
        Category food = category(1L, "食費");
        // 小数第1位までしかない金額（10.5）でリクエストを組み立てる
        CreateExpenseRequest request = new CreateExpenseRequest(
                new BigDecimal("10.5"), 1L, "コーヒー", LocalDate.of(2026, 6, 9));
        // カテゴリ検索が食費を返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(food));
        // 保存時は受け取った Expense をそのまま返す（実際の DB 挙動を模し、amount は呼び出し引数から検証する）
        when(expenseRepository.saveAndFlush(any(Expense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // テスト対象の create を呼び出す
        expenseService.create(request);

        // saveAndFlush に渡された Expense を捕捉し、amount の scale が 2 に揃っていることを検証する
        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("10.5");
        assertThat(captor.getValue().getAmount().scale()).isEqualTo(2);
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
        // 保存が一度も呼ばれていないことを検証する（saveOrThrowIfCategoryVanished は saveAndFlush を使う）
        verify(expenseRepository, never()).saveAndFlush(any());
    }

    // create: 存在チェック後にカテゴリが消えて保存が外部キー違反になった場合、500 ではなく 404 に変換されることを検証する
    @Test
    void create_保存時の外部キー制約違反は404例外に変換() {
        // 事前チェック時点では存在するカテゴリ（食費）を用意する
        Category food = category(1L, "食費");
        // 作成リクエスト（食費）を用意する
        CreateExpenseRequest request = new CreateExpenseRequest(
                // 金額
                new BigDecimal("1280"),
                // カテゴリ ID
                1L,
                // 説明
                "ランチ",
                // 支出日
                LocalDate.of(2026, 6, 9));
        // カテゴリ検索は成功する（事前チェックは通過する）ようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(food));
        // 保存時に別リクエストがカテゴリを削除したレースを模擬して外部キー違反を投げさせる
        // （saveOrThrowIfCategoryVanished は saveAndFlush を使う）
        when(expenseRepository.saveAndFlush(any(Expense.class)))
                // DB の制約違反例外を投げる
                .thenThrow(new DataIntegrityViolationException("fk violation on category_id"));

        // create 呼び出しが NotFoundException（404 相当）へ変換されることを検証する
        assertThatThrownBy(() -> expenseService.create(request))
                // 例外型が NotFoundException であることを確認する（500 ではない）
                .isInstanceOf(NotFoundException.class)
                // 安全な日本語文言（カテゴリ未存在）に変換されていることを確認する
                .hasMessage(ErrorMessages.CATEGORY_NOT_FOUND);
    }

    // create: 受け付け年範囲外の支出日は、DB へ渡す前に 400（InvalidRequestException）で弾かれ、
    // 保存時 catch の「カテゴリ消失」404 へ誤変換されないことを検証する
    @Test
    void create_範囲外の支出日は400例外で保存に到達しない() {
        // 事前チェックで存在するカテゴリ（食費）を用意する
        Category food = category(1L, "食費");
        // 支出日に PostgreSQL の date 範囲外の極端に古い年（-99999 年）を持つ作成リクエストを用意する
        CreateExpenseRequest request = new CreateExpenseRequest(
                // 金額
                new BigDecimal("1280"),
                // カテゴリ ID
                1L,
                // 説明
                "ランチ",
                // 範囲外の支出日（年 -99999）
                LocalDate.of(-99999, 1, 1));
        // カテゴリ検索は成功する（存在チェックは通過する）ようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(food));

        // create 呼び出しが 400（InvalidRequestException）になることを検証する（404 でも 500 でもない）
        assertThatThrownBy(() -> expenseService.create(request))
                // 例外型が InvalidRequestException であることを確認する
                .isInstanceOf(InvalidRequestException.class)
                // 安全な日本語文言（支出日が不正）であることを確認する
                .hasMessage(ErrorMessages.INVALID_SPENT_ON);
        // DB へ渡す前に弾くため、保存は一度も呼ばれていないことを検証する
        verify(expenseRepository, never()).saveAndFlush(any());
    }

    // update: 受け付け年範囲外の支出日は、DB へ渡す前に 400（InvalidRequestException）で弾かれることを検証する
    @Test
    void update_範囲外の支出日は400例外で保存に到達しない() {
        // 交通費カテゴリと既存支出（id=5）を用意する
        Category transport = category(2L, "交通費");
        // 既存の支出を用意する
        Expense existing = expense(5L, transport, "300", LocalDate.of(2026, 6, 1));
        // 支出日に範囲外の極端に古い年（-99999 年）を持つ更新リクエストを用意する
        UpdateExpenseRequest request = new UpdateExpenseRequest(
                // 新しい金額
                new BigDecimal("480"),
                // カテゴリ ID
                2L,
                // 説明
                "バス",
                // 範囲外の支出日（年 -99999）
                LocalDate.of(-99999, 1, 1));
        // 対象支出の取得は成功するようモックする
        when(expenseRepository.findById(5L)).thenReturn(Optional.of(existing));
        // カテゴリ取得も成功する（存在チェックは通過する）ようモックする
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(transport));

        // update 呼び出しが 400（InvalidRequestException）になることを検証する（404 でも 500 でもない）
        assertThatThrownBy(() -> expenseService.update(5L, request))
                // 例外型が InvalidRequestException であることを確認する
                .isInstanceOf(InvalidRequestException.class)
                // 安全な日本語文言（支出日が不正）であることを確認する
                .hasMessage(ErrorMessages.INVALID_SPENT_ON);
        // DB へ渡す前に弾くため、保存は一度も呼ばれていないことを検証する
        verify(expenseRepository, never()).saveAndFlush(any());
    }

    // update: 保存が外部キー違反になった場合も 500 ではなく 404 に変換されることを検証する
    @Test
    void update_保存時の外部キー制約違反は404例外に変換() {
        // 交通費カテゴリと既存支出（id=5）を用意する
        Category transport = category(2L, "交通費");
        // 既存の支出を用意する
        Expense existing = expense(5L, transport, "300", LocalDate.of(2026, 6, 1));
        // 更新リクエストを用意する
        UpdateExpenseRequest request = new UpdateExpenseRequest(
                // 新しい金額
                new BigDecimal("480"),
                // カテゴリ ID
                2L,
                // 説明
                "バス",
                // 新しい支出日
                LocalDate.of(2026, 6, 5));
        // 対象支出の取得は成功するようモックする
        when(expenseRepository.findById(5L)).thenReturn(Optional.of(existing));
        // カテゴリ取得も成功する（事前チェックは通過する）ようモックする
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(transport));
        // 保存時にカテゴリ削除レースを模擬して外部キー違反を投げさせる
        // （saveOrThrowIfCategoryVanished は saveAndFlush を使う。update は merge 経由で遅延flushされうるため、
        // この検証にこそ saveAndFlush への切り替えが本質的に必要）
        when(expenseRepository.saveAndFlush(any(Expense.class)))
                // DB の制約違反例外を投げる
                .thenThrow(new DataIntegrityViolationException("fk violation on category_id"));

        // update 呼び出しが NotFoundException（404 相当）へ変換されることを検証する
        assertThatThrownBy(() -> expenseService.update(5L, request))
                // 例外型が NotFoundException であることを確認する（500 ではない）
                .isInstanceOf(NotFoundException.class)
                // 安全な日本語文言（カテゴリ未存在）に変換されていることを確認する
                .hasMessage(ErrorMessages.CATEGORY_NOT_FOUND);
    }

    // update: 事前チェック後に更新対象の支出自体が同時実行で削除され、UPDATE の影響行数が0件
    // （楽観ロック例外）になった場合も 500 ではなく 404（支出未存在）に変換されることを検証する
    @Test
    void update_保存対象の支出自体が消えたレースは404例外に変換() {
        // 交通費カテゴリと既存支出（id=5）を用意する
        Category transport = category(2L, "交通費");
        // 既存の支出を用意する
        Expense existing = expense(5L, transport, "300", LocalDate.of(2026, 6, 1));
        // 更新リクエストを用意する
        UpdateExpenseRequest request = new UpdateExpenseRequest(
                // 新しい金額
                new BigDecimal("480"),
                // カテゴリ ID
                2L,
                // 説明
                "バス",
                // 新しい支出日
                LocalDate.of(2026, 6, 5));
        // 対象支出の取得は成功するようモックする（この時点ではまだ存在する）
        when(expenseRepository.findById(5L)).thenReturn(Optional.of(existing));
        // カテゴリ取得も成功する（事前チェックは通過する）ようモックする
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(transport));
        // 保存時に別リクエストが同じ支出を削除したレースを模擬して楽観ロック例外を投げさせる
        // （UPDATE の影響行数が0件だった場合に Hibernate/Spring が送出する例外）
        when(expenseRepository.saveAndFlush(any(Expense.class)))
                // 楽観ロックの行数不一致例外を投げる
                .thenThrow(new OptimisticLockingFailureException("0 rows affected"));

        // update 呼び出しが NotFoundException（404 相当）へ変換されることを検証する
        assertThatThrownBy(() -> expenseService.update(5L, request))
                // 例外型が NotFoundException であることを確認する（500 ではない）
                .isInstanceOf(NotFoundException.class)
                // 安全な日本語文言（支出未存在。カテゴリ未存在とは異なるメッセージ）に変換されていることを確認する
                .hasMessage(ErrorMessages.EXPENSE_NOT_FOUND);
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

    // search: 形式は正しいが範囲外（Year 上限を超える翌月初計算）の月も400例外になることを検証する。
    // "999999999-12" は YearMonth.parse は通るが plusMonths(1) で Year 上限を超えて DateTimeException を投げる。
    // parseMonth で捕捉できていないと未捕捉例外が catch-all に落ちて500になるため、その回帰を防ぐ。
    @Test
    void search_範囲外の月は400例外() {
        // 範囲外の月で search を呼ぶと InvalidRequestException（400 相当）になることを検証する
        assertThatThrownBy(() -> expenseService.search("999999999-12", null, PageRequest.of(0, 20)))
                // 例外型が InvalidRequestException であることを確認する（500 ではない）
                .isInstanceOf(InvalidRequestException.class);
    }

    // search: YearMonth.parse は通り plusMonths でもオーバーフローしないが、PostgreSQL の date 範囲
    // （西暦 5874897 まで）を超える年は DB で範囲外エラーになり 500 に落ちていた回帰を防ぐ。
    // "9999999-01"（7 桁の年）は plusMonths のオーバーフロー検証だけでは弾けないため、年範囲チェックで 400 にする。
    @Test
    void search_PostgreSQLのdate範囲を超える年は400例外() {
        // 7 桁の年で search を呼ぶと InvalidRequestException（400 相当）になることを検証する（DB へ渡さず 500 を防ぐ）
        assertThatThrownBy(() -> expenseService.search("9999999-01", null, PageRequest.of(0, 20)))
                // 例外型が InvalidRequestException であることを確認する（500 ではない）
                .isInstanceOf(InvalidRequestException.class);
        // 負の年も同様に 400 として弾かれることを検証する（PostgreSQL date の下限を下回るため）
        assertThatThrownBy(() -> expenseService.search("-1-01", null, PageRequest.of(0, 20)))
                // 例外型が InvalidRequestException であることを確認する（500 ではない）
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
        // 保存は渡された支出をそのまま返すようモックする（saveOrThrowIfCategoryVanished は saveAndFlush を使う）
        when(expenseRepository.saveAndFlush(any(Expense.class)))
                // saveAndFlush に渡された引数（更新後の支出）を返す
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

    // delete: 事前の存在チェック後、削除対象の支出自体が同時実行で既に削除され、
    // DELETE の影響行数が0件（楽観ロック例外）になった場合も 500 ではなく 404 に変換されることを検証する
    @Test
    void delete_削除対象が既に消えたレースは404例外に変換() {
        // 既存カテゴリを用意する
        Category food = category(1L, "食費");
        // 既存支出（id=7）を用意する（この時点ではまだ存在する）
        Expense existing = expense(7L, food, "1000", LocalDate.of(2026, 6, 2));
        // 支出検索が既存支出を返すようモックする
        when(expenseRepository.findById(7L)).thenReturn(Optional.of(existing));
        // flush() の時点で別リクエストが先に削除していたレースを模擬して楽観ロック例外を投げさせる
        // （CategoryService.delete() と同じく明示的な flush() でこの try 内での検知を保証する設計）
        doThrow(new OptimisticLockingFailureException("0 rows affected"))
                // flush 呼び出し時に例外を投げるようモックする
                .when(expenseRepository).flush();

        // delete 呼び出しが NotFoundException（404 相当）へ変換されることを検証する
        assertThatThrownBy(() -> expenseService.delete(7L))
                // 例外型が NotFoundException であることを確認する（500 ではない）
                .isInstanceOf(NotFoundException.class)
                // 安全な日本語文言（支出未存在）に変換されていることを確認する
                .hasMessage(ErrorMessages.EXPENSE_NOT_FOUND);
    }

    // summary: 総合計は月全体の SUM クエリから、内訳はカテゴリ別集計から組み立てられることを検証する
    @Test
    void summary_月全体の合計とカテゴリ別内訳を返す() {
        // カテゴリ別集計（食費1500・交通費500）をモック結果として用意する
        List<CategorySummary> byCategory = List.of(
                // 食費の合計行
                new CategorySummary(1L, "食費", new BigDecimal("1500")),
                // 交通費の合計行
                new CategorySummary(2L, "交通費", new BigDecimal("500")));
        // 集計クエリが上記結果を返すようモックする（第3引数は件数上限のページ指定）
        when(expenseRepository.summarizeByCategory(any(), any(), any())).thenReturn(byCategory);
        // 月全体の合計クエリが 2000 を返すようモックする
        when(expenseRepository.sumAmount(any(), any())).thenReturn(new BigDecimal("2000.00"));

        // 2026-06 の集計を取得する
        SummaryResponse response = expenseService.summary("2026-06");

        // 月がそのまま返ることを検証する
        assertThat(response.month()).isEqualTo("2026-06");
        // 総合計が月全体の SUM（2000）であることを検証する
        assertThat(response.total()).isEqualByComparingTo("2000");
        // 金額の小数桁が常に 2 桁へ揃えられていることを検証する（"2000.00"）
        assertThat(response.total().scale()).isEqualTo(2);
        // カテゴリ別の件数が 2 件であることを検証する
        assertThat(response.byCategory()).hasSize(2);
        // 内訳側の金額も総合計と同じく常に小数2桁へ正規化されることを検証する
        // (モックの入力は scale=0 の "1500"/"500" であり、DB の SUM 結果スケールが
        // 保存済み金額の scale=2 と食い違う場合に total と桁数がバラつく回帰を防ぐ)
        assertThat(response.byCategory().get(0).total()).isEqualByComparingTo("1500");
        assertThat(response.byCategory().get(0).total().scale()).isEqualTo(2);
        assertThat(response.byCategory().get(1).total()).isEqualByComparingTo("500");
        assertThat(response.byCategory().get(1).total().scale()).isEqualTo(2);
    }

    // summary: カテゴリ別内訳は上限件数（100件・先頭ページ）で打ち切られるページ指定で問い合わせられ、
    // 総合計は打ち切りの影響を受けない月全体の SUM を使うことを検証する（無制限取得の防止・共通規約 §8/§9）
    @Test
    void summary_内訳は上限100件で問い合わせ総合計は月全体のSUMを使う() {
        // 上限まで打ち切られた内訳を模すため、任意の1件だけを返すようモックする
        when(expenseRepository.summarizeByCategory(any(), any(), any()))
                // 内訳としては食費 1500 円の行だけが返る（打ち切り後の姿を模す）
                .thenReturn(List.of(new CategorySummary(1L, "食費", new BigDecimal("1500"))));
        // 月全体の合計は内訳の合計より大きい 9999.99 を返すようモックする（打ち切りに影響されない値）
        when(expenseRepository.sumAmount(any(), any())).thenReturn(new BigDecimal("9999.99"));

        // 2026-06 の集計を取得する
        SummaryResponse response = expenseService.summary("2026-06");

        // 総合計が内訳の足し上げ（1500）ではなく月全体の SUM（9999.99）であることを検証する
        assertThat(response.total()).isEqualByComparingTo("9999.99");

        // 集計クエリに渡されたページ指定を捕捉するキャプチャを用意する
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        // summarizeByCategory 呼び出しの第3引数（ページ指定）を捕捉する
        verify(expenseRepository).summarizeByCategory(any(), any(), pageableCaptor.capture());
        // 先頭ページ（0 ページ目）が指定されていることを検証する
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        // 件数上限が一覧 API と同じ 100 件であることを検証する
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    // summary: 支出が1件も無い月でも、総合計を小数2桁("0.00")で返し JSON の桁数を一定に保つことを検証する。
    // 月全体の SUM は該当行が無いと null を返すため、null をゼロへフォールバックできていないと
    // NullPointerException で 500 に落ちる。あわせて scale=0 の "0" に退行しないことも確認する。
    @Test
    void summary_支出の無い月でも合計は小数2桁のゼロを返す() {
        // 集計クエリが空リストを返すようモックする（対象月に支出が無い状態）
        when(expenseRepository.summarizeByCategory(any(), any(), any())).thenReturn(List.of());
        // 月全体の合計クエリは SUM の仕様どおり null を返すようモックする
        when(expenseRepository.sumAmount(any(), any())).thenReturn(null);

        // 2026-06 の集計を取得する
        SummaryResponse response = expenseService.summary("2026-06");

        // 総合計が値として 0 であることを検証する
        assertThat(response.total()).isEqualByComparingTo("0");
        // 小数桁が 2 桁（"0.00"）に揃っていることを検証する
        assertThat(response.total().scale()).isEqualTo(2);
        // カテゴリ別が空であることを検証する
        assertThat(response.byCategory()).isEmpty();
    }

    // summary: 不正な月形式は InvalidRequestException（400 相当）になることを検証する
    @Test
    void summary_不正な月形式は400例外() {
        // 不正な月で summary を呼ぶと例外になることを検証する
        assertThatThrownBy(() -> expenseService.summary("June"))
                // 例外型が InvalidRequestException であることを確認する
                .isInstanceOf(InvalidRequestException.class);
    }

    // summary: 形式は正しいが範囲外（Year 上限を超える翌月初計算）の月も400例外になることを検証する。
    // search 側と同じく、parseMonth が plusMonths のオーバーフローを捕捉できていないと500に落ちる回帰を防ぐ。
    @Test
    void summary_範囲外の月は400例外() {
        // 範囲外の月で summary を呼ぶと InvalidRequestException（400 相当）になることを検証する
        assertThatThrownBy(() -> expenseService.summary("999999999-12"))
                // 例外型が InvalidRequestException であることを確認する（500 ではない）
                .isInstanceOf(InvalidRequestException.class);
    }

    // summary: search 側と同じく、PostgreSQL の date 範囲を超える年（plusMonths ではオーバーフロー
    // しない 7 桁の年）が DB へ渡って 500 に落ちていた回帰を防ぐ。年範囲チェックで 400 にする。
    @Test
    void summary_PostgreSQLのdate範囲を超える年は400例外() {
        // 7 桁の年で summary を呼ぶと InvalidRequestException（400 相当）になることを検証する（DB へ渡さず 500 を防ぐ）
        assertThatThrownBy(() -> expenseService.summary("9999999-01"))
                // 例外型が InvalidRequestException であることを確認する（500 ではない）
                .isInstanceOf(InvalidRequestException.class);
    }
}
