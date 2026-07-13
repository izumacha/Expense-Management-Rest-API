// サービスパッケージ
package com.izumacha.expensetracker.service;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// 支出エンティティを参照する
import com.izumacha.expensetracker.domain.Expense;
// 支出作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateExpenseRequest;
// 支出更新リクエスト DTO を参照する（作成と更新の API 契約を分離する）
import com.izumacha.expensetracker.dto.request.UpdateExpenseRequest;
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
// 合計の小数桁を揃える際の丸めモードを指定する型
import java.math.RoundingMode;
// 日付・時刻計算の失敗を表す親例外（形式不正の DateTimeParseException も範囲外の年月計算もこの型で捕捉できる）
import java.time.DateTimeException;
// 日付型
import java.time.LocalDate;
// 月（年月）を表す型
import java.time.YearMonth;
// 一覧の戻り型
import java.util.List;
// DB 制約違反（外部キー違反など）を表す例外。同時実行でカテゴリが消えた場合の保存失敗を捕捉する
import org.springframework.dao.DataIntegrityViolationException;
// ページ単位の取得結果を表す型
import org.springframework.data.domain.Page;
// ページ番号・件数からページ指定を組み立てる実装（summary の件数上限に使う）
import org.springframework.data.domain.PageRequest;
// ページ指定（ページ番号・件数）を表す型
import org.springframework.data.domain.Pageable;
// Spring のサービスコンポーネント宣言
import org.springframework.stereotype.Service;
// トランザクションの分離レベル（他の同時実行処理からどこまで隔離するか）を指定する列挙
import org.springframework.transaction.annotation.Isolation;
// トランザクション境界の宣言
import org.springframework.transaction.annotation.Transactional;

// 支出に関する業務ロジックを担うサービス
@Service
public class ExpenseService {

    // 支出リポジトリへの参照
    private final ExpenseRepository expenseRepository;
    // カテゴリリポジトリへの参照
    private final CategoryRepository categoryRepository;

    // 金額の小数桁数（DB の amount 列 numeric(19,2) の scale=2 に合わせる）。
    // 集計結果の総合計を常にこの桁数へ揃え、支出のない月（"0"）と支出のある月（"1234.00"）で
    // JSON の小数桁がバラつく API 契約の不整合を防ぐ。
    private static final int MONEY_SCALE = 2;

    // 月次集計（summary）の byCategory が返すカテゴリ別内訳の最大件数。
    // カテゴリ数は API 経由で無制限に増やせるため、上限が無いと集計応答が際限なく肥大化し
    // リソース枯渇を招く（共通規約 §8「一覧取得は必ず上限を持たせる」§9 DoS 防止）。
    // 一覧 API の上限（application.yml の spring.data.web.pageable.max-page-size: 100）と
    // 同じ値に揃え、上限を超えた分は合計降順の上位だけ返して打ち切る（README に契約として明記）。
    private static final int SUMMARY_MAX_CATEGORIES = 100;

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
        applyFields(expense, request.amount(), request.description(), request.spentOn(), category);
        // 事前チェック後に別リクエストがカテゴリを削除したレースは外部キー違反として捕捉する
        return saveOrThrowIfCategoryVanished(expense);
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
    public ExpenseResponse update(Long id, UpdateExpenseRequest request) {
        // 更新対象の支出を取得する（無ければ404）
        Expense expense = findExpenseOrThrow(id);
        // カテゴリ ID から対象カテゴリを取得する（無ければ404）
        Category category = findCategoryOrThrow(request.categoryId());
        // リクエスト内容をエンティティへ反映する
        applyFields(expense, request.amount(), request.description(), request.spentOn(), category);
        // 事前チェック後に別リクエストがカテゴリを削除したレースは外部キー違反として捕捉する
        return saveOrThrowIfCategoryVanished(expense);
    }

    // 支出を削除する
    @Transactional
    public void delete(Long id) {
        // 削除対象の支出を取得する（無ければ404）
        Expense expense = findExpenseOrThrow(id);
        // 取得した支出を削除する
        expenseRepository.delete(expense);
    }

