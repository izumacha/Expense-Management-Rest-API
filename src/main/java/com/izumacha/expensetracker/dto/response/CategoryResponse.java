// レスポンス DTO のパッケージ
package com.izumacha.expensetracker.dto.response;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;

// カテゴリの返却用 DTO を表す record
public record CategoryResponse(

        // カテゴリ ID
        Long id,

        // カテゴリ名
        String name
) {

    // エンティティから DTO を生成する静的ファクトリ
    public static CategoryResponse from(Category category) {
        // ID と名前を取り出して DTO を組み立てる
        return new CategoryResponse(category.getId(), category.getName());
    }
}
