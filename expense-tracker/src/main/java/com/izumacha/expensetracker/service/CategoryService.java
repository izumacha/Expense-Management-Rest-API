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
// 重複例外を参照する
import com.izumacha.expensetracker.exception.DuplicateException;
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
// 未存在例外を参照する（404 相当。指定 ID のカテゴリが無いとき送出する）
import com.izumacha.expensetracker.exception.NotFoundException;
// カテゴリリポジトリを参照する
import com.izumacha.expensetracker.repository.CategoryRepository;
// 支出リポジトリを参照する（削除時にカテゴリが支出から使用中か確認するため）
import com.izumacha.expensetracker.repository.ExpenseRepository;
// Unicode 正規化（合成済み/分解済みなど見た目が同じでも符号化が異なる文字列を同一視するため）に使う
import java.text.Normalizer;
// 一意制約違反を検出する例外
import org.springframework.dao.DataIntegrityViolationException;
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
        // 前後の空白除去とUnicode正規化を行う（create/updateで重複しないよう一元化）。
        // 実際に保存される値と重複チェックの対象を一致させるためここで明示的に揃える
        String normalizedName = normalizeName(request.name());
        // 同名カテゴリが既に存在する場合は重複例外を投げる（409）
        if (categoryRepository.existsByName(normalizedName)) {
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
        // create と同じ正規化（空白除去+Unicode正規化）。実際に保存される値で重複チェックを行うため
        String normalizedName = normalizeName(request.name());
        // 自分自身を除いた同名カテゴリが既に存在する場合は重複例外を投げる（409）
        if (categoryRepository.existsByNameAndIdNot(normalizedName, id)) {
            // 入力値を含めない安全な文言で重複を示す例外を送出する
            throw new DuplicateException(ErrorMessages.CATEGORY_NAME_DUPLICATE);
        }
        // 正規化済みの名前をエンティティへ反映する
        category.setName(normalizedName);
        // 事前チェックをすり抜けた同時実行の重複は一意制約違反として捕捉する。
        // UPDATE 文は既存の管理対象エンティティに対する save() のため、IDENTITY 採番の
        // INSERT（create）と異なり既定では即座にフラッシュされずコミット時まで遅延されうる。
        // saveAndFlush で明示的に即時反映させ、一意制約違反をこの try 内で確実に検知する。
        try {
            // 変更を即時反映して保存し、DTO に変換して返す
            return CategoryResponse.from(categoryRepository.saveAndFlush(category));
        } catch (DataIntegrityViolationException e) {
            // DB の一意制約違反を、入力値を含めない安全な文言で 409 相当の重複例外へ変換する。
            // 生の DB メッセージは外部に出さず、原因例外は追跡用に連鎖させる（共通規約 §6/§9）。
            throw new DuplicateException(ErrorMessages.CATEGORY_NAME_DUPLICATE, e);
        }
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
        // 事前チェックをすり抜けた同時実行の参照（この間に支出が新規登録された場合）は
        // 外部キー制約違反として捕捉する。DELETE 文も既定では即時フラッシュされないため、
        // flush() で明示的に即時反映させてこの try 内で確実に検知する。
        try {
            // 参照が無ければ削除し、即時反映してから確定する
            categoryRepository.delete(category);
            categoryRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // DB の外部キー制約違反を、入力値を含めない安全な文言で 409 相当の使用中例外へ変換する。
            // 生の DB メッセージは外部に出さず、原因例外は追跡用に連鎖させる（共通規約 §6/§9）。
            throw new CategoryInUseException(ErrorMessages.CATEGORY_IN_USE, e);
        }
    }

    // カテゴリ名を正規化する（空白除去 + Unicode NFC正規化）。
    // 濁点/半濁点付き仮名などは合成済み(NFC)と分解済み(NFD)の2通りの符号化で
    // 見た目が同一の文字列を作れてしまい、strip() だけでは別名として重複チェックを
    // すり抜けてしまう（既存の existsByName / existsByNameAndIdNot はDBの一意制約も
    // 含め単純な文字列比較のため）。NFC に揃えることで見た目が同じ名前を確実に同一視する。
    private static String normalizeName(String name) {
        // strip() で前後の空白を取り除いてから正規化する（strip() 自体はUnicode対応）
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
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
