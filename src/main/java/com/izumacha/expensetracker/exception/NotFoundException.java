// 例外パッケージ
package com.izumacha.expensetracker.exception;

// リソースが見つからない場合に投げる実行時例外
public class NotFoundException extends RuntimeException {

    // メッセージを受け取るコンストラクタ
    public NotFoundException(String message) {
        // 親クラスにメッセージを渡す
        super(message);
    }

    // メッセージと原因例外を受け取るコンストラクタ（握り潰さず原因を連鎖させる。共通規約 §6）
    public NotFoundException(String message, Throwable cause) {
        // 親クラスにメッセージと原因例外を渡す
        super(message, cause);
    }
}
