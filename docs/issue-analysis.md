# 課題分析: 機能面・セキュリティ面

分析日: 2026-06-09
対象: `expense-tracker/`（Java 21 / Spring Boot 3.3.5 / PostgreSQL 16）を主軸とする支出管理 REST API
分析者: コードベース静的レビュー（コード変更なし・所見の棚卸しのみ）

> **パスに関する注記（後日追記）**: 本ドキュメントは分析当時のディレクトリ構成に基づき `expense-tracker/...` というパスで参照している。その後リポジトリ整理により Java アプリ一式はリポジトリ直下へ移動し、C# の AgentForge は削除された。現在のパスは `expense-tracker/` を取り除いたもの（例: `expense-tracker/src/main/java/...` → `src/main/java/...`）に読み替えること。

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

> 上表は 2026-06-09 の当初分析（計 14 件）の内訳であり、当時の記録として固定する。
> **当初分析より後に追加した所見は含まれない**（A.1【中】専用の監査ログがない・**解消済み**）。
> 未解消の課題を一覧するときは、下の「現状ステータス」表を参照すること。

各所見には根拠ファイルと、リポジトリ規約 `CLAUDE.md` の該当節（§8 パフォーマンス、§9 セキュリティ、
§11 テスト、§14 CI）を併記する。

## 現状ステータス（2026-07-19 再検証 / 2026-08-12 に追加所見 A.1 を追記）

本分析（2026-06-09時点）から約1か月半が経過し、その間の一連の修正 PR（#37〜#46 ほか）で大半の所見が
解消済みであることを、実際のコード読み合わせと `./mvnw test` 実行（DB 依存の `*RepositoryTest` を除く）
の両方で確認した。**以下の一覧は原文の所見を書き換えず、現状ステータスを追記するものである**
（元の分析日時点の記録として §1・§2 本文は保持する）。

| # | 所見 | 現状 | 根拠 |
|---|---|---|---|
| 1.1 | 認証・認可が一切ない | **解消済み** | `config/SecurityConfig.java` が `anyRequest().authenticated()` を強制し、JWT（Resource Server 方式・HS256）を必須化。認証不要は `POST /api/auth/token`（＋ERROR ディスパッチ）のみ。`JwtConfig`（シークレット未設定/32 バイト未満で起動失敗）・`ApiUserConfig`（ユーザー名/bcrypt ハッシュ未設定・平文で起動失敗）・CORS 許可オリジンの明示リスト（未設定は全拒否・`*` は起動失敗）がいずれも fail-closed。`JwtAuthorizationTest` / `CorsPolicyTest` / `SecurityConfigValidationTest` で回帰テスト済み |
| 1.2 | レート制限・サイズ上限・タイムアウトがない | **解消済み** | `security/RateLimitFilter.java`（IP 単位・ウィンドウ制限）、`RequestBodySizeLimitFilter`（本文サイズ上限＋413）、`application.yml` の `server.tomcat.connection-timeout`/`max-swallow-size` |
| 1.3 | 一覧 API に上限・ページネーションがない | **解消済み** | `CategoryController`/`ExpenseController` が `Pageable` + `PageableSanitizer`（sort 固定・page 上限）を使用、`application.yml` の `spring.data.web.pageable.max-page-size: 100` |
| 1.4 | 汎用例外ハンドラがない | **解消済み** | `GlobalExceptionHandler` が `Exception`/`DataAccessException`/`MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException`/`NoHandlerFoundException` を含め網羅的に `{status, message}` へ整形 |
| 1.5 | DB 認証情報のハードコード既定値 | **解消済み** | `application.yml` の `password: ${SPRING_DATASOURCE_PASSWORD}`（既定値なし＝未設定で起動失敗）。`docker-compose.yml` も `${SPRING_DATASOURCE_PASSWORD:?...}` で同様に fail-closed |
| 1.6 | Docker root 実行 | **解消済み** | `Dockerfile` の実行ステージで `app` ユーザーを作成し `USER app` で非 root 実行 |
| 1.7 | エラーメッセージの内部 ID 露出 | **解消済み** | `ExpenseService`/`CategoryService` の `NotFoundException` はすべて `ErrorMessages.CATEGORY_NOT_FOUND`/`EXPENSE_NOT_FOUND` の定型文言のみを使用し、ID を文字列連結していない |
| 1.8 | DB ポートのホスト公開 | **解消済み** | `docker-compose.yml` の `db` サービスが `127.0.0.1:5432:5432`（ループバック限定）に変更済み |
| 2.1 | Java 側テストが皆無 | **解消済み** | `expense-tracker/src/test/java` 配下に Controller/Service/Repository/Exception 各層のテストが多数追加され、`./mvnw test` で全パス |
| 2.2 | CI が Java を未検証 | **解消済み** | `.github/workflows/ci.yml` に `java-build-test` ジョブ（`./mvnw -B verify`）が追加済み |
| 2.3 | `ddl-auto: update` の運用使用 | **解消済み** | `application.yml` の既定値を `${SPRING_JPA_HIBERNATE_DDL_AUTO:validate}` へ変更し、設定を忘れた環境が自動 DDL 側（fail-open）に倒れないようにした。スキーマ作成が必要な環境だけが明示的に上書きする（`docker-compose.yml` の app サービスが `update`、Testcontainers の `AbstractRepositoryTest` が `create-drop`）。既定値は `DdlAutoDefaultTest` で固定 |
| 2.4 | `amount` の上限検証がない | **解消済み** | `CreateExpenseRequest` に `@Digits(integer = 17, fraction = 2)` を追加 |
| 2.5 | `@PastOrPresent` の TZ 依存 | **解消済み** | `config/TimeZoneConfig.java` が `@PostConstruct` で JVM 既定タイムゾーンを起動時に `Asia/Tokyo` へ固定し、`@PastOrPresent`（`Clock.systemDefaultZone()` 依存）がコンテナの実行環境 TZ に左右されないようにした。`TimeZoneConfigTest` で回帰テスト済み |
| 2.6 | カテゴリ更新・削除 API 不在 | **解消済み** | `CategoryController` に `PUT /api/categories/{id}`・`DELETE /api/categories/{id}` を追加（使用中カテゴリの削除は `CategoryInUseException` で 409） |
| A.1 | 専用の監査ログがない（2026-08-12 追加所見） | **解消済み** | `domain/AuditLog.java`（`audit_logs` テーブル）と `audit/` パッケージを追加。データ変更は JPA の永続化フック（`EntityAuditListener` + `@EntityListeners`）で、認証イベントは `AuthTokenService`（トークン発行の唯一の経路）で記録する。書き込みはコミット後・fail-open。記録漏れは `AuditedEntityCoverageTest`（全 `@Entity` の走査）、実行時配線の断線は `AuditLogPersistenceTest`、認証記録の過不足は `AuthenticationAuditScopeTest` が検出する。残る限界は下記「追加所見」に追記 |

