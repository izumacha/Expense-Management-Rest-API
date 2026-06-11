// リポジトリパッケージ
package com.izumacha.expensetracker.repository;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// 標準的な CRUD を提供する基底インタフェース
import org.springframework.data.jpa.repository.JpaRepository;

// カテゴリの永続化を担うリポジトリ
public interface CategoryRepository extends JpaRepository<Category, Long> {

    // 同名カテゴリが存在するか判定する（重複チェック用）
    boolean existsByName(String name);
}
