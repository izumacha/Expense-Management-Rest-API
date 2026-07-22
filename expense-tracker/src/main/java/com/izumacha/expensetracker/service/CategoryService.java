// サービスパッケージ
package com.izumacha.expensetracker.service;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// カテゴリ作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateCategoryRequest;
// カテゴリ更新リクエスト DTO を参照する（作成と更新の API 契約を分離する）
import com.izumacha.expensetracker.dto.request.UpdateCategoryRequest;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// 参照中カテゴリの削除禁止例外を参照する（409 相当。支出から使用中のカテゴリを削除しようとしたとき送出する）
import com.izumacha.expensetracker.exception.CategoryInUseException;
// 楽観ロック競合例外を参照する（409 相当。別の操作が対象を先に変更していたとき送出する）
import com.izumacha.expensetracker.exception.ConflictException;
// 重複例外を参照する
import com.izumacha.expensetracker.exception.DuplicateException;
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
// 不正リクエスト例外を参照する（400 相当。NFC 正規化後に文字数上限を超えた名前を拒否するとき送出する）
import com.izumacha.expensetracker.exception.InvalidRequestException;
// 未存在例外を参照する（404 相当。指定 ID のカテゴリが無いとき送出する）
import com.izumacha.expensetracker.exception.NotFoundException;
// カテゴリリポジトリを参照する
import com.izumacha.expensetracker.repository.CategoryRepository;
// 支出リポジトリを参照する（削除時にカテゴリが支出から使用中か確認するため）
import com.izumacha.expensetracker.repository.ExpenseRepository;
// 一意制約違反を検出する例外（create() の事前チェックすり抜けを捕捉する。update()/delete() は
// RaceGuard.guarded() のラムダ内で暗黙に扱うためこのクラス自体を直接参照しない）
import org.springframework.dao.DataIntegrityViolationException;
// 楽観ロック失敗例外（版番号の不一致・対象行の消失。resolveOptimisticLockFailure で 409/404 へ振り分ける）
import org.springframework.dao.OptimisticLockingFailureException;
// ページ単位の取得結果を表す型
import org.springframework.data.domain.Page;
// ページ指定（ページ番号・件数）を表す型
import org.springframework.data.domain.Pageable;
// Spring のサービスコンポーネント宣言
import org.springframework.stereotype.Service;
// トランザクション境界の宣言
import org.springframework.transaction.annotation.Transactional;

// カテゴリに関する業務ロジックを担うサービス
@Service
public class CategoryService {

    // カテゴリリポジトリへの参照
    private final CategoryRepository categoryRepository;
    // 支出リポジトリへの参照（削除時の使用中チェックに使う）
    private final ExpenseRepository expenseRepository;

    // コンストラクタインジェクションで依存を受け取る
    public CategoryService(CategoryRepository categoryRepository, ExpenseRepository expenseRepository) {
        // 受け取ったカテゴリリポジトリをフィールドに設定する
        this.categoryRepository = categoryRepository;
        // 受け取った支出リポジトリをフィールドに設定する
        this.expenseRepository = expenseRepository;
    }

    // カテゴリを新規作成する
    @Transactional
    public CategoryResponse create(CreateCategoryRequest request) {
        // 空白除去+NFC正規化はDTOの正規コンストラクタで完了済み（request.name()）。
        // ここではその正規化後の名前がDB列の文字数上限に収まっているかを検証する
        // （create/updateで重複しないよう一元化）
        String normalizedName = validateNormalizedNameLength(request.name());
        // 同名カテゴリが既に存在する場合は重複例外を投げる（409）。大文字小文字を区別せず判定する
        if (categoryRepository.existsByNameIgnoreCase(normalizedName)) {
            // 入力値を含めない安全な文言で重複を示す例外を送出する
            throw new DuplicateException(ErrorMessages.CATEGORY_NAME_DUPLICATE);
        }
        // 正規化済みの名前からエンティティを生成する
        Category category = new Category(normalizedName);
        // 事前チェックをすり抜けた同時実行の重複は一意制約違反として捕捉する
        try {
            // エンティティを保存して採番済みのインスタンスを取得する
            Category saved = categoryRepository.save(category);
            // 保存結果を DTO に変換して返す
            return CategoryResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // DB の一意制約違反を、入力値を含めない安全な文言で 409 相当の重複例外へ変換する。
            // 生の DB メッセージは外部に出さず、原因例外は追跡用に連鎖させる（共通規約 §6/§9）。
            throw new DuplicateException(ErrorMessages.CATEGORY_NAME_DUPLICATE, e);
        }
    }

