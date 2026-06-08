# expense-tracker

個人の支出を記録・集計する家計簿アプリのバックエンド REST API です。
カテゴリごとに支出を登録し、月次でカテゴリ別・総合計を集計できます。

## 技術スタック

| 領域      | 採用技術                                         |
|-----------|--------------------------------------------------|
| 言語      | Java 21                                          |
| FW        | Spring Boot 3.3.x（Web / Data JPA / Validation） |
| DB        | PostgreSQL 16                                    |
| ビルド    | Maven                                            |
| 補助      | Lombok                                           |
| インフラ  | Docker / docker-compose                          |

## クイックスタート

### Docker で起動（推奨）

```bash
# アプリ + DB をビルドして起動する
docker compose up --build
```

起動後、`http://localhost:8080` で API を待ち受けます。
DB は PostgreSQL 16 が `db` サービスとして同時に起動します。

### ローカルで起動

PostgreSQL を別途用意し、以下を実行します。

```bash
# 依存解決とビルド・起動
./mvnw spring-boot:run
# もしくは Maven がインストール済みなら
mvn spring-boot:run
```

接続情報は環境変数 `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` /
`SPRING_DATASOURCE_PASSWORD` で上書きできます（既定値は `application.yml` を参照）。

## API 一覧

### カテゴリ

| Method | Path               | 説明         | 成功 |
|--------|--------------------|--------------|------|
| POST   | `/api/categories`  | カテゴリ作成 | 201  |
| GET    | `/api/categories`  | 一覧         | 200  |

### 支出

| Method | Path                                        | 説明                             | 成功 |
|--------|---------------------------------------------|----------------------------------|------|
| POST   | `/api/expenses`                             | 支出登録                         | 201  |
| GET    | `/api/expenses?month=YYYY-MM&categoryId=`   | 一覧（月・カテゴリで絞込、任意） | 200  |
| GET    | `/api/expenses/{id}`                        | 詳細                             | 200  |
| PUT    | `/api/expenses/{id}`                        | 更新                             | 200  |
| DELETE | `/api/expenses/{id}`                        | 削除                             | 204  |
| GET    | `/api/expenses/summary?month=YYYY-MM`       | 月次集計（合計＋カテゴリ別）     | 200  |

## エラーレスポンス

すべての例外は `@RestControllerAdvice` で一元処理し、共通フォーマットで返します。

```json
{ "status": 400, "message": "amount: must be greater than 0" }
```

| 状況                   | ステータス |
|------------------------|------------|
| バリデーション違反     | 400        |
| リソース未存在         | 404        |
| カテゴリ名の重複       | 409        |

## curl 例

```bash
# カテゴリを作成する
curl -X POST http://localhost:8080/api/categories \
  -H "Content-Type: application/json" \
  -d '{"name":"食費"}'

# カテゴリ一覧を取得する
curl http://localhost:8080/api/categories

# 支出を登録する
curl -X POST http://localhost:8080/api/expenses \
  -H "Content-Type: application/json" \
  -d '{"amount":1280,"categoryId":1,"description":"ランチ","spentOn":"2026-06-09"}'

# 月とカテゴリで支出一覧を絞り込む
curl "http://localhost:8080/api/expenses?month=2026-06&categoryId=1"

# 支出の詳細を取得する
curl http://localhost:8080/api/expenses/1

# 支出を更新する
curl -X PUT http://localhost:8080/api/expenses/1 \
  -H "Content-Type: application/json" \
  -d '{"amount":1500,"categoryId":1,"description":"ランチ（更新）","spentOn":"2026-06-09"}'

# 支出を削除する
curl -X DELETE http://localhost:8080/api/expenses/1

# 月次集計を取得する
curl "http://localhost:8080/api/expenses/summary?month=2026-06"
```

### 月次集計レスポンス例

```json
{
  "month": "2026-06",
  "total": 52340,
  "byCategory": [
    { "categoryId": 1, "categoryName": "食費",   "total": 31200 },
    { "categoryId": 2, "categoryName": "交通費", "total": 21140 }
  ]
}
```

## 設計メモ

- エンティティは直接返さず、`record` + 静的ファクトリ `from()` で DTO へ変換します。
- 金額は `BigDecimal` で扱い、`double` は使用しません。
- 月次集計は JPQL の `GROUP BY` で実装し、アプリ側での集計や N+1 を避けます。
- `month=YYYY-MM` はサービス層で月初〜翌月初の期間に変換して範囲検索します。
- `spring.jpa.open-in-view: false`、`ddl-auto: update`（Flyway はスコープ外）。

## レイヤ構成

```
controller / service / repository / domain / dto(request・response) / exception
```
