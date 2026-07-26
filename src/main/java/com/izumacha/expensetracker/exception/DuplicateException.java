// 例外パッケージ
package com.izumacha.expensetracker.exception;

// 一意制約に違反する重複が発生した場合に投げる実行時例外
public class DuplicateException extends RuntimeException {

    // メッセージを受け取るコンストラクタ
    public DuplicateException(String message) {
        // 親クラスにメッセージを渡す
        super(message);
    }

    // メッセージと原因例外を受け取るコンストラクタ（握り潰さず原因を連鎖させる。共通規約 §6）
    public DuplicateException(String message, Throwable cause) {
        // 親クラスにメッセージと原因例外を渡す
        super(message, cause);
    }
}
