// 設定クラスのテストパッケージ
package com.izumacha.expensetracker.config;

// JVM の既定タイムゾーンを扱うクラス
import java.util.TimeZone;

// 各テスト後に片付け処理を実行するアノテーション
import org.junit.jupiter.api.AfterEach;
// 各テスト前に準備処理を実行するアノテーション
import org.junit.jupiter.api.BeforeEach;
// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 検証用の assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// TimeZoneConfig が JVM 既定タイムゾーンを JST に固定することを検証する（Spring コンテキストは起動しない単体テスト）
class TimeZoneConfigTest {

    // テスト前の JVM 既定タイムゾーンを退避しておく（他のテストへの副作用を避けるため）
    private TimeZone originalDefault;

    // 各テスト実行前に現在の既定タイムゾーンを記録する
    @BeforeEach
    void saveOriginalDefault() {
        // 実行前の既定タイムゾーンを保存する
        originalDefault = TimeZone.getDefault();
    }

    // 各テスト実行後に既定タイムゾーンを元に戻す（テスト間の独立性を保つ）
    @AfterEach
    void restoreOriginalDefault() {
        // 保存しておいた既定タイムゾーンへ戻す
        TimeZone.setDefault(originalDefault);
    }

    // initDefaultTimeZone: 既定タイムゾーンが UTC 等の別ゾーンでも Asia/Tokyo に固定されることを検証する
    @Test
    void initDefaultTimeZone_既定タイムゾーンをAsiaTokyoに固定する() {
        // 既定タイムゾーンをあえて UTC に設定しておく（実行環境が UTC の場合を模す）
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // テスト対象の初期化コールバックを直接呼び出す
        new TimeZoneConfig().initDefaultTimeZone();

        // 既定タイムゾーンが Asia/Tokyo に固定されたことを検証する
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Tokyo");
    }
}
