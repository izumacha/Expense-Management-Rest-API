// 例外パッケージ
package com.izumacha.expensetracker.exception;

// リソースが見つからない場合に投げる実行時例外
public class NotFoundException extends RuntimeException {

    // メッセージを受け取るコンストラクタ
    public NotFoundException(String message) {
        // 親クラスにメッセージを渡す
        super(message);
    }
}
