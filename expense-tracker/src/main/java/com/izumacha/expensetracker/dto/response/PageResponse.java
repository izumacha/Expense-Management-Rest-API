// レスポンス DTO のパッケージ
package com.izumacha.expensetracker.dto.response;

// ページ情報を取り出す元になる Spring Data のページ型
import org.springframework.data.domain.Page;
// 一覧の戻り型
import java.util.List;

// 一覧 API の戻り値を表す汎用ページ DTO。
// Spring Data の Page をそのまま返すと JSON 構造が不安定になるため、必要な項目だけを持つ安定した契約に変換する。
public record PageResponse<T>(

        // このページに含まれる要素の一覧
        List<T> content,
        // 現在のページ番号（0 始まり）
        int page,
        // 1 ページあたりの件数
        int size,
        // 条件に一致する全要素数
        long totalElements,
        // 全ページ数
        int totalPages
) {

    // Spring Data の Page から PageResponse を生成するファクトリメソッド
    public static <T> PageResponse<T> from(Page<T> source) {
        // Page の各情報を取り出して PageResponse に詰め替える
        return new PageResponse<>(
                // このページの要素一覧
                source.getContent(),
                // 現在のページ番号
                source.getNumber(),
                // 1 ページあたりの件数
                source.getSize(),
                // 全要素数
                source.getTotalElements(),
                // 全ページ数
                source.getTotalPages());
    }
}
