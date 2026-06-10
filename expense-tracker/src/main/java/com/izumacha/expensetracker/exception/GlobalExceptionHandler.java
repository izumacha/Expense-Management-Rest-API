// 例外パッケージ
package com.izumacha.expensetracker.exception;

// ログ出力に使うロガー本体
import org.slf4j.Logger;
// ロガーを生成するファクトリ
import org.slf4j.LoggerFactory;
// DB アクセス層の例外（接続失敗・SQL 実行失敗等）
import org.springframework.dao.DataAccessException;
// HTTP ヘッダを表すクラス
import org.springframework.http.HttpHeaders;
// HTTP ステータスを表す列挙
import org.springframework.http.HttpStatus;
// 数値で表される HTTP ステータスコード型
import org.springframework.http.HttpStatusCode;
// HTTP レスポンス全体を表すクラス
import org.springframework.http.ResponseEntity;
// バリデーション失敗時の例外
import org.springframework.web.bind.MethodArgumentNotValidException;
// 必須リクエストパラメータ欠落時の例外
import org.springframework.web.bind.MissingServletRequestParameterException;
// 例外ハンドラを宣言するアノテーション
import org.springframework.web.bind.annotation.ExceptionHandler;
// 全コントローラ横断の例外処理を宣言するアノテーション
import org.springframework.web.bind.annotation.RestControllerAdvice;
// リクエスト情報を抽象化したインターフェース
import org.springframework.web.context.request.WebRequest;
// パス変数・クエリの型変換失敗時の例外
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
// Spring 標準 MVC 例外を一括処理する基底クラス
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// 全コントローラ共通の例外ハンドラ（標準 MVC 例外も {status, message} 契約に統一する）
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // サーバ内部エラーの詳細をサーバログにだけ残すためのロガー
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 共通のエラーレスポンス本体を表す record
    public record ErrorResponse(int status, String message) {
    }

    // 不正な引数（例：月フォーマット不正）を400として処理するハンドラ
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        // 400 のエラーレスポンスを返す
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                // 本体にステータスと例外メッセージを格納する
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    // リソース未存在（404）を処理するハンドラ
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        // 404 のエラーレスポンスを返す
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                // 本体にステータスと例外メッセージを格納する
                .body(new ErrorResponse(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    // 重複（409）を処理するハンドラ
    @ExceptionHandler(DuplicateException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateException ex) {
        // 409 のエラーレスポンスを返す
        return ResponseEntity.status(HttpStatus.CONFLICT)
                // 本体にステータスと例外メッセージを格納する
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), ex.getMessage()));
    }

    // パス変数・クエリの型不一致（例：GET /api/expenses/abc）を400として処理するハンドラ
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // どのパラメータが不正かは契約上公開してよいため名前だけを付ける（内部値は含めない）
        String message = ErrorMessages.INVALID_PARAMETER + ex.getName();
        // 400 のエラーレスポンスを返す
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                // 本体にステータスと安全なメッセージを格納する
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), message));
    }

    // DB アクセス障害（500）を処理するハンドラ。詳細はログにのみ残し、外部には汎用文言を返す
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex) {
        // 障害の詳細はサーバログにだけ記録する（外部に内部情報を漏らさない）
        log.error("データアクセス中に例外が発生しました", ex);
        // 500 と汎用メッセージのエラーレスポンスを返す
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                // 本体にステータスと汎用の安全メッセージを格納する
                .body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), ErrorMessages.INTERNAL_ERROR));
    }

    // 上記いずれにも当てはまらない想定外の実行時例外を500としてフォールバック処理するハンドラ
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // 想定外の例外の詳細はサーバログにだけ記録する（スタックトレースを外部に出さない）
        log.error("想定外の例外が発生しました", ex);
        // 500 と汎用メッセージのエラーレスポンスを返す
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                // 本体にステータスと汎用の安全メッセージを格納する
                .body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), ErrorMessages.INTERNAL_ERROR));
    }

    // バリデーション違反（400）を基底クラス経由で受け取り、{status, message} 形式に整形する
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            // 発生したバリデーション例外
            MethodArgumentNotValidException ex,
            // レスポンスヘッダ
            HttpHeaders headers,
            // 基底クラスが決めたステータス（400）
            HttpStatusCode status,
            // リクエスト情報
            WebRequest request) {
        // 最初のフィールドエラーを取得する
        var fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst();
        // 「フィールド名: メッセージ」形式の文言を組み立てる
        String message = fieldError
                // フィールドエラーが存在する場合に整形する
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                // 存在しない場合の既定メッセージ
                .orElse("validation error");
        // 400 のエラーレスポンスを返す
        return ResponseEntity.status(status)
                // 本体にステータスとメッセージを格納する
                .body(new ErrorResponse(status.value(), message));
    }

    // 必須パラメータ欠落（例：GET /api/expenses/summary の month 欠落）を400として整形する
    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            // 発生したパラメータ欠落例外
            MissingServletRequestParameterException ex,
            // レスポンスヘッダ
            HttpHeaders headers,
            // 基底クラスが決めたステータス（400）
            HttpStatusCode status,
            // リクエスト情報
            WebRequest request) {
        // 不足しているパラメータ名は契約上公開してよいため文言に含める
        String message = ErrorMessages.MISSING_PARAMETER + ex.getParameterName();
        // 400 のエラーレスポンスを返す
        return ResponseEntity.status(status)
                // 本体にステータスと安全なメッセージを格納する
                .body(new ErrorResponse(status.value(), message));
    }

    // 上記以外の Spring 標準 MVC 例外（不正 JSON・未対応メソッド・未対応メディアタイプ等）の本体を統一整形する
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            // 発生した例外
            Exception ex,
            // 基底クラスが用意した本体（ProblemDetail 等。本実装では使わない）
            Object body,
            // レスポンスヘッダ
            HttpHeaders headers,
            // 基底クラスが決めたステータス
            HttpStatusCode statusCode,
            // リクエスト情報
            WebRequest request) {
        // サーバ起因（5xx）の場合は詳細をサーバログに残し、外部には汎用文言を返す
        if (statusCode.is5xxServerError()) {
            // 5xx の詳細をサーバログにだけ記録する
            log.error("リクエスト処理中にサーバエラーが発生しました", ex);
        }
        // 5xx ならサーバ内部エラー文言、それ以外（4xx）はリクエスト不正の汎用文言を選ぶ
        String message = statusCode.is5xxServerError() ? ErrorMessages.INTERNAL_ERROR : ErrorMessages.BAD_REQUEST;
        // 基底クラスが決めたステータスのまま、統一フォーマットのエラーレスポンスを返す
        return ResponseEntity.status(statusCode)
                // 元のヘッダを引き継ぐ（Allow ヘッダ等を保持するため）
                .headers(headers)
                // 本体にステータスと安全なメッセージを格納する
                .body(new ErrorResponse(statusCode.value(), message));
    }
}
