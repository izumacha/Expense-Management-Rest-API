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
        // JVM 既定タイムゾーンの JST 固定は config.TimeZoneConfig（@PostConstruct）に一元化した。
        // main() だけで固定すると、main() を経由しない起動経路（@SpringBootTest 等のテストコンテキストや
        // 将来のサーブレットコンテナ配備）では未適用のままになるため、Spring コンテナ側で保証する。
        SpringApplication.run(ExpenseTrackerApplication.class, args);
    }
}
