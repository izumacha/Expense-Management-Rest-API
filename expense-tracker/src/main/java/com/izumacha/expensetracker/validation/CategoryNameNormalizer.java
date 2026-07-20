// バリデーション関連パッケージ（MaxCodePoints と同居させる。DTO/サービス両方から参照される
// 横断的な正規化ロジックのため、特定 DTO のパッケージではなくここに置く）
package com.izumacha.expensetracker.validation;

// Unicode 正規化（合成済み/分解済みなど見た目が同じでも符号化が異なる文字列を同一視するため）に使う
import java.text.Normalizer;

/**
 * カテゴリ名の正規化（前後空白除去 + Unicode NFC 正規化）を一箇所にまとめたユーティリティ。
 *
 * <p>{@code CreateCategoryRequest} / {@code UpdateCategoryRequest} の正規コンストラクタで
 * 呼び出し、Bean Validation（{@code @NotBlank} / {@link MaxCodePoints}）より先に正規化を
 * 済ませる。これにより {@link MaxCodePoints} による文字数検証が実際に DB へ保存・重複チェック
 * される値（NFC 合成済み）と同じ基準のコードポイント数を数えるようになる。NFD 分解済み表現
 * （例: 濁点付き仮名が基底文字＋結合文字の2コードポイントに分解された表現）は NFC 合成表現より
 * コードポイント数が多くなりやすいため、正規化前の生入力のまま検証すると、合成後は上限内に
 * 収まるはずの名前を誤って 400 で拒否してしまう。ここで DTO 受け取り時点から正規化しておくことで
 * この誤検知を防ぐ。
 */
public final class CategoryNameNormalizer {

    // ユーティリティクラスのためインスタンス化を禁止する
    private CategoryNameNormalizer() {
    }

    /**
     * 前後の空白（Unicode 対応）を除去してから Unicode NFC 正規化した文字列を返す。
     * null はそのまま返す（{@code @NotBlank} 側の判定に委ねるため、ここでは検証しない）。
     */
    public static String normalize(String rawName) {
        // null はそのまま返す（呼び出し元の @NotBlank 等に判定を委ねる）
        if (rawName == null) {
            return null;
        }
        // strip() で前後の空白（Unicode対応）を除去してから NFC 正規化する
        return Normalizer.normalize(rawName.strip(), Normalizer.Form.NFC);
    }
}
