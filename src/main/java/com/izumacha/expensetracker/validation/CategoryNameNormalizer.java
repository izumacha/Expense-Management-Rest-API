// バリデーション関連パッケージ（MaxCodePoints と同居させる。DTO/サービス両方から参照される
// 横断的な正規化ロジックのため、特定 DTO のパッケージではなくここに置く）
package com.izumacha.expensetracker.validation;

// Unicode 正規化（合成済み/分解済みなど見た目が同じでも符号化が異なる文字列を同一視するため）に使う
import java.text.Normalizer;
// 実行環境の既定ロケールに左右されない小文字化（Locale.ROOT）に使う
import java.util.Locale;

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

    /**
     * 一意制約用の正規化キー（{@link #normalize(String)} の結果を {@link Locale#ROOT} で
     * 小文字化したもの）を返す。null はそのまま返す。
     *
     * <p>{@code existsByNameKey} による大文字小文字を区別しない重複チェックは
     * check-then-act のため同時実行ではすり抜けうる。{@code Category.nameKey} 列の一意制約が
     * この関数と同じ規則で導出したキー同士を比較する最終防波堤（DB 側の砦）になるよう、
     * キーの導出ロジックをここへ一元化する（§6 一元管理。サービス層の事前チェックも
     * この関数で導出したキーを {@code existsByNameKey} へ渡すため、事前チェックと
     * 一意制約が常に同じ「同名」の定義を共有する）。小文字化に {@link Locale#ROOT} を
     * 使うのは、実行環境の既定ロケール（例: トルコ語ロケールの I → ı 変換）に結果が左右されない
     * 移植可能な変換にするため（§10）。
     */
    public static String normalizeKey(String rawName) {
        // まず既存の正規化（前後空白除去 + NFC）を適用する
        String normalized = normalize(rawName);
        // null はそのまま返し、それ以外はロケール非依存の小文字化でキーへ変換して返す
        return (normalized == null) ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
