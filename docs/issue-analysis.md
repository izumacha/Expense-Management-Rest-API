# 課題分析: 機能面・セキュリティ面

分析日: 2026-06-09
対象: `expense-tracker/`（Java 21 / Spring Boot 3.3.5 / PostgreSQL 16）を主軸とする支出管理 REST API
分析者: コードベース静的レビュー（コード変更なし・所見の棚卸しのみ）

## サマリ

支出管理 API として CRUD・月次集計の基本機能は動作し、N+1 回避や DTO 分離など良い設計判断も見られる。
一方で **本番運用を見据えると重大な欠落が複数ある**。最大の問題は次の 3 点。

1. **認証・認可が一切ない**（誰でも全データを CRUD 可能）
2. **Java 側にテストが皆無で、CI も Java を一切検証していない**（.NET 側のみ）
3. **一覧 API に上限・ページネーションがなく、レート制限もない**（DoS・リソース枯渇）

評価対象は `expense-tracker/` のみ。AgentForge（`src/`・`tests/`、C# .NET 10）は雛形段階
（`PlaceholderEntity` のみ）のため本レポートの主対象外とし、末尾で簡潔に触れる。

### 重大度サマリ

| 重大度 | 件数 | 主な内容 |
|---|---|---|
| 重大（Critical） | 3 | 認証・認可の欠如 / Java テスト皆無 / CI が Java 未検証 |
| 高（High） | 4 | 一覧の上限・ページネーション欠如 / レート制限・サイズ上限欠如 / 汎用例外ハンドラ欠如 / DB 認証情報のハードコード既定値 |
| 中（Medium） | 4 | `ddl-auto: update` の運用使用 / `amount` の上限検証欠如 / Docker root 実行 / エラーメッセージの内部情報露出 |
| 低（Low） | 3 | DB ポートのホスト公開 / `@PastOrPresent` の TZ 依存 / カテゴリ更新・削除 API 不在 |

各所見には根拠ファイルと、リポジトリ規約 `CLAUDE.md` の該当節（§8 パフォーマンス、§9 セキュリティ、
§11 テスト、§14 CI）を併記する。

## 現状ステータス（2026-07-19 再検証）

本分析（2026-06-09時点）から約1か月半が経過し、その間の一連の修正 PR（#37〜#46 ほか）で大半の所見が
解消済みであることを、実際のコード読み合わせと `./mvnw test` 実行（DB 依存の `*RepositoryTest` を除く）
の両方で確認した。**以下の一覧は原文の所見を書き換えず、現状ステータスを追記するものである**
（元の分析日時点の記録として §1・§2 本文は保持する）。

| # | 所見 | 現状 | 根拠 |
|---|---|---|---|
| 1.1 | 認証・認可が一切ない | **意図的スコープ外（未解消）** | `config/SecurityConfig.java` が `permitAll()` を維持しつつ、Javadoc で「MVP フェーズの明示的な設計判断」であることと本番移行時の対応手順（JWT/OAuth2・ロール認可・CORS・CSRF 再評価）を明記 |
| 1.2 | レート制限・サイズ上限・タイムアウトがない | **解消済み** | `security/RateLimitFilter.java`（IP 単位・ウィンドウ制限）、`RequestBodySizeLimitFilter`（本文サイズ上限＋413）、`application.yml` の `server.tomcat.connection-timeout`/`max-swallow-size` |
| 1.3 | 一覧 API に上限・ページネーションがない | **解消済み** | `CategoryController`/`ExpenseController` が `Pageable` + `PageableSanitizer`（sort 固定・page 上限）を使用、`application.yml` の `spring.data.web.pageable.max-page-size: 100` |
| 1.4 | 汎用例外ハンドラがない | **解消済み** | `GlobalExceptionHandler` が `Exception`/`DataAccessException`/`MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException`/`NoHandlerFoundException` を含め網羅的に `{status, message}` へ整形 |
| 1.5 | DB 認証情報のハードコード既定値 | **解消済み** | `application.yml` の `password: ${SPRING_DATASOURCE_PASSWORD}`（既定値なし＝未設定で起動失敗）。`docker-compose.yml` も `${SPRING_DATASOURCE_PASSWORD:?...}` で同様に fail-closed |
| 1.6 | Docker root 実行 | **解消済み** | `Dockerfile` の実行ステージで `app` ユーザーを作成し `USER app` で非 root 実行 |
| 1.7 | エラーメッセージの内部 ID 露出 | **解消済み** | `ExpenseService`/`CategoryService` の `NotFoundException` はすべて `ErrorMessages.CATEGORY_NOT_FOUND`/`EXPENSE_NOT_FOUND` の定型文言のみを使用し、ID を文字列連結していない |
| 1.8 | DB ポートのホスト公開 | **解消済み** | `docker-compose.yml` の `db` サービスが `127.0.0.1:5432:5432`（ループバック限定）に変更済み |
| 2.1 | Java 側テストが皆無 | **解消済み** | `expense-tracker/src/test/java` 配下に Controller/Service/Repository/Exception 各層のテストが多数追加され、`./mvnw test` で全パス |
| 2.2 | CI が Java を未検証 | **解消済み** | `.github/workflows/ci.yml` に `java-build-test` ジョブ（`./mvnw -B verify`）が追加済み |
| 2.3 | `ddl-auto: update` の運用使用 | **一部解消（既定値は据え置き）** | `application.yml` が `${SPRING_JPA_HIBERNATE_DDL_AUTO:update}` として環境変数で `validate` 等へ上書き可能にはなったが、既定値自体は引き続き `update` |
| 2.4 | `amount` の上限検証がない | **解消済み** | `CreateExpenseRequest` に `@Digits(integer = 17, fraction = 2)` を追加 |
| 2.5 | `@PastOrPresent` の TZ 依存 | **未検証・据え置き** | 今回の再検証範囲では変更を確認できず。優先度は引き続き低 |
| 2.6 | カテゴリ更新・削除 API 不在 | **解消済み** | `CategoryController` に `PUT /api/categories/{id}`・`DELETE /api/categories/{id}` を追加（使用中カテゴリの削除は `CategoryInUseException` で 409） |

