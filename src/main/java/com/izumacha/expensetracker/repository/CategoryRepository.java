// リポジトリパッケージ
package com.izumacha.expensetracker.repository;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// 標準的な CRUD を提供する基底インタフェース
import org.springframework.data.jpa.repository.JpaRepository;

// カテゴリの永続化を担うリポジトリ
public interface CategoryRepository extends JpaRepository<Category, Long> {

    // 同名カテゴリが存在するか判定する（重複チェック用）。大文字小文字を区別せず判定する
    // (IgnoreCase) — "Travel" と "travel" のような表記違いを別カテゴリとして
    // 重複チェックをすり抜けさせないため（CategoryService.normalizeName の Unicode
    // 正規化と同じ「見た目が同じ名前は同一視する」方針に揃える）
    boolean existsByNameIgnoreCase(String name);

    // 指定 ID 以外に同名カテゴリが存在するか判定する（更新時の重複チェック用。自分自身は対象から除く）。
    // 大文字小文字を区別せず判定する（上記 existsByNameIgnoreCase と同じ理由）
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
