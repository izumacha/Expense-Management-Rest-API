# C# ベストプラクティス導出 & 2026年5月需要アプリ提案

## Context（なぜこの設計か）

`izumacha/cs-app` リポジトリは README のみのグリーンフィールド。ユーザーの依頼は2つ：

1. **評価の高いC#ソースコードを参照してベストプラクティスを導出する**
2. **2026年5月時点で需要の高いアプリを提案する**

スコープは「**提案・設計書のみ（コードは書かない）**」。本ドキュメントを設計書として確定し、リポジトリ `docs/DESIGN.md` としてコミット・プッシュする。実装は別タスクで段階的に行う前提。

---

## Part 1. C# ベストプラクティス（評価の高いOSSから導出）

### 1.1 参照したOSS（GitHubスター・実績ベース）

| カテゴリ | リポジトリ | スター | 学べる点 |
|---|---|---|---|
| Windows生産性 | `microsoft/PowerToys` | 132k+ | プラグインアーキテクチャ、WinUI 3 + Win32相互運用、巨大OSSのモジュール分割 |
| メディアサーバー | `jellyfin/jellyfin` | 51k+ | ASP.NET Core長期運用、プラグイン拡張、ストリーミング、認可 |
| ファイルマネージャ | `files-community/Files` | 43k+ | WinUI 3 + MVVM、シェル統合、パフォーマンスチューニング |
| 耐障害性ライブラリ | `App-vNext/Polly` | — | Retry/Circuit Breaker/Timeout/Fallbackの宣言的合成、純度の高いAPI設計 |
| エンタープライズ基盤 | `abpframework/abp` | — | DDD、モジュラーモノリス、テナント、認可、CQRS |
| Clean Arch テンプレート | `ardalis/CleanArchitecture` | 18k+ | Core / UseCases / Infrastructure / Web、Ardalis.ApiEndpoints |
| Clean Arch テンプレート | `jasontaylordev/CleanArchitecture` | 20k+ | CQRS+MediatR、AutoMapper、FluentValidation、Aspire、Scalar |

### 1.2 抽出したベストプラクティス（採用方針）

#### アーキテクチャ
- **Clean / Onion Architecture**：依存は内側のみへ。`Domain` → `Application` → `Infrastructure` / `Presentation`
- **CQRS + Mediator**：`MediatR` でコマンド/クエリを分離。ハンドラ単位でテスト容易化
- **垂直スライス**：機能単位（Feature folder）で `Command` / `Handler` / `Validator` / `Endpoint` を凝集
- **ドメインイベント**：副作用を疎結合化（メールやキャッシュ無効化など）
- **ApiEndpoints**：肥大化する Controller を1リクエスト1クラスへ分解（`ardalis/ApiEndpoints`）

#### コーディング・ライブラリ
- **C# 13 / .NET 10**：primary constructors、collection expressions、`required` メンバー、Native AOT 視野
- **`Result<T>` 型 or `OneOf`**：例外を制御フローに使わない。失敗を型で表現
- **`FluentValidation`**：入力検証はハンドラから分離
- **`Polly v8`**：HTTP / DBアクセスに `ResiliencePipeline`
- **`Serilog` + OpenTelemetry**：構造化ログ + 分散トレース（Aspire ダッシュボード）
- **`EF Core 10`**：マイグレーション必須、`AsNoTracking`、`Compiled Queries` をホットパスに

#### テスト
- **xUnit + Shouldly + NSubstitute（or Moq）**
- **TestContainers** で Postgres / Redis を実起動した統合テスト
- **Verify** でスナップショットテスト（プロンプトやJSONレスポンスの回帰検知に有効）
- **アーキテクチャテスト**：`NetArchTest` で依存方向の違反を CI で検知

#### 運用
- **.NET Aspire** でローカル開発の依存サービスをコード定義（OTel 完備）
- **`Microsoft.Extensions.AI`**：LLMクライアントの抽象化（OpenAI / Anthropic / Ollama を差し替え）
- **`Scalar`**：OpenAPI UI（Swagger UI の後継として浸透）
- **GitHub Actions**：`dotnet test --logger trx`、`dotnet format --verify-no-changes`、`dotnet pack` の3点セット

---

## Part 2. 提案アプリ：`AgentForge` — ローカルAIエージェントオーケストレーター

### 2.1 なぜこのアプリか（2026年5月の需要根拠）

- 2026年は **エージェント＝独立した製品カテゴリ** に格上げ。Microsoft Agent 365 が5/1にローンチし、各社がエージェントSDKを出揃わせた局面。
- 一方で **ローカル実行・プライバシー重視** のエージェント基盤は手薄。多くは SaaS 連携前提。
- C# / .NET は **Semantic Kernel が Microsoft Agent Framework v1.0** として GA、**ModelContextProtocol C# SDK v1.0** も提供されており、エージェント／MCPホストとして実装するのに最も追い風が吹いている言語。
- **Avalonia 11** は Unity / JetBrains / NASA 採用実績があり、MCP統合済み・クロスプラットフォーム（Win/macOS/Linux）。デスクトップ常駐型の AI ハブに最適。

