// 例外パッケージ
package com.izumacha.expensetracker.exception;

// 一意制約に違反する重複が発生した場合に投げる実行時例外
public class DuplicateException extends RuntimeException {

    // メッセージを受け取るコンストラクタ
    public DuplicateException(String message) {
        // 親クラスにメッセージを渡す
        super(message);
    }
}
