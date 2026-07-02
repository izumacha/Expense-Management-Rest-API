// コントローラパッケージ
package com.izumacha.expensetracker.controller;

// 支出作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateExpenseRequest;
// 支出更新リクエスト DTO を参照する（作成と更新の API 契約を分離する）
import com.izumacha.expensetracker.dto.request.UpdateExpenseRequest;
// 支出返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.ExpenseResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// 月次集計返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.SummaryResponse;
// 支出サービスを参照する
import com.izumacha.expensetracker.service.ExpenseService;
// リクエストボディの検証を有効化するアノテーション
import jakarta.validation.Valid;
// 作成したリソースの URI を組み立てる型
import java.net.URI;
// ページ指定（ページ番号・件数）を表す型
import org.springframework.data.domain.Pageable;
// 一覧取得時の既定ページサイズを指定するアノテーション
import org.springframework.data.web.PageableDefault;
// HTTP レスポンス全体を表すクラス
import org.springframework.http.ResponseEntity;
// DELETE マッピング用アノテーション
import org.springframework.web.bind.annotation.DeleteMapping;
// GET マッピング用アノテーション
import org.springframework.web.bind.annotation.GetMapping;
// パス変数取得用アノテーション
import org.springframework.web.bind.annotation.PathVariable;
// POST マッピング用アノテーション
import org.springframework.web.bind.annotation.PostMapping;
// PUT マッピング用アノテーション
import org.springframework.web.bind.annotation.PutMapping;
// リクエストボディ取得用アノテーション
import org.springframework.web.bind.annotation.RequestBody;
// 共通パスを宣言するアノテーション
import org.springframework.web.bind.annotation.RequestMapping;
// クエリパラメータ取得用アノテーション
import org.springframework.web.bind.annotation.RequestParam;
// REST コントローラ宣言用アノテーション
import org.springframework.web.bind.annotation.RestController;

// 支出関連のエンドポイントを提供するコントローラ
@RestController
// 共通のベースパスを設定する
@RequestMapping("/api/expenses")
public class ExpenseController {

    // 支出サービスへの参照
    private final ExpenseService expenseService;

    // コンストラクタインジェクションで依存を受け取る
    public ExpenseController(ExpenseService expenseService) {
        // 受け取ったサービスをフィールドに設定する
        this.expenseService = expenseService;
    }

    // 支出を登録する（成功時 201。Location ヘッダに作成したリソースの URI を含める）
    @PostMapping
    public ResponseEntity<ExpenseResponse> create(@Valid @RequestBody CreateExpenseRequest request) {
        // サービスで支出を登録する
        ExpenseResponse response = expenseService.create(request);
        // 201 Created と、作成した支出を指す Location ヘッダ・登録結果を返す
        return ResponseEntity.created(URI.create("/api/expenses/" + response.id())).body(response);
    }

    // 支出一覧をページ単位で取得する（月・カテゴリで絞込、両方任意。成功時 200）
    @GetMapping
    public PageResponse<ExpenseResponse> list(
            // 月（YYYY-MM）での絞り込み（任意）
            @RequestParam(value = "month", required = false) String month,
            // カテゴリ ID での絞り込み（任意）
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            // ページ指定（page / size。size 未指定時は既定 20。上限は application.yml で制限）
            @PageableDefault(size = 20) Pageable pageable) {
        // サービスで条件に合う支出をページ単位で取得して返す
        return expenseService.search(month, categoryId, pageable);
    }

    // 月次集計を取得する（成功時 200）。一覧より先に固定パスを定義する
    @GetMapping("/summary")
    public SummaryResponse summary(@RequestParam("month") String month) {
        // サービスで月次集計を取得して返す
        return expenseService.summary(month);
    }

    // 支出の詳細を取得する（成功時 200）
    @GetMapping("/{id}")
    public ExpenseResponse detail(@PathVariable("id") Long id) {
        // サービスで指定 ID の支出を取得して返す
        return expenseService.findById(id);
    }

    // 支出を更新する（成功時 200）
    @PutMapping("/{id}")
    public ExpenseResponse update(
            // 更新対象の支出 ID
            @PathVariable("id") Long id,
            // 更新内容（検証付き。UpdateExpenseRequest で作成との API 契約を明示分離）
            @Valid @RequestBody UpdateExpenseRequest request) {
        // サービスで支出を更新して返す
        return expenseService.update(id, request);
    }

    // 支出を削除する（成功時 204）
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        // サービスで支出を削除する
        expenseService.delete(id);
        // 204 No Content を返す
        return ResponseEntity.noContent().build();
    }
}
