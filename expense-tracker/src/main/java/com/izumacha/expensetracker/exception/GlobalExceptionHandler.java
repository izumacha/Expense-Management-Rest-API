// 例外パッケージ
package com.izumacha.expensetracker.exception;

// HTTP ステータスを表す列挙
import org.springframework.http.HttpStatus;
// HTTP レスポンス全体を表すクラス
import org.springframework.http.ResponseEntity;
// バリデーション失敗時の例外
import org.springframework.web.bind.MethodArgumentNotValidException;
// 例外ハンドラを宣言するアノテーション
import org.springframework.web.bind.annotation.ExceptionHandler;
// 全コントローラ横断の例外処理を宣言するアノテーション
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 全コントローラ共通の例外ハンドラ
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 共通のエラーレスポンス本体を表す record
    public record ErrorResponse(int status, String message) {
    }

    // バリデーション違反（400）を処理するハンドラ
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        // 最初のフィールドエラーを取得する
        var fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst();
        // 「フィールド名: メッセージ」形式の文言を組み立てる
        String message = fieldError
                // フィールドエラーが存在する場合に整形する
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                // 存在しない場合の既定メッセージ
                .orElse("validation error");
        // 400 のエラーレスポンスを返す
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                // 本体にステータスとメッセージを格納する
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), message));
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
}
