// 例外パッケージ
package com.izumacha.expensetracker.exception;

// 同時実行の別操作との競合（楽観ロック失敗＝版番号の不一致など）が発生した場合に投げる実行時例外（409 相当）。
// 「対象が存在しない」（NotFoundException）とも「名前の重複」（DuplicateException）とも異なり、
// 対象は存在するが別の操作が先に変更していたため今回の操作を安全に適用できない状態を表す
public class ConflictException extends RuntimeException {

    // メッセージを受け取るコンストラクタ
    public ConflictException(String message) {
        // 親クラスにメッセージを渡す
        super(message);
    }

    // メッセージと原因例外を受け取るコンストラクタ（握り潰さず原因を連鎖させる。共通規約 §6）
    public ConflictException(String message, Throwable cause) {
        // 親クラスにメッセージと原因例外を渡す
        super(message, cause);
    }
}
