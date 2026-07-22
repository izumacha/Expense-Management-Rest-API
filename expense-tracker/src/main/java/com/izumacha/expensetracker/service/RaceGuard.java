// サービスパッケージ
package com.izumacha.expensetracker.service;

// 一意制約/外部キー制約違反を検出する例外（事前チェックをすり抜けた同時実行の競合で発生する）
import org.springframework.dao.DataIntegrityViolationException;
// 楽観ロックの行数不一致例外（UPDATE/DELETEの対象行自体が同時実行で消えた場合のほか、
// 別の操作が先に更新して版番号（@Version）が進んだ場合にも発生する）
import org.springframework.dao.OptimisticLockingFailureException;
// 例外を受け取って呼び出し元のドメイン例外へ変換する関数型インタフェース
import java.util.function.Function;
// 保存/削除など、結果を返す実処理を表す関数型インタフェース
import java.util.function.Supplier;

// CategoryService.update()/delete() と ExpenseService.saveOrThrowIfCategoryVanished() で
// 同一のまま3箇所に重複していた「事前チェックをすり抜けた同時実行の競合を、DB制約違反
// (DataIntegrityViolationException)・楽観ロック失敗(OptimisticLockingFailureException)として検知し、
// それぞれ呼び出し元指定のドメイン例外へ変換する」パターンを一元化するヘルパー（CLAUDE.md §6 DRY）。
// 1箇所にしか無い形（CategoryService.create() の DIVE のみ、ExpenseService.delete() の
// OptimisticLockingFailureException のみ）まで無理に共通化すると呼び出し元が1つしかない
// オーバーロードが増えるだけで見通しが悪化するため、ここでは対象を絞っている。
final class RaceGuard {

    // ユーティリティクラスのためインスタンス化を禁止する
    private RaceGuard() {
    }

    // 保存/削除などの実処理を実行し、一意制約違反と楽観ロック失敗をそれぞれ変換する。
    // T: 実処理の戻り値の型（void な処理は呼び出し元で `return null;` するラムダにして渡す）
    static <T> T guarded(Supplier<T> action,
            Function<DataIntegrityViolationException, RuntimeException> onConflict,
            Function<OptimisticLockingFailureException, RuntimeException> onGone) {
        try {
            // 渡された実処理（保存/削除）を実行する
            return action.get();
        } catch (DataIntegrityViolationException e) {
            // 一意制約/外部キー制約違反を、呼び出し元が指定したドメイン例外へ変換して送出する
            // (生のDBメッセージは外部に出さず、原因例外は追跡用に連鎖させる。共通規約 §6/§9)
            throw onConflict.apply(e);
        } catch (OptimisticLockingFailureException e) {
            // 楽観ロック失敗(影響行数0件。対象行の消失または版番号が進んだ同時更新)を、
            // 呼び出し元が指定したドメイン例外へ変換して送出する
            throw onGone.apply(e);
        }
    }
}
