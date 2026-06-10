// サービスのテストパッケージ
package com.izumacha.expensetracker.service;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// カテゴリ作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateCategoryRequest;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// 重複例外を参照する
import com.izumacha.expensetracker.exception.DuplicateException;
// カテゴリリポジトリを参照する
import com.izumacha.expensetracker.repository.CategoryRepository;

// 一覧の戻り型
import java.util.List;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// Mockito を JUnit 5 と連携させる拡張
import org.junit.jupiter.api.extension.ExtendWith;
// 一意制約違反を表す例外
import org.springframework.dao.DataIntegrityViolationException;
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

// CategoryService をモック依存だけでテストする（DB には接続しない）
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    // カテゴリリポジトリのモック
    @Mock
    private CategoryRepository categoryRepository;

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
        when(categoryRepository.existsByName("食費")).thenReturn(false);
        // 保存時は ID 採番済みのカテゴリを返すようモックする
        when(categoryRepository.save(any(Category.class))).thenReturn(category(1L, "食費"));

        // テスト対象の create を呼び出す
        CategoryResponse response = categoryService.create(request);

        // 返却 DTO の ID が採番値であることを検証する
        assertThat(response.id()).isEqualTo(1L);
        // 名前が一致することを検証する
        assertThat(response.name()).isEqualTo("食費");
    }

    // create: 同名が既に存在すれば DuplicateException になり保存されないことを検証する
    @Test
    void create_事前チェックで重複なら409例外() {
        // 作成リクエスト（食費）を用意する
        CreateCategoryRequest request = new CreateCategoryRequest("食費");
        // 同名チェックが true（重複あり）を返すようモックする
        when(categoryRepository.existsByName("食費")).thenReturn(true);

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
        when(categoryRepository.existsByName("食費")).thenReturn(false);
        // 保存時に一意制約違反が起きるようモックする
        when(categoryRepository.save(any(Category.class)))
                // DB の一意制約違反例外を投げる
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        // create 呼び出しで DuplicateException に変換されることを検証する
        assertThatThrownBy(() -> categoryService.create(request))
                // 例外型が DuplicateException であることを確認する
                .isInstanceOf(DuplicateException.class);
    }

    // findAll: 全カテゴリが DTO のリストに変換されることを検証する
    @Test
    void findAll_全件をDTOへ変換して返す() {
        // 2 件のカテゴリを返すようモックする
        when(categoryRepository.findAll())
                // 食費と交通費を返す
                .thenReturn(List.of(category(1L, "食費"), category(2L, "交通費")));

        // テスト対象の findAll を呼び出す
        List<CategoryResponse> result = categoryService.findAll();

        // 件数が 2 件であることを検証する
        assertThat(result).hasSize(2);
        // 1 件目の名前が食費であることを検証する
        assertThat(result.get(0).name()).isEqualTo("食費");
        // 2 件目の名前が交通費であることを検証する
        assertThat(result.get(1).name()).isEqualTo("交通費");
    }
}
