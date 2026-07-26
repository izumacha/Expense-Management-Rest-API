// 例外パッケージ
package com.izumacha.expensetracker.exception;

// 支出から参照中のカテゴリを削除しようとした場合に投げる実行時例外（409 相当）
public class CategoryInUseException extends RuntimeException {

    // メッセージを受け取るコンストラクタ
    public CategoryInUseException(String message) {
        // 親クラスにメッセージを渡す
        super(message);
    }

    // メッセージと原因例外を受け取るコンストラクタ（握り潰さず原因を連鎖させる。共通規約 §6）
    public CategoryInUseException(String message, Throwable cause) {
        // 親クラスにメッセージと原因例外を渡す
        super(message, cause);
    }
}