**再評価後の重大度サマリ**: 当初の 14 件（「重大 3 件」「高 4 件」「中 4 件」「低 3 件」）は
**すべて解消済み**。当初分析より後に追加した所見（A.1 専用の監査ログがない）も解消済みである
（範囲を限定した実装のため、残る限界は「追加所見」§A.1 の「対応の記録」に明記している）。
最後まで残っていた 2 件（1.1 認証・認可の欠如 / 2.3 `ddl-auto` 既定値）も後続 PR で解消した。
なお 1.1 は本ドキュメントの前回更新（2026-07-19）時点では「意図的スコープ外」と記載していたが、
その後 JWT 認証が実装され、記載が実装から取り残されていたため本追記で訂正している。

> **本ドキュメントの位置付け（更新ルール）**: §1・§2 の本文は 2026-06-09 の分析時点の記録として
> 書き換えず保持し、当初 14 件（1.1〜2.6）については上表のステータス欄だけを更新する。
> セキュリティ関連の実装（認証・認可・レート制限・スキーマ管理方針）を変更したら、
> **同じ PR で上表の該当行も更新する**こと（コードとドキュメントの乖離を許さない原則。
> 共通規約 §3・§15）。
>
> **当初分析より後に見つかった課題**は、原文に混ぜず「追加所見」節（§2 の後）に `A.n` として追記し、
> 上表にも同じ ID で 1 行足す。当初 14 件の凍結を保ったまま、棚卸し表としての網羅性を維持するため。

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

## 追加所見（2026-08-12 追記）

> 2026-06-09 の当初分析より後に見つかった課題をここに追記する。§1・§2 の本文は当時の記録として
> 凍結しているため、後日の所見は原文に混ぜずこの節へ積み、上のステータス表にも 1 行追加する。

### A.1【中】専用の監査ログ（audit log）がない

- 根拠: `src/main/java/com/izumacha/expensetracker/` 配下に監査用のエンティティ・リポジトリ・
  インターセプタが存在しない。`ExpenseService` / `CategoryService` は変更内容を記録せず永続化する。
