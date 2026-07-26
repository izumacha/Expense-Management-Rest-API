// Web 横断ユーティリティのテストパッケージ
package com.izumacha.expensetracker.web;

// レスポンス本体の JSON を検証用に読み戻すための Jackson の木構造ノード
import com.fasterxml.jackson.databind.JsonNode;
// JSON へ直列化・逆直列化するための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
// ERROR ディスパッチ時の属性名の定数（jakarta.servlet.error.status_code 等）
import jakarta.servlet.RequestDispatcher;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// HTTP メディアタイプを表す型（content-type の検証に使う）
import org.springframework.http.MediaType;
// テスト用のモック HTTP リクエスト
import org.springframework.mock.web.MockHttpServletRequest;
// テスト用のモック HTTP レスポンス
import org.springframework.mock.web.MockHttpServletResponse;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// ApiErrorController（/error の自前実装）が {status, message} 契約で応答することを検証する。
// Spring Boot 既定の BasicErrorController は /error 直接アクセスで {timestamp,status:999,error,path}
// 形式の契約外応答を返し、フィルタ層から漏れた例外（ERROR ディスパッチ）も同形式になっていた。
// 本コントローラがその両経路を契約形式に統一することを、Spring コンテキストを使わず
// コントローラを直接呼び出す純粋ユニットテストで確認する（共通規約 §11）。
class ApiErrorControllerTest {

    // JSON の書き出し・読み戻しに使うマッパー
    private final ObjectMapper objectMapper = new ObjectMapper();

    // テスト対象のコントローラ（ObjectMapper を直接渡して生成する）
    private final ApiErrorController controller = new ApiErrorController(objectMapper);

    // 指定のステータスコード属性（null なら属性なし）で handleError を実行しレスポンスを返すヘルパー
    private MockHttpServletResponse handleError(Integer errorStatusCode) throws Exception {
        // モックのリクエストを生成する
        MockHttpServletRequest request = new MockHttpServletRequest();
        // ERROR ディスパッチを模す場合はコンテナが設定する属性を積む
        if (errorStatusCode != null) {
            // 元のエラーステータスコードを属性として設定する
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, errorStatusCode);
        }
        // モックのレスポンスを生成する
        MockHttpServletResponse response = new MockHttpServletResponse();
        // テスト対象のエラーハンドリングを実行する
        controller.handleError(request, response);
        // 書き出されたレスポンスを返す
        return response;
    }

    // レスポンス本体の JSON を読み戻すヘルパー
    private JsonNode body(MockHttpServletResponse response) throws Exception {
        // 本体の文字列を JSON の木構造として読み戻す
        return objectMapper.readTree(response.getContentAsString());
    }

    // /error への直接アクセス（エラー属性なし）は 500 と契約形式（{status, message}）になることを検証する。
    // BasicErrorController の {timestamp, status:999, error:"None", path} 形式に化けないことの確認
    @Test
    void 直接アクセスは500で統一形式() throws Exception {
        // 属性なし（直接 GET /error を模す）でハンドリングする
        MockHttpServletResponse response = handleError(null);

        // HTTP ステータスが 500 であることを検証する
        assertThat(response.getStatus()).isEqualTo(500);
        // content-type が JSON であることを検証する
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        // 本体の status が 500 であることを検証する
        assertThat(body(response).get("status").asInt()).isEqualTo(500);
        // 本体の message が汎用の安全文言（内部詳細を含まない）であることを検証する
        assertThat(body(response).get("message").asText()).isEqualTo(ErrorMessages.INTERNAL_ERROR);
        // 既定実装の契約外フィールド（timestamp）が存在しないことを検証する
        assertThat(body(response).has("timestamp")).isFalse();
        // 既定実装の契約外フィールド（path）が存在しないことを検証する
        assertThat(body(response).has("path")).isFalse();
    }

    // フィルタ層から漏れた例外の ERROR ディスパッチ（status_code=500）も契約形式になることを検証する
    @Test
    void エラーディスパッチの500は統一形式() throws Exception {
        // 500 のエラーステータス属性付きでハンドリングする
        MockHttpServletResponse response = handleError(500);

        // HTTP ステータスが 500 であることを検証する
        assertThat(response.getStatus()).isEqualTo(500);
        // 本体の status が 500 であることを検証する
        assertThat(body(response).get("status").asInt()).isEqualTo(500);
        // 本体の message が汎用の安全文言であることを検証する
        assertThat(body(response).get("message").asText()).isEqualTo(ErrorMessages.INTERNAL_ERROR);
    }

    // 4xx の ERROR ディスパッチ（例: フィルタ層の sendError(400)）は元のステータスを保ち、
    // 内部詳細を含まないリクエスト不正の汎用文言になることを検証する
    @Test
    void エラーディスパッチの4xxは元ステータスと汎用文言() throws Exception {
        // 400 のエラーステータス属性付きでハンドリングする
        MockHttpServletResponse response = handleError(400);

        // HTTP ステータスが元の 400 のままであることを検証する
        assertThat(response.getStatus()).isEqualTo(400);
        // 本体の status が 400 であることを検証する
        assertThat(body(response).get("status").asInt()).isEqualTo(400);
        // 本体の message がリクエスト不正の汎用文言であることを検証する
        assertThat(body(response).get("message").asText()).isEqualTo(ErrorMessages.BAD_REQUEST);
    }

    // HttpStatus に解決できない未知のステータスコードは安全側の 500 として扱うことを検証する（fail-safe）
    @Test
    void 未知のステータスコードは500へフォールバック() throws Exception {
        // 存在しないステータスコード（999）の属性付きでハンドリングする
        MockHttpServletResponse response = handleError(999);

        // HTTP ステータスが安全側の 500 になることを検証する
        assertThat(response.getStatus()).isEqualTo(500);
        // 本体の status も 500 であることを検証する
        assertThat(body(response).get("status").asInt()).isEqualTo(500);
        // 本体の message が汎用の安全文言であることを検証する
        assertThat(body(response).get("message").asText()).isEqualTo(ErrorMessages.INTERNAL_ERROR);
    }
}
