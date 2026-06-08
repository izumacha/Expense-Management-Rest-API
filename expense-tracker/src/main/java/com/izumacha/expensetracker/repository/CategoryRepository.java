// リポジトリパッケージ
package com.izumacha.expensetracker.repository;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// 真偽値を返すクエリ用の戻り型
import java.util.Optional;
// 標準的な CRUD を提供する基底インタフェース
import org.springframework.data.jpa.repository.JpaRepository;

// カテゴリの永続化を担うリポジトリ
public interface CategoryRepository extends JpaRepository<Category, Long> {

    // 同名カテゴリが存在するか判定する（重複チェック用）
    boolean existsByName(String name);

    // 名前でカテゴリを検索する
    Optional<Category> findByName(String name);
}
