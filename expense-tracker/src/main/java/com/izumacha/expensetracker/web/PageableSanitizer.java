// Web 横断のユーティリティを置くパッケージ
package com.izumacha.expensetracker.web;

// ページ番号・件数・並び順から Pageable を組み立てる実装
import org.springframework.data.domain.PageRequest;
// ページ指定（ページ番号・件数・並び順）を表す型
import org.springframework.data.domain.Pageable;
// 並び順（どの列で昇順/降順に並べるか）を表す型
import org.springframework.data.domain.Sort;

// 一覧 API が受け取る Pageable から、クライアント由来の並び順（sort クエリパラメータ）を無害化する共通ユーティリティ。
//
// 【なぜ必要か】
// Spring Data Web はデフォルトで sort クエリパラメータ（例: ?sort=amount,desc）を Pageable に取り込む。
// しかし本 API のページング契約は page / size のみで、並び順はサーバ側が固定する（README 参照）。
// 未検証の sort をそのままクエリへ流すと、存在しない列名（例: ?sort=notAColumn）で
// JPQL/派生クエリのプロパティ解決に失敗し、統一エラー契約 {status, message} を外れて
// 500＋スタックトレースのログ出力を招く（外部入力起因＝CLAUDE.md §9、ログ肥大による二次的 DoS）。
// そこで Web 境界でクライアントの sort を捨て、サーバ側で定めた安全な並び順に固定する。
public final class PageableSanitizer {

    // インスタンス化を防ぐための private コンストラクタ（静的メソッドだけを提供するため）
    private PageableSanitizer() {
    }

    // クライアント指定の sort を無視し、ページ番号・件数だけを引き継いだ上で、
    // サーバ側で定めた安全な並び順（fixedSort）に固定した Pageable を返す。
    // fixedSort に Sort.unsorted() を渡した場合は、並び順を下位（リポジトリの JPQL 側 ORDER BY）に委ねる。
    public static Pageable withFixedSort(Pageable pageable, Sort fixedSort) {
        // ページ番号・件数はクライアント指定を尊重し、並び順だけをサーバ指定の安全な値へ差し替える
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), fixedSort);
    }
}
