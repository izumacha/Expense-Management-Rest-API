// Web 横断ユーティリティのテストパッケージ
package com.izumacha.expensetracker.web;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// ページ番号・件数・並び順から Pageable を組み立てる型
import org.springframework.data.domain.PageRequest;
// ページ指定（ページ番号・件数・並び順）を表す型
import org.springframework.data.domain.Pageable;
// 並び順（どの列で昇順/降順に並べるか）を表す型
import org.springframework.data.domain.Sort;

// 値を検証する assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;

// PageableSanitizer の純粋ロジック（クライアント sort の無害化）を検証するユニットテスト
class PageableSanitizerTest {

    // 通常のページ指定では、クライアントの sort が捨てられ、ページ番号・件数は引き継がれ、
    // 並び順がサーバ指定の固定値へ差し替わることを検証する
    @Test
    void ページ指定ありならページ番号と件数を引き継ぎ並び順だけ固定される() {
        // クライアントが name 降順の sort を付けた 2 ページ目・サイズ 5 の Pageable を用意する
        Pageable input = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "name"));
        // サーバ指定の固定並び順（id 昇順）を用意する
        Sort fixed = Sort.by(Sort.Direction.ASC, "id");

        // sort を無害化して固定並び順に差し替える
        Pageable result = PageableSanitizer.withFixedSort(input, fixed);

        // ページ番号がクライアント指定（index 1）のまま引き継がれることを検証する
        assertThat(result.getPageNumber()).isEqualTo(1);
        // 件数がクライアント指定（5 件）のまま引き継がれることを検証する
        assertThat(result.getPageSize()).isEqualTo(5);
        // 並び順がサーバ指定の id 昇順に差し替わっている（クライアントの name 降順は捨てられた）ことを検証する
        assertThat(result.getSort()).isEqualTo(fixed);
    }

    // Sort.unsorted() を固定値に渡した場合は、並び順なし（下位クエリの ORDER BY 任せ）になることを検証する
    @Test
    void 固定並び順にunsortedを渡すと並び順なしになる() {
        // クライアントが amount 降順の sort を付けた既定ページの Pageable を用意する
        Pageable input = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "amount"));

        // 並び順を「なし」に固定して無害化する
        Pageable result = PageableSanitizer.withFixedSort(input, Sort.unsorted());

        // 並び順が無指定（クライアントの sort が捨てられている）であることを検証する
        assertThat(result.getSort().isSorted()).isFalse();
        // ページ番号・件数は引き継がれることを検証する
        assertThat(result.getPageNumber()).isEqualTo(0);
        assertThat(result.getPageSize()).isEqualTo(20);
    }

    // ページ指定なし（unpaged）の Pageable でも例外にならず、unpaged のまま固定並び順が適用されることを検証する
    // （公開ユーティリティとしての防御。@PageableDefault の無いエンドポイントで再利用されても 500 にしない）
    @Test
    void unpagedなPageableでも例外にならず固定並び順が適用される() {
        // ページングしない Pageable を用意する
        Pageable input = Pageable.unpaged();
        // サーバ指定の固定並び順（id 昇順）を用意する
        Sort fixed = Sort.by(Sort.Direction.ASC, "id");

        // 無害化する（getPageNumber()/getPageSize() を呼ぶと例外になる入力）
        Pageable result = PageableSanitizer.withFixedSort(input, fixed);

        // ページングしない状態が保たれることを検証する
        assertThat(result.isUnpaged()).isTrue();
        // それでもサーバ指定の並び順が適用されていることを検証する
        assertThat(result.getSort()).isEqualTo(fixed);
    }
}
