// 例外パッケージ
package com.izumacha.expensetracker.exception;

// 現在の状態と矛盾する操作（例：使用中カテゴリの削除）を拒否する 409 相当の実行時例外。
// メッセージは必ず外部公開して安全な文言に限定する前提で使う（内部詳細・内部 ID を入れない）。
public class ConflictException extends RuntimeException {

    // 外部公開して安全なメッセージを受け取るコンストラクタ
    public ConflictException(String message) {
        // 親クラスにメッセージを渡す
        super(message);
    }
}
