// サービスパッケージのテスト（RaceGuard はパッケージプライベートのため同一パッケージに置く）
package com.izumacha.expensetracker.service;

// 一意制約/外部キー制約違反を検出する例外（RaceGuard が変換対象とするもの）
import org.springframework.dao.DataIntegrityViolationException;
// 楽観ロックの行数不一致例外（RaceGuard が変換対象とするもの）
import org.springframework.dao.OptimisticLockingFailureException;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 検証用の assertThat / assertThatThrownBy を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// RaceGuard.guarded() の3分岐（正常終了・一意制約違反の変換・対象行消失の変換）を検証する。
// CategoryServiceTest/ExpenseServiceTest 経由の間接テストはあるが、このヘルパー自体を
// 直接ピン留めするテストが無かったため追加する（共通規約 §11）。DB/Spring コンテキストは
// 使わず、渡す例外・変換関数もすべて手組みの純粋ユニットテストにする。
class RaceGuardTest {

    // 呼び出し元が指定するドメイン例外の代わりに使う、テスト専用の単純な RuntimeException
    private static final class DomainException extends RuntimeException {
        DomainException(String message) {
            super(message);
        }
    }

    // action が正常終了した場合、その戻り値がそのまま返り、onConflict/onGone は呼ばれないことを検証する
    @Test
    void guarded_正常終了時はactionの戻り値をそのまま返す() {
        // 呼ばれたかどうかを記録するフラグ(呼ばれてはいけない側)
        boolean[] onConflictCalled = {false};
        boolean[] onGoneCalled = {false};

        // 例外を投げず単純に値を返す action で guarded() を呼び出す
        String result = RaceGuard.guarded(
                () -> "ok",
                e -> {
                    onConflictCalled[0] = true;
                    return new DomainException("unexpected onConflict");
                },
                e -> {
                    onGoneCalled[0] = true;
                    return new DomainException("unexpected onGone");
                });

        // action の戻り値がそのまま返っていることを確認する
        assertThat(result).isEqualTo("ok");
        // 変換関数はどちらも呼ばれていないことを確認する
        assertThat(onConflictCalled[0]).isFalse();
        assertThat(onGoneCalled[0]).isFalse();
    }

    // DataIntegrityViolationException が飛んだ場合、onConflict で変換した例外が送出されることを検証する
    @Test
    void guarded_一意制約違反はonConflictが変換した例外を送出する() {
        // action 内で投げる元例外を用意する
        DataIntegrityViolationException original = new DataIntegrityViolationException("duplicate key");

        // action が一意制約違反を投げるケースで guarded() を呼び出し、送出される例外を検証する
        assertThatThrownBy(() -> RaceGuard.guarded(
                () -> {
                    throw original;
                },
                e -> new DomainException("conflict: " + e.getMessage()),
                e -> new DomainException("unexpected onGone")))
                // onConflict が変換した DomainException(元例外のメッセージを含む)が送出されることを確認する
                .isInstanceOf(DomainException.class)
                .hasMessage("conflict: duplicate key");
    }

    // OptimisticLockingFailureException が飛んだ場合、onGone で変換した例外が送出されることを検証する
    @Test
    void guarded_対象行消失はonGoneが変換した例外を送出する() {
        // action 内で投げる元例外を用意する
        OptimisticLockingFailureException original = new OptimisticLockingFailureException("row vanished");

        // action が対象行消失を投げるケースで guarded() を呼び出し、送出される例外を検証する
        assertThatThrownBy(() -> RaceGuard.guarded(
                () -> {
                    throw original;
                },
                e -> new DomainException("unexpected onConflict"),
                e -> new DomainException("gone: " + e.getMessage())))
                // onGone が変換した DomainException(元例外のメッセージを含む)が送出されることを確認する
                .isInstanceOf(DomainException.class)
                .hasMessage("gone: row vanished");
    }
}