### 2.2 ターゲットユーザーと価値

| ユーザー | 課題 | AgentForgeの価値 |
|---|---|---|
| 個人開発者 | Claude Code / Cursor 外でも自分のエージェントを走らせたい | ローカルでエージェントワークフローを定義・実行・可視化 |
| 中小企業の業務担当 | 機密データを SaaS に出せない | ローカル LLM（Ollama）+ 社内 MCP サーバーで完結 |
| エージェント開発者 | 観測・デバッグ環境がない | トレース、評価、リプレイを GUI で提供 |

### 2.3 主要機能（MVP範囲）

1. **Agent Designer**：YAMLまたは GUI でエージェント（モデル・プロンプト・許可ツール）を定義
2. **MCP クライアントハブ**：複数の MCP サーバー（stdio / HTTP）を一元接続・ツールを横断利用
3. **Run コンソール**：エージェント実行をストリーミング表示、ツール呼び出しを階層ツリーで可視化
4. **Trace Viewer**：OpenTelemetry トレースを取り込み、レイテンシ・コスト・失敗を可視化
5. **Provider 切替**：OpenAI / Anthropic / Azure OpenAI / Ollama（ローカル）を統一抽象で切替
6. **シークレット保管**：OSキーチェーン（DPAPI / macOS Keychain / libsecret）を `Microsoft.Extensions.Configuration` プロバイダ化

### 2.4 ターゲットプラットフォーム

**Avalonia 11 デスクトップ（Windows / macOS / Linux）+ 組み込み ASP.NET Core 10 ローカルサーバー**

理由：
- ローカル実行＆プライバシーが最大の差別化 → デスクトップ必須
- MCPホスティングや観測 API は HTTP のほうが拡張しやすい → 同一プロセスで `WebApplication` を起動
- 将来 CLI / VS Code 拡張から接続する余地を残す

### 2.5 ソリューション構成（提案）

```
AgentForge.sln
├── src/
│   ├── AgentForge.Domain/              # エンティティ、ドメインイベント、Result型
│   ├── AgentForge.Application/         # MediatR Handlers, ポート（IAgentRunner, IMcpHub）
│   ├── AgentForge.Infrastructure/      # EF Core (SQLite既定), Polly, OTel エクスポータ
│   ├── AgentForge.Agents/              # Microsoft Agent Framework / Semantic Kernel ラッパ
│   ├── AgentForge.Mcp/                 # ModelContextProtocol C# SDK (Client + Server両対応)
│   ├── AgentForge.Server/              # ASP.NET Core 10 (Minimal API + ApiEndpoints, Scalar)
│   ├── AgentForge.Desktop/             # Avalonia 11 + CommunityToolkit.Mvvm
│   └── AgentForge.Shared/              # DTO / Contracts
├── tests/
│   ├── AgentForge.UnitTests/           # xUnit + Shouldly + NSubstitute
│   ├── AgentForge.IntegrationTests/    # TestContainers + WebApplicationFactory
│   └── AgentForge.ArchitectureTests/   # NetArchTest による依存方向検証
├── aspire/
│   └── AgentForge.AppHost/             # .NET Aspire ローカルオーケストレーション
├── .github/workflows/ci.yml
├── Directory.Packages.props            # Central Package Management
├── Directory.Build.props               # Nullable, AnalysisLevel=latest, TreatWarningsAsErrors
└── docs/
    ├── DESIGN.md                       # 本ドキュメント
    └── adr/                            # Architecture Decision Records
```

### 2.6 技術選定

| 領域 | 採用 | 理由 |
|---|---|---|
| ランタイム | .NET 10 (LTS) | 最新 LTS、Native AOT 安定 |
| エージェント | Microsoft Agent Framework 1.0 (旧 Semantic Kernel) | C#で最も成熟、MCP相互運用 |
| MCP | `ModelContextProtocol` / `ModelContextProtocol.AspNetCore` v1.0 | 公式 .NET SDK |
| LLM抽象 | `Microsoft.Extensions.AI` | プロバイダ差し替え可能 |
| デスクトップUI | Avalonia 11 + `CommunityToolkit.Mvvm` | クロスプラットフォーム、ソースジェネレータで軽量 |
| ローカルAPI | ASP.NET Core 10 Minimal API + `Ardalis.ApiEndpoints` | エージェント実行の HTTP/SSE エンドポイント |
| データ | EF Core 10 + SQLite | ローカル単一ファイル、ゼロ設定 |
| 設定/シークレット | `Microsoft.Extensions.Configuration` + OSキーチェーン | OSネイティブのセキュア保管 |
| メディエータ | MediatR | CQRS の事実上の標準 |
| 検証 | FluentValidation | パイプライン挙動と相性◎ |
| 耐障害性 | Polly v8 ResiliencePipeline | LLM API のリトライ／サーキット |
| 観測 | OpenTelemetry + Aspire Dashboard | ローカルで完結するトレース／メトリクス |
| ログ | Serilog (構造化、ファイル＋OTLP) | エージェントの行動監査 |
| テスト | xUnit, Shouldly, NSubstitute, TestContainers, Verify | 上記ベストプラクティスに準拠 |
| CI | GitHub Actions | OSSデファクト |

