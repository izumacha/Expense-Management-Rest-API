// ドメイン（JPA エンティティ）のパッケージ
package com.izumacha.expensetracker.domain;

/**
 * 変更が監査ログ（{@link AuditLog}）に記録されるエンティティであることを示すインターフェース。
 *
 * <p><b>役割</b>: 監査リスナ（{@code audit.EntityAuditListener}）が「どの行が変わったか」を
 * 記録するには主キーが要る。JPA のコールバックは引数が {@code Object} 型で渡ってくるため、
 * このインターフェースを実装させておくことで<b>リフレクションを使わずに型安全に</b>主キーを
 * 取り出せる（フィールド名の文字列を書かないので、名前変更でリスナが静かに壊れない）。
 *
 * <p><b>実装するとどうなるか</b>: 実装しただけでは記録されない。記録されるのは
 * {@code @EntityListeners(EntityAuditListener.class)} を併せて付けたエンティティだけである。
 * 「実装したのに注釈を忘れた」という取りこぼしは
 * {@code AuditedEntityCoverageTest}（全 {@code @Entity} を走査する完全性テスト）が検出する。
 *
 * <p>{@code getId()} は Lombok の {@code @Getter} が生成するゲッターがそのまま実装になるため、
 * 実装側に追加のコードは要らない。
 */
public interface AuditedEntity {

    /**
     * 監査記録に載せる主キーを返す。
     *
     * @return 主キー。永続化前（採番前）は {@code null} になりうる
     */
    Long getId();
}
