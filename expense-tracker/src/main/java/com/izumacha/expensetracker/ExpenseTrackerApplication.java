// アプリケーションのルートパッケージ
package com.izumacha.expensetracker;

// Spring Boot 起動用アノテーションとメソッドを取り込む
import org.springframework.boot.SpringApplication;
// Spring Boot の自動設定を有効化するアノテーション
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 支出管理 API のエントリポイントとなるクラス
@SpringBootApplication
public class ExpenseTrackerApplication {

    // アプリケーションを起動する main メソッド
    public static void main(String[] args) {
        // Spring Boot アプリケーションを起動する
        SpringApplication.run(ExpenseTrackerApplication.class, args);
    }
}
