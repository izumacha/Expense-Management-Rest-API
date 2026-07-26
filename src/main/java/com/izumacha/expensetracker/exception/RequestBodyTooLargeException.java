// 例外パッケージ
package com.izumacha.expensetracker.exception;

// リクエスト本文が許容サイズを超えた場合に投げる実行時例外（413 相当）。
// RequestBodySizeLimitFilter が Content-Length を偽る／付けないクライアントに備えて
// 実読み取りバイト数そのものを上限で打ち切る際、下位の JSON パーサ内部（Jackson のストリーム読取中）
// から送出される。GlobalExceptionHandler が捕捉して {status:413, message} 契約に整形する。
public class RequestBodyTooLargeException extends RuntimeException {

    // メッセージを受け取るコンストラクタ
    public RequestBodyTooLargeException(String message) {
        // 親クラスにメッセージを渡す
        super(message);
    }
}