    // 指定月の合計とカテゴリ別合計を集計する。
    // 分離レベルを REPEATABLE_READ に上げる理由: このメソッドはカテゴリ別内訳（summarizeByCategory）と
    // 総合計（sumAmount）の 2 クエリを発行する。既定の READ_COMMITTED では 2 クエリの間に別リクエストの
    // 書き込みがコミットされると、total と byCategory の足し上げが（意図した上限打ち切り以外の理由で）
    // 食い違ってしまう。PostgreSQL の REPEATABLE_READ はスナップショット分離であり、トランザクション内の
    // 全クエリが同一スナップショット（同じ瞬間のデータ）を見るため、両クエリの整合が保証される。
    // Isolation は JPA/Spring 標準の指定であり、プロバイダ固有 SQL を持ち込まない（JPQL も無変更）。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SummaryResponse summary(String month) {
        // 対象月をパースする
        YearMonth target = parseMonth(month);
        // 期間開始（月初）を求める
        LocalDate start = target.atDay(1);
        // 期間終了（翌月初・含まない）を求める
        LocalDate end = target.plusMonths(1).atDay(1);
        // GROUP BY でカテゴリ別合計を取得する。件数無制限の取得を避けるため（共通規約 §8/§9）、
        // 合計降順→カテゴリID昇順の上位 SUMMARY_MAX_CATEGORIES 件だけに打ち切る
        List<CategorySummary> byCategory = expenseRepository.summarizeByCategory(
                // 期間（月初〜翌月初）を渡す
                start, end,
                // 先頭ページ＋上限件数のページ指定で LIMIT を掛ける（並び順は JPQL 側で固定済み）
                PageRequest.of(0, SUMMARY_MAX_CATEGORIES));
        // 総合計は byCategory の足し上げではなく月全体の SUM クエリで求める。
        // byCategory が上限で打ち切られた場合でも、total は常に「その月のすべての支出の合計」
        // であり続け、打ち切りの影響を受けない（API 契約の意味を保つ）。
        BigDecimal monthTotal = expenseRepository.sumAmount(start, end);
        // 支出が1件も無い月は SUM が null を返すためゼロへフォールバックし、そのうえで
        // 常に scale=2（"0.00"/"1234.00"）に正規化して JSON の小数桁がバラつかない契約を保つ
        // （金額は既に2桁以内なので丸めは発生しない）。
        BigDecimal total = (monthTotal == null ? BigDecimal.ZERO : monthTotal)
                // 金額の桁数（小数2桁）へ揃える
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        // 集計結果を DTO に詰めて返す
        return new SummaryResponse(month, total, byCategory);
    }

    // 作成・更新の両リクエストで共通の内容を支出エンティティへ反映する
    // （CreateExpenseRequest/UpdateExpenseRequest の DTO 自体は API 契約として分離したまま維持し、
    // ここではプリミティブな値だけを受け取ることでロジックの重複を1箇所に共通化する）
    private void applyFields(Expense expense, BigDecimal amount, String description, LocalDate spentOn,
            Category category) {
        // 支出日が受け付け年範囲外なら、DB へ渡す前に 400 で弾く（詳細は validateSpentOn 参照）
        validateSpentOn(spentOn);
        // 金額の桁数(小数2桁)へ揃えてから設定する。@Digits(fraction=2) は上限のみを検証し
        // 桁数不足（例: 10.5）を弾かないため、揃えないと作成/更新直後のレスポンスだけ
        // scale がずれ（"10.5"）、後続の GET（DB の numeric(19,2) 由来）は "10.50" になる。
        expense.setAmount(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        // カテゴリを設定する
        expense.setCategory(category);
        // 説明を設定する
        expense.setDescription(description);
        // 支出日を設定する
        expense.setSpentOn(spentOn);
    }

    // 支出を保存し、保存時の外部キー違反（カテゴリ消失レース）は404例外へ変換する（create/update 共通）
    private ExpenseResponse saveOrThrowIfCategoryVanished(Expense expense) {
        // 事前の存在チェックをすり抜けた同時実行の削除は保存時に一意/外部キー制約違反となる。
        // update() では対象の Expense が既に ID を持つ管理対象エンティティのため、save() は
        // 内部で merge() を呼び、UPDATE 文は既定では即座にフラッシュされずコミット時まで遅延されうる
        // （CategoryService.update() と同じ理由）。遅延されたままだと、この try の外（トランザクション
        // コミット時）で例外が発生し、この catch では捕捉できず 500 に戻ってしまう。create() は
        // IDENTITY 採番の INSERT のため元々即時フラッシュされるが、update() と経路を共通化している
        // 都合上、saveAndFlush で明示的に即時反映させ、両経路とも一意/外部キー制約違反をこの try 内で
        // 確実に検知する。
        try {
            // 保存を即時反映し、採番済み（更新時は更新後）のインスタンスを取得する
            Expense saved = expenseRepository.saveAndFlush(expense);
            // 保存結果を DTO に変換して返す
            return ExpenseResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // Expense の外部キーは category_id のみ。amount は @NotNull + @Digits/@DecimalMin で、
            // spentOn は @NotNull + @PastOrPresent に加え applyFields の validateSpentOn で年範囲まで
            // 事前検証済み（DB の date 範囲外は 400 で先に弾く）。よってこの時点で残る制約違反は
            // 「参照先カテゴリが消えた」レースだけなので、500 ではなく 404 を返す。
            // 生の DB メッセージは外部に出さず、原因例外は追跡用に連鎖させる（共通規約 §6/§9）。
            throw new NotFoundException(ErrorMessages.CATEGORY_NOT_FOUND, e);
        }
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

    // 受け付ける年の下限・上限（YYYY-MM 契約どおり 4 桁の正の年だけを許可する）。
    // 背景: YearMonth.parse は西暦 999999999 まで通してしまう。しかし search / summary が期間として
    // 使う LocalDate は最終的に PostgreSQL の date 型（西暦 5874897 まで）へ束縛されるため、5874897 を
    // 超える年（例 "9999999-01"）はパースも「翌月初」計算も成功したうえで DB でだけ範囲外エラーになり、
    // catch-all に落ちて 500（＋スタックトレース出力）になっていた。ここで妥当な年範囲に丸め、
    // 範囲外は形式不正と同じ 400（不正リクエスト）として弾く。
    private static final int MIN_YEAR = 1;     // 受け付ける年の下限（負の年・0 年は不正として弾く）
    private static final int MAX_YEAR = 9999;  // 受け付ける年の上限（YYYY = 4 桁。PostgreSQL date 範囲に十分収まる）

    // YYYY-MM 形式の文字列を YearMonth に変換する
    private YearMonth parseMonth(String month) {
        // まず文字列を年月としてパースする。形式不正（DateTimeParseException など）は
        // 生の入力値を外部に返さない安全な文言の 400 例外へ変換する（原因例外は追跡用に連鎖させる）。
        YearMonth target;  // パース結果の年月を受け取る変数を宣言する
        try {
            // 文字列を年月としてパースする
            target = YearMonth.parse(month);
        } catch (DateTimeException e) {
            // 形式不正を外部公開して安全な 400 例外へ変換する（原因はログ追跡用に連鎖させ、外部へは出さない）
            throw new InvalidRequestException(ErrorMessages.INVALID_MONTH_FORMAT, e);
        }
        // 年が受け付け範囲外なら 400 として弾く。これにより PostgreSQL の date 範囲外の年が
        // そのまま DB へ渡って 500 になる不具合（例 "9999999-01" や負の年）を未然に防ぐ。
        if (target.getYear() < MIN_YEAR || target.getYear() > MAX_YEAR) {
            // 範囲外の年は生入力を含めない安全な文言の 400 例外にする（形式不正と同じ扱い）
            throw new InvalidRequestException(ErrorMessages.INVALID_MONTH_FORMAT);
        }
        // 検証を通過した年月を返す
        return target;
    }

    // 支出日の年が受け付け範囲外なら 400 として弾く。
    // spentOn は DTO 側で @NotNull + @PastOrPresent（未来禁止＝上限のみ）しか検証されておらず、
    // 下限が無い。LocalDate は -999999999 年まで受け付ける一方、PostgreSQL の date 型は 4713 BC
    // までしか表現できないため、極端に古い年（例 "-99999-01-01"）がそのまま DB へ渡ると範囲外
    // エラーになる。しかもその DataIntegrityViolationException は保存時 catch で「カテゴリが消えた」
    // 404 に誤変換され、実在するカテゴリなのに 404＋誤ったメッセージを返してしまう。parseMonth と
    // 同じ年範囲（MIN_YEAR..MAX_YEAR）で DB へ渡す前に弾き、月と同様に 400 として扱う。
    private void validateSpentOn(LocalDate spentOn) {
        // spentOn は @NotNull 済みだが、防御的に null のときは何もしない（ここでは年範囲だけを見る）
        if (spentOn == null) {
            return;
        }
        // 年が受け付け範囲外なら、生入力を含めない安全な文言の 400 例外にする
        if (spentOn.getYear() < MIN_YEAR || spentOn.getYear() > MAX_YEAR) {
            throw new InvalidRequestException(ErrorMessages.INVALID_SPENT_ON);
        }
    }
}
