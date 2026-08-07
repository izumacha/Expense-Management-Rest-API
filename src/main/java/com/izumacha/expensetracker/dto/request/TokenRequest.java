// リクエスト DTO のパッケージ
package com.izumacha.expensetracker.dto.request;

// 空文字を禁止するバリデーション
import jakarta.validation.constraints.NotBlank;

// トークン発行（POST /api/auth/token）のリクエストを表す record
public record TokenRequest(

        // 認証するユーザー名（必須）
        @NotBlank(message = "must not be blank")
        String username,

        // 認証するパスワード（必須。照合にのみ使い、保存・ログ出力はしない）
        @NotBlank(message = "must not be blank")
        String password
) {
    // record の自動生成 toString はパスワードの平文を含んでしまうため、マスクした表現で上書きする。
    // フレームワークやログがうっかり DTO を文字列化しても平文パスワードが漏れないようにする（§9）
    @Override
    public String toString() {
        // ユーザー名は出しつつ、パスワードは固定の伏字で置き換えた文字列を返す
        return "TokenRequest[username=" + username + ", password=****]";
    }
}
