// 例外パッケージ
package com.izumacha.expensetracker.exception;

// クライアント起因の不正リクエスト（400）を表す実行時例外。
// メッセージは必ず外部公開して安全な文言に限定する前提で使う（内部詳細・生入力を入れない）。
public class InvalidRequestException extends RuntimeException {

    // 外部公開して安全なメッセージを受け取るコンストラクタ
    public InvalidRequestException(String message) {
        // 親クラスにメッセージを渡す
        super(message);
    }

    // 安全なメッセージと原因例外を受け取るコンストラクタ（原因はログ追跡用に保持し、外部へは出さない）
    public InvalidRequestException(String message, Throwable cause) {
        // 親クラスにメッセージと原因を渡す
        super(message, cause);
    }
}
