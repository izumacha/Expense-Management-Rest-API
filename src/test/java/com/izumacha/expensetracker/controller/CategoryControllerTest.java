// コントローラのテストパッケージ
package com.izumacha.expensetracker.controller;

// カテゴリ作成リクエスト DTO を参照する（NFD正規化の回帰テストでサービスへの引数を検証するため）
import com.izumacha.expensetracker.dto.request.CreateCategoryRequest;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// 参照中カテゴリの削除禁止例外を参照する
import com.izumacha.expensetracker.exception.CategoryInUseException;
// 楽観ロック競合例外を参照する（同時更新レースの 409 応答の検証に使う）
import com.izumacha.expensetracker.exception.ConflictException;
// 重複例外を参照する
import com.izumacha.expensetracker.exception.DuplicateException;
// 外部向けエラーメッセージ定数を参照する（競合時の安全な文言の検証に使う）
import com.izumacha.expensetracker.exception.ErrorMessages;
// 未存在例外を参照する
import com.izumacha.expensetracker.exception.NotFoundException;
// カテゴリサービスを参照する
import com.izumacha.expensetracker.service.CategoryService;

// 一覧の戻り型
import java.util.List;
// Unicode 正規化（NFD分解表現を作ってNFC正規化の回帰テストに使うため）
import java.text.Normalizer;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// サービスへ渡る引数（Pageable）を捕捉するためのキャプチャ
import org.mockito.ArgumentCaptor;
// ページ指定（ページ番号・件数・並び順）を表す型
import org.springframework.data.domain.Pageable;
// 並び順（どの列で昇順/降順に並べるか）を表す型
import org.springframework.data.domain.Sort;
// MockMvc の自動設定（フィルタの有効・無効を制御する）アノテーション
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
// Web スライステストを有効化するアノテーション
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
// サービスをモック Bean として差し込むアノテーション
import org.springframework.boot.test.mock.mockito.MockBean;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;
// HTTP リクエストを擬似実行するクライアント
import org.springframework.test.web.servlet.MockMvc;

// any() マッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.any;
// 特定値との一致を検証するマッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.eq;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// void メソッドが例外を投げるようスタブする doThrow を取り込む（Mockito）
import static org.mockito.Mockito.doThrow;
// モックへの呼び出しを検証する verify を取り込む（Mockito）
import static org.mockito.Mockito.verify;
// 値を検証する assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
// POST リクエストを組み立てる post を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
// PUT リクエストを組み立てる put を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
// DELETE リクエストを組み立てる delete を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
// GET リクエストを組み立てる get を取り込む
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
// レスポンスのステータスを検証する status を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
// レスポンスヘッダを検証する header を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
// レスポンス本体を JSONPath で検証する jsonPath を取り込む
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

// CategoryController を Web スライスでテストする（サービスはモック）
@WebMvcTest(CategoryController.class)
// レート制限フィルタは別テストで検証するため、ここでは無効化してコントローラの挙動に集中する
@AutoConfigureMockMvc(addFilters = false)
class CategoryControllerTest {

    // 擬似 HTTP リクエストを送るクライアント
    @Autowired
    private MockMvc mockMvc;

    // コントローラが依存するカテゴリサービスのモック
    @MockBean
    private CategoryService categoryService;

