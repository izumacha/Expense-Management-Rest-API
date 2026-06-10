// アプリケーションのルートパッケージ
package com.izumacha.expensetracker;

// Spring Boot 起動用アノテーションとメソッドを取り込む
import org.springframework.boot.SpringApplication;
// Spring Boot の自動設定を有効化するアノテーション
import org.springframework.boot.autoconfigure.SpringBootApplication;
// JVM の既定タイムゾーンを扱うクラス
import java.util.TimeZone;

// 支出管理 API のエントリポイントとなるクラス
@SpringBootApplication
public class ExpenseTrackerApplication {

    // アプリケーションを起動する main メソッド
    public static void main(String[] args) {
        // 「未来日かどうか」などの日付判定をサーバ稼働環境に依存させないため、既定タイムゾーンを日本時間に固定する
        // （@PastOrPresent は JVM の既定タイムゾーンの「今日」を基準に判定するため、起動前に固定しておく）
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        // Spring Boot アプリケーションを起動する
        SpringApplication.run(ExpenseTrackerApplication.class, args);
    }
}
