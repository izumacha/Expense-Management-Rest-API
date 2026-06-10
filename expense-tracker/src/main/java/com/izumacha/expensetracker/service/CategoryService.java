// サービスパッケージ
package com.izumacha.expensetracker.service;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// カテゴリ作成・更新リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateCategoryRequest;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// 状態の競合（使用中カテゴリの削除）を表す例外を参照する
import com.izumacha.expensetracker.exception.ConflictException;
// 重複例外を参照する
import com.izumacha.expensetracker.exception.DuplicateException;
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
// 未存在例外を参照する
import com.izumacha.expensetracker.exception.NotFoundException;
// カテゴリリポジトリを参照する
import com.izumacha.expensetracker.repository.CategoryRepository;
// 支出リポジトリを参照する（カテゴリ削除可否の判定に使う）
import com.izumacha.expensetracker.repository.ExpenseRepository;
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
    // 支出リポジトリへの参照（カテゴリ削除時に紐づく支出の有無を確認する）
    private final ExpenseRepository expenseRepository;

    // コンストラクタインジェクションで依存を受け取る
    public CategoryService(CategoryRepository categoryRepository, ExpenseRepository expenseRepository) {
        // カテゴリリポジトリをフィールドに設定する
        this.categoryRepository = categoryRepository;
        // 支出リポジトリをフィールドに設定する
        this.expenseRepository = expenseRepository;
    }

    // カテゴリを新規作成する
    @Transactional
    public CategoryResponse create(CreateCategoryRequest request) {
        // 同名カテゴリが既に存在する場合は重複例外を投げる（409）
        if (categoryRepository.existsByName(request.name())) {
            // 入力値を含めない安全な文言で重複を示す例外を送出する
            throw new DuplicateException(ErrorMessages.CATEGORY_NAME_DUPLICATE);
        }
        // リクエストからエンティティを生成する
        Category category = new Category(request.name());
        // 事前チェックをすり抜けた同時実行の重複は一意制約違反として捕捉する
        try {
            // エンティティを保存して採番済みのインスタンスを取得する
            Category saved = categoryRepository.save(category);
            // 保存結果を DTO に変換して返す
            return CategoryResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // DB の一意制約違反を、入力値を含めない安全な文言で 409 相当の重複例外へ変換する
            throw new DuplicateException(ErrorMessages.CATEGORY_NAME_DUPLICATE);
        }
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

    // カテゴリ名を更新する
    @Transactional
    public CategoryResponse update(Long id, CreateCategoryRequest request) {
        // 更新対象のカテゴリを取得する（無ければ404）
        Category category = findCategoryOrThrow(id);
        // 新しい名前が他のカテゴリと重複する場合は重複例外を投げる（自分自身の同名は許可する）
        if (!category.getName().equals(request.name()) && categoryRepository.existsByName(request.name())) {
            // 入力値を含めない安全な文言で重複を示す例外を送出する
            throw new DuplicateException(ErrorMessages.CATEGORY_NAME_DUPLICATE);
        }
        // 新しい名前を反映する
        category.setName(request.name());
        // 事前チェックをすり抜けた同時実行の重複は一意制約違反として捕捉する
        try {
            // 変更を保存して結果を取得する
            Category saved = categoryRepository.save(category);
            // 保存結果を DTO に変換して返す
            return CategoryResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // DB の一意制約違反を、入力値を含めない安全な文言で 409 相当の重複例外へ変換する
            throw new DuplicateException(ErrorMessages.CATEGORY_NAME_DUPLICATE);
        }
    }

    // カテゴリを削除する
    @Transactional
    public void delete(Long id) {
        // 削除対象のカテゴリを取得する（無ければ404）
        Category category = findCategoryOrThrow(id);
        // 支出が紐づくカテゴリは参照整合性を壊さないよう削除を拒否する（409）
        if (expenseRepository.existsByCategoryId(id)) {
            // 内部 ID を含めない安全な文言で使用中を示す例外を送出する
            throw new ConflictException(ErrorMessages.CATEGORY_IN_USE);
        }
        // 紐づく支出が無いカテゴリのみ削除する
        categoryRepository.delete(category);
    }

    // カテゴリを取得し、無ければ404例外を投げる
    private Category findCategoryOrThrow(Long id) {
        // ID でカテゴリを検索し、無ければ例外を送出する
        return categoryRepository.findById(id)
                // 見つからない場合は内部 ID を含めない安全な文言で未存在例外を投げる
                .orElseThrow(() -> new NotFoundException(ErrorMessages.CATEGORY_NOT_FOUND));
    }
}
