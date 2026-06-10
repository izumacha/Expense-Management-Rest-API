// 例外パッケージ
package com.izumacha.expensetracker.exception;

// 一意制約に違反する重複が発生した場合に投げる例外。
// 状態の競合（409）の一種なので ConflictException を継承し、409 への変換は共通のハンドラに任せる。
public class DuplicateException extends ConflictException {

    // メッセージを受け取るコンストラクタ
    public DuplicateException(String message) {
        // 親クラス（ConflictException）にメッセージを渡す
        super(message);
    }
}
