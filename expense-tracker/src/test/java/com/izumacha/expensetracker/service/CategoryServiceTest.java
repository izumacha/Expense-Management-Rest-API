// サービスのテストパッケージ
package com.izumacha.expensetracker.service;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// カテゴリ作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateCategoryRequest;
// カテゴリ更新リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.UpdateCategoryRequest;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// 参照中カテゴリの削除禁止例外を参照する
import com.izumacha.expensetracker.exception.CategoryInUseException;
// 楽観ロック競合例外を参照する（同時更新レースが 409 になることの検証に使う）
import com.izumacha.expensetracker.exception.ConflictException;
// 重複例外を参照する
import com.izumacha.expensetracker.exception.DuplicateException;
// 外部向けエラーメッセージ定数を参照する（競合時の安全な文言の検証に使う）
import com.izumacha.expensetracker.exception.ErrorMessages;
// 不正リクエスト例外を参照する（NFC 正規化後の文字数超過が 400 になることの検証に使う）
import com.izumacha.expensetracker.exception.InvalidRequestException;
// 未存在例外を参照する
import com.izumacha.expensetracker.exception.NotFoundException;
// カテゴリリポジトリを参照する
import com.izumacha.expensetracker.repository.CategoryRepository;
// 支出リポジトリを参照する（削除時の使用中チェックのモックに使う）
import com.izumacha.expensetracker.repository.ExpenseRepository;

// 一覧の戻り型
import java.util.List;
// 取得結果が無いかもしれないことを表す型
import java.util.Optional;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// Mockito を JUnit 5 と連携させる拡張
import org.junit.jupiter.api.extension.ExtendWith;
// 一意制約違反を表す例外
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
// ページ実体を組み立てる型
import org.springframework.data.domain.PageImpl;
// ページ指定（ページ番号・件数）を表す型
import org.springframework.data.domain.Pageable;
// モック対象を宣言するアノテーション
import org.mockito.InjectMocks;
// モックを生成するアノテーション
import org.mockito.Mock;
// Mockito の JUnit 5 拡張本体
import org.mockito.junit.jupiter.MockitoExtension;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
// 例外発生を検証する assertThatThrownBy を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThatThrownBy;
// any() マッチャを取り込む（Mockito）
import static org.mockito.ArgumentMatchers.any;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// 呼び出し検証の verify を取り込む（Mockito）
import static org.mockito.Mockito.verify;
// 呼び出されなかったことを検証する never を取り込む（Mockito）
import static org.mockito.Mockito.never;
// void メソッドが例外を投げるようスタブする doThrow を取り込む（Mockito）
import static org.mockito.Mockito.doThrow;

