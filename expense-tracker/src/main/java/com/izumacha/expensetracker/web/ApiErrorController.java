// Web 横断のユーティリティ・コントローラを置くパッケージ
package com.izumacha.expensetracker.web;

// JSON へ直列化するための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
// エラーディスパッチ時にサーブレットコンテナが設定する属性名の定数（jakarta.servlet.error.status_code 等）
import jakarta.servlet.RequestDispatcher;
// HTTP リクエストを表す型
import jakarta.servlet.http.HttpServletRequest;
// HTTP レスポンスを表す型
import jakarta.servlet.http.HttpServletResponse;
// 入出力例外型
import java.io.IOException;
// Spring Boot に「エラーページの実装」であることを伝えるマーカーインタフェース
import org.springframework.boot.web.servlet.error.ErrorController;
// HTTP ステータスを表す列挙
import org.springframework.http.HttpStatus;
// リクエストをメソッドへマッピングするアノテーション
import org.springframework.web.bind.annotation.RequestMapping;
// REST コントローラ宣言
import org.springframework.web.bind.annotation.RestController;

// エラーページ（/error）を {status, message} 契約で応答する自前の ErrorController。
//
// 【なぜ必要か】application.yml の throw-exception-if-no-handler-found / add-mappings:false は
// 「ハンドラ未検出（404）を GlobalExceptionHandler で整形する」ためのものだが、それだけでは
// Spring Boot 既定の BasicErrorController が /error に登録されたまま残る（SecurityConfig は
// permitAll のため外部から直接 GET /error も可能）。既定実装は {timestamp,status,error,path}
// 形式（エラー属性が無い直接アクセスでは status:999 の 500）を返し、本 API のエラー契約
// {status, message}（CLAUDE.md §1）を破る。またフィルタ層から漏れた例外（ERROR ディスパッチ）も
// @RestControllerAdvice を経由せず /error へ届くため、同じく契約外の形式になっていた。
// 本クラスを ErrorController として登録すると Boot の自動設定（@ConditionalOnMissingBean）が
// 既定実装の登録を止め、/error 経路のすべてが契約どおりの JSON になる。
//
// 【メッセージの方針】外部には内部詳細を含まない汎用の安全文言だけを返す（§9）。
// GlobalExceptionHandler.handleExceptionInternal と同じ規則
// （5xx → INTERNAL_ERROR / それ以外 → BAD_REQUEST）に揃える。
@RestController
public class ApiErrorController implements ErrorController {

    // エラーページのパス（Boot 既定の server.error.path / error.path 設定に追従し、未設定時は /error）。
    // BasicErrorController と同じプレースホルダ式を使い、設定変更時もずれないようにする
    static final String ERROR_PATH = "${server.error.path:${error.path:/error}}";

    // JSON 直列化に使う ObjectMapper
    private final ObjectMapper objectMapper;

    // ObjectMapper をコンストラクタで受け取る
    public ApiErrorController(ObjectMapper objectMapper) {
        // ObjectMapper をフィールドに保持する
        this.objectMapper = objectMapper;
    }

    // /error へのすべてのリクエスト（直接アクセス・ERROR ディスパッチ）を契約形式で応答する
    @RequestMapping(ERROR_PATH)
    public void handleError(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // ERROR ディスパッチ時にコンテナが設定した元のステータスコード属性を取得する（直接アクセスでは null）
        Object statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        // 属性が整数なら対応する HttpStatus へ解決する（未知のコードなら null になる）
        HttpStatus status = (statusCode instanceof Integer code) ? HttpStatus.resolve(code) : null;
        // 属性が無い／解決できない場合は安全側の 500 として扱う（fail-safe、§9）
        if (status == null) {
            // 既定ステータスとしてサーバ内部エラーを設定する
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        // 5xx はサーバ内部エラーの汎用文言、それ以外（4xx）はリクエスト不正の汎用文言を選ぶ（内部詳細は含めない）
        String message = status.is5xxServerError() ? ErrorMessages.INTERNAL_ERROR : ErrorMessages.BAD_REQUEST;
        // 既存の共通ユーティリティで {status, message} 形式の JSON を書き出す（§6 DRY）
        ApiErrorWriter.write(response, objectMapper, status, message);
    }
}