**再評価後の重大度サマリ**: 当初の「重大 3 件」のうち 2 件（Java テスト皆無・CI 未検証）は解消済みで、
残る「認証・認可の欠如」は `SecurityConfig.java` に明記された意図的な MVP スコープ判断であり、
本番移行前に必ず対応すべき唯一の残課題として位置付けを変更する。「高 4 件」はすべて解消済み。
「中 4 件」は 3 件解消・1 件（`ddl-auto` 既定値）一部解消。「低 3 件」は 2 件解消・1 件未検証のまま。

---

## 1. セキュリティ面の課題

### 1.1【重大】認証・認可が一切ない

- 根拠: `expense-tracker/src/main/java/com/izumacha/expensetracker/controller/ExpenseController.java`、
  `controller/CategoryController.java`（全エンドポイント）。`pom.xml` に `spring-boot-starter-security` の依存なし。
- 問題: `/api/expenses`・`/api/categories` の作成・参照・更新・削除がすべて無認証で誰でも実行できる。
  ユーザーやテナントの概念がなく、データ分離もない。公開した瞬間に第三者が全データを閲覧・改ざん・削除できる。
- 該当規約: §9「認可はサーバー側で強制する」。
- 推奨対応: Spring Security を導入し、最低限の認証（API キー / JWT / OAuth2 Resource Server 等）を全エンドポイントに適用。
  将来マルチユーザ化するなら、支出・カテゴリに所有者（ユーザー/テナント）列を持たせ、クエリに必ず所有者条件を差し込む。

### 1.2【高】レート制限・リクエストサイズ上限・タイムアウトがない

- 根拠: アプリ全体（`application.yml` に関連設定なし、フィルタ/インターセプタ実装なし）。
- 問題: 公開エンドポイントが無防備で、大量リクエストや巨大ボディで容易にリソースを枯渇させられる（DoS）。
- 該当規約: §9「公開エンドポイントを保護する（レート制限・リクエストサイズ・タイムアウト・ページネーション上限）」。
- 推奨対応: リバースプロキシ or アプリ層（Bucket4j 等）でレート制限。`server.tomcat.max-swallow-size` /
  `spring.servlet.multipart` 等でサイズ上限、接続・読み取りタイムアウトを設定。

### 1.3【高】一覧 API に上限・ページネーションがない

- 根拠: `repository/ExpenseRepository.java#search`（条件一致を全件返す）、
  `service/ExpenseService.java#search`、`service/CategoryService.java#findAll`（全件 `findAll`）。
- 問題: 支出が大量にある月や全カテゴリ取得で、無制限に行を返す。レスポンス肥大・メモリ圧迫・DoS の起点。
- 該当規約: §8「一覧取得は必ず上限・ページネーションを持たせる」、§9。
- 推奨対応: `Pageable` を導入し、既定件数・最大件数を定数で一元管理（§6）。`search` は `Page<ExpenseResponse>` 返却に。
  ※ セキュリティ性質上は 1.2 と一体で対応するのが望ましい。

### 1.4【高】汎用例外ハンドラがなく fail-safe が不足

- 根拠: `exception/GlobalExceptionHandler.java`。ハンドラは
  `MethodArgumentNotValidException` / `IllegalArgumentException` / `NotFoundException` / `DuplicateException` の 4 種のみ。
