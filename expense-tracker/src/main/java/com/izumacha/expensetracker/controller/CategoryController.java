// コントローラパッケージ
package com.izumacha.expensetracker.controller;

// カテゴリ作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateCategoryRequest;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// カテゴリサービスを参照する
import com.izumacha.expensetracker.service.CategoryService;
// リクエストボディの検証を有効化するアノテーション
import jakarta.validation.Valid;
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
// 一覧の戻り型
import java.util.List;

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

    // カテゴリ一覧を取得する（成功時 200）
    @GetMapping
    public List<CategoryResponse> list() {
        // サービスで全カテゴリを取得して返す
        return categoryService.findAll();
    }
}
