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

    // ページ番号（0始まり）の上限。件数（size）側は application.yml の
    // spring.data.web.pageable.max-page-size で Spring Boot が自動的に丸めるが、
    // ページ番号側には Spring Data Web の既定機能に上限がない。
    // 未検証のまま極端に大きい page（例: ?page=999999999）を許すと、行数が少ない
    // 応答のために DB へ深い OFFSET を発行させ続けられ、公開・無認証エンドポイントに対する
    // 安価なリソース消費増幅の起点になる（§8 一覧取得の上限・§9 DoS 対策）。
    // 家計簿という利用規模を踏まえ、size=100 と組み合わせても十分な件数（100万件超）を
    // カバーできる値を上限として一元管理する。
    static final int MAX_PAGE_INDEX = 10_000;

    // インスタンス化を防ぐための private コンストラクタ（静的メソッドだけを提供するため）
    private PageableSanitizer() {
    }

    // クライアント指定の sort を無視し、ページ番号・件数だけを引き継いだ上で、
    // サーバ側で定めた安全な並び順（fixedSort）に固定した Pageable を返す。
    // fixedSort に Sort.unsorted() を渡した場合は、並び順を下位（リポジトリの JPQL 側 ORDER BY）に委ねる。
    public static Pageable withFixedSort(Pageable pageable, Sort fixedSort) {
        // ページ指定なし（unpaged）の Pageable に getPageNumber()/getPageSize() を呼ぶと例外になるため、
        // ページングせず並び順だけ固定した Pageable を返す（公開ユーティリティとしての防御。§9 fail-safe）
        if (pageable.isUnpaged()) {
            // ページングは行わず、サーバ指定の安全な並び順だけを適用した unpaged な Pageable を返す
            return Pageable.unpaged(fixedSort);
        }
        // ページ番号が上限を超えていれば上限に丸める（深い OFFSET クエリを防ぐ。§8/§9）
        int clampedPageNumber = Math.min(pageable.getPageNumber(), MAX_PAGE_INDEX);
        // 件数はクライアント指定を尊重し（size 自体の上限は application.yml 側で担保済み）、
        // ページ番号は上で丸めた値、並び順はサーバ指定の安全な値へ差し替える
        return PageRequest.of(clampedPageNumber, pageable.getPageSize(), fixedSort);
    }
}
