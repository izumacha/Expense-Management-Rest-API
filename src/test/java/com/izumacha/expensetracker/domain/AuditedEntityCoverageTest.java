// ドメイン（JPA エンティティ）のテストパッケージ
package com.izumacha.expensetracker.domain;

// 監査リスナ（エンティティに紐づいているべきクラス）を参照する
import com.izumacha.expensetracker.audit.EntityAuditListener;
// エンティティであることを示すアノテーション（走査対象の判定に使う）
import jakarta.persistence.Entity;
// エンティティリスナの紐づけを表すアノテーション
import jakarta.persistence.EntityListeners;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// クラスパス上の候補クラスを走査するスキャナ
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
// 指定アノテーションが付いた型だけを拾うフィルタ
import org.springframework.core.type.filter.AnnotationTypeFilter;
// 走査結果のクラス定義を表す型
import org.springframework.beans.factory.config.BeanDefinition;
// 注釈付きクラス定義（走査対象の判定を上書きするために使う）
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
// 走査結果を集める入れ物
import java.util.ArrayList;
// 走査結果を集める入れ物のインターフェース
import java.util.List;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「監査対象から漏れているエンティティが無いこと」を機械的に保証する完全性テスト。
 *
 * <p>【何を守るテストか】監査ログはエンティティごとに
 * {@code @EntityListeners(EntityAuditListener.class)} と {@link AuditedEntity} の実装という
 * <b>2 つの目印を人手で付ける</b>方式で成り立っている。新しいエンティティを追加した人が
 * どちらかを付け忘れても、アプリは正常に動きテストも通り、<b>そのテーブルの変更だけが監査ログに
 * 残らない</b>という状態が静かに成立してしまう。監査ログは「記録が無い＝何も起きていない」と
 * 読まれる性質のものなので、この漏れは監査そのものの信頼性を壊す。
 *
 * <p>そこでアプリのパッケージ配下の {@code @Entity} をすべて走査し、監査ログ自身
 * （{@link AuditLog}）を除く全エンティティが 2 つの目印を備えていることを検証する。
 * エンティティを追加した時点でこのテストが落ちるため、付け忘れはビルドで止まる。
 *
 * <p>DB も Spring コンテキストも起動せず、クラスパスの走査だけで完結する（共通規約 §11）。
 */
class AuditedEntityCoverageTest {

    // 走査対象のパッケージ（アプリ全体の起点）。
    // domain 配下に限定しないのは、規約から外れた場所に置かれたエンティティこそ
    // 監査対象の付け忘れが起きやすく、検出網から外れては意味がないため
    private static final String APPLICATION_PACKAGE = "com.izumacha.expensetracker";

    // 監査記録そのものを表すエンティティは、自分自身の変更を記録する必要がないため対象外にする
    // （記録するとログを書くたびにログが増える無限連鎖になる）
    private static final Class<?> AUDIT_LOG_ENTITY = AuditLog.class;

    // アプリのパッケージ配下のすべての @Entity クラスを集めて返す
    private List<Class<?>> findEntityClasses() {
        // 既定のフィルタを使わないスキャナを用意する（@Component 等を拾わないようにするため）。
        // 併せて候補の判定を上書きし、抽象クラスも拾えるようにする。
        // 【なぜ上書きが要るか】既定の判定は「具象クラスであること」を要求するため、
        // JPA の継承マッピングで使う抽象 @Entity（マップされた状態を持つ基底クラス）が
        // 黙って走査から漏れる。漏れれば付け忘れを検出できず、この検出網の意味が無くなる
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    // 候補かどうかの判定を上書きする
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        // 抽象・具象を問わず、include フィルタに合致したものはすべて候補にする
                        return true;
                    }
                };
        // @Entity が付いていることを走査の条件にする
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        // 見つかったクラスを集める入れ物を用意する
        List<Class<?>> entityClasses = new ArrayList<>();
        // アプリのパッケージを走査して候補を 1 件ずつ処理する
        for (BeanDefinition definition : scanner.findCandidateComponents(APPLICATION_PACKAGE)) {
            // クラス名を取り出す
            String className = definition.getBeanClassName();
            // クラス名からクラスオブジェクトを解決する
            try {
                // 解決したクラスを結果に加える
                entityClasses.add(Class.forName(className));
            } catch (ClassNotFoundException e) {
                // 走査で見つかったのに解決できないのは環境の異常なので、握り潰さず失敗させる（§6）
                throw new IllegalStateException("走査で見つかったエンティティを解決できません: " + className, e);
            }
        }
        // 集めたエンティティを返す
        return entityClasses;
    }

    // 走査そのものが機能していること（0 件で素通りしていないこと）を検証する
    @Test
    void エンティティの走査が機能している() {
        // 既知のエンティティ（支出・カテゴリ・監査ログ）が最低限見つかることを検証する。
        // 走査に失敗して 0 件になると、以降の検証が「対象なし」で常に成功してしまうため、
        // 検出網そのものが生きていることを先に確かめる
        assertThat(findEntityClasses())
                // 3 つの既知エンティティが含まれることを検証する
                .contains(Expense.class, Category.class, AuditLog.class);
    }

    // 監査ログ自身を除く全エンティティが AuditedEntity を実装していることを検証する
    @Test
    void 全エンティティが監査対象インターフェースを実装している() {
        // 走査で見つかった全エンティティを 1 件ずつ確認する
        for (Class<?> entityClass : findEntityClasses()) {
            // 監査ログ自身は対象外なので飛ばす
            if (entityClass.equals(AUDIT_LOG_ENTITY)) {
                // 次のエンティティへ進む
                continue;
            }
            // AuditedEntity を実装していることを、どのクラスかが分かる形で検証する
            assertThat(AuditedEntity.class.isAssignableFrom(entityClass))
                    // 失敗時にどのクラスで漏れたかと直し方を示す
                    .as("%s は AuditedEntity を実装していません（監査ログに主キーを記録できません）",
                            entityClass.getSimpleName())
                    // 実装済みであることを検証する
                    .isTrue();
        }
    }

    // 監査ログ自身を除く全エンティティに監査リスナが紐づいていることを検証する
    @Test
    void 全エンティティに監査リスナが紐づいている() {
        // 走査で見つかった全エンティティを 1 件ずつ確認する
        for (Class<?> entityClass : findEntityClasses()) {
            // 監査ログ自身は対象外なので飛ばす
            if (entityClass.equals(AUDIT_LOG_ENTITY)) {
                // 次のエンティティへ進む
                continue;
            }
            // エンティティに付いている @EntityListeners を取り出す
            EntityListeners listeners = entityClass.getAnnotation(EntityListeners.class);
            // 注釈そのものが付いていることを、どのクラスかが分かる形で検証する
            assertThat(listeners)
                    // 失敗時にどのクラスで漏れたかと直し方を示す
                    .as("%s に @EntityListeners が付いていません（変更が監査ログに残りません）",
                            entityClass.getSimpleName())
                    // 注釈が存在することを検証する
                    .isNotNull();
            // 紐づいているリスナに監査リスナが含まれることを検証する
            assertThat(listeners.value())
                    // 失敗時にどのクラスで漏れたかと直し方を示す
                    .as("%s の @EntityListeners に EntityAuditListener が含まれていません",
                            entityClass.getSimpleName())
                    // 監査リスナが含まれることを検証する
                    .contains(EntityAuditListener.class);
        }
    }
}
