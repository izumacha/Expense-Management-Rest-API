// ドメインエンティティのテストパッケージ
package com.izumacha.expensetracker.domain;

// 説明フィールドの入力検証（コードポイント上限）を持つリクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateExpenseRequest;
// 更新側のリクエスト DTO も同じ上限を共有していることを確かめるため参照する
import com.izumacha.expensetracker.dto.request.UpdateExpenseRequest;
// コードポイント単位の文字数制約（DTO 側の上限が付いているアノテーション）
import com.izumacha.expensetracker.validation.MaxCodePoints;
// DB 列長（@Column(length)）を読み取るための JPA アノテーション
import jakarta.persistence.Column;
// アノテーションの属性値をフィールドから取り出すためのリフレクション API
import java.lang.reflect.Field;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expense の「説明（description）の最大文字数」が、DB 列長と DTO の入力検証で
 * 常に同じ単一の定数（{@link Expense#DESCRIPTION_MAX_LENGTH}）から導かれていることを検証する。
 *
 * <p>【何を守るテストか】この上限は 2 箇所（エンティティの {@code @Column(length)} と
 * リクエスト DTO の {@code @MaxCodePoints(max)}）で使われる。裸の数値を各所に書いて片方だけ
 * 変更すると「入力検証は通るのに DB 列に収まらない」状態になり、保存時の
 * {@code DataIntegrityViolationException} がサービス層の {@code RaceGuard} で
 * 「参照先カテゴリが消えたレース」と誤認され、実際の原因（説明が長すぎる）とは無関係な
 * <b>404「カテゴリが見つかりません」</b>がクライアントへ返ってしまう。
 * 定数への一本化が将来の編集で崩れていないことを、この構造テストで固定する（共通規約 §6 / §11）。
 */
class ExpenseTest {

    // description の DB 列長（@Column(length)）が単一の定数から来ていることを検証する
    @Test
    void description列の長さは定数から導かれる() throws NoSuchFieldException {
        // Expense エンティティの description フィールドを取得する
        Field field = Expense.class.getDeclaredField("description");
        // そのフィールドに付与された @Column アノテーションを取得する
        Column column = field.getAnnotation(Column.class);

        // 列長が定数と一致していることを検証する（直書きの数値へ戻っていないことの担保）
        assertThat(column.length()).isEqualTo(Expense.DESCRIPTION_MAX_LENGTH);
    }

    // 作成リクエスト DTO の入力検証上限が DB 列長と同じ定数から来ていることを検証する
    @Test
    void 作成リクエストの説明上限はDB列長と一致する() throws NoSuchFieldException {
        // CreateExpenseRequest の description に付いた上限値を取り出して比較する
        assertThat(maxCodePointsOf(CreateExpenseRequest.class))
                .isEqualTo(Expense.DESCRIPTION_MAX_LENGTH);
    }

    // 更新リクエスト DTO の入力検証上限も同じ定数から来ていることを検証する
    @Test
    void 更新リクエストの説明上限はDB列長と一致する() throws NoSuchFieldException {
        // UpdateExpenseRequest の description に付いた上限値を取り出して比較する
        assertThat(maxCodePointsOf(UpdateExpenseRequest.class))
                .isEqualTo(Expense.DESCRIPTION_MAX_LENGTH);
    }

    // 指定した record クラスの description フィールドに付いた @MaxCodePoints の上限値を返すヘルパー
    private static int maxCodePointsOf(Class<?> requestType) throws NoSuchFieldException {
        // record のコンポーネントに付けた制約は同名のフィールドにも伝播するため、フィールド経由で取得する
        Field field = requestType.getDeclaredField("description");
        // フィールドに付与された @MaxCodePoints アノテーションを取得する
        MaxCodePoints annotation = field.getAnnotation(MaxCodePoints.class);
        // アノテーションが付いていること自体（検証の付け忘れ）も同時に担保する
        assertThat(annotation)
                .as("%s.description には @MaxCodePoints が必要です", requestType.getSimpleName())
                .isNotNull();
        // 宣言された上限値を返す
        return annotation.max();
    }
}
