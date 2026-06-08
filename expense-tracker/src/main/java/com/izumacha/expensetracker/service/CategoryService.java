// サービスパッケージ
package com.izumacha.expensetracker.service;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// カテゴリ作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateCategoryRequest;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// 重複例外を参照する
import com.izumacha.expensetracker.exception.DuplicateException;
// カテゴリリポジトリを参照する
import com.izumacha.expensetracker.repository.CategoryRepository;
// 一意制約違反を検出する例外
import org.springframework.dao.DataIntegrityViolationException;
// 一覧の戻り型
import java.util.List;
// DTO への変換に使う Stream 収集
import java.util.stream.Collectors;
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
        // 同名カテゴリが既に存在する場合は重複例外を投げる（409）
        if (categoryRepository.existsByName(request.name())) {
            // 重複を示す例外を送出する
            throw new DuplicateException("category name already exists: " + request.name());
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
            // DB の一意制約違反を 409 相当の重複例外へ変換する
            throw new DuplicateException("category name already exists: " + request.name());
        }
    }

    // カテゴリ一覧を取得する
    @Transactional(readOnly = true)
    public List<CategoryResponse> findAll() {
        // 全カテゴリを取得して DTO のリストに変換する
        return categoryRepository.findAll().stream()
                // 各エンティティを DTO へ変換する
                .map(CategoryResponse::from)
                // リストにまとめる
                .collect(Collectors.toList());
    }
}