- 問題: 以下が未捕捉で、Spring デフォルトの `/error` 応答になりエラー契約 `{status, message}` が崩れる。環境次第で内部情報が露出する恐れ。
  - `Exception`（想定外の実行時例外）・`DataAccessException`（DB 障害）→ 500 が整形されない。
  - `MissingServletRequestParameterException`: `GET /api/expenses/summary`（`month` 必須）でパラメータ欠落時。
  - `MethodArgumentTypeMismatchException`: `GET /api/expenses/abc` や `?categoryId=abc` など型不一致時。
- 該当規約: §9「失敗しても安全側に倒す」「スタックトレース・内部詳細を漏らさない」、§6「エラーを握り潰さない」。
- 推奨対応: 上記例外のハンドラを追加し、すべて `ErrorResponse(status, message)` の安全な文言に統一。
  汎用 `Exception` は 500 + 一般化メッセージ（詳細はサーバログのみ）でフォールバック。

### 1.5【高】DB 認証情報をハードコードした既定値で埋め込んでいる

- 根拠: `src/main/resources/application.yml`（`${SPRING_DATASOURCE_USERNAME:expensetracker}` 等の既定値）、
  `docker-compose.yml`（`POSTGRES_USER/PASSWORD: expensetracker`）。
- 問題: 環境変数が未設定でも `expensetracker/expensetracker` で起動してしまう fail-open。
  本番でうっかり既定値のまま動く危険があり、推測されやすい資格情報がリポジトリに残る。
- 該当規約: §9「秘密情報をコミットしない」「未設定なら起動を失敗させる（fail-closed）」。
- 推奨対応: パスワードは既定値を外し `${SPRING_DATASOURCE_PASSWORD:?must be set}` のように未設定で起動失敗に。
  `.env.example` にキー名のみ記載し、実値はコミットしない。compose もシークレット/環境変数参照に。

### 1.6【中】Docker コンテナがアプリを root で実行している

- 根拠: `Dockerfile`（`USER` 指定がなく root のまま `ENTRYPOINT`）。
- 問題: コンテナ侵害時の権限が大きい。最小権限の原則に反する。
- 推奨対応: 実行ステージで非 root ユーザーを作成し `USER` で降格する。

### 1.7【中】エラーメッセージに内部 ID 等を含む

- 根拠: `service/ExpenseService.java`（`"category not found: id=" + categoryId` ほか）、`CategoryService.java`。
- 問題: 内部識別子や構造をレスポンスに返しており、情報過多。列挙的な探索の手がかりになりうる。
- 該当規約: §9「外部にはサニタイズした安全なメッセージだけを返す」。
- 推奨対応: 外部向けは一般化（例: 「指定された支出が見つかりません」）し、詳細はサーバログに限定。

### 1.8【低】DB ポート 5432 をホストに公開している

- 根拠: `docker-compose.yml`（`ports: "5432:5432"`、コメントは「任意・確認用」）。
- 問題: 本番では不要な攻撃面。開発用途であっても既定で開けるのは望ましくない。
- 推奨対応: 公開はローカル確認時のみに限定（compose override で分離）し、本番構成では公開しない。

---

## 2. 機能面・品質面の課題

### 2.1【重大】Java 側にテストが存在しない

- 根拠: `expense-tracker/src/test` が存在しない（テストクラスなし）。`pom.xml` には `spring-boot-starter-test` はある。
- 問題: サービスのロジック（月パース・集計・重複チェック・404 分岐）や API 契約がまったく検証されていない。
  リグレッションを機械的に検知できない。
- 該当規約: §11「テストは必ず通過させること」。境界値（0・空・非数値・月フォーマット不正）重視。
- 推奨対応: 純粋ロジックは `ExpenseService`/`CategoryService` のユニットテスト（リポジトリはモック）、
  DB を伴う検証は `@DataJpaTest` や Testcontainers（PostgreSQL）で契約テストに寄せる（§11）。

### 2.2【重大】CI が Java（expense-tracker）を一切検証していない

- 根拠: `.github/workflows/ci.yml`。`lint` / `build-test` ともに `dotnet`（restore/format/build/test/脆弱性スキャン）のみで、
  `mvn -B verify` 等の Java ジョブが無い。
- 問題: 主軸である expense-tracker のビルド・テスト・脆弱性が CI で担保されない。Java 側が壊れても緑になる。
- 該当規約: §14「PR を出す前に、§2 と CI 設定に記載のローカル検証コマンドを通す」、§11。
- 推奨対応: Maven ジョブを追加（`mvn -B verify`）。テスト DB が必要なら GitHub Actions の PostgreSQL サービスコンテナを利用。
  併せて 2.1 のテスト追加が前提。

