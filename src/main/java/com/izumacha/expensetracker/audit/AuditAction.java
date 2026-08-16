// 監査ログ（誰が・いつ・何をしたか）に関するパッケージ
package com.izumacha.expensetracker.audit;

/**
 * 監査ログに記録する操作の種別。
 *
 * <p>データ変更（{@link #CREATE} / {@link #UPDATE} / {@link #DELETE}）と認証イベント
 * （{@link #LOGIN_SUCCESS} / {@link #LOGIN_FAILURE}）を 1 つの列挙にまとめている。
 * 監査の利用者が知りたいのは「この時間帯に何が起きたか」の時系列であり、データ変更と
 * 認証を別テーブルに分けると毎回 2 つを突き合わせることになるため、同じテーブル・同じ
 * 種別列で扱えるようにしている。
 *
 * <p>DB へは {@code EnumType.STRING}（名前そのまま）で保存する。序数で保存すると、
 * 定数の並び順を変えただけで過去の記録の意味が変わり、監査ログとして成立しなくなるため。
 */
public enum AuditAction {

    /** 行を新規作成した（JPA の {@code @PostPersist}）。 */
    CREATE,

    /** 既存の行を更新した（JPA の {@code @PostUpdate}）。 */
    UPDATE,

    /** 行を削除した（JPA の {@code @PostRemove}）。 */
    DELETE,

    /** トークン発行でユーザー名とパスワードの照合に成功した。 */
    LOGIN_SUCCESS,

    /** トークン発行でユーザー名とパスワードの照合に失敗した（総当たりの検知・立証に使う）。 */
    LOGIN_FAILURE;

    /**
     * {@code AuditLog.action} 列の長さ。
     *
     * <p>この列挙の名前をそのまま保存するため、<b>最も長い定数名が必ず収まる</b>値でなければ
     * ならない。定数を追加したときに列長を超えていないかは {@code AuditActionTest} が
     * 機械的に検証する（人間の目視に頼ると、追加した定数だけが保存時に落ちる壊れ方をする）。
     */
    public static final int NAME_MAX_LENGTH = 32;
}
