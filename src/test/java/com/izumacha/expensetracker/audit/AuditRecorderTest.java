// 監査ログ関連のテストパッケージ
package com.izumacha.expensetracker.audit;

// 監査ログのエンティティ（列長・保存される値の検証に使う）
import com.izumacha.expensetracker.domain.AuditLog;
// 監査対象のカテゴリエンティティ（記録対象の代表として使う）
import com.izumacha.expensetracker.domain.Category;
// 各テストの後始末を宣言するアノテーション
import org.junit.jupiter.api.AfterEach;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 引数の捕捉に使う ArgumentCaptor
import org.mockito.ArgumentCaptor;
// トランザクション同期の登録・取り出しを行う静的ホルダ
import org.springframework.transaction.support.TransactionSynchronization;
// トランザクション同期の状態を制御する静的ホルダ
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
// 「例外が投げられないこと」を検証する AssertJ のヘルパーを取り込む
import static org.assertj.core.api.Assertions.assertThatCode;
// 任意の引数にマッチさせるヘルパーを取り込む
import static org.mockito.ArgumentMatchers.any;
// 例外を投げるモックを設定するヘルパーを取り込む
import static org.mockito.Mockito.doThrow;
// モックを生成するヘルパーを取り込む
import static org.mockito.Mockito.mock;
// 呼び出し回数の検証に使うヘルパーを取り込む
import static org.mockito.Mockito.never;
// 呼び出しの検証に使うヘルパーを取り込む
import static org.mockito.Mockito.verify;
// 呼び出しが 1 回も無かったことを検証するヘルパーを取り込む
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * AuditRecorder（監査ログを「いつ書くか」を決める入口）のユニットテスト。
 *
 * <p>【何を守るテストか】このクラスには監査ログの信頼性を左右する 3 つの約束がある。
 * <ul>
 *   <li><b>コミット後にだけ書く</b>: 同一トランザクション内で書くと、ロールバックされた
 *       変更まで「起きたこと」として記録に残り、監査ログが実態と食い違う。</li>
 *   <li><b>失敗しても業務処理を止めない（fail-open）</b>: 記録はコミット後に走るので、
 *       ここで例外を投げても変更は巻き戻せず、クライアントにだけ嘘のエラーが返る。</li>
 *   <li><b>外部由来のユーザー名を無害化する</b>: ログイン失敗の actor は攻撃者が内容も
 *       長さも決められる唯一の値であり、記録の偽装と監査テーブルの肥大の入口になる（§9）。</li>
 * </ul>
 * いずれも「壊れても実行時には静かに見える」性質なので、ここで機械的に固定する。
 *
 * <p>DB は使わず、書き込み担当（{@link AuditLogWriter}）をモックにした純粋ユニットテスト（共通規約 §11）。
 */
class AuditRecorderTest {

    // 書き込み担当のモック（実際には DB へ書かない）
    private final AuditLogWriter writer = mock(AuditLogWriter.class);

    // 操作主体の解決担当（依存を持たないので本物を使う。未認証なら anonymous が返る）
    private final AuditActorResolver actorResolver = new AuditActorResolver();

    // 検証対象
    private final AuditRecorder recorder = new AuditRecorder(writer, actorResolver);

    // トランザクション同期を有効にしたテストの後始末（他テストへ状態を漏らさない）
    @AfterEach
    void clearSynchronization() {
        // 同期が有効なままなら解除する
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // スレッドローカルの同期状態を消す
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // トランザクションの最中は書き込まず、コミット後に初めて書くことを検証する
    @Test
    void 変更はコミット後に書き込まれる() {
        // トランザクション同期が有効な状態（＝トランザクションの中）を作る
        TransactionSynchronizationManager.initSynchronization();
        // カテゴリの作成を記録する
        recorder.recordEntityChange(new Category("食費"), AuditAction.CREATE);
        // まだコミットしていないので書き込みは起きていないことを検証する
        verify(writer, never()).write(any());
        // 登録されたコールバックを取り出してコミット完了を模擬する
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            // コミット後の処理を呼ぶ
            synchronization.afterCommit();
        }
        // コミット後に 1 回だけ書き込まれたことを検証する
        verify(writer).write(any());
    }

    // ロールバック（コミットされなかった場合）は記録が残らないことを検証する
    @Test
    void ロールバックされた変更は記録されない() {
        // トランザクション同期が有効な状態を作る
        TransactionSynchronizationManager.initSynchronization();
        // カテゴリの更新を記録する
        recorder.recordEntityChange(new Category("食費"), AuditAction.UPDATE);
        // afterCommit を呼ばずに（＝コミットされなかったとして）検証する
        // 書き込みが 1 度も行われていないことを検証する
        verifyNoInteractions(writer);
    }