    // POST: 正しい入力なら 201 Created と本体が返ることを検証する
    @Test
    void カテゴリ作成_正常時は201() throws Exception {
        // サービスが返す DTO を用意する
        when(categoryService.create(any()))
                // ID 採番済みのカテゴリ DTO を返す
                .thenReturn(new CategoryResponse(1L, "食費"));

        // 正しい JSON ボディで POST する
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 名前を持つ本体を渡す
                        .content("""
                                {"name":"食費"}
                                """))
                // ステータスが 201 であることを検証する
                .andExpect(status().isCreated())
                // Location ヘッダが作成したカテゴリを指していることを検証する
                .andExpect(header().string("Location", "/api/categories/1"))
                // 本体の id が 1 であることを検証する
                .andExpect(jsonPath("$.id").value(1))
                // 本体の name が食費であることを検証する
                .andExpect(jsonPath("$.name").value("食費"));
    }

    // POST: 名前が空白なら検証で 400 になることを検証する
    @Test
    void カテゴリ作成_空白名は400() throws Exception {
        // 空文字の名前で POST する
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 空の名前を持つ本体を渡す
                        .content("""
                                {"name":""}
                                """))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status フィールドが 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400));
    }

    // POST: 全角スペースのみの名前は400になることを検証する。
    // @NotBlank は ASCII 空白のみを trim() で除去するため、全角スペース（U+3000）だけの値を
    // 誤って「空白でない」と判定しうる。CreateCategoryRequest の正規コンストラクタで
    // strip()（Unicode 対応）してから検証することで、この誤判定を防いでいることを確認する。
    @Test
    void カテゴリ作成_全角スペースのみの名前は400() throws Exception {
        // 全角スペース（U+3000）1文字のみの名前で POST する
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 全角スペースのみの名前を持つ本体を渡す
                        .content("{\"name\":\"　\"}"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status フィールドが 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400));
    }

    // POST: 名前が 50 文字超なら検証で 400 になることを検証する
    @Test
    void カテゴリ作成_長すぎる名前は400() throws Exception {
        // 51 文字の名前を作る（"あ" を 51 回繰り返す）
        String longName = "あ".repeat(51);
        // 長すぎる名前で POST する
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 51 文字の名前を持つ本体を渡す
                        .content("{\"name\":\"" + longName + "\"}"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest());
    }

    // POST: 基本多言語面(BMP)外の文字（絵文字などのサロゲートペア）を含む名前でも、
    // コードポイント数が上限(50)以内なら DTO 検証で弾かれず 201 になることを検証する。
    // 標準の @Size（UTF-16 コード単位数で数える）のままだと、この名前は
    // codePointCount=50・length()=100 となり誤って 400 になっていた（@MaxCodePoints への
    // 変更で修正した回帰を防ぐテスト）。
    @Test
    void カテゴリ作成_絵文字がちょうど50コードポイントなら201() throws Exception {
        // U+1F600（😀）を50回繰り返した名前を用意する（コードポイント数50、UTF-16コード単位数100）
        String emojiName = "😀".repeat(50);
        // サービスが返す DTO を用意する（DTO 検証を通過したことの確認が主目的のため中身は簡略化）
        when(categoryService.create(any()))
                // ID 採番済みのカテゴリ DTO を返す
                .thenReturn(new CategoryResponse(1L, emojiName));

        // 絵文字混じりの名前で POST する
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 50コードポイントの絵文字名を持つ本体を渡す
                        .content("{\"name\":\"" + emojiName + "\"}"))
                // DTO 検証で弾かれず 201 になることを検証する（400 ではない）
                .andExpect(status().isCreated());
    }

    // POST: NFD分解表現（濁点付き仮名が基底文字＋結合文字の複数コードポイントに分解された表現）は
    // コードポイント数が多くなるため、NFC正規化前のまま検証すると上限を超えて誤って400になっていた。
    // CreateCategoryRequestの正規コンストラクタでNFC正規化してから@MaxCodePointsが検証することで、
    // 合成後は上限内に収まる名前が誤って拒否されず201になり、かつサービスに渡る名前がNFC合成済みに
    // なっていることを検証する（回帰防止テスト）。
    @Test
    void カテゴリ作成_NFD分解表現は合成後のコードポイント数で検証され201() throws Exception {
        // 濁点付きひらがな「が」（NFCでは1コードポイント）をNFD分解表現に変換する
        // （「か」+結合文字U+3099の2コードポイントになる）
        String decomposedGa = Normalizer.normalize("が", Normalizer.Form.NFD);
        // 26回繰り返すとNFD表現では52コードポイントとなり上限(50)を超えるが、
        // NFC合成後は26コードポイントとなり上限内に収まる
        String nfdName = decomposedGa.repeat(26);
        // 合成後に期待される名前（「が」を26回繰り返したもの）
        String composedName = "が".repeat(26);
        // サービスが返す DTO を用意する
        when(categoryService.create(any()))
                // ID 採番済みのカテゴリ DTO を返す
                .thenReturn(new CategoryResponse(1L, composedName));

        // NFD分解表現の名前でPOSTする
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // NFD分解表現（52コードポイント）の名前を持つ本体を渡す
                        .content("{\"name\":\"" + nfdName + "\"}"))
                // DTO 検証で誤って弾かれず 201 になることを検証する（400 ではない）
                .andExpect(status().isCreated());

        // サービスに渡されたリクエストの名前がNFC合成済み（26コードポイント）であることを検証する
        ArgumentCaptor<CreateCategoryRequest> captor = ArgumentCaptor.forClass(CreateCategoryRequest.class);
        verify(categoryService).create(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo(composedName);
    }

    // POST: NUL（U+0000）を含む名前は Bean Validation で 400 になることを検証する。
    // NUL は @NotBlank / @MaxCodePoints をすべて通過する一方、PostgreSQL の text/varchar 列には
    // 保存できず DB 層で初めてエラーになり、誤った 404/500 に化けていた。@NoControlCharacters が
    // 入力段階で 400 として弾くことを確認する（JSON のユニコードエスケープ（バックスラッシュ + u0000）で NUL を表現し、
    // ソースファイル自体には不可視の制御文字を埋め込まない）
    @Test
    void カテゴリ作成_NULを含む名前は400() throws Exception {
        // NUL（JSON のユニコードエスケープで表現）を含む名前で POST する
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 名前の途中に NUL を含む本体を渡す（\\u0000 は JSON パース後に実際の NUL になる）
                        .content("{\"name\":\"食\\u0000費\"}"))
                // ステータスが 400 であることを検証する（DB 由来の 404/500 ではない）
                .andExpect(status().isBadRequest())
                // 本体の status フィールドが 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400));
    }

    // POST: サービスが重複例外を投げると 409 になることを検証する
    @Test
    void カテゴリ作成_重複は409() throws Exception {
        // サービスの create が DuplicateException を投げるようモックする
        when(categoryService.create(any()))
                // 重複例外を投げる
                .thenThrow(new DuplicateException("category name already exists: 食費"));

        // 既存と同名で POST する
        mockMvc.perform(post("/api/categories")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 名前を持つ本体を渡す
                        .content("""
                                {"name":"食費"}
                                """))
                // ステータスが 409 であることを検証する
                .andExpect(status().isConflict())
                // 本体の status フィールドが 409 であることを検証する
                .andExpect(jsonPath("$.status").value(409));
    }

    // GET: 一覧取得は 200 とページ形式のサービス結果を返すことを検証する
    @Test
    void カテゴリ一覧_200で返る() throws Exception {
        // サービスが 1 件のカテゴリを含むページを返すようモックする
        when(categoryService.findAll(any()))
                // 食費 1 件・全 1 件のページを返す
                .thenReturn(new PageResponse<>(List.of(new CategoryResponse(1L, "食費")), 0, 20, 1, 1));

        // 一覧エンドポイントへ GET する
        mockMvc.perform(get("/api/categories"))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk())
                // 本体 content 先頭の name が食費であることを検証する
                .andExpect(jsonPath("$.content[0].name").value("食費"));
    }

    // GET: クライアントが sort クエリを付けても無視され、サービスへは常に id 昇順に固定した Pageable が
    // 渡ることを検証する。未検証の並び順による 500 を防ぎ（§9）、ページングの決定性を担保する（§8）。
    @Test
    void カテゴリ一覧_sortクエリは無視されid昇順に固定される() throws Exception {
        // サービスが空のページを返すようモックする
        when(categoryService.findAll(any()))
                // 0 件・既定サイズ 20 の空ページを返す
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        // 別の列の降順を指定する sort を付けて一覧を GET する（無視されるので 200 で返るはず）
        mockMvc.perform(get("/api/categories").param("sort", "name,desc"))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk());

        // サービスへ渡された Pageable を捕捉するキャプチャを用意する
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        // findAll 呼び出しの Pageable 引数を捕捉する
        verify(categoryService).findAll(pageableCaptor.capture());
        // 捕捉した Pageable の並び順が id 昇順に固定されていることを検証する
        assertThat(pageableCaptor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
    }

    // GET /{id}: 作成時の Location（/api/categories/{id}）が指すリソースを取得でき、200 と本体を返すことを検証する
    @Test
    void カテゴリ詳細_200で返る() throws Exception {
        // サービスの findById が対象カテゴリを返すようモックする
        when(categoryService.findById(1L))
                // ID 採番済みのカテゴリ DTO を返す
                .thenReturn(new CategoryResponse(1L, "食費"));

        // Location が指す詳細エンドポイントへ GET する
        mockMvc.perform(get("/api/categories/1"))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk())
                // 本体の id が 1 であることを検証する
                .andExpect(jsonPath("$.id").value(1))
                // 本体の name が食費であることを検証する
                .andExpect(jsonPath("$.name").value("食費"));
    }

    // GET /{id}: 対象が無ければサービスの例外で 404 になることを検証する
    @Test
    void カテゴリ詳細_不在時は404() throws Exception {
        // サービスの findById が NotFoundException を投げるようモックする
        when(categoryService.findById(404L))
                // 未存在例外を投げる
                .thenThrow(new NotFoundException("category not found: id=404"));

        // 存在しない ID で詳細を GET する
        mockMvc.perform(get("/api/categories/404"))
                // ステータスが 404 であることを検証する
                .andExpect(status().isNotFound())
                // 本体の status フィールドが 404 であることを検証する
                .andExpect(jsonPath("$.status").value(404));
    }

    // PUT: 正しい入力なら 200 と更新後の本体が返ることを検証する
    @Test
    void カテゴリ更新_正常時は200() throws Exception {
        // サービスの update が更新後の DTO を返すようモックする
        when(categoryService.update(eq(1L), any()))
                // 更新後（交通費）のカテゴリ DTO を返す
                .thenReturn(new CategoryResponse(1L, "交通費"));

        // 正しい JSON ボディで PUT する
        mockMvc.perform(put("/api/categories/1")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 更新後の名前を持つ本体を渡す
                        .content("""
                                {"name":"交通費"}
                                """))
                // ステータスが 200 であることを検証する
                .andExpect(status().isOk())
                // 本体の id が 1 であることを検証する
                .andExpect(jsonPath("$.id").value(1))
                // 本体の name が交通費であることを検証する
                .andExpect(jsonPath("$.name").value("交通費"));
    }

    // PUT: 名前が空白なら検証で 400 になることを検証する（更新経路でも @Valid が効くことを確認）
    @Test
    void カテゴリ更新_空白名は400() throws Exception {
        // 空文字の名前で PUT する
        mockMvc.perform(put("/api/categories/1")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 空の名前を持つ本体を渡す
                        .content("""
                                {"name":""}
                                """))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status フィールドが 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400));
    }

    // PUT: 全角スペースのみの名前は400になることを検証する（更新経路でも Unicode 対応の正規化後に
    // @NotBlank が効くことを確認。理由は POST 側のテストと同じ）
    @Test
    void カテゴリ更新_全角スペースのみの名前は400() throws Exception {
        // 全角スペース（U+3000）1文字のみの名前で PUT する
        mockMvc.perform(put("/api/categories/1")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 全角スペースのみの名前を持つ本体を渡す
                        .content("{\"name\":\"　\"}"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest())
                // 本体の status フィールドが 400 であることを検証する
                .andExpect(jsonPath("$.status").value(400));
    }

    // PUT: 名前が 50 文字超なら検証で 400 になることを検証する（更新経路でも @MaxCodePoints が効くことを確認）
    @Test
    void カテゴリ更新_長すぎる名前は400() throws Exception {
        // 51 文字の名前を作る（"あ" を 51 回繰り返す）
        String longName = "あ".repeat(51);
        // 長すぎる名前で PUT する
        mockMvc.perform(put("/api/categories/1")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 51 文字の名前を持つ本体を渡す
                        .content("{\"name\":\"" + longName + "\"}"))
                // ステータスが 400 であることを検証する
                .andExpect(status().isBadRequest());
    }

    // PUT: 更新対象が無ければサービスの例外で 404 になることを検証する
    @Test
    void カテゴリ更新_対象不在時は404() throws Exception {
        // サービスの update が NotFoundException を投げるようモックする
        when(categoryService.update(eq(404L), any()))
                // 未存在例外を投げる
                .thenThrow(new NotFoundException("category not found: id=404"));

        // 存在しない ID で PUT する
        mockMvc.perform(put("/api/categories/404")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 正しい形式の本体を渡す（対象が無いことだけを検証する）
                        .content("""
                                {"name":"交通費"}
                                """))
                // ステータスが 404 であることを検証する
                .andExpect(status().isNotFound())
                // 本体の status フィールドが 404 であることを検証する
                .andExpect(jsonPath("$.status").value(404));
    }

    // PUT: サービスが重複例外を投げると 409 になることを検証する
    @Test
    void カテゴリ更新_重複は409() throws Exception {
        // サービスの update が DuplicateException を投げるようモックする
        when(categoryService.update(eq(1L), any()))
                // 重複例外を投げる
                .thenThrow(new DuplicateException("category name already exists: 交通費"));

        // 既存と同名で PUT する
        mockMvc.perform(put("/api/categories/1")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 名前を持つ本体を渡す
                        .content("""
                                {"name":"交通費"}
                                """))
                // ステータスが 409 であることを検証する
                .andExpect(status().isConflict())
                // 本体の status フィールドが 409 であることを検証する
                .andExpect(jsonPath("$.status").value(409));
    }

    // PUT: サービスが楽観ロック競合の例外（別の操作が先に更新した）を投げると 409 になり、
    // {status, message} 契約の安全な文言（再試行の案内）が返ることを検証する
    @Test
    void カテゴリ更新_同時更新の競合は409() throws Exception {
        // サービスの update が ConflictException を投げるようモックする
        when(categoryService.update(eq(1L), any()))
                // 楽観ロック競合例外を投げる（本番と同じ一元管理された文言を使う）
                .thenThrow(new ConflictException(ErrorMessages.CONCURRENT_CONFLICT));

        // 正しい形式の本体で PUT する（競合はサービス層で検知される想定）
        mockMvc.perform(put("/api/categories/1")
                        // JSON 形式であることを宣言する
                        .contentType("application/json")
                        // 名前を持つ本体を渡す
                        .content("""
                                {"name":"交通費"}
                                """))
                // ステータスが 409 であることを検証する
                .andExpect(status().isConflict())
                // 本体の status フィールドが 409 であることを検証する
                .andExpect(jsonPath("$.status").value(409))
                // 本体の message が安全な競合文言（内部詳細を含まない）であることを検証する
                .andExpect(jsonPath("$.message").value(ErrorMessages.CONCURRENT_CONFLICT));
    }

    // DELETE: 正常時は 204 No Content になることを検証する
    @Test
    void カテゴリ削除_正常時は204() throws Exception {
        // サービスの delete は何もしない（戻り値なし。既定で何もしないため明示のスタブは不要）

        // 削除エンドポイントへ DELETE する
        mockMvc.perform(delete("/api/categories/1"))
                // ステータスが 204 であることを検証する
                .andExpect(status().isNoContent());
    }

    // DELETE: 対象が無ければサービスの例外で 404 になることを検証する
    @Test
    void カテゴリ削除_不在時は404() throws Exception {
        // delete が NotFoundException を投げるようモックする
        doThrow(new NotFoundException("category not found: id=404"))
                // 対象のサービスメソッドを指定する
                .when(categoryService).delete(404L);

        // 存在しない ID で DELETE する
        mockMvc.perform(delete("/api/categories/404"))
                // ステータスが 404 であることを検証する
                .andExpect(status().isNotFound());
    }

    // DELETE: 支出から参照中ならサービスの例外で 409 になることを検証する
    @Test
    void カテゴリ削除_参照中は409() throws Exception {
        // delete が CategoryInUseException を投げるようモックする
        doThrow(new CategoryInUseException("category in use: id=1"))
                // 対象のサービスメソッドを指定する
                .when(categoryService).delete(1L);

        // 参照中の ID で DELETE する
        mockMvc.perform(delete("/api/categories/1"))
                // ステータスが 409 であることを検証する
                .andExpect(status().isConflict())
                // 本体の status フィールドが 409 であることを検証する
                .andExpect(jsonPath("$.status").value(409));
    }
}