### 2.7 重要な設計判断（ADR候補）

1. **MCPツールはプラグインとして動的ロード**：起動時にユーザー定義の MCP サーバーをスポーン／接続し、`Microsoft Agent Framework` の `KernelPlugin` に `AsKernelFunction()` で投入
2. **コンテンツセーフティは SK Filters**：ツール実行前にコンテンツ検査フックを通す
3. **HTTP transport を既定**：高スループットを見越して `ModelContextProtocol.AspNetCore`（stdio は単発ツール向け）
4. **エージェント実行は別プロセス**：UIのクラッシュ耐性とリソース分離。`System.Threading.Channels` でストリーム連携
5. **Result型でエラーを表現**：例外はインフラ層境界のみ。アプリ層は `OneOf<Success, ValidationError, ExternalError>` を返す
6. **NetArchTest をCIで強制**：Domain は Infrastructure を参照しないなどの依存方向を保証

### 2.8 既存資産の再利用（重要）

新規実装の前に以下を必ず利用する：

- **Microsoft Agent Framework**：エージェントのループ・ツール呼び出し・並列化を **自前で書かない**
- **ModelContextProtocol C# SDK**：MCP の JSON-RPC / stdio / SSE を **自前で書かない**
- **`Microsoft.Extensions.AI`**：LLM クライアント抽象を **自前で書かない**
- **`Polly v8` プリセット**：HTTP 用 `ResiliencePipeline` を流用
- **`CommunityToolkit.Mvvm`**：`[ObservableProperty]` / `[RelayCommand]` で MVVM ボイラープレートを書かない

---

## 重要ファイル（実装時に作成する想定の主要パス）

| ファイル | 役割 |
|---|---|
| `Directory.Build.props` | Nullable有効、警告をエラー化、`LangVersion=latest` |
| `Directory.Packages.props` | Central Package Management（バージョン一元管理） |
| `src/AgentForge.Application/DependencyInjection.cs` | MediatR + Validators + Pipeline Behavior 登録 |
| `src/AgentForge.Agents/AgentRunner.cs` | Agent Framework のラッパ、ストリーミング実行 |
| `src/AgentForge.Mcp/McpHub.cs` | 複数MCPサーバーの起動・接続・ツール集約 |
| `src/AgentForge.Desktop/App.axaml(.cs)` | Avalonia エントリ、`AppBuilder` + DI |
| `src/AgentForge.Desktop/ViewModels/RunConsoleViewModel.cs` | 実行コンソールのMVVM |
| `src/AgentForge.Server/Endpoints/Agents/RunAgentEndpoint.cs` | SSE で実行ストリームを配信 |
| `aspire/AgentForge.AppHost/Program.cs` | Aspire オーケストレーション |
| `.github/workflows/ci.yml` | build / test / format / arch-test |
| `docs/adr/0001-clean-architecture.md` 〜 | 各設計判断のADR |

---

## 検証（このプロポーザル自体の検証方法）

実装はスコープ外だが、本設計の妥当性は次で確認できる：

1. **ベストプラクティスの裏取り**：参照したOSSのリンクと採用パターンが本文に明示されている（Part 1.1, 1.2）
2. **需要根拠**：2026年5月時点の市場動向（Microsoft Agent 365、Agent Framework GA、MCP普及）を Part 2.1 に明記
3. **再利用優先**：Part 2.8 で自前実装回避ライブラリを列挙
4. **依存方向**：Part 2.5 の構成で `Domain` が外側に依存しないこと、`Desktop` と `Server` が `Application` のみに依存する形になっていること

実装フェーズに移行する際の検証：
- `dotnet new` テンプレートとして `ardalis/CleanArchitecture` を起点に骨格を生成
- `dotnet test` + アーキテクチャテストが緑になる
- Avalonia アプリ起動 → `Run` ボタンで Ollama or Claude を呼び出し、最小のエージェントが MCP `everything` サーバーのツールを呼べる
- Aspire ダッシュボードでトレースが見える

---

## 参考リンク

- [ardalis/CleanArchitecture](https://github.com/ardalis/CleanArchitecture)
- [jasontaylordev/CleanArchitecture](https://github.com/jasontaylordev/CleanArchitecture)
- [microsoft/semantic-kernel](https://github.com/microsoft/semantic-kernel)
- [Microsoft Agent Framework — Building a MCP Server with Semantic Kernel](https://devblogs.microsoft.com/agent-framework/building-a-model-context-protocol-server-with-semantic-kernel/)
- [Avalonia UI](https://avaloniaui.net/)
- [State of .NET 2026](https://devnewsletter.com/p/state-of-dot-net-2026/)
- [AI agent trends 2026 — Google Cloud](https://cloud.google.com/resources/content/ai-agent-trends-2026)
- [120+ Agentic AI Tools Mapped (2026) — StackOne](https://www.stackone.com/blog/ai-agent-tools-landscape-2026/)
- [Clean Architecture in .NET (2026) — Milan Jovanović](https://www.milanjovanovic.tech/blog/clean-architecture-dotnet)
