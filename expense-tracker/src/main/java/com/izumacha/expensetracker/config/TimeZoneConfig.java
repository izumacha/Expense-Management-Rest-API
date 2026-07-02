// 設定クラスのパッケージ
package com.izumacha.expensetracker.config;

// Bean 初期化完了後に一度だけ呼ばれるコールバックを宣言するアノテーション
import jakarta.annotation.PostConstruct;
// JVM の既定タイムゾーンを扱うクラス
import java.util.TimeZone;
// このクラス自体を Spring に設定クラスとして登録するアノテーション
import org.springframework.context.annotation.Configuration;

/**
 * JVM の既定タイムゾーンをアプリ起動時に日本時間（Asia/Tokyo）へ固定する設定クラス。
 *
 * <p><b>なぜ必要か</b><br>
 * {@code CreateExpenseRequest} / {@code UpdateExpenseRequest} の {@code @PastOrPresent} と、
 * {@code Expense.onCreate()} が使う {@code LocalDateTime.now()} は、いずれも
 * {@code Clock.systemDefaultZone()}（＝JVM の既定タイムゾーン）に依存する。
 * {@code application.yml} の {@code spring.jackson.time-zone: Asia/Tokyo} は
 * JSON シリアライズ時のタイムゾーンにしか効かず、ゾーン非依存型（{@code LocalDate}/{@code LocalDateTime}）の
 * 「今日」判定には効果がない。コンテナ実行環境の既定タイムゾーンが UTC の場合、
 * JST 深夜0時〜9時に「今日」の日付で支出を登録すると UTC 換算では前日となり、
 * {@code @PastOrPresent} が未来日と誤判定してしまう。
 *
 * <p>JVM の既定タイムゾーンをアプリ起動の最初期に一度だけ固定すれば、
 * {@code Clock.systemDefaultZone()} を使うすべての箇所（上記のバリデーションと
 * エンティティの現在時刻取得の両方）を個別に直さずまとめて解決できる。
 *
 * <p>{@code main()} メソッド内で直接呼び出す方式ではなく Spring の {@code @PostConstruct} に置くのは、
 * {@code @SpringBootTest} 等のテストコンテキストのように {@code main()} を経由しない起動経路でも
 * 同じ挙動を保証するため（詳細は {@link com.izumacha.expensetracker.ExpenseTrackerApplication} 参照）。
 * この Bean は他の singleton Bean が実際にリクエストを処理し始める（組み込み Tomcat が
 * 待ち受けを開始する）よりも必ず前に初期化されるため、タイミングの問題は生じない。
 */
@Configuration
public class TimeZoneConfig {

    // アプリで統一して使うタイムゾーン ID（日本標準時）
    private static final String APP_TIME_ZONE_ID = "Asia/Tokyo";

    // Bean 生成直後に一度だけ実行され、JVM 既定タイムゾーンを固定するコールバック
    @PostConstruct
    public void initDefaultTimeZone() {
        // JVM の既定タイムゾーンを日本標準時に固定する
        TimeZone.setDefault(TimeZone.getTimeZone(APP_TIME_ZONE_ID));
    }
}
