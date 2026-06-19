// サービスパッケージ
package com.izumacha.expensetracker.service;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// 支出エンティティを参照する
import com.izumacha.expensetracker.domain.Expense;
// 支出作成・更新リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateExpenseRequest;
// カテゴリ別集計 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategorySummary;
// 支出返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.ExpenseResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
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
// 合計初期値に使う10進数型
import java.math.BigDecimal;
// 日付型
import java.time.LocalDate;
// 月（年月）を表す型
import java.time.YearMonth;
// 月のパース失敗を検出する例外
import java.time.format.DateTimeParseException;
// 一覧の戻り型
import java.util.List;
// ページ単位の取得結果を表す型
import org.springframework.data.domain.Page;
// ページ指定（ページ番号・件数）を表す型
import org.springframework.data.domain.Pageable;
// Spring のサービスコンポーネント宣言
import org.springframework.stereotype.Service;
// トランザクション境界の宣言
import org.springframework.transaction.annotation.Transactional;

// 支出に関する業務ロジックを担うサービス
@Service
public class ExpenseService {

    // 支出リポジトリへの参照
    private final ExpenseRepository expenseRepository;
    // カテゴリリポジトリへの参照
    private final CategoryRepository categoryRepository;

    // コンストラクタインジェクションで依存を受け取る
    public ExpenseService(ExpenseRepository expenseRepository, CategoryRepository categoryRepository) {
        // 支出リポジトリをフィールドに設定する
        this.expenseRepository = expenseRepository;
        // カテゴリリポジトリをフィールドに設定する
        this.categoryRepository = categoryRepository;
    }

    // 支出を新規登録する
    @Transactional
    public ExpenseResponse create(CreateExpenseRequest request) {
        // カテゴリ ID から対象カテゴリを取得する（無ければ404）
        Category category = findCategoryOrThrow(request.categoryId());
        // 空の支出エンティティを生成する
        Expense expense = new Expense();
        // リクエスト内容をエンティティへ反映する
        applyRequest(expense, request, category);
        // 保存して採番済みのインスタンスを取得する
        Expense saved = expenseRepository.save(expense);
        // 保存結果を DTO に変換して返す
        return ExpenseResponse.from(saved);
    }

    // 月とカテゴリ（いずれも任意）で支出一覧をページ単位で取得する
    @Transactional(readOnly = true)
    public PageResponse<ExpenseResponse> search(String month, Long categoryId, Pageable pageable) {
        // month が指定されていれば一度だけパースし、無ければ null とする
        YearMonth target = (month == null) ? null : parseMonth(month);
        // 月初を期間開始とする（month 未指定なら null）
        LocalDate start = (target == null) ? null : target.atDay(1);
        // 翌月初を期間終了とする（month 未指定なら null）
        LocalDate end = (target == null) ? null : target.plusMonths(1).atDay(1);
        // 条件で絞り込んだ支出をページ単位で取得する
        Page<ExpenseResponse> page = expenseRepository.search(start, end, categoryId, pageable)
                // 各エンティティを DTO へ変換する（ページ情報は維持する）
                .map(ExpenseResponse::from);
        // ページ情報を安定した契約の DTO へ詰め替えて返す
        return PageResponse.from(page);
    }

    // 支出を1件取得する
    @Transactional(readOnly = true)
    public ExpenseResponse findById(Long id) {
        // DTO 変換でカテゴリ名を参照するため、カテゴリ込みで取得して N+1 を避ける（共通規約 §8）
        return ExpenseResponse.from(findExpenseWithCategoryOrThrow(id));
    }

    // 支出を更新する
    @Transactional
    public ExpenseResponse update(Long id, CreateExpenseRequest request) {
        // 更新対象の支出を取得する（無ければ404）
        Expense expense = findExpenseOrThrow(id);
        // カテゴリ ID から対象カテゴリを取得する（無ければ404）
        Category category = findCategoryOrThrow(request.categoryId());
        // リクエスト内容をエンティティへ反映する
        applyRequest(expense, request, category);
        // 変更を保存する
        Expense saved = expenseRepository.save(expense);
        // 保存結果を DTO に変換して返す
        return ExpenseResponse.from(saved);
    }

    // 支出を削除する
    @Transactional
    public void delete(Long id) {
        // 削除対象の支出を取得する（無ければ404）
        Expense expense = findExpenseOrThrow(id);
        // 取得した支出を削除する
        expenseRepository.delete(expense);
    }

    // 指定月の合計とカテゴリ別合計を集計する
    @Transactional(readOnly = true)
    public SummaryResponse summary(String month) {
        // 対象月をパースする
        YearMonth target = parseMonth(month);
        // 期間開始（月初）を求める
        LocalDate start = target.atDay(1);
        // 期間終了（翌月初・含まない）を求める
        LocalDate end = target.plusMonths(1).atDay(1);
        // GROUP BY でカテゴリ別合計を取得する
        List<CategorySummary> byCategory = expenseRepository.summarizeByCategory(start, end);
        // カテゴリ別合計を足し上げて総合計を算出する
        BigDecimal total = byCategory.stream()
                // 各行の合計値を取り出す
                .map(CategorySummary::total)
                // ゼロを初期値に総和を求める
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 集計結果を DTO に詰めて返す
        return new SummaryResponse(month, total, byCategory);
    }

    // リクエスト内容をエンティティへ反映する共通処理
    private void applyRequest(Expense expense, CreateExpenseRequest request, Category category) {
        // 金額を設定する
        expense.setAmount(request.amount());
        // カテゴリを設定する
        expense.setCategory(category);
        // 説明を設定する
        expense.setDescription(request.description());
        // 支出日を設定する
        expense.setSpentOn(request.spentOn());
    }

    // カテゴリを取得し、無ければ404例外を投げる
    private Category findCategoryOrThrow(Long categoryId) {
        // ID でカテゴリを検索し、無ければ例外を送出する
        return categoryRepository.findById(categoryId)
                // 見つからない場合は内部 ID を含めない安全な文言で未存在例外を投げる
                .orElseThrow(() -> new NotFoundException(ErrorMessages.CATEGORY_NOT_FOUND));
    }

    // 支出を取得し、無ければ404例外を投げる（更新・削除用。カテゴリ参照が不要な経路）
    private Expense findExpenseOrThrow(Long id) {
        // ID で支出を検索し、無ければ例外を送出する
        return expenseRepository.findById(id)
                // 見つからない場合は内部 ID を含めない安全な文言で未存在例外を投げる
                .orElseThrow(() -> new NotFoundException(ErrorMessages.EXPENSE_NOT_FOUND));
    }

    // 支出をカテゴリ込みで取得し、無ければ404例外を投げる（詳細取得用。DTO 変換でカテゴリ名を参照するため eager-load する）
    private Expense findExpenseWithCategoryOrThrow(Long id) {
        // ID で支出をカテゴリごと検索し、無ければ例外を送出する
        return expenseRepository.findByIdWithCategory(id)
                // 見つからない場合は内部 ID を含めない安全な文言で未存在例外を投げる
                .orElseThrow(() -> new NotFoundException(ErrorMessages.EXPENSE_NOT_FOUND));
    }

    // YYYY-MM 形式の文字列を YearMonth に変換する
    private YearMonth parseMonth(String month) {
        // 不正な形式は400として扱うため変換失敗を捕捉する
        try {
            // 文字列を年月としてパースする
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            // 生の入力値を外部に返さない安全な文言へ変換し、原因例外は追跡用に連鎖させる（外部公開して安全な400例外）
            throw new InvalidRequestException(ErrorMessages.INVALID_MONTH_FORMAT, e);
        }
    }
}
