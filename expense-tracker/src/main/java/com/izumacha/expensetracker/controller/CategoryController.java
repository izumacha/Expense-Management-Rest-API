// コントローラパッケージ
package com.izumacha.expensetracker.controller;

// カテゴリ作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateCategoryRequest;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// カテゴリサービスを参照する
import com.izumacha.expensetracker.service.CategoryService;
// リクエストボディの検証を有効化するアノテーション
import jakarta.validation.Valid;
// ページ指定（ページ番号・件数）を表す型
import org.springframework.data.domain.Pageable;
// 一覧取得時の既定ページサイズを指定するアノテーション
import org.springframework.data.web.PageableDefault;
// HTTP ステータスを表す列挙
import org.springframework.http.HttpStatus;
// HTTP レスポンス全体を表すクラス
import org.springframework.http.ResponseEntity;
// GET マッピング用アノテーション
import org.springframework.web.bind.annotation.GetMapping;
// POST マッピング用アノテーション
import org.springframework.web.bind.annotation.PostMapping;
// リクエストボディ取得用アノテーション
import org.springframework.web.bind.annotation.RequestBody;
// 共通パスを宣言するアノテーション
import org.springframework.web.bind.annotation.RequestMapping;
// REST コントローラ宣言用アノテーション
import org.springframework.web.bind.annotation.RestController;

// カテゴリ関連のエンドポイントを提供するコントローラ
@RestController
// 共通のベースパスを設定する
@RequestMapping("/api/categories")
public class CategoryController {

    // カテゴリサービスへの参照
    private final CategoryService categoryService;

    // コンストラクタインジェクションで依存を受け取る
    public CategoryController(CategoryService categoryService) {
        // 受け取ったサービスをフィールドに設定する
        this.categoryService = categoryService;
    }

    // カテゴリを作成する（成功時 201）
    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        // サービスでカテゴリを作成する
        CategoryResponse response = categoryService.create(request);
        // 201 Created とともに作成結果を返す
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // カテゴリ一覧をページ単位で取得する（成功時 200）
    @GetMapping
    public PageResponse<CategoryResponse> list(
            // ページ指定（page / size。size 未指定時は既定 20。上限は application.yml で制限）
            @PageableDefault(size = 20) Pageable pageable) {
        // サービスでカテゴリをページ単位で取得して返す
        return categoryService.findAll(pageable);
    }
}
