// リポジトリのテストパッケージ（実 DB を使う基底クラス AbstractRepositoryTest を共有するためここに置く）
package com.izumacha.expensetracker.repository;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// カテゴリ更新リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.UpdateCategoryRequest;
// 楽観ロック競合（409 相当）の例外を参照する
import com.izumacha.expensetracker.exception.ConflictException;
// 未存在（404 相当）の例外を参照する
import com.izumacha.expensetracker.exception.NotFoundException;
// テスト対象のカテゴリサービスを参照する
import com.izumacha.expensetracker.service.CategoryService;

// 永続化コンテキスト操作用のエンティティマネージャ（同時実行の書き込みをネイティブ SQL で模すために使う）
import jakarta.persistence.EntityManager;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;
// スライステストのコンテキストへ追加のビーンを読み込むアノテーション
import org.springframework.context.annotation.Import;

// 例外の型を検証する assertThatThrownBy を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 楽観ロック失敗（OptimisticLockingFailureException）の 409/404 振り分けを本物の PostgreSQL で検証する。
// この経路はモックのユニットテストでは検証できない Hibernate 実装依存の罠を含む:
// flush 失敗後に失敗した UPDATE/DELETE がアクションキューに残留したまま存在確認クエリを発行すると、
// auto-flush が同じ文を再実行して楽観ロック例外を再送出し、振り分け自体が 500 になってしまう
// （resolveOptimisticLockFailure が entityManager.clear() で保留中の変更を破棄してから
// existsById を呼ぶのはそのため）。ここでは実 DB でその後始末を含めて末端まで検証する。
@Import(CategoryService.class)
class CategoryServiceOptimisticLockTest extends AbstractRepositoryTest {

    // テスト対象のカテゴリサービス（実リポジトリ・実 DB で動かす）
    @Autowired
    private CategoryService categoryService;

    // カテゴリの保存に使うリポジトリ
    @Autowired
    private CategoryRepository categoryRepository;

    // 同時実行の書き込みを模すネイティブ SQL の発行に使うエンティティマネージャ
    @Autowired
    private EntityManager entityManager;

    // update: 行が残ったまま版番号だけが進んだ同時更新は 409（ConflictException）になることを検証する
    @Test
    void update_版番号が進んだ同時更新は409のConflictExceptionへ振り分ける() {
        // 食費カテゴリを保存して即時フラッシュする（管理下のエンティティは version=0 を保持する）
        Category food = categoryRepository.saveAndFlush(new Category("食費"));
        // 別クライアントが先に更新して版番号が進んだ状況を、Hibernate の変更追跡を迂回する
        // ネイティブ SQL で模す（管理下のエンティティは古い version=0 のまま残る）
        entityManager.createNativeQuery("UPDATE categories SET version = version + 1 WHERE id = :id")
                // 対象行の ID をバインドする
                .setParameter("id", food.getId())
                // UPDATE を即時実行する
                .executeUpdate();

        // 古い版番号での更新は影響行数 0 件となるが、行は実在するため 404 ではなく
        // 409（ConflictException）へ振り分けられることを検証する
        assertThatThrownBy(() -> categoryService.update(food.getId(), new UpdateCategoryRequest("外食費")))
                .isInstanceOf(ConflictException.class);
    }

    // delete: 対象行そのものが同時実行で消えた場合は従来どおり 404（NotFoundException）になることを検証する
    @Test
    void delete_行が消えた同時削除は404のNotFoundExceptionへ振り分ける() {
        // 食費カテゴリを保存して即時フラッシュする（エンティティは管理下＝1次キャッシュに残る）
        Category food = categoryRepository.saveAndFlush(new Category("食費"));
        // 別クライアントが先に削除した状況を、Hibernate の変更追跡を迂回するネイティブ SQL で模す
        // （1次キャッシュには消えた行のエンティティが残るため、サービスの findById は成功する）
        entityManager.createNativeQuery("DELETE FROM categories WHERE id = :id")
                // 対象行の ID をバインドする
                .setParameter("id", food.getId())
                // DELETE を即時実行する
                .executeUpdate();

        // DELETE の影響行数 0 件から楽観ロック失敗となり、行が実在しないため
        // 404（NotFoundException）へ振り分けられることを検証する
        assertThatThrownBy(() -> categoryService.delete(food.getId()))
                .isInstanceOf(NotFoundException.class);
    }
}
