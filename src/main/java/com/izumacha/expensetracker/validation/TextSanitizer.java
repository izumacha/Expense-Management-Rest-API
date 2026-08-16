// バリデーション関連パッケージ（CategoryNameNormalizer と同居させる。特定の DTO や層に属さず、
// 複数の層から参照される横断的な文字列加工ロジックのため、ここにまとめて置く）
package com.izumacha.expensetracker.validation;

/**
 * 外部由来（攻撃者が内容を決められる）の文字列を、記録先へ書く前に無害化するユーティリティ。
 *
 * <p><b>何をするか</b>: (1) 制御文字（改行・復帰・タブ・エスケープ等の ISO 制御文字）を
 * {@code '_'} に置き換え、(2) 指定した最大長で打ち切る。
 *
 * <p><b>なぜ必要か</b><br>
 * 外部由来の値をそのまま記録すると 2 つの実害がある。
 * <ul>
 *   <li><b>ログ偽装 / 記録の汚染</b>: CR/LF を含む値はログに偽の行を挿入でき、監査記録では
 *       1 件の記録を複数件に見せかけられる（§9 「機密情報・PII・スタックトレースを漏らさない」
 *       と同じく、記録の信頼性を守るための無害化）。</li>
 *   <li><b>資源枯渇</b>: 長大な値をそのまま書くとログ・テーブルが肥大し、二次的な DoS になる
 *       （§9 「公開エンドポイントを保護する」）。</li>
 * </ul>
 *
 * <p><b>なぜ共通化するか</b><br>
 * 同じ「制御文字の除去 ＋ 長さの打ち切り」を
 * {@code security.RateLimitFilter}（X-Forwarded-For 等をログへ出す前）と
 * {@code audit}（認証イベントの actor を監査テーブルへ書く前）の 2 箇所が必要とする。
 * 書き写すと片方だけ直したときに無害化の強度がずれるため、実際に 2 箇所目が現れた時点で
 * ここへ一元化する（§6 DRY / 一元管理）。打ち切り長は用途で異なる（ログ行の見やすさ基準か、
 * DB 列長か）ため<b>呼び出し側が渡す</b>設計にし、それ以外の規則だけを共有する。
 *
 * <p><b>正規表現を使わない理由</b>: 信頼できない入力へ正規表現を当てない方針
 * （§9 ReDoS 回避）に揃え、1 文字ずつの線形走査で実装する。
 */
public final class TextSanitizer {

    // ユーティリティクラスのためインスタンス化を禁止する
    private TextSanitizer() {
    }

    /**
     * 制御文字を {@code '_'} に置き換え、{@code maxLength} 文字で打ち切った文字列を返す。
     *
     * @param value     無害化したい外部由来の文字列（{@code null} 可）
     * @param maxLength 出力の最大文字数（0 以上。超過分は捨てる）
     * @return 無害化済みの文字列。{@code value} が {@code null} なら文字列 {@code "null"}
     *         （呼び出し元で NPE にしないための防御。呼び出し元が別の既定値を使いたい場合は
     *         呼び出す前に自分で分岐する）
     * @throws IllegalArgumentException {@code maxLength} が負の場合（呼び出し側のバグを早期に露見させる）
     */
    public static String sanitize(String value, int maxLength) {
        // 最大長に負値が来るのは呼び出し側の定数設定ミスなので、黙って動かさず即座に知らせる
        if (maxLength < 0) {
            // どの引数がどう不正かが分かるメッセージを添えて停止する
            throw new IllegalArgumentException("maxLength は 0 以上で指定してください: " + maxLength);
        }
        // null は文字列 "null" として返す（呼び出し元で NPE にしないための防御）
        if (value == null) {
            return "null";
        }
        // 出力する長さを上限で打ち切る（超過分は記録しない）
        int length = Math.min(value.length(), maxLength);
        // 無害化後の文字を組み立てるバッファを、確定した長さで用意する
        StringBuilder sanitized = new StringBuilder(length);
        // 打ち切り後の範囲を 1 文字ずつ走査する
        for (int i = 0; i < length; i++) {
            // 現在位置の文字を取り出す
            char c = value.charAt(i);
            // 制御文字（改行・復帰・タブ・エスケープ等）は '_' に置換し、それ以外はそのまま残す
            sanitized.append(Character.isISOControl(c) ? '_' : c);
        }
        // 無害化した文字列を返す
        return sanitized.toString();
    }
}