// CategoryService をモック依存だけでテストする（DB には接続しない）
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    // カテゴリリポジトリのモック
    @Mock
    private CategoryRepository categoryRepository;

    // 支出リポジトリのモック（削除時の使用中チェックで使う）
    @Mock
    private ExpenseRepository expenseRepository;

    // 上記モックを注入したテスト対象のサービス
    @InjectMocks
    private CategoryService categoryService;

    // ID を持つカテゴリを組み立てるヘルパー
    private Category category(long id, String name) {
        // 名前を指定してカテゴリを生成する
        Category c = new Category(name);
        // 採番済みを模すため ID を設定する
        c.setId(id);
        // 組み立てたカテゴリを返す
        return c;
    }

    // create: 同名が存在しなければ保存して DTO を返すことを検証する
    @Test
    void create_重複なしなら保存して返す() {
        // 作成リクエスト（食費）を用意する
        CreateCategoryRequest request = new CreateCategoryRequest("食費");
        // 同名チェックが false（重複なし）を返すようモックする
        when(categoryRepository.existsByNameIgnoreCase("食費")).thenReturn(false);
        // 保存時は ID 採番済みのカテゴリを返すようモックする
        when(categoryRepository.save(any(Category.class))).thenReturn(category(1L, "食費"));

        // テスト対象の create を呼び出す
        CategoryResponse response = categoryService.create(request);

        // 返却 DTO の ID が採番値であることを検証する
        assertThat(response.id()).isEqualTo(1L);
        // 名前が一致することを検証する
        assertThat(response.name()).isEqualTo("食費");
    }

    // create: 前後に空白を含む名前は正規化（strip）されてから重複チェック・保存されることを検証する
    @Test
    void create_前後の空白は正規化してから重複チェックする() {
        // 前後に空白を含む作成リクエスト（" 食費"）を用意する
        CreateCategoryRequest request = new CreateCategoryRequest(" 食費 ");
        // 正規化後の名前（"食費"）で重複チェックが false（重複なし）を返すようモックする
        when(categoryRepository.existsByNameIgnoreCase("食費")).thenReturn(false);
        // 保存時は正規化済み名前で採番済みのカテゴリを返すようモックする
        when(categoryRepository.save(any(Category.class))).thenReturn(category(1L, "食費"));

        // テスト対象の create を呼び出す
        CategoryResponse response = categoryService.create(request);

        // 返却 DTO の名前が正規化済み（空白なし）であることを検証する
        assertThat(response.name()).isEqualTo("食費");
        // 前後空白付きの生の値では重複チェックを呼んでいないことを検証する
        verify(categoryRepository, never()).existsByNameIgnoreCase(" 食費 ");
    }

    // create: 濁点付き仮名の分解表現(NFD)は合成表現(NFC)へ正規化してから重複チェックすることを検証する。
    // "\u30D0\u30B9\u4EE3"(バス代)は「\u30D0(バ、合成済み1文字)+\u30B9(ス)+\u4EE3(代)」(NFC)と
    // 「\u30CF(ハ)+\u3099(濁点、結合文字)+\u30B9(ス)+\u4EE3(代)」(NFD)の2通りの符号化で
    // 見た目が同一になるため、NFC正規化が無いと別名として重複チェックをすり抜けてしまう
    // (Category.name のDB一意制約も単純な文字列比較のため防げない)。
    // コード中は Unicode コードポイントのエスケープ表記で明示する
    // (エディタ上では両表現が視覚的に区別できず、テストが実際にNFD入力を
    // 使っていることをソースコード上で保証できないため)。
    @Test
    void create_NFD分解表現の濁点はNFC合成表現へ正規化してから重複チェックする() {
        // "バス代"のNFD分解表現("\u30CF\u3099\u30B9\u4EE3")で作成リクエストを用意する
        CreateCategoryRequest request = new CreateCategoryRequest("\u30CF\u3099\u30B9\u4EE3");
        // NFC正規化後の名前("\u30D0\u30B9\u4EE3"、合成表現)で重複チェックが false(重複なし)を返すようモックする
        when(categoryRepository.existsByNameIgnoreCase("\u30D0\u30B9\u4EE3")).thenReturn(false);
        // 保存時はNFC正規化済み名前で採番済みのカテゴリを返すようモックする
        when(categoryRepository.save(any(Category.class))).thenReturn(category(1L, "\u30D0\u30B9\u4EE3"));

        // テスト対象の create を呼び出す
        CategoryResponse response = categoryService.create(request);

        // 返却 DTO の名前がNFC正規化済み(合成表現)であることを検証する
        assertThat(response.name()).isEqualTo("\u30D0\u30B9\u4EE3");
        // NFD分解表現の生の値では重複チェックを呼んでいないことを検証する
        verify(categoryRepository, never()).existsByNameIgnoreCase("\u30CF\u3099\u30B9\u4EE3");
    }

    // create: NFC 正規化で文字数が増えて上限を超える名前は InvalidRequestException（400 相当）に
    // なることを検証する。"\u0958"（デーヴァナーガリー文字 QA、合成済み1文字）は合成除外文字で、
    // NFC 正規化しても "\u0915"（KA）+"\u093C"（ヌクタ、結合文字）の 2 文字に分解されたままになる
    // ため、正規化前 50 文字 → 正規化後 100 文字となり varchar(50) に収まらない。
    // この超過を DB まで持ち込むと制約違反経由で誤って 409（重複）になってしまうため、
    // 保存前に 400 として拒否されること（DuplicateException ではないこと）を確認する。
    // コード中は Unicode コードポイントのエスケープ表記で明示する（テストが実際に合成済み
    // 1 文字の入力を使っていることをソースコード上で保証するため。上の NFD テストと同じ方針）
    @Test
    void create_NFC正規化後に50文字を超える名前は400例外() {
        // 正規化前はちょうど 50 文字（正規化後は 100 文字）になる名前で作成リクエストを用意する
        CreateCategoryRequest request = new CreateCategoryRequest("\u0958".repeat(50));

        // create 呼び出しで InvalidRequestException（400 相当）が投げられることを検証する
        assertThatThrownBy(() -> categoryService.create(request))
                // 例外型が InvalidRequestException であることを確認する（409 の重複例外ではない）
                .isInstanceOf(InvalidRequestException.class);
        // 保存が一度も呼ばれていないことを検証する
        verify(categoryRepository, never()).save(any());
    }

    // create: NFC 正規化しても文字数が変わらない、ちょうど上限（50 文字）の名前は
    // 通常どおり保存されることを検証する（境界値。上限ぴったりまでは 400 にならない）
    @Test
    void create_ちょうど50文字の名前は保存して返す() {
        // NFC 正規化で長さが変わらない "あ" を 50 回繰り返した境界値の名前を用意する
        String maxLengthName = "あ".repeat(50);
        // 上限ちょうどの名前で作成リクエストを用意する
        CreateCategoryRequest request = new CreateCategoryRequest(maxLengthName);
        // 同名チェックが false（重複なし）を返すようモックする
        when(categoryRepository.existsByNameIgnoreCase(maxLengthName)).thenReturn(false);
        // 保存時は ID 採番済みのカテゴリを返すようモックする
        when(categoryRepository.save(any(Category.class))).thenReturn(category(1L, maxLengthName));

        // テスト対象の create を呼び出す
        CategoryResponse response = categoryService.create(request);

        // 返却 DTO の名前が上限ちょうどの名前のままであることを検証する
        assertThat(response.name()).isEqualTo(maxLengthName);
    }

    // create: 基本多言語面(BMP)外の文字（サロゲートペア）を含む、ちょうど50コードポイントの
    // 名前は保存されることを検証する（境界値）。U+1F600（😀 GRINNING FACE）は Java の
    // String 内部表現では UTF-16 サロゲートペア2コード単位を要するため、String#length() で
    // 数えると 100（上限50を超過）になってしまうが、実際の文字数（コードポイント数）は50で
    // あり、PostgreSQL の varchar(50) にも収まる。normalizeName の再検証が
    // codePointCount（コードポイント基準）で数えていることを、この境界値で確認する。
    @Test
    void create_サロゲートペア文字がちょうど50コードポイントの名前は保存して返す() {
        // U+1F600 を50回繰り返した名前を用意する（コードポイント数50、UTF-16コード単位数100）
        String emojiName = "😀".repeat(50);
        // コードポイント数がちょうど50であることを前提として組み立てていることを自己検証する
        assertThat(emojiName.codePointCount(0, emojiName.length())).isEqualTo(50);
        // 上限ちょうどの名前で作成リクエストを用意する
        CreateCategoryRequest request = new CreateCategoryRequest(emojiName);
        // 同名チェックが false（重複なし）を返すようモックする
        when(categoryRepository.existsByNameIgnoreCase(emojiName)).thenReturn(false);
        // 保存時は ID 採番済みのカテゴリを返すようモックする
        when(categoryRepository.save(any(Category.class))).thenReturn(category(1L, emojiName));

        // テスト対象の create を呼び出す（400 にならず正常に保存されることを検証する）
        CategoryResponse response = categoryService.create(request);

        // 返却 DTO の名前が絵文字混じりの名前のままであることを検証する
        assertThat(response.name()).isEqualTo(emojiName);
    }

    // create: 同名が既に存在すれば DuplicateException になり保存されないことを検証する
    @Test
    void create_事前チェックで重複なら409例外() {
        // 作成リクエスト（食費）を用意する
        CreateCategoryRequest request = new CreateCategoryRequest("食費");
        // 同名チェックが true（重複あり）を返すようモックする
        when(categoryRepository.existsByNameIgnoreCase("食費")).thenReturn(true);

        // create 呼び出しで DuplicateException が投げられることを検証する
        assertThatThrownBy(() -> categoryService.create(request))
                // 例外型が DuplicateException であることを確認する
                .isInstanceOf(DuplicateException.class);
        // 保存が一度も呼ばれていないことを検証する
        verify(categoryRepository, never()).save(any());
    }

    // create: 事前チェックをすり抜けた同時実行の重複（DB 制約違反）も DuplicateException に変換することを検証する
    @Test
    void create_保存時の制約違反も409例外に変換() {
        // 作成リクエスト（食費）を用意する
        CreateCategoryRequest request = new CreateCategoryRequest("食費");
        // 同名チェックは false（すり抜け）を返すようモックする
        when(categoryRepository.existsByNameIgnoreCase("食費")).thenReturn(false);
        // 保存時に一意制約違反が起きるようモックする
        when(categoryRepository.save(any(Category.class)))
                // DB の一意制約違反例外を投げる
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        // create 呼び出しで DuplicateException に変換されることを検証する
        assertThatThrownBy(() -> categoryService.create(request))
                // 例外型が DuplicateException であることを確認する
                .isInstanceOf(DuplicateException.class);
    }

    // findById: 存在する ID なら DTO を返すことを検証する
    @Test
    void findById_存在すればDTOを返す() {
        // 主キー 1 の検索で採番済みカテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, "食費")));

        // テスト対象の findById を呼び出す
        CategoryResponse response = categoryService.findById(1L);

        // 返却 DTO の ID が一致することを検証する
        assertThat(response.id()).isEqualTo(1L);
        // 返却 DTO の名前が一致することを検証する
        assertThat(response.name()).isEqualTo("食費");
    }

    // findById: 存在しない ID なら NotFoundException（404 相当）になることを検証する
    @Test
    void findById_不在なら404例外() {
        // 主キー 404 の検索で空（未存在）を返すようモックする
        when(categoryRepository.findById(404L)).thenReturn(Optional.empty());

        // findById 呼び出しで NotFoundException が投げられることを検証する
        assertThatThrownBy(() -> categoryService.findById(404L))
                // 例外型が NotFoundException であることを確認する
                .isInstanceOf(NotFoundException.class);
    }

    // update: 重複なしなら名前を更新して保存し DTO を返すことを検証する
    @Test
    void update_重複なしなら更新して返す() {
        // 更新前のカテゴリ（食費）を用意する
        Category existing = category(1L, "食費");
        // 更新リクエスト（交通費への変更）を用意する
        UpdateCategoryRequest request = new UpdateCategoryRequest("交通費");
        // 主キー 1 の検索で更新前カテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 自分以外に同名が無い（重複なし）を返すようモックする
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("交通費", 1L)).thenReturn(false);
        // 保存時（即時反映）は引数のエンティティをそのまま返すようモックする
        when(categoryRepository.saveAndFlush(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // テスト対象の update を呼び出す
        CategoryResponse response = categoryService.update(1L, request);

        // 返却 DTO の名前が更新後の値であることを検証する
        assertThat(response.name()).isEqualTo("交通費");
    }

    // update: 前後に空白を含む名前は正規化してから重複チェック・保存されることを検証する
    @Test
    void update_前後の空白は正規化してから重複チェックする() {
        // 更新前のカテゴリ（食費）を用意する
        Category existing = category(1L, "食費");
        // 前後に空白を含む更新リクエスト（" 交通費 "）を用意する
        UpdateCategoryRequest request = new UpdateCategoryRequest(" 交通費 ");
        // 主キー 1 の検索で更新前カテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 正規化後の名前（"交通費"）で重複なしを返すようモックする
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("交通費", 1L)).thenReturn(false);
        // 保存時（即時反映）は引数のエンティティをそのまま返すようモックする
        when(categoryRepository.saveAndFlush(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // テスト対象の update を呼び出す
        CategoryResponse response = categoryService.update(1L, request);

        // 返却 DTO の名前が正規化済み（空白なし）であることを検証する
        assertThat(response.name()).isEqualTo("交通費");
        // 前後空白付きの生の値では重複チェックを呼んでいないことを検証する
        verify(categoryRepository, never()).existsByNameIgnoreCaseAndIdNot(" 交通費 ", 1L);
    }

    // update: 存在しない ID なら NotFoundException（404 相当）になることを検証する
    @Test
    void update_不在なら404例外() {
        // 主キー 404 の検索で空（未存在）を返すようモックする
        when(categoryRepository.findById(404L)).thenReturn(Optional.empty());

        // update 呼び出しで NotFoundException が投げられることを検証する
        assertThatThrownBy(() -> categoryService.update(404L, new UpdateCategoryRequest("交通費")))
                // 例外型が NotFoundException であることを確認する
                .isInstanceOf(NotFoundException.class);
        // 保存が一度も呼ばれていないことを検証する
        verify(categoryRepository, never()).saveAndFlush(any());
    }

    // update: 自分以外に同名カテゴリが存在すれば DuplicateException になり保存されないことを検証する
    @Test
    void update_自分以外に同名があれば409例外() {
        // 更新前のカテゴリ（食費）を用意する
        Category existing = category(1L, "食費");
        // 主キー 1 の検索で更新前カテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 自分以外に同名（交通費）が存在する（重複あり）を返すようモックする
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("交通費", 1L)).thenReturn(true);

        // update 呼び出しで DuplicateException が投げられることを検証する
        assertThatThrownBy(() -> categoryService.update(1L, new UpdateCategoryRequest("交通費")))
                // 例外型が DuplicateException であることを確認する
                .isInstanceOf(DuplicateException.class);
        // 保存が一度も呼ばれていないことを検証する
        verify(categoryRepository, never()).saveAndFlush(any());
    }

    // update: 事前チェックをすり抜けた同時実行の重複（DB 制約違反）も DuplicateException に変換することを検証する。
    // saveAndFlush で即時反映させているため、この一意制約違反は update() 自身の try 内で検知できる
    // （save() のままだと UPDATE がコミット時まで遅延され、この try/catch をすり抜けてしまう）。
    @Test
    void update_保存時の制約違反も409例外に変換() {
        // 更新前のカテゴリ（食費）を用意する
        Category existing = category(1L, "食費");
        // 主キー 1 の検索で更新前カテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 事前チェックは false（すり抜け）を返すようモックする
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("交通費", 1L)).thenReturn(false);
        // 保存時（即時反映）に一意制約違反が起きるようモックする
        when(categoryRepository.saveAndFlush(any(Category.class)))
                // DB の一意制約違反例外を投げる
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        // update 呼び出しで DuplicateException に変換されることを検証する
        assertThatThrownBy(() -> categoryService.update(1L, new UpdateCategoryRequest("交通費")))
                // 例外型が DuplicateException であることを確認する
                .isInstanceOf(DuplicateException.class);
    }

    // update: 事前チェック後に更新対象のカテゴリ自体が同時実行で削除され、UPDATE の影響行数が0件
    // （楽観ロック例外）になった場合も 500 ではなく 404（カテゴリ未存在）に変換されることを検証する
    @Test
    void update_保存対象のカテゴリ自体が消えたレースは404例外に変換() {
        // 更新前のカテゴリ（食費）を用意する
        Category existing = category(1L, "食費");
        // 主キー 1 の検索で更新前カテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 事前チェックは false（重複なし）を返すようモックする
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("交通費", 1L)).thenReturn(false);
        // 保存時に別リクエストが同じカテゴリを削除したレースを模擬して楽観ロック例外を投げさせる
        when(categoryRepository.saveAndFlush(any(Category.class)))
                // 楽観ロックの行数不一致例外を投げる
                .thenThrow(new OptimisticLockingFailureException("0 rows affected"));
        // 楽観ロック失敗後の存在確認で「行はもう無い」（＝本当に削除された）を返すようモックする
        when(categoryRepository.existsById(1L)).thenReturn(false);

        // update 呼び出しで NotFoundException（404 相当）に変換されることを検証する
        assertThatThrownBy(() -> categoryService.update(1L, new UpdateCategoryRequest("交通費")))
                // 例外型が NotFoundException であることを確認する（409 の重複例外ではない）
                .isInstanceOf(NotFoundException.class);
    }

    // update: 楽観ロック例外の発生後も行が残っている（＝削除ではなく別の操作が先に更新して
    // 版番号が進んだ競合）場合は、404 ではなく 409（ConflictException）に変換されることを検証する。
    // 実在するカテゴリに対して誤って「見つかりません」を返さないための振り分けを確認する
    @Test
    void update_同時更新で版番号が進んだ競合は409例外に変換() {
        // 更新前のカテゴリ（食費）を用意する
        Category existing = category(1L, "食費");
        // 主キー 1 の検索で更新前カテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 事前チェックは false（重複なし）を返すようモックする
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("交通費", 1L)).thenReturn(false);
        // 保存時に別リクエストが先に同じカテゴリを更新したレースを模擬して楽観ロック例外を投げさせる
        when(categoryRepository.saveAndFlush(any(Category.class)))
                // 楽観ロックの版番号不一致例外を投げる
                .thenThrow(new OptimisticLockingFailureException("version mismatch"));
        // 楽観ロック失敗後の存在確認で「行はまだある」（＝同時更新の競合）を返すようモックする
        when(categoryRepository.existsById(1L)).thenReturn(true);

        // update 呼び出しで ConflictException（409 相当）に変換されることを検証する
        assertThatThrownBy(() -> categoryService.update(1L, new UpdateCategoryRequest("交通費")))
                // 例外型が ConflictException であることを確認する（404 の未存在例外ではない）
                .isInstanceOf(ConflictException.class)
                // 安全な日本語文言（競合・再試行の案内）に変換されていることを確認する
                .hasMessage(ErrorMessages.CONCURRENT_CONFLICT);
    }

    // delete: 支出から参照されていなければ削除することを検証する
    @Test
    void delete_未参照なら削除する() {
        // 削除対象のカテゴリ（食費）を用意する
        Category existing = category(1L, "食費");
        // 主キー 1 の検索で対象カテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 支出からの参照が無い（false）を返すようモックする
        when(expenseRepository.existsByCategoryId(1L)).thenReturn(false);

        // テスト対象の delete を呼び出す
        categoryService.delete(1L);

        // リポジトリの削除が対象カテゴリで呼ばれたことを検証する
        verify(categoryRepository).delete(existing);
    }

    // delete: 存在しない ID なら NotFoundException（404 相当）になることを検証する
    @Test
    void delete_不在なら404例外() {
        // 主キー 404 の検索で空（未存在）を返すようモックする
        when(categoryRepository.findById(404L)).thenReturn(Optional.empty());

        // delete 呼び出しで NotFoundException が投げられることを検証する
        assertThatThrownBy(() -> categoryService.delete(404L))
                // 例外型が NotFoundException であることを確認する
                .isInstanceOf(NotFoundException.class);
        // 削除が一度も呼ばれていないことを検証する
        verify(categoryRepository, never()).delete(any());
    }

    // delete: 支出から参照されていれば CategoryInUseException（409 相当）になり削除されないことを検証する
    @Test
    void delete_支出から参照中なら409例外() {
        // 削除対象のカテゴリ（食費）を用意する
        Category existing = category(1L, "食費");
        // 主キー 1 の検索で対象カテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 支出からの参照がある（true）を返すようモックする
        when(expenseRepository.existsByCategoryId(1L)).thenReturn(true);

        // delete 呼び出しで CategoryInUseException が投げられることを検証する
        assertThatThrownBy(() -> categoryService.delete(1L))
                // 例外型が CategoryInUseException であることを確認する
                .isInstanceOf(CategoryInUseException.class);
        // 削除が一度も呼ばれていないことを検証する
        verify(categoryRepository, never()).delete(any());
    }

    // delete: 事前チェックをすり抜けた同時実行の参照（DB の外部キー制約違反）も
    // CategoryInUseException に変換することを検証する。flush() で即時反映させているため、
    // この制約違反は delete() 自身の try 内で検知できる
    // （flush しないままだと DELETE がコミット時まで遅延され、この try/catch をすり抜けてしまう）。
    @Test
    void delete_保存時の外部キー制約違反も409例外に変換() {
        // 削除対象のカテゴリ（食費）を用意する
        Category existing = category(1L, "食費");
        // 主キー 1 の検索で対象カテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 事前チェックは false（すり抜け）を返すようモックする
        when(expenseRepository.existsByCategoryId(1L)).thenReturn(false);
        // 即時反映（flush）時に外部キー制約違反が起きるようモックする
        doThrow(new DataIntegrityViolationException("fk violation"))
                // 対象のメソッドを指定する
                .when(categoryRepository).flush();

        // delete 呼び出しで CategoryInUseException に変換されることを検証する
        assertThatThrownBy(() -> categoryService.delete(1L))
                // 例外型が CategoryInUseException であることを確認する
                .isInstanceOf(CategoryInUseException.class);
    }

    // delete: 事前の存在チェック後、削除対象のカテゴリ自体が同時実行で既に削除され、
    // DELETE の影響行数が0件（楽観ロック例外）になった場合も 500 ではなく 404 に変換されることを検証する
    @Test
    void delete_削除対象が既に消えたレースは404例外に変換() {
        // 削除対象のカテゴリ（食費）を用意する
        Category existing = category(1L, "食費");
        // 主キー 1 の検索で対象カテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 支出からの参照は無い（false）を返すようモックする
        when(expenseRepository.existsByCategoryId(1L)).thenReturn(false);
        // flush() の時点で別リクエストが先に削除していたレースを模擬して楽観ロック例外を投げさせる
        doThrow(new OptimisticLockingFailureException("0 rows affected"))
                // 対象のメソッドを指定する
                .when(categoryRepository).flush();
        // 楽観ロック失敗後の存在確認で「行はもう無い」（＝本当に削除された）を返すようモックする
        when(categoryRepository.existsById(1L)).thenReturn(false);

        // delete 呼び出しで NotFoundException（404 相当）に変換されることを検証する
        assertThatThrownBy(() -> categoryService.delete(1L))
                // 例外型が NotFoundException であることを確認する（409 の使用中例外ではない）
                .isInstanceOf(NotFoundException.class);
    }

    // delete: 楽観ロック例外の発生後も行が残っている（＝削除ではなく別の操作が先に更新して
    // 版番号が進んだ競合）場合は、404 ではなく 409（ConflictException）に変換されることを検証する
    @Test
    void delete_同時更新で版番号が進んだ競合は409例外に変換() {
        // 削除対象のカテゴリ（食費）を用意する
        Category existing = category(1L, "食費");
        // 主キー 1 の検索で対象カテゴリを返すようモックする
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 支出からの参照は無い（false）を返すようモックする
        when(expenseRepository.existsByCategoryId(1L)).thenReturn(false);
        // flush() の時点で別リクエストが先に同じカテゴリを更新していたレースを模擬して楽観ロック例外を投げさせる
        doThrow(new OptimisticLockingFailureException("version mismatch"))
                // 対象のメソッドを指定する
                .when(categoryRepository).flush();
        // 楽観ロック失敗後の存在確認で「行はまだある」（＝同時更新の競合）を返すようモックする
        when(categoryRepository.existsById(1L)).thenReturn(true);

        // delete 呼び出しで ConflictException（409 相当）に変換されることを検証する
        assertThatThrownBy(() -> categoryService.delete(1L))
                // 例外型が ConflictException であることを確認する（404 の未存在例外ではない）
                .isInstanceOf(ConflictException.class)
                // 安全な日本語文言（競合・再試行の案内）に変換されていることを確認する
                .hasMessage(ErrorMessages.CONCURRENT_CONFLICT);
    }

    // findAll: 全カテゴリが DTO のページに変換されることを検証する
    @Test
    void findAll_全件をDTOへ変換して返す() {
        // 2 件のカテゴリを 1 ページとして返すようモックする
        when(categoryRepository.findAll(any(Pageable.class)))
                // 食費と交通費を含むページを返す
                .thenReturn(new PageImpl<>(List.of(category(1L, "食費"), category(2L, "交通費"))));

        // テスト対象の findAll を呼び出す（既定ページ指定）
        PageResponse<CategoryResponse> result = categoryService.findAll(Pageable.unpaged());

        // 件数が 2 件であることを検証する
        assertThat(result.content()).hasSize(2);
        // 1 件目の名前が食費であることを検証する
        assertThat(result.content().get(0).name()).isEqualTo("食費");
        // 2 件目の名前が交通費であることを検証する
        assertThat(result.content().get(1).name()).isEqualTo("交通費");
    }
}