    // 指定 ID のカテゴリを 1 件取得する（作成時の Location ヘッダが指す取得用エンドポイントの実体）
    @Transactional(readOnly = true)
    public CategoryResponse findById(Long id) {
        // 主キーでカテゴリを検索し、存在すれば DTO へ変換して返す
        return categoryRepository.findById(id)
                // エンティティを安定した契約の DTO へ変換する
                .map(CategoryResponse::from)
                // 見つからなければ内部 ID を含めない安全な文言で 404 相当の例外を送出する
                .orElseThrow(() -> new NotFoundException(ErrorMessages.CATEGORY_NOT_FOUND));
    }

    // カテゴリ名を更新する
    @Transactional
    public CategoryResponse update(Long id, UpdateCategoryRequest request) {
        // 更新対象のカテゴリを取得する（無ければ404）
        Category category = categoryRepository.findById(id)
                // 見つからなければ内部 ID を含めない安全な文言で 404 相当の例外を送出する
                .orElseThrow(() -> new NotFoundException(ErrorMessages.CATEGORY_NOT_FOUND));
        // create と同じ検証（DTO層で正規化済みの名前が文字数上限に収まっているか）
        String normalizedName = validateNormalizedNameLength(request.name());
        // 自分自身を除いた同名カテゴリが既に存在する場合は重複例外を投げる（409）。大文字小文字を区別せず判定する
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(normalizedName, id)) {
            // 入力値を含めない安全な文言で重複を示す例外を送出する
            throw new DuplicateException(ErrorMessages.CATEGORY_NAME_DUPLICATE);
        }
        // 正規化済みの名前をエンティティへ反映する
        category.setName(normalizedName);
        // 事前チェックをすり抜けた同時実行の重複・対象行消失レースを RaceGuard で検知・変換する。
        // UPDATE 文は既存の管理対象エンティティに対する save() のため、IDENTITY 採番の
        // INSERT（create）と異なり既定では即座にフラッシュされずコミット時まで遅延されうる。
        // saveAndFlush で明示的に即時反映させ、一意制約違反をこの try 内で確実に検知する。
        return RaceGuard.guarded(
                // 変更を即時反映して保存し、DTO に変換して返す実処理
                () -> CategoryResponse.from(categoryRepository.saveAndFlush(category)),
                // DB の一意制約違反を、入力値を含めない安全な文言で 409 相当の重複例外へ変換する
                e -> new DuplicateException(ErrorMessages.CATEGORY_NAME_DUPLICATE, e),
                // 楽観ロック失敗（UPDATE の影響行数が0件）は「対象が同時実行で削除された」と
                // 「別の操作が先に更新して版番号（@Version）が進んだ（行は残っている）」の両方で
                // 起きるため、存在を確認して 409（競合）か 404（未存在）へ振り分ける
                e -> resolveOptimisticLockFailure(id, e));
    }

    // カテゴリを削除する（支出から参照中の場合は削除を拒否する）
    @Transactional
    public void delete(Long id) {
        // 削除対象のカテゴリを取得する（無ければ404）
        Category category = categoryRepository.findById(id)
                // 見つからなければ内部 ID を含めない安全な文言で 404 相当の例外を送出する
                .orElseThrow(() -> new NotFoundException(ErrorMessages.CATEGORY_NOT_FOUND));
        // 支出が1件でも参照していれば、参照整合性を保つため削除を拒否する（409）
        if (expenseRepository.existsByCategoryId(id)) {
            // 内部 ID を含めない安全な文言で使用中例外を送出する
            throw new CategoryInUseException(ErrorMessages.CATEGORY_IN_USE);
        }
        // 事前チェックをすり抜けた同時実行の参照（この間に支出が新規登録された場合）・
        // 対象行消失レースを RaceGuard で検知・変換する。DELETE 文も既定では即時フラッシュ
        // されないため、flush() で明示的に即時反映させてこの中で確実に検知する。
        RaceGuard.guarded(() -> {
            // 参照が無ければ削除し、即時反映してから確定する（戻り値を使わないため null を返す）
            categoryRepository.delete(category);
            categoryRepository.flush();
            return null;
        },
                // DB の外部キー制約違反を、入力値を含めない安全な文言で 409 相当の使用中例外へ変換する
                e -> new CategoryInUseException(ErrorMessages.CATEGORY_IN_USE, e),
                // 楽観ロック失敗（DELETE の影響行数が0件）は「対象が同時実行で既に削除された」と
                // 「別の操作が先に更新して版番号（@Version）が進んだ（行は残っている）」の両方で
                // 起きるため、存在を確認して 409（競合）か 404（未存在）へ振り分ける
                e -> resolveOptimisticLockFailure(id, e));
    }

    // 楽観ロック失敗（OptimisticLockingFailureException）を 409/404 のどちらのドメイン例外へ
    // 変換するかを決める。@Version 導入後、この例外は「対象行が同時実行で消えた」だけでなく
    // 「別の操作が先に更新して版番号が進んだ（行は残っている）」場合にも発生する。後者まで
    // 一律 404 にすると、実在するカテゴリに対して誤って「見つかりません」を返してしまうため、
    // 行の存在を再確認して振り分ける（update/delete の両経路で共通利用する）
    private RuntimeException resolveOptimisticLockFailure(Long id, OptimisticLockingFailureException e) {
        // 対象行がまだ DB に存在するかを確認する（存在すれば削除ではなく同時更新の競合と判断できる）
        if (categoryRepository.existsById(id)) {
            // 行が残っている＝同時更新の競合なので、安全な文言の 409 例外へ変換する（原因は追跡用に連鎖させる）
            return new ConflictException(ErrorMessages.CONCURRENT_CONFLICT, e);
        }
        // 行が消えている＝同時削除なので、404 相当の未存在例外へ変換する（原因は追跡用に連鎖させる）
        return new NotFoundException(ErrorMessages.CATEGORY_NOT_FOUND, e);
    }

    // カテゴリ名がNFC正規化後も文字数上限に収まっているか検証する。
    // strip() + NFC正規化そのものは CreateCategoryRequest/UpdateCategoryRequest の
    // 正規コンストラクタ（CategoryNameNormalizer.normalize()）で既に完了している
    // （record の正規コンストラクタはインスタンス生成経路によらず必ず実行されるため、
    // ここに渡ってくる name は常に正規化済み。二重に正規化する必要はない）。
    // ここで改めて検証するのは、DTO層の @MaxCodePoints 検証をすり抜けてしまう
    // 「NFC正規化で文字数が伸びて上限を超える」ケース（下記コメント参照）。
    // 濁点/半濁点付き仮名などは合成済み(NFC)と分解済み(NFD)の2通りの符号化で
    // 見た目が同一の文字列を作れてしまうが、DTO側で既にNFCに揃えられているため、
    // ここでの existsByNameIgnoreCase / existsByNameIgnoreCaseAndIdNot による重複チェックは
    // 見た目が同じ名前を確実に同一視できる（DBの一意制約も含め単純な文字列比較のため、
    // NFCに揃っていることが前提）。
    // 大文字小文字の違い（"Travel"/"travel"）は existsByNameIgnoreCase 側で同一視しており、
    // ここでは大文字小文字を変えない（保存される表示名の見た目を尊重するため）。
    // 注: DB の一意制約(@Column(unique=true))自体は大文字小文字を区別するため、
    // 大文字小文字だけが異なる名前が真に同時作成された場合の最終防波堤にはならない
    // （NFC正規化はDTO層で保存前に揃えているため一意制約が最終防波堤として機能するが、
    // 大文字小文字はここで揃えていないため）。頻度が極めて低い理論上のレースであり、
    // 発生しても409ではなく後勝ちで2件目が別カテゴリとして作成されるだけで実害は小さいため、
    // MVPの割り切りとしてDB制約の変更（式インデックス等、DBプロバイダ依存になりうる）までは行わない。
    private static String validateNormalizedNameLength(String name) {
        // NFC 正規化は文字数を増やすことがある（合成除外文字。例: U+0958 は NFC でも
        // U+0915+U+093C の 2 文字に分解されたままになる）ため、DTO の @MaxCodePoints(max=50) を
        // 正規化前の値で通過した名前が、ここで 50 文字を超えてしまう場合がある。
        // そのまま保存すると varchar(50) 超過の DataIntegrityViolationException となり、
        // create/update の catch が一意制約違反と区別できず誤って 409（重複）として
        // 返してしまうため、正規化後の長さをここで再検証して 400（不正入力）で拒否する
        // （create と update の両方がこのメソッドを通るため、両経路を一括で防げる）。
        // 長さは PostgreSQL の varchar(n) と同じコードポイント基準で数える（UTF-16 コード単位数の
        // String#length() を使うと、絵文字等のサロゲートペア文字を 2 と数えてしまい、DB には
        // 収まる名前を誤って拒否しうる。DTO 側の @MaxCodePoints と基準を揃えるため、ここでも
        // codePointCount を使う）。
        if (name.codePointCount(0, name.length()) > Category.NAME_MAX_LENGTH) {
            // 入力値を含めない安全な文言で 400 相当の不正リクエスト例外を送出する
            throw new InvalidRequestException(ErrorMessages.CATEGORY_NAME_TOO_LONG);
        }
        // 検証を通過した（DTO層で正規化済みの）名前をそのまま返す
        return name;
    }

    // カテゴリ一覧をページ単位で取得する
    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> findAll(Pageable pageable) {
        // 全カテゴリをページ単位で取得して DTO へ変換する（ページ情報は維持する）
        Page<CategoryResponse> page = categoryRepository.findAll(pageable)
                // 各エンティティを DTO へ変換する
                .map(CategoryResponse::from);
        // ページ情報を安定した契約の DTO へ詰め替えて返す
        return PageResponse.from(page);
    }
}