    // 記録される列の中身（対象の種類・主キー・種別・操作主体）が期待どおりであることを検証する
    @Test
    void 変更の記録内容が期待どおり() {
        // トランザクション同期の無い状態（その場で書き込まれる経路）で記録する
        Category category = new Category("交通費");
        // 主キーはテストから直接は設定できないため null のまま（採番前）で記録する
        recorder.recordEntityChange(category, AuditAction.DELETE);
        // 書き込まれた監査ログを捕捉する
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        // 書き込みが 1 回行われたことを確認しつつ引数を取り出す
        verify(writer).write(captor.capture());
        // 捕捉した監査ログを取り出す
        AuditLog written = captor.getValue();
        // 対象の種類がエンティティの単純クラス名であることを検証する
        assertThat(written.getEntityName()).isEqualTo("Category");
        // 操作の種別が渡したとおりであることを検証する
        assertThat(written.getAction()).isEqualTo(AuditAction.DELETE);
        // 未認証で実行したので操作主体が匿名になることを検証する
        assertThat(written.getActor()).isEqualTo(AuditActorResolver.ANONYMOUS_ACTOR);
        // 記録時刻が設定されていることを検証する
        assertThat(written.getOccurredAt()).isNotNull();
    }

    // 認証イベントはトランザクションを待たずその場で書かれることを検証する
    @Test
    void 認証イベントは即時に書き込まれる() {
        // ログイン成功を記録する
        recorder.recordAuthentication(AuditAction.LOGIN_SUCCESS, "api-user");
        // 書き込まれた監査ログを捕捉する
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        // 書き込みが 1 回行われたことを確認しつつ引数を取り出す
        verify(writer).write(captor.capture());
        // 捕捉した監査ログを取り出す
        AuditLog written = captor.getValue();
        // 認証イベントを表す固定値が対象の種類に入ることを検証する
        assertThat(written.getEntityName()).isEqualTo(AuditRecorder.AUTHENTICATION_ENTITY_NAME);
        // 認証は特定の行に対する操作ではないので主キーが無いことを検証する
        assertThat(written.getEntityId()).isNull();
        // 認証に使われたユーザー名が操作主体として記録されることを検証する
        assertThat(written.getActor()).isEqualTo("api-user");
    }

    // 書き込みが失敗しても例外を外へ出さない（業務処理を止めない）ことを検証する
    @Test
    void 書き込み失敗でも例外を外に出さない() {
        // 書き込みが必ず失敗するようモックを設定する（DB 断などを模擬）
        doThrow(new IllegalStateException("DB へ接続できません")).when(writer).write(any());
        // 認証イベントの記録が例外を投げずに完了することを検証する（fail-open）
        assertThatCode(() -> recorder.recordAuthentication(AuditAction.LOGIN_FAILURE, "attacker"))
                // 例外が投げられないことを検証する
                .doesNotThrowAnyException();
    }

    // 空のユーザー名は「特定不能」を表す固定値になることを検証する（境界値）
    @Test
    void 空のユーザー名は特定不能として記録される() {
        // 空文字のユーザー名が固定値へ置き換わることを検証する
        assertThat(AuditRecorder.sanitizeAttemptedUsername("")).isEqualTo(AuditRecorder.UNKNOWN_ACTOR);
        // 空白のみのユーザー名も同じく固定値になることを検証する
        assertThat(AuditRecorder.sanitizeAttemptedUsername("   ")).isEqualTo(AuditRecorder.UNKNOWN_ACTOR);
        // null も同じく固定値になることを検証する（NPE にしない）
        assertThat(AuditRecorder.sanitizeAttemptedUsername(null)).isEqualTo(AuditRecorder.UNKNOWN_ACTOR);
    }

    // 外部から送られたユーザー名の制御文字が置換されることを検証する（記録の偽装を防ぐ）
    @Test
    void ユーザー名の制御文字は置換される() {
        // 改行で偽の記録行を挿入しようとする値が無害化されることを検証する
        assertThat(AuditRecorder.sanitizeAttemptedUsername("user\r\nFAKE")).isEqualTo("user__FAKE");
    }

    // 外部から送られた長すぎるユーザー名が列長へ切り詰められることを検証する（監査テーブルの肥大を防ぐ）
    @Test
    void 長すぎるユーザー名は列長に切り詰められる() {
        // 列長より 1 文字長いユーザー名を無害化する（境界値の超過側）
        String sanitized = AuditRecorder.sanitizeAttemptedUsername("u".repeat(AuditLog.ACTOR_MAX_LENGTH + 1));
        // 列長ちょうどに収まることを検証する
        assertThat(sanitized).hasSize(AuditLog.ACTOR_MAX_LENGTH);
    }
}
