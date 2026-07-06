// セキュリティ関連のテストパッケージ
package com.izumacha.expensetracker.security;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// Spring Security のフィルタチェーンの既定順序（-100）を保持する定数クラス
import org.springframework.boot.autoconfigure.security.SecurityProperties;
// クラスに付与された @Order アノテーションをメタアノテーション込みで読み取るユーティリティ
import org.springframework.core.annotation.AnnotatedElementUtils;
// フィルタの適用順を指定するアノテーション本体
import org.springframework.core.annotation.Order;

// 値を検証する assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// RateLimitFilter の実行順序（@Order）を Spring コンテナ無しで検証する軽量な回帰テスト。
//
// 【背景】RateLimitFilter は「認証より手前で流量を絞る」設計意図を持つが、以前は @Order(1) だった。
// Spring Boot は spring-boot-starter-security 導入時、Spring Security のフィルタチェーンを
// 既定で order = -100（SecurityProperties.DEFAULT_FILTER_ORDER）で登録する。フィルタは order の
// 昇順（小さい値ほど先）に実行されるため、1 > -100 の @Order(1) では実際には Security の
// フィルタチェーンより「後」に実行されてしまい、設計意図と逆になっていた（現状は permitAll() のため
// 実害は小さいが、将来 JWT/OAuth2 等の実認証を追加すると、認証処理より後にしかレート制限がかからず
// §9「公開エンドポイントを保護する」を満たせなくなる）。
// この回帰を機械的に検知するため、@Order の値が Security の既定順序より確実に小さい（＝先に実行される）
// ことだけを Spring コンテナを起動せずに検証する。
class RateLimitFilterOrderTest {

    // RateLimitFilter の @Order の値が Spring Security の既定フィルタ順序（-100）より小さい
    // （＝ Security より確実に先に実行される）ことを検証する
    @Test
    void レート制限フィルタはSpringSecurityの既定フィルタ順序より先に実行される() {
        // RateLimitFilter クラスに付与された @Order アノテーションを読み取る
        Order order = AnnotatedElementUtils.findMergedAnnotation(RateLimitFilter.class, Order.class);

        // @Order アノテーションが付与されていることを検証する（前提条件）
        assertThat(order).isNotNull();
        // その値が Spring Security の既定フィルタ順序（-100）より小さく、
        // レート制限が認証処理より先に実行されることを検証する
        assertThat(order.value()).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
    }
}
