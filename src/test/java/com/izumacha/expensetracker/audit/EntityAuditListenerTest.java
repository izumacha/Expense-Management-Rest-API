// 監査ログ関連のテストパッケージ
package com.izumacha.expensetracker.audit;

// 監査対象のカテゴリエンティティ（記録対象の代表として使う）
import com.izumacha.expensetracker.domain.Category;
// 監査対象であることを示すインターフェース（型判定の検証に使う）
import com.izumacha.expensetracker.domain.AuditedEntity;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存 Bean を遅延解決するプロバイダ（モック化して差し込む）
import org.springframework.beans.factory.ObjectProvider;

// 「例外が投げられないこと」を検証する AssertJ のヘルパーを取り込む
import static org.assertj.core.api.Assertions.assertThatCode;
// 任意の引数にマッチさせるヘルパーを取り込む
import static org.mockito.ArgumentMatchers.any;
// 例外を投げるモックを設定するヘルパーを取り込む
import static org.mockito.Mockito.doThrow;
// モックを生成するヘルパーを取り込む
import static org.mockito.Mockito.mock;
// モックの戻り値を設定するヘルパーを取り込む
import static org.mockito.Mockito.when;
// 呼び出しの検証に使うヘルパーを取り込む
import static org.mockito.Mockito.verify;
// 呼び出しが 1 回も無かったことを検証するヘルパーを取り込む
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * EntityAuditListener（JPA のライフサイクルコールバックを監査ログへ流すリスナ）のユニットテスト。
 *
 * <p>【何を守るテストか】
 * <ul>
 *   <li><b>コールバックと操作種別の対応</b>: 作成なのに更新として記録するような取り違えは、
 *       監査ログを読んでも気づけない（記録は残っているので「動いている」ように見える）。</li>
 *   <li><b>記録の失敗で保存処理を巻き添えにしない</b>: このリスナは保存処理の途中で呼ばれる。
 *       ここで例外が外へ出ると、監査の不調がそのまま支出の登録失敗になる（fail-open。§9）。</li>
 * </ul>
 *
 * <p>JPA も Spring コンテキストも起動せず、コールバックを直接呼ぶ純粋ユニットテスト（共通規約 §11）。
 */
class EntityAuditListenerTest {

    // 監査ログの記録先のモック
    private final AuditRecorder recorder = mock(AuditRecorder.class);

    // 記録先を遅延解決するプロバイダのモック（本番では Spring が注入する）
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AuditRecorder> recorderProvider = mock(ObjectProvider.class);

    // 検証対象
    private final EntityAuditListener listener = new EntityAuditListener(recorderProvider);

    // プロバイダが記録先を返すようにするヘルパー（各テストの冒頭で呼ぶ）
    private void givenRecorderAvailable() {
        // getObject() でモックの記録先が返るように設定する
        when(recorderProvider.getObject()).thenReturn(recorder);
    }

    // 永続化直後のコールバックが「作成」として記録されることを検証する
    @Test
    void 永続化直後は作成として記録される() {
        // 記録先が取得できる状態にする
        givenRecorderAvailable();
        // 記録対象のカテゴリを用意する
        Category category = new Category("食費");
        // 永続化直後のコールバックを呼ぶ
        listener.onPostPersist(category);
        // 作成として記録されたことを検証する
        verify(recorder).recordEntityChange(category, AuditAction.CREATE);
    }

    // 更新直後のコールバックが「更新」として記録されることを検証する
    @Test
    void 更新直後は更新として記録される() {
        // 記録先が取得できる状態にする
        givenRecorderAvailable();
        // 記録対象のカテゴリを用意する
        Category category = new Category("食費");
        // 更新直後のコールバックを呼ぶ
        listener.onPostUpdate(category);
        // 更新として記録されたことを検証する
        verify(recorder).recordEntityChange(category, AuditAction.UPDATE);
    }

    // 削除直後のコールバックが「削除」として記録されることを検証する
    @Test
    void 削除直後は削除として記録される() {
        // 記録先が取得できる状態にする
        givenRecorderAvailable();
        // 記録対象のカテゴリを用意する
        Category category = new Category("食費");
        // 削除直後のコールバックを呼ぶ
        listener.onPostRemove(category);
        // 削除として記録されたことを検証する
        verify(recorder).recordEntityChange(category, AuditAction.DELETE);
    }

    // 監査対象インターフェースを実装しない型が来ても、記録せず落ちもしないことを検証する（保険の経路）
    @Test
    void 監査対象でない型は記録されない() {
        // AuditedEntity を実装しない任意のオブジェクトでコールバックを呼んでも例外にならないことを検証する
        assertThatCode(() -> listener.onPostPersist("監査対象ではない何か"))
                // 例外が投げられないことを検証する
                .doesNotThrowAnyException();
        // 記録先が一度も呼ばれていないことを検証する（主キーを取り出せない以上、記録は作れない）
        verifyNoInteractions(recorder);
    }

    // null が渡ってきても落ちないことを検証する（防御的な境界値）
    @Test
    void nullが渡っても落ちない() {
        // null でコールバックを呼んでも例外にならないことを検証する
        assertThatCode(() -> listener.onPostUpdate(null))
                // 例外が投げられないことを検証する
                .doesNotThrowAnyException();
    }

    // 記録側が失敗しても保存処理を巻き添えにしないことを検証する（fail-open）
    @Test
    void 記録の失敗は保存処理に伝播しない() {
        // 記録先が取得できる状態にする
        givenRecorderAvailable();
        // 記録が必ず失敗するようモックを設定する
        doThrow(new IllegalStateException("記録できません"))
                // 任意のエンティティ・任意の種別で失敗させる
                .when(recorder).recordEntityChange(any(AuditedEntity.class), any(AuditAction.class));
        // コールバックが例外を外へ出さないことを検証する
        assertThatCode(() -> listener.onPostPersist(new Category("食費")))
                // 例外が投げられないことを検証する
                .doesNotThrowAnyException();
    }
}
