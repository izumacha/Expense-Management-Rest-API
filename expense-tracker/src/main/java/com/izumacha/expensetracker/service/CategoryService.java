// サービスパッケージ
package com.izumacha.expensetracker.service;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// カテゴリ作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateCategoryRequest;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// 重複例外を参照する
import com.izumacha.expensetracker.exception.DuplicateException;
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
// カテゴリリポジトリを参照する
import com.izumacha.expensetracker.repository.CategoryRepository;
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

    // コンストラクタインジェクションで依存を受け取る
    public CategoryService(CategoryRepository categoryRepository) {
        // 受け取ったリポジトリをフィールドに設定する
        this.categoryRepository = categoryRepository;
    }

    // カテゴリを新規作成する
    @Transactional
    public CategoryResponse create(CreateCategoryRequest request) {
        // 前後の空白を取り除く（" 食費" と "食費" を別名として扱わないため。
        // Category のコンストラクタでも同じ正規化を行うが、重複チェックを
        // 実際に保存される値と一致させるためここでも明示的に揃えておく）
        String normalizedName = request.name().strip();
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
}
