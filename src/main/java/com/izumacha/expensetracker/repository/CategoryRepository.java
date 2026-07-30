// リポジトリパッケージ
package com.izumacha.expensetracker.repository;

// カテゴリエンティティを参照する
import com.izumacha.expensetracker.domain.Category;
// 標準的な CRUD を提供する基底インタフェース
import org.springframework.data.jpa.repository.JpaRepository;

// カテゴリの永続化を担うリポジトリ
public interface CategoryRepository extends JpaRepository<Category, Long> {

    // 同名カテゴリが存在するか判定する（重複チェック用）。呼び出し側は
    // CategoryNameNormalizer.normalizeKey で導出した正規化キー（NFC + Locale.ROOT 小文字化）を渡す。
    // 【なぜ nameKey 列で比較するか】以前の existsByNameIgnoreCase は SQL の UPPER(name)=UPPER(?)
    // へ変換され、(a) 大文字小文字の同一視ルールが DB のコレーション依存になり、DB 側の最終防波堤で
    // ある nameKey 一意制約（Java の Locale.ROOT 小文字化基準）と食い違う（例: PostgreSQL の
    // UPPER('ß')='SS' は "ß" と "SS" を重複扱いするが、Java 基準では別名）、(b) 関数適用で
    // name 列の既存インデックスが使えず全件走査になる、という 2 つの問題があった。
    // nameKey 列の等値比較なら、事前チェックと一意制約が同一の定義（normalizeKey）を共有し、
    // nameKey の一意インデックスもそのまま使える（§6 一元管理・§8 全件走査の回避）
    boolean existsByNameKey(String nameKey);

    // 指定 ID 以外に同名カテゴリが存在するか判定する（更新時の重複チェック用。自分自身は対象から除く）。
    // 正規化キー（nameKey）の等値比較で判定する（上記 existsByNameKey と同じ理由）
    boolean existsByNameKeyAndIdNot(String nameKey, Long id);
}
