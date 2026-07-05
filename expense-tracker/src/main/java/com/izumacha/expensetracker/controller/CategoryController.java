// コントローラパッケージ
package com.izumacha.expensetracker.controller;

// カテゴリ作成リクエスト DTO を参照する
import com.izumacha.expensetracker.dto.request.CreateCategoryRequest;
// カテゴリ更新リクエスト DTO を参照する（作成と更新の API 契約を分離する）
import com.izumacha.expensetracker.dto.request.UpdateCategoryRequest;
// カテゴリ返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.CategoryResponse;
// ページ形式の返却 DTO を参照する
import com.izumacha.expensetracker.dto.response.PageResponse;
// カテゴリサービスを参照する
import com.izumacha.expensetracker.service.CategoryService;
// クライアント由来の並び順（sort）を無害化する共通ユーティリティを参照する
import com.izumacha.expensetracker.web.PageableSanitizer;
// リクエストボディの検証を有効化するアノテーション
import jakarta.validation.Valid;
// 作成したリソースの URI を組み立てる型
import java.net.URI;
// ページ指定（ページ番号・件数）を表す型
import org.springframework.data.domain.Pageable;
// 並び順（どの列で昇順/降順に並べるか）を表す型
import org.springframework.data.domain.Sort;
// 一覧取得時の既定ページサイズを指定するアノテーション
import org.springframework.data.web.PageableDefault;
// HTTP レスポンス全体を表すクラス
import org.springframework.http.ResponseEntity;
// DELETE マッピング用アノテーション
import org.springframework.web.bind.annotation.DeleteMapping;
// GET マッピング用アノテーション
import org.springframework.web.bind.annotation.GetMapping;
// パス変数（URL 中の {id} など）を取得するアノテーション
import org.springframework.web.bind.annotation.PathVariable;
// POST マッピング用アノテーション
import org.springframework.web.bind.annotation.PostMapping;
// PUT マッピング用アノテーション
import org.springframework.web.bind.annotation.PutMapping;
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

    // カテゴリ一覧の並び順（id 昇順）。クライアント由来の sort を無視して常にこの順に固定する。
    // ページング時に安定した並び順を保証し、ページをまたいだ行の重複・欠落を防ぐ（§8 一覧の決定性）。
    private static final Sort CATEGORY_LIST_SORT = Sort.by(Sort.Direction.ASC, "id");

    // カテゴリサービスへの参照
    private final CategoryService categoryService;

    // コンストラクタインジェクションで依存を受け取る
    public CategoryController(CategoryService categoryService) {
        // 受け取ったサービスをフィールドに設定する
        this.categoryService = categoryService;
    }

    // カテゴリを作成する（成功時 201。Location ヘッダに作成したリソースの URI を含める）
    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        // サービスでカテゴリを作成する
        CategoryResponse response = categoryService.create(request);
        // 201 Created と、作成したカテゴリを指す Location ヘッダ・作成結果を返す
        return ResponseEntity.created(URI.create("/api/categories/" + response.id())).body(response);
    }

    // カテゴリ詳細を取得する（成功時 200）。作成時の Location ヘッダ（/api/categories/{id}）が
    // 指す取得用エンドポイント。存在しない ID はサービスが 404 相当の例外を送出する。
    // 一覧の固定パス（@GetMapping）と衝突しないよう、可変パス {id} をこの順序で定義する。
    @GetMapping("/{id}")
    public CategoryResponse detail(@PathVariable("id") Long id) {
        // サービスで指定 ID のカテゴリを取得して返す
        return categoryService.findById(id);
    }

    // カテゴリ一覧をページ単位で取得する（成功時 200）
    @GetMapping
    public PageResponse<CategoryResponse> list(
            // ページ指定（page / size。size 未指定時は既定 20。上限は application.yml で制限）
            @PageableDefault(size = 20) Pageable pageable) {
        // クライアント由来の sort クエリパラメータは無視し、id 昇順に固定する。
        // 未検証の並び順が下位クエリへ届くのを Web 境界で防ぎ（§9）、ページングの決定性を担保する（§8）。
        Pageable sanitized = PageableSanitizer.withFixedSort(pageable, CATEGORY_LIST_SORT);
        // サービスでカテゴリをページ単位で取得して返す
        return categoryService.findAll(sanitized);
    }

    // カテゴリ名を更新する（成功時 200）
    @PutMapping("/{id}")
    public CategoryResponse update(
            // 更新対象のカテゴリ ID
            @PathVariable("id") Long id,
            // 更新内容（検証付き。UpdateCategoryRequest で作成との API 契約を明示分離）
            @Valid @RequestBody UpdateCategoryRequest request) {
        // サービスでカテゴリを更新して返す
        return categoryService.update(id, request);
    }

    // カテゴリを削除する（成功時 204。支出から参照中の場合はサービスが409相当の例外を送出する）
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        // サービスでカテゴリを削除する
        categoryService.delete(id);
        // 204 No Content を返す
        return ResponseEntity.noContent().build();
    }
}
