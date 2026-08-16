// 監査ログ関連のテストパッケージ
package com.izumacha.expensetracker.audit;

// 監査ログのエンティティ（列長の定義元）を参照する
import com.izumacha.expensetracker.domain.AuditLog;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuditAction（監査ログに記録する操作の種別）が DB 列に収まることを機械的に固定するテスト。
 *
 * <p>【何を守るテストか】操作種別は {@code EnumType.STRING}（定数名そのまま）で保存するため、
 * 列長を超える名前の定数を追加すると<b>その種別の記録だけが保存時に落ちる</b>という、
 * 追加した本人が気づきにくい壊れ方をする。しかも監査の書き込みは fail-open（失敗しても
 * 業務処理を止めない）なので、実行時には WARN ログが出るだけで API は正常に見えてしまう。
 * 定数を足した瞬間にビルドで止まるよう、名前の長さをここで検証する（共通規約 §11 境界値）。
 */
class AuditActionTest {

    // すべての定数名が action 列の長さに収まることを検証する
    @Test
    void すべての操作種別の名前が列長に収まる() {
        // 定義済みの全定数を走査する
        for (AuditAction action : AuditAction.values()) {
            // 定数名の文字数が列長以下であることを、どの定数かが分かる形で検証する
            assertThat(action.name())
                    // どの定数で落ちたかを失敗メッセージに出す
                    .as("AuditAction.%s の名前が action 列の長さ（%d）を超えています", action.name(), AuditAction.NAME_MAX_LENGTH)
                    // 列長以下であることを検証する
                    .hasSizeLessThanOrEqualTo(AuditAction.NAME_MAX_LENGTH);
        }
    }

    // 認証イベントの entityName に使う固定値が列長に収まることを検証する
    @Test
    void 認証イベントのエンティティ名が列長に収まる() {
        // 固定値の文字数が entity_name 列の長さ以下であることを検証する
        assertThat(AuditRecorder.AUTHENTICATION_ENTITY_NAME)
                // 列長以下であることを検証する
                .hasSizeLessThanOrEqualTo(AuditLog.ENTITY_NAME_MAX_LENGTH);
    }
}
