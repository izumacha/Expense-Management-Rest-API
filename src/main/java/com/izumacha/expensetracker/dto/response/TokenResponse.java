// レスポンス DTO のパッケージ
package com.izumacha.expensetracker.dto.response;

// トークン発行（POST /api/auth/token）の返却用 DTO を表す record
public record TokenResponse(

        // 発行したアクセストークン（JWT 文字列。Authorization: Bearer <この値> として使う）
        String accessToken,

        // トークンの有効期間（秒）。クライアントが失効前の再取得を計画できるように返す
        long expiresIn
) {
}