### 2.3【中】`hibernate.ddl-auto: update` を運用設定に使用している

- 根拠: `application.yml`（`spring.jpa.hibernate.ddl-auto: update`、コメントに「Flyway はスコープ外」）。
- 問題: エンティティ差分でスキーマを自動変更するため、本番で意図しない列追加・データ整合性問題を招きうる。列削除・型変更は追従しない。
- 推奨対応: 本番は `validate` とし、スキーマ変更は Flyway / Liquibase のマイグレーションで管理（§12「スキーマ変更とマイグレーションは同一コミット」）。

### 2.4【中】`amount` に上限・桁数の検証がない

- 根拠: `dto/request/CreateExpenseRequest.java`（`@NotNull` + `@DecimalMin(0, inclusive=false)` のみ）。
  エンティティは `precision = 19, scale = 2`（`domain/Expense.java`）。
- 問題: リクエスト段階で上限・小数桁の検証がなく、巨大値や過剰な小数桁を受け付ける。DB 列精度（scale=2）との不整合で丸め/エラーが発生しうる。
- 推奨対応: `@Digits(integer = 17, fraction = 2)` と必要に応じ `@DecimalMax` を追加。

### 2.5【低】`@PastOrPresent` がサーバのタイムゾーンに依存する

- 根拠: `dto/request/CreateExpenseRequest.java`（`spentOn` に `@PastOrPresent`）。
- 問題: 「当日」の判定がサーバ TZ 基準。利用者と TZ が異なると日付境界で未来日扱い/許可の誤判定が起こりうる。
- 推奨対応: 想定 TZ（例: JST）を明示し、必要なら検証を TZ 固定で行う。仕様として将来日を許すか否かも明確化。

### 2.6【低】カテゴリの更新・削除 API がない

- 根拠: `controller/CategoryController.java`（作成・一覧のみ）。
- 問題: カテゴリ名の修正や不要カテゴリの整理ができない。使用中カテゴリ削除時の支出の扱い（参照整合）も未設計。
- 備考: MVP の意図的なスコープ判断の可能性あり。**要確認**事項として記載。実装する場合は支出が紐づくカテゴリの削除可否（禁止 or カスケード）を定義する。

---

## 3. 評価できる点（良い実装）

- **N+1 を回避している**: `ExpenseRepository.search` は `JOIN FETCH e.category` で関連を一括取得、
  `summarizeByCategory` は GROUP BY 集計で 1 クエリ。§8 準拠。
- **SQL インジェクション耐性**: JPQL は名前付きパラメータでバインドし、文字列連結で値を混ぜていない。§9 準拠。
- **DTO 分離**: `dto/request`・`dto/response` を分け、内部エンティティを API 契約から切り離している。
- **金額は `BigDecimal`**: 浮動小数の誤差を避けている。§3 の設計原則に整合。
- **重複作成の二段防御**: `CategoryService.create` は `existsByName` での事前チェックに加え、
  競合時の `DataIntegrityViolationException` を捕捉して 409 に変換（同時実行の取りこぼしを防ぐ）。
- **`open-in-view: false`**: 遅延ロードをトランザクション境界内に収め、ビュー層での予期せぬクエリを防いでいる。
- **入力検証の基本適用**: Jakarta Bean Validation で必須・桁・空文字を検証し、`GlobalExceptionHandler` で 400 に整形。

---

## 4. 推奨対応の優先順位

1. **【最優先】テスト追加 + CI への Java ジョブ追加**（2.1・2.2）— 以降の修正を安全に行う土台。
2. **認証・認可の導入**（1.1）— 公開前の必須要件。
3. **一覧のページネーション + レート制限・サイズ上限**（1.3・1.2）— DoS 対策。
4. **例外ハンドラの網羅と安全なエラー応答**（1.4・1.7）— 契約の一貫性と情報漏えい防止。
5. **資格情報の fail-closed 化・本番スキーマ管理**（1.5・2.3）— 運用安全性。
6. **Docker 非 root 化・DB ポート非公開・`amount` 検証・TZ・カテゴリ API**（1.6・1.8・2.4・2.5・2.6）— 段階的に。

---

## 付記: AgentForge（C# .NET 10）について

`src/`・`tests/` は Clean Architecture の雛形段階（`PlaceholderEntity` とプレースホルダテストのみ）で、
現時点での実機能リスクは低い。一方、CI（`.github/workflows/ci.yml`）の脆弱性スキャン・フォーマット・
ロック厳守は整備済みで良好。今後の実装時に §9（入力検証・認可・秘密情報管理）と §11（テスト）を
最初から適用することを推奨する。詳細な設計方針は `docs/DESIGN.md` を参照。
