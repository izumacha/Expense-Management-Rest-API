# CI 実装プラン (`.github/workflows/ci.yml`)

`docs/DESIGN.md` の Part 1.2「運用」および Part 2.5 のソリューション構成が前提にしている GitHub Actions ワークフローの実装プラン。**本ドキュメントは設計のみ**。実装（YAMLの追加）はソリューションスケルトン生成と同タイミングで別PRで行う。

## ゴール

| # | 目的 | チェック内容 |
|---|---|---|
| G1 | ビルドの健全性 | `dotnet build` がエラー・警告ゼロ（`TreatWarningsAsErrors`） |
| G2 | コード整形 | `dotnet format --verify-no-changes` が緑 |
| G3 | テスト | 単体・統合・アーキテクチャテストが緑、カバレッジを成果物化 |
| G4 | アーキテクチャ規約 | `NetArchTest` による依存方向違反を検知 |
| G5 | 脆弱パッケージ検知 | `dotnet list package --vulnerable` で既知CVEを検出 |
| G6 | 再現性 | Central Package Management + `--locked-mode` でロック逸脱を防止 |

## 採用しないもの（明示）

- **コンテナイメージのpush / リリース成果物の自動公開**: スコープ外。リリース用ワークフローは別ファイル `release.yml` で後日。
- **マルチOSビルドマトリクス（初期段階）**: 初手は `ubuntu-latest` 単一で立ち上げ、Avalonia デスクトップが入った段階で `windows-latest` / `macos-latest` を追加。
- **本番デプロイ**: AgentForge はデスクトップ配布想定のため、CI からの本番デプロイは存在しない。

## トリガー

```yaml
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  workflow_dispatch:
```

- `push` は `main` のみ。feature ブランチは PR でしかCIを走らせない（重複実行回避）。
- `workflow_dispatch` は手動再実行用。

## ジョブ構成

```
ci.yml
├── lint        (format + vulnerable packages)
├── build-test  (build + unit + arch tests + coverage)
└── integration (TestContainers 必要 / docker サービス利用)
```

3ジョブ並列。`integration` は重いので **PR ラベル `run-integration` がある場合のみ** 実行（後述）。

### ジョブ1: `lint`

| ステップ | コマンド | 失敗条件 |
|---|---|---|
| Checkout | `actions/checkout@v4` | — |
| .NET セットアップ | `actions/setup-dotnet@v4`（`global.json` 参照） | — |
| ツール復元 | `dotnet tool restore` | — |
| フォーマット検査 | `dotnet format --verify-no-changes --severity warn` | 差分があればfail |
| 脆弱パッケージ | `dotnet list package --vulnerable --include-transitive` をパースしてfail | CRITICAL/HIGH があればfail |

### ジョブ2: `build-test`

| ステップ | コマンド | 失敗条件 |
|---|---|---|
| Checkout | `actions/checkout@v4` | — |
| .NET セットアップ | `actions/setup-dotnet@v4` | — |
| NuGetキャッシュ | `actions/cache@v4` (`~/.nuget/packages`, key: `nuget-${{ hashFiles('**/Directory.Packages.props') }}`) | — |
| リストア | `dotnet restore --locked-mode` | ロックファイル不整合でfail |
| ビルド | `dotnet build --configuration Release --no-restore` | 警告/エラーでfail（`TreatWarningsAsErrors=true`） |
| 単体テスト | `dotnet test tests/AgentForge.UnitTests --no-build -c Release --logger trx --collect:"XPlat Code Coverage"` | — |
| アーキテクチャテスト | `dotnet test tests/AgentForge.ArchitectureTests --no-build -c Release` | `NetArchTest` 違反でfail |
| カバレッジ集約 | `reportgenerator` → `coverage/` | — |
| 成果物アップロード | `actions/upload-artifact@v4` (`coverage/`, `**/TestResults/*.trx`) | — |

### ジョブ3: `integration`（条件付き）

実行条件: `github.event_name == 'push'` OR `contains(github.event.pull_request.labels.*.name, 'run-integration')`

| ステップ | コマンド |
|---|---|
| Checkout / setup-dotnet | — |
| Docker 起動確認 | `docker info` |
| TestContainers 実行 | `dotnet test tests/AgentForge.IntegrationTests --no-build -c Release --logger trx` |

理由: Postgres / Redis を起動するため最も時間がかかる。全PRで走らせると DX が落ちる。

## 共有設定

- ジョブのデフォルト `timeout-minutes: 20`（`integration` のみ 30）
- `concurrency: group: ci-${{ github.ref }}, cancel-in-progress: true`（同一PRの古い実行をキャンセル）
- `permissions: contents: read`（最小権限）
- `pull_request` でPR元がforkの場合、シークレットを使うステップ（脆弱パッケージスキャンの認証など）はスキップ

## 依存ファイル（CI実装と同時に必要）

| パス | 役割 |
|---|---|
| `global.json` | .NET SDK バージョン固定 |
| `Directory.Build.props` | `Nullable=enable`, `AnalysisLevel=latest`, `TreatWarningsAsErrors=true`, `LangVersion=latest` |
| `Directory.Packages.props` | Central Package Management |
| `.config/dotnet-tools.json` | `dotnet-format`, `dotnet-reportgenerator-globaltool` を tool manifest として固定 |
| `NuGet.config` | `packageSourceMapping` で公式 nuget.org のみ許可（サプライチェーン対策） |
| `packages.lock.json`（各csproj配下） | `--locked-mode` 用ロックファイル |
| `tests/AgentForge.ArchitectureTests/*.cs` | `NetArchTest` での依存方向ルール |

## マイルストーン

| フェーズ | 内容 | 完了条件 |
|---|---|---|
| Phase 0（本PR） | CI実装プランの確定 | 本ドキュメントが main にマージされる |
| Phase 1 | ソリューションスケルトン生成 + 最小 `ci.yml`（lint + build-test のみ） | PR上でCIが緑 |
| Phase 2 | アーキテクチャテスト追加 + カバレッジレポート | カバレッジ成果物が PR にアップロードされる |
| Phase 3 | `integration` ジョブ追加（TestContainers） | `run-integration` ラベル付きPRで Postgres 統合テストが緑 |
| Phase 4 | OSマトリクス追加（Avalonia デスクトップ導入時） | windows-latest / macos-latest でビルド緑 |

## オープン論点

1. **SDK バージョン固定の粒度**: `global.json` の `rollForward` を `latestFeature` にするか `disable` にするか。**初手は `latestFeature`** を推奨（パッチ更新を許容、機能更新は止める）。
2. **`dotnet format` の重複**: `Directory.Build.props` で `EnforceCodeStyleInBuild=true` を入れるなら `dotnet format` はCIから外す選択肢もある。**初手は両方残す**（IDE での乖離を早期検知できるため）。
3. **PR への自動コメント**: カバレッジ結果を PR に自動コメントするかは保留（`MishaKav/jest-coverage-comment` 系の .NET 版が必要）。Phase 2 で評価。
4. **依存自動更新**: `dependabot.yml` をCIと同時に入れるか別PR か。**別PRで導入** を推奨（CIが安定してから）。
