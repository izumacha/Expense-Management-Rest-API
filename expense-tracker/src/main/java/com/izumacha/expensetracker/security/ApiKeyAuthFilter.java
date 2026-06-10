// 認証・アクセス制御に関するパッケージ
package com.izumacha.expensetracker.security;

// JSON 応答を書き出すための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
// {status, message} 形式のエラー応答を書き出す共通ユーティリティ
import com.izumacha.expensetracker.web.ApiErrorWriter;
// サーブレットのフィルタ連鎖を表す型
import jakarta.servlet.FilterChain;
// サーブレット例外型
import jakarta.servlet.ServletException;
// HTTP リクエストを表す型
import jakarta.servlet.http.HttpServletRequest;
// HTTP レスポンスを表す型
import jakarta.servlet.http.HttpServletResponse;
// 入出力例外型
import java.io.IOException;
// 定数時間比較に使うユーティリティ
import java.security.MessageDigest;
// 文字コードを定数で扱うためのクラス
import java.nio.charset.StandardCharsets;
// プロパティ値を注入するアノテーション
import org.springframework.beans.factory.annotation.Value;
// HTTP ステータスを表す列挙
import org.springframework.http.HttpStatus;
// Bean の優先順位（フィルタの適用順）を指定するアノテーション
import org.springframework.core.annotation.Order;
// Spring に管理させるためのコンポーネント宣言
import org.springframework.stereotype.Component;
// 1 リクエストにつき一度だけ実行されることを保証するフィルタ基底
import org.springframework.web.filter.OncePerRequestFilter;

// すべてのリクエストに対し API キー認証を強制するフィルタ（§9 認可はサーバー側で強制する）。
// 認証はコントローラより手前で行うため OncePerRequestFilter として実装する。
@Component
// レート制限フィルタ（Order 1）の後に認証を行う
@Order(2)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    // クライアントが API キーを載せるリクエストヘッダ名
    private static final String API_KEY_HEADER = "X-API-Key";

    // 設定から読み込んだ正解の API キー（環境変数 APP_API_KEY 由来）
    private final String configuredApiKey;
    // エラー応答を JSON で書き出すための ObjectMapper
    private final ObjectMapper objectMapper;

    // 設定値と ObjectMapper をコンストラクタで受け取る
    public ApiKeyAuthFilter(
            // 受け付ける API キー（app.security.api-key）
            @Value("${app.security.api-key}") String configuredApiKey,
            // JSON 直列化に使う ObjectMapper
            ObjectMapper objectMapper) {
        // 正解の API キーをフィールドに保持する
        this.configuredApiKey = configuredApiKey;
        // ObjectMapper をフィールドに保持する
        this.objectMapper = objectMapper;
        // API キーが未設定（空白）なら起動を失敗させる（fail-closed：認証を素通りさせない）
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            // 設定不備として例外を投げ、アプリの起動を止める
            throw new IllegalStateException("APP_API_KEY が未設定です。API キーを必ず設定してください");
        }
    }

    // 各リクエストでヘッダの API キーを検証する本体
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // リクエストヘッダから API キーを取り出す
        String providedKey = request.getHeader(API_KEY_HEADER);
        // 提供されたキーが正解と一致しなければ 401 を返して処理を打ち切る
        if (!isValidKey(providedKey)) {
            // 認証失敗の安全な文言で 401 応答を書き出す（正解キーや内部詳細は含めない）
            ApiErrorWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED, ErrorMessages.UNAUTHORIZED);
            // 後続のフィルタ・コントローラへは進ませない
            return;
        }
        // 認証に成功したので後続の処理へ進める
        filterChain.doFilter(request, response);
    }

    // 提供された API キーが正解と一致するかを定数時間で判定する
    private boolean isValidKey(String providedKey) {
        // キーが渡されていなければ不一致とする
        if (providedKey == null) {
            // null は無効
            return false;
        }
        // 文字列をバイト列へ変換する（比較のため）
        byte[] provided = providedKey.getBytes(StandardCharsets.UTF_8);
        // 正解キーもバイト列へ変換する
        byte[] expected = configuredApiKey.getBytes(StandardCharsets.UTF_8);
        // タイミング攻撃を避けるため長さ差があっても処理時間が一定な比較を使う
        return MessageDigest.isEqual(provided, expected);
    }
}
