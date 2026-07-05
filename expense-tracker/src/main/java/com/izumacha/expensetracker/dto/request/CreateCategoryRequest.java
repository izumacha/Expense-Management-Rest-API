// リクエスト DTO のパッケージ
package com.izumacha.expensetracker.dto.request;

// 空文字を禁止するバリデーション
import jakarta.validation.constraints.NotBlank;
// 文字数を制限するバリデーション
import jakarta.validation.constraints.Size;

// カテゴリ作成リクエストを表す record
public record CreateCategoryRequest(

        // カテゴリ名（必須・最大50文字）
        @NotBlank(message = "must not be blank")
        // 最大50文字までに制限する
        @Size(max = 50, message = "must be at most 50 characters")
        String name
) {
    // 正規コンストラクタで @NotBlank より先に正規化する。Bean Validation の @NotBlank は
    // ASCII 空白のみを trim() で除去するため、全角スペース（U+3000）等の Unicode 空白だけの
    // 値は「空白でない」と誤判定されてしまう。ここで strip()（Unicode 対応）しておくことで、
    // 実際に永続化される値と同じ文字列を @NotBlank / @Size が検証できるようにする。
    public CreateCategoryRequest {
        // null はそのまま維持し、非 null なら前後の空白（Unicode 対応）を取り除く
        name = (name == null) ? null : name.strip();
    }
}