- 問題: 「誰が・いつ・どの支出を作成/更新/削除したか」を後から追えない。認証イベント
  （`POST /api/auth/token` の成功/失敗）も記録されないため、資格情報の総当たりを検知・立証できない。
  現状の代替手段はコンテナ標準出力のアプリログだけだが、これは追跡には不十分である
  （json-file のローテーションは上限超過分を捨てるディスク保護であり、`docker compose down` で
  コンテナごと消える。README の「運用手順」に明記）。
- 影響範囲: 単一 API ユーザーの MVP では実害が限定的だが、マルチユーザー化（1.1 の残課題）と同時に
  必要になる。金額データを扱う以上、変更履歴の説明責任は運用要件になり得る。
- 対応案: 変更を記録する監査テーブルを設け、サービス層ではなく永続化フック
  （Hibernate のイベントリスナ / Spring Data の監査機能）に寄せて記録漏れを防ぐ。認証イベントは
  Spring Security の `AuthenticationSuccessEvent` / `AuthenticationFailureBadCredentialsEvent` を購読する。
  記録先はアプリログではなく DB とし、失敗時もアプリを停止させない（fail-open で機能を縮退）か、
  監査必須の要件なら fail-closed にするかを先に決める。ログ・監査記録にパスワードやトークンを
  含めないこと（共通規約 §9）。
- 関連: README「既知の制約・今後の課題」の該当項目。

#### 対応の記録（2026-08-16）

**実装**: `domain/AuditLog.java`（`audit_logs` テーブル）＋ `audit/` パッケージ
（`AuditAction` / `AuditActorResolver` / `AuditRecorder` / `AuditLogWriter` /
`EntityAuditListener`）と、認証の成否を記録する `service/AuthTokenService`。記録するのは 2 種類。

| 種類 | 記録経路 | `entity_name` | `action` |
|---|---|---|---|
| データ変更 | JPA の永続化フック（`@EntityListeners(EntityAuditListener.class)`） | `Expense` / `Category` | `CREATE` / `UPDATE` / `DELETE` |
| 認証 | `AuthTokenService`（トークン発行の唯一の経路） | `Authentication` | `LOGIN_SUCCESS` / `LOGIN_FAILURE` |

**データ変更**は対応案どおり、サービス層ではなく永続化フックに寄せた（新しい保存経路を足しても
記録の書き忘れが起きない）。

**認証イベント**は対応案が挙げていた「Spring Security のイベント購読」を<b>採らなかった</b>。
実装して検証したところ、Bearer トークンを検証するリソースサーバの認証も同じイベントに乗るため、
次の 2 つが起きることが分かったためである（`AuthenticationAuditScopeTest` で実証）。

1. **通常の API 呼び出し 1 回ごとに「ログイン成功」が記録される。** 保存期間を持たない監査テーブルが
   リクエスト数に比例して膨れ、ログイン成功の記録が「トークンが発行された証拠」として使えなくなる
   （総当たりの立証という A.1 の目的が果たせない）。リクエストごとに監査書き込みのトランザクションも増える。
2. **トークン検証の失敗では認証の主体名が Bearer トークン文字列そのものになる。** それを actor として
   保存すると、資格情報を追記専用テーブルへ書き込むことになる（§9）。

そこで記録は経路が 1 つしかない `service/AuthTokenService`（`POST /api/auth/token` の実装）の中で
行う。イベントに寄せる利点（記録し忘れを防ぐ）より、記録元を自分たちが制御する 1 箇所に閉じる利点
（記録しすぎを防ぐ）が上回るという判断である。

**先に決めた判断**

- **fail-open（記録できなくても業務処理は続ける）を採用した。** データ変更の記録は業務
  トランザクションの<b>コミット後</b>に書くため、記録に失敗しても変更はもう巻き戻せない。
  そこで例外を投げるとデータは保存済みなのにクライアントには失敗が返る、という実態と応答が
  食い違う壊れ方になる。認証側も、監査テーブルの不調でトークン発行が止まると全 API が停止する。
  代償として**記録の欠落は起こりうる**（WARN ログ `監査ログの記録に失敗しました` でのみ判る）。
  監査が必須要件になったら fail-closed へ切り替える必要があり、その際はデータ変更側を
  「同一トランザクション内で書く」設計に戻すところから見直す。
