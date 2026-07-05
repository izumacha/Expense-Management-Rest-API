// 例外パッケージ
package com.izumacha.expensetracker.exception;

// 支出から参照中のカテゴリを削除しようとした場合に投げる実行時例外（409 相当）
public class CategoryInUseException extends RuntimeException {

    // メッセージを受け取るコンストラクタ
    public CategoryInUseException(String message) {
        // 親クラスにメッセージを渡す
        super(message);
    }
}
