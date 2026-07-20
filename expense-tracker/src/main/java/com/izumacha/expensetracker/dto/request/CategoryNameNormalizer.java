// リクエスト DTO のパッケージ（CreateCategoryRequest / UpdateCategoryRequest と同居させる）
package com.izumacha.expensetracker.dto.request;

// Unicode 正規化（合成済み/分解済みなど見た目が同じでも符号化が異なる文字列を同一視するため）に使う
import java.text.Normalizer;

/**
 * カテゴリ名の正規化（前後空白除去 + Unicode NFC 正規化）を一箇所にまとめたユーティリティ。
 *
 * <p>{@link CreateCategoryRequest} / {@link UpdateCategoryRequest} の正規コンストラクタと、
 * サービス層（{@code CategoryService.normalizeName}）の3箇所で同じ正規化ロジックを
 * 重複させないための共通化。{@code service} パッケージから呼べるよう public にしている。
 *
 * <p>DTO の正規コンストラクタでこの正規化を行うことで、{@code MaxCodePoints} による文字数検証が
 * 実際に DB へ保存・重複チェックされる値（NFC 合成済み）と同じ基準のコードポイント数を数える
 * ようになる。NFD 分解済み表現（例: 濁点付き仮名が基底文字＋結合文字の2コードポイントに分解された
 * 表現）は NFC 合成表現よりコードポイント数が多くなりやすいため、正規化前の生入力のまま検証すると、
 * 合成後は上限内に収まるはずの名前を誤って 400 で拒否してしまう。ここで DTO 受け取り時点から
 * 正規化しておくことでこの誤検知を防ぐ。
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
