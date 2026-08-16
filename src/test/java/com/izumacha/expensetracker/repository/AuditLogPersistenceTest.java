// リポジトリのテストパッケージ
package com.izumacha.expensetracker.repository;

// 監査ログの操作種別を参照する
import com.izumacha.expensetracker.audit.AuditAction;
// 監査ログの「誰が」を解決するコンポーネント（期待値の定数を参照する）
import com.izumacha.expensetracker.audit.AuditActorResolver;
// 監査ログのエンティティを参照する
import com.izumacha.expensetracker.domain.AuditLog;
// カテゴリエンティティ（監査対象の代表として使う）
import com.izumacha.expensetracker.domain.Category;
// 各テストの後処理を宣言するアノテーション
import org.junit.jupiter.api.AfterEach;
// 各テストの前処理を宣言するアノテーション
import org.junit.jupiter.api.BeforeEach;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// 依存を注入するアノテーション
import org.springframework.beans.factory.annotation.Autowired;
// トランザクションの伝播方法を指定する列挙
import org.springframework.transaction.annotation.Propagation;
// テストのトランザクション制御に使うアノテーション
import org.springframework.transaction.annotation.Transactional;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 監査ログが<b>実際に DB へ書かれる</b>ことを、本物の PostgreSQL 上で検証する結合テスト。
 *
 * <p>【なぜユニットテストだけでは足りないか】監査の記録は
 * 「JPA が {@code @EntityListeners} で指定したリスナを生成し、そのリスナへ Spring が依存を注入し、
 * コールバックがコミット後の書き込みを予約する」という<b>実行時にしか成立しない配線</b>で
 * 動いている。リスナ単体のユニットテスト（{@code EntityAuditListenerTest}）はコールバックを
 * 直接呼ぶため、この配線が壊れていても通ってしまう。配線が切れると監査ログは
 * <b>1 行も書かれないまま API は正常に動く</b>ので、実際に保存して行を数えるここが唯一の砦になる。
 *
 * <p>【なぜテストのトランザクションを無効にするか】監査ログはコミット後に書かれる。
 * スライステスト既定の「テストごとにトランザクションを張って最後にロールバックする」挙動では
 * コミットが起きず、コールバックが発火しないため何も検証できない。
 * {@link Propagation#NOT_SUPPORTED} でテスト側のトランザクションを外し、リポジトリ呼び出しが
 * それぞれ独立にコミットされる（＝本番と同じ）形にする。その代わり行が残るので、
 * 前処理と後処理の両方で消す（後処理を欠かすと、コミットした行が同じコンテナ DB を共有する
 * 他のテストクラスへ漏れる）。
 *
 * <p><b>Docker が必要</b>: 基底クラスが Testcontainers で PostgreSQL を起動する
 * （CLAUDE.md §2 のとおり、Docker が無い環境では初期化エラーになる）。
 */
// テスト側のトランザクションを無効にして、リポジトリ呼び出しごとに実際にコミットさせる
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditLogPersistenceTest extends AbstractRepositoryTest {

    // 監査対象の操作に使うカテゴリリポジトリ
    @Autowired
    private CategoryRepository categoryRepository;

    // 記録された監査ログを読み出すリポジトリ
    @Autowired
    private AuditLogRepository auditLogRepository;

    // 各テストが独立して行数を数えられるよう、開始前に対象テーブルを空にする
    @BeforeEach
    void clearTablesBefore() {
        // 監査ログとカテゴリを空にする
        clearTables();
    }

    /**
     * 各テストが<b>コミットした行を必ず消す</b>。
     *
     * <p>このクラスはテスト側のトランザクションを外している（＝ロールバックされない）ため、
     * 残した行は同じコンテナ DB を共有する他のテストクラスへそのまま漏れる。とくにカテゴリ名には
     * 一意制約があるので、残骸が 1 行あるだけで他クラスの「同名で登録できること」を確かめる
     * テストが一意制約違反で落ちる。後始末はこのクラスの責任として必ず行う。
     */
    @AfterEach
    void clearTablesAfter() {
        // 監査ログとカテゴリを空にする
        clearTables();
    }

    // 監査ログとカテゴリを空にする共通処理（前処理と後処理で使う。§6 DRY）
    private void clearTables() {
        // 先に監査ログを空にする
        auditLogRepository.deleteAll();
        // カテゴリを全件削除する（この削除自体も監査ログを生む）
        categoryRepository.deleteAll();
        // 直前の削除が生んだ監査ログを消して、監査ログを 0 件に揃える
        auditLogRepository.deleteAll();
    }

    // 作成が CREATE として記録されることを検証する（配線が生きている最小の証明）
    @Test
    void カテゴリの作成が監査ログに記録される() {
        // カテゴリを保存する（この時点でコミットされ、コミット後に監査ログが書かれる）
        Category saved = categoryRepository.save(new Category("監査テスト用カテゴリ"));

        // 監査ログが 1 件だけ書かれていることを検証する
        assertThat(auditLogRepository.findAll()).singleElement()
                // 記録内容が期待どおりであることをまとめて検証する
                .satisfies(auditLog -> {
                    // 対象の種類がカテゴリであることを検証する
                    assertThat(auditLog.getEntityName()).isEqualTo("Category");
                    // 対象の主キーが保存されたカテゴリのものであることを検証する
                    assertThat(auditLog.getEntityId()).isEqualTo(String.valueOf(saved.getId()));
                    // 操作の種別が作成であることを検証する
                    assertThat(auditLog.getAction()).isEqualTo(AuditAction.CREATE);
                    // 未認証で実行しているので操作主体が匿名であることを検証する
                    assertThat(auditLog.getActor()).isEqualTo(AuditActorResolver.ANONYMOUS_ACTOR);
                    // 記録時刻が入っていることを検証する
                    assertThat(auditLog.getOccurredAt()).isNotNull();
                });
    }

    // 更新が UPDATE として記録されることを検証する
    @Test
    void カテゴリの更新が監査ログに記録される() {
        // カテゴリを保存する（1 件目の記録＝作成が生まれる）
        Category saved = categoryRepository.save(new Category("監査テスト用カテゴリ"));
        // 名前を変更する
        saved.setName("監査テスト用カテゴリ（更新後）");
        // 変更を保存する（2 件目の記録＝更新が生まれる）
        categoryRepository.save(saved);

        // 記録された種別を新しい順に取り出し、作成と更新が 1 件ずつあることを検証する
        assertThat(auditLogRepository.findAll())
                // 監査ログから操作の種別だけを取り出す
                .extracting(AuditLog::getAction)
                // 作成と更新が順不同で 1 件ずつ含まれることを検証する
                .containsExactlyInAnyOrder(AuditAction.CREATE, AuditAction.UPDATE);
    }

    // 削除が DELETE として記録されることを検証する
    @Test
    void カテゴリの削除が監査ログに記録される() {
        // カテゴリを保存する（1 件目の記録＝作成が生まれる）
        Category saved = categoryRepository.save(new Category("監査テスト用カテゴリ（削除）"));
        // 保存したカテゴリを削除する（2 件目の記録＝削除が生まれる）
        categoryRepository.delete(saved);

        // 作成と削除が 1 件ずつ記録されていることを検証する
        assertThat(auditLogRepository.findAll())
                // 監査ログから操作の種別だけを取り出す
                .extracting(AuditLog::getAction)
                // 作成と削除が順不同で 1 件ずつ含まれることを検証する
                .containsExactlyInAnyOrder(AuditAction.CREATE, AuditAction.DELETE);
    }
}