- **値の差分は保存しない。** 記録するのは「いつ・誰が・どの行に・どの操作をしたか」まで。
  フィールド単位の差分まで残すと本体テーブルの家計情報が監査テーブルにも複製され、保護すべき
  範囲・バックアップ・権限管理が二重になる（§9 最小公開）。A.1 の目的（変更履歴の説明責任・
  総当たりの立証）は行単位の記録で満たせる。
- **ログイン失敗の actor には送られたユーザー名を残す。** 総当たりの立証に必要なため。
  外部入力なので制御文字を除去し列長（100 文字）へ切り詰めてから保存する（`validation/TextSanitizer`）。
  パスワードは受け取りも記録もしない。
- **書き込み量の絞りはレート制限へ一本化した。** トークン発行は未認証で叩けるため失敗記録が
  資源枯渇に使われうるが、既存の `security/RateLimitFilter`（トークン発行は重み付きで既定
  10 回/分・IP ごと）が上限を担保する。監査側にも別の上限を置くと実効値が 2 箇所に分かれて読めなくなる。

**記録漏れを機械的に防ぐ仕掛け**（人手の付け忘れが「静かに監査されないテーブル」を生むため）

- `AuditedEntityCoverageTest` — アプリのパッケージ配下の全 `@Entity`（抽象クラスを含む）を走査し、`AuditLog` 自身を除く
  すべてが `AuditedEntity` の実装と `@EntityListeners(EntityAuditListener.class)` を
  備えていることを検証する（新エンティティの付け忘れはビルドで落ちる）。
- `AuthenticationAuditScopeTest` — 実物のフィルタチェーン上で認証記録の**過不足の両方**を固定する
  （通常の API 呼び出しでは 1 件も記録しないこと／トークン発行では成功・失敗とも記録し、
  渡すのはユーザー名だけでパスワードは渡さないこと）。上記 1・2 の再発を防ぐ検出網。
- `AuditLogPersistenceTest` — 本物の PostgreSQL 上で実際に行が書かれることを検証する
  （JPA リスナの生成と依存注入という、実行時にしか成立しない配線の唯一の砦）。
- `AuditActionTest` — 操作種別の定数名が `action` 列長に収まることを検証する。
- `AuditLogReadmeDdlTest` — README に載せた手書き DDL が `AuditLog` のマッピング（列名・型・
  NOT NULL・主キーと識別列の指定・インデックス）と過不足なく一致することを検証する。本番のスキーマ方針は
  `validate` なので、この写しがずれると **(a)** 列のずれは本番の起動失敗として現れる（ローカルと
  Testcontainers はスキーマを自動生成するため手元では再現しない）、**(b)** インデックスのずれは
  `validate` の検証対象外なので**何の合図も出ないまま**本番だけインデックスが欠けた状態になる。
  (b) は気付く手段が無いため、写しの追随を人の記憶に委ねず機械で止める。
  期待値は**Hibernate に組み立てさせたマッピング情報から読む**（本番と同じ方言・命名規則を指定し、
  DB へは接続しない）。「エンティティがどんなテーブルになるか」を自前で書き写すと、写し間違いが
  そのまま誤った指示になる——たとえば命名規則を自前で実装すると `actorIP` のようなフィールドで
  Hibernate（`actorip`）と食い違い、テストは README に `actor_i_p` と書けと指示し、従うと本番の
  `validate` が落ちる。**検出網が防ぐはずの失敗を検出網自身が引き起こす**ため、対応関係は
  Hibernate に聞く（`@Transient` の除外・`columnDefinition`・関連の `@JoinColumn` も同時に正しくなる）。

**残る限界**（README「既知の制約・今後の課題」にも記載）

- 値の差分を残さない（上記の判断による）。
- 記録は fail-open のため欠落しうる（上記の判断による）。
- 参照用 API は無い（DB へ直接接続して読む。監査ログ自体が機微なため、参照 API を作るなら
  「誰が監査ログを読めるか」の認可設計が先）。
- 保存期間の自動管理が無い（行は増え続ける。運用で手動削除する）。
- JPQL / SQL の一括更新・削除は永続化フックを迂回するため記録されない。現在のサービス層は
  すべて `save` / `delete` 経由なので該当しないが、一括更新クエリを追加するときは監査記録も同時に設計する。
- 本番（`ddl-auto: validate`）では `audit_logs` テーブルの DDL を手動適用する必要がある
  （DDL は README「監査ログの確認」に記載）。**適用作業そのものは手動のまま**だが、適用する DDL が
  エンティティとずれていないことは `AuditLogReadmeDdlTest` が保証する（上記の検出網を参照）。

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
