// コントローラパッケージ
package com.izumacha.expensetracker.controller;

// トークン発行リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.TokenRequest;
// トークン発行レスポンス DTO を参照する
import com.izumacha.expensetracker.dto.response.TokenResponse;
// トークン発行のビジネスロジックを参照する
import com.izumacha.expensetracker.service.AuthTokenService;
// リクエストボディの検証を有効化するアノテーション
import jakarta.validation.Valid;
// POST マッピング用アノテーション
import org.springframework.web.bind.annotation.PostMapping;
// リクエストボディ取得用アノテーション
import org.springframework.web.bind.annotation.RequestBody;
// 共通パスを宣言するアノテーション
import org.springframework.web.bind.annotation.RequestMapping;
// REST コントローラ宣言用アノテーション
import org.springframework.web.bind.annotation.RestController;

// 認証（アクセストークン発行）のエンドポイントを提供するコントローラ。
// このパス（/api/auth/token）だけは SecurityConfig で未認証アクセスが許可されている
// （トークンを持っていない状態でトークンを取得するための唯一の入り口）。
@RestController
// 共通のベースパスを設定する
@RequestMapping("/api/auth")
public class AuthController {

    // トークン発行サービスへの参照
    private final AuthTokenService authTokenService;

    // コンストラクタインジェクションで依存を受け取る
    public AuthController(AuthTokenService authTokenService) {
        // 受け取ったサービスをフィールドに設定する
        this.authTokenService = authTokenService;
    }

    // ユーザー名とパスワードを検証してアクセストークン（JWT）を発行する（成功時 200）。
    // 認証失敗時はサービスから AuthenticationException が送出され、GlobalExceptionHandler が
    // 既存エラー契約 {status, message} の 401 に整形する。入力検証エラー（空欄等）は 400。
    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        // サービスで認証とトークン発行を行い、結果（トークン＋有効期間）を返す
        return authTokenService.issueToken(request);
    }
}
