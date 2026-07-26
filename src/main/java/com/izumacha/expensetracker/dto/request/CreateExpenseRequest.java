// リクエスト DTO のパッケージ
package com.izumacha.expensetracker.dto.request;

// 0より大きいことを検証するバリデーション
import jakarta.validation.constraints.DecimalMin;
// 整数部・小数部の桁数を制限するバリデーション
import jakarta.validation.constraints.Digits;
// null を禁止するバリデーション
import jakarta.validation.constraints.NotNull;
// 過去または当日であることを検証するバリデーション
import jakarta.validation.constraints.PastOrPresent;
// 10進数の金額型
import java.math.BigDecimal;
// 日付型
import java.time.LocalDate;
// 文字数をコードポイント単位で制限するバリデーション（DB varchar 列の基準に合わせる）
import com.izumacha.expensetracker.validation.MaxCodePoints;
// 制御文字（NUL 等）を含まないことを検証するバリデーション（PostgreSQL は NUL を text 列に
// 保存できず DB 層で初めてエラーになり、誤った 404/500 に化けるため入力段階で 400 として弾く）
import com.izumacha.expensetracker.validation.NoControlCharacters;

// 支出作成・更新リクエストを表す record
public record CreateExpenseRequest(

        // 金額（必須・0より大きい・桁数は DB 列 precision=19/scale=2 に合わせる）
        @NotNull(message = "must not be null")
        // 0 を含まず 0 より大きい値のみ許可する
        @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        // 整数部 17 桁・小数部 2 桁までに制限する（DB 列精度との不整合や巨大値を防ぐ）
        @Digits(integer = 17, fraction = 2, message = "must have at most 17 integer digits and 2 fraction digits")
        BigDecimal amount,

        // カテゴリ ID（必須・存在しなければサービス層で404）
        @NotNull(message = "must not be null")
        Long categoryId,

        // 説明（任意・最大255文字・制御文字禁止。サロゲートペア文字でも DB の varchar(255) と基準を合わせるためコードポイント単位で検証する）
        @MaxCodePoints(max = 255, message = "must be at most 255 characters")
        // NUL 等の制御文字を禁止する（タブ・改行・復帰は許容。詳細は NoControlCharacters の Javadoc を参照）
        @NoControlCharacters(message = "must not contain control characters")
        String description,

        // 支出日（必須・未来日不可）
        @NotNull(message = "must not be null")
        // 過去または当日のみ許可する
        @PastOrPresent(message = "must not be a future date")
        LocalDate spentOn
) {
}
