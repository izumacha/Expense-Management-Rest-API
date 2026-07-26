// Web 横断ユーティリティのテストパッケージ
package com.izumacha.expensetracker.web;

// レート制限フィルタ（本文サイズ上限フィルタより先に実行されるべき比較対象）を参照する
import com.izumacha.expensetracker.security.RateLimitFilter;

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

// RequestBodySizeLimitFilter の実行順序（@Order）を Spring コンテナ無しで検証する軽量な回帰テスト。
//
// 【背景】本フィルタは「ボディを読み込む前（＝認証やその他の処理より手前）でサイズ超過を弾く」設計意図を
// 持つ。RateLimitFilter と同じ理由で、Spring Boot は Security のフィルタチェーンを既定 order = -100
// （SecurityProperties.DEFAULT_FILTER_ORDER）で登録するため、確実に先に実行させるには最小値付近の
// @Order（Ordered.HIGHEST_PRECEDENCE + 1）を使う必要がある。この回帰を機械的に検知する。
class RequestBodySizeLimitFilterOrderTest {

    // RequestBodySizeLimitFilter の @Order の値が Spring Security の既定フィルタ順序（-100）より小さい
    // （＝ Security より確実に先に実行される）ことを検証する
    @Test
    void 本文サイズ上限フィルタはSpringSecurityの既定フィルタ順序より先に実行される() {
        // RequestBodySizeLimitFilter クラスに付与された @Order アノテーションを読み取る
        Order order = AnnotatedElementUtils.findMergedAnnotation(RequestBodySizeLimitFilter.class, Order.class);

        // @Order アノテーションが付与されていることを検証する（前提条件）
        assertThat(order).isNotNull();
        // その値が Spring Security の既定フィルタ順序（-100）より小さく、
        // サイズ上限判定が認証処理より先に実行されることを検証する
        assertThat(order.value()).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
    }

    // レート制限フィルタが、本文サイズ上限フィルタより確実に先に実行される（@Order の値が小さい）ことを
    // 検証する回帰テスト。本文サイズ上限フィルタが先に実行される過去の実装では、サイズ超過リクエストが
    // レート制限のカウントより先に 413 で打ち切られてカウントされず、巨大な本文を送り続ける攻撃者が
    // 一切レート制限されない抜け穴になっていた（レート制限が先に効くべき）。
    @Test
    void レート制限フィルタは本文サイズ上限フィルタより先に実行される() {
        // 両フィルタクラスに付与された @Order アノテーションをそれぞれ読み取る
        Order bodySizeOrder = AnnotatedElementUtils.findMergedAnnotation(RequestBodySizeLimitFilter.class, Order.class);
        Order rateLimitOrder = AnnotatedElementUtils.findMergedAnnotation(RateLimitFilter.class, Order.class);

        // 両方に @Order アノテーションが付与されていることを検証する（前提条件）
        assertThat(bodySizeOrder).isNotNull();
        assertThat(rateLimitOrder).isNotNull();
        // レート制限フィルタの値が本文サイズ上限フィルタの値より小さい（＝先に実行される）ことを検証する
        assertThat(rateLimitOrder.value()).isLessThan(bodySizeOrder.value());
    }
}
