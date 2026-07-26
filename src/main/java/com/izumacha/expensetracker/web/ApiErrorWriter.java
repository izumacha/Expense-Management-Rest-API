// Web 横断のユーティリティを置くパッケージ
package com.izumacha.expensetracker.web;

// JSON へ直列化するための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;
// HTTP レスポンスを操作するためのサーブレット API
import jakarta.servlet.http.HttpServletResponse;
// 文字コードを定数で扱うためのクラス
import java.nio.charset.StandardCharsets;
// 入出力例外型
import java.io.IOException;
// 出力順を保つマップ実装（status を先・message を後にする）
import java.util.LinkedHashMap;
// マップのインターフェース
import java.util.Map;
// HTTP ステータスを表す列挙
import org.springframework.http.HttpStatus;
// JSON のメディアタイプ定数を参照する
import org.springframework.http.MediaType;

// フィルタ層から {status, message} 形式のエラー応答を書き出す共通ユーティリティ。
// コントローラより手前のフィルタで発生したエラーは @RestControllerAdvice では整形できないため、
// ここでレスポンス本体を直接書き出して GlobalExceptionHandler と同じ契約に揃える（§6 一元管理）。
public final class ApiErrorWriter {

    // インスタンス化を防ぐための private コンストラクタ（静的メソッドだけを提供するため）
    private ApiErrorWriter() {
    }

    // 指定ステータスと安全な文言で {status, message} の JSON 応答を書き出す
    public static void write(HttpServletResponse response, ObjectMapper objectMapper,
                             HttpStatus status, String message) throws IOException {
        // 応答の HTTP ステータスコードを設定する
        response.setStatus(status.value());
        // 応答の content-type を UTF-8 の JSON に設定する
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 文字エンコーディングを UTF-8 に設定する（日本語の文字化けを防ぐ）
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 出力順を保つマップに status と message を詰める
        Map<String, Object> body = new LinkedHashMap<>();
        // ステータスコードの数値を格納する
        body.put("status", status.value());
        // 外部公開して安全なメッセージを格納する
        body.put("message", message);
        // マップを JSON 文字列へ変換して応答本体に書き出す
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
