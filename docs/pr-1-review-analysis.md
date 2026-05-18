# PR #1 レビュー分析

対象PR: [#1 docs: C# best practices research and AgentForge proposal](https://github.com/izumacha/cs-app/pull/1)
レビュアー: @izumacha (OWNER)
レビュー提出日: 2026-05-18
レビュー判定: **Request changes 相当**（コメントのみだが内容上は修正前提）

## サマリ

`docs/DESIGN.md` のみを追加する docs-only PR。設計の方向性（C# / .NET によるローカルAIエージェントオーケストレーター `AgentForge`）は肯定的に評価されているが、**2026年5月時点の市場・技術主張に対する一次情報の裏取りが不足** している点が最大の課題。設計書としてマージする前に、外部根拠の明示、バージョン整合、MVPスコープの縮小を入れる必要がある。

## 指摘事項の分類

| 重要度 | 件数 | 対応方針 |
|---|---|---|
| 修正必須 | 1 | 本PRで対応。マージブロッカー |
| 修正推奨 | 3 | 本PRで対応。マージ前に解消 |
| 軽微 | 1 | 同PRで合わせて対応 |

### 1. 修正必須: 2026年時点の外部根拠が弱い

| 指摘された主張 | 現状の問題 | 対応案 |
|---|---|---|
| Microsoft Agent 365 が 5/1 にローンチ | 一次情報リンクなし | 公式アナウンスURL + 確認日を明記 |
| Semantic Kernel が Microsoft Agent Framework v1.0 として GA | 同上 | Microsoft DevBlog / GitHub Release ノートを引用 |
| ModelContextProtocol C# SDK v1.0 | 同上 | NuGet / 公式リポジトリのリリースタグを参照 |
| Avalonia 11 の Unity / JetBrains / NASA 採用 | 出典なし | Avalonia 公式の Adopters ページを引用 |
| Scalar が Swagger UI の後継として浸透 | 主観的断定 | 採用根拠（実OSSテンプレでの利用例など）に置き換え |

**対応:** Part 2.1 と Part 2.6 の主張に対し、根拠テーブル（主張 / URL / 確認日 / 採用判断への影響）を追加。

### 2. 修正推奨: C# / .NET バージョン整合

- 本文: `C# 13 / .NET 10`
- 指摘: .NET 10 世代なら C# 14 を前提にする可能性。実装で `LangVersion=latest` を使うなら本文もそれに合わせるべき。
- **対応:** `C# latest / .NET 10` に統一。`Directory.Build.props` の方針記述も `LangVersion=latest` で揃える。

### 3. 修正推奨: Clean Architecture と垂直スライスの併用方針の具体化

- 指摘: 何も決めずに混ぜると `Application` 配下が UseCase地獄と Feature地獄のハイブリッドになる。
- **対応:** Part 1.2 / Part 2.5 に配置ルールを1段具体化:
  - `Application` は feature 単位（例: `Application/Agents/RunAgent`、`Application/Mcp/RegisterServer`）
  - 各 feature に `Command` / `Query` / `Handler` / `Validator` を配置
  - `Domain` はエンティティ・値オブジェクト・ドメインサービスのみ
  - `Infrastructure` は `Application` の port 実装のみ

### 4. 修正推奨: MVPスコープの縮小

- 現状MVP: Agent Designer / MCPクライアントハブ / Run コンソール / Trace Viewer / Provider切替 / OSキーチェーン（6機能）
- 指摘: 初回実装としては重い。完成率を下げると設計書だけが残る。
- **対応:** MVPを2段階に分割:
  - **MVP 1:** YAMLでAgent定義 / OpenAI または Ollama のどちらか1つ / MCP stdio 1サーバー / 実行ログ画面表示 / SQLiteでRun履歴
  - **MVP 2:** Provider切替 / Trace Viewer / 複数MCPサーバー / OSキーチェーン

### 5. 軽微: GitHubスター数の鮮度

- 指摘: `PowerToys 132k+` のような固定値はすぐ変わる。
- **対応:** スター数を残すなら確認日（`2026-05-xx 確認時点`）を明記、または「成熟度・採用理由」表現に寄せて省略。

## 対応プラン

1. `docs/DESIGN.md` に **根拠テーブル** を新規セクションとして追加（Part 2.1 直後）
2. `C# 13` → `C# latest` に置換、`LangVersion=latest` 方針を Part 1.2 / Directory.Build.props 記述で明示
3. Part 1.2 / Part 2.5 に **配置ルール** ブロックを追加
4. Part 2.3 の MVP を **MVP 1 / MVP 2** に分割
5. Part 1.1 のスター数列に **確認日注記** を追加
6. 設計書の更新と合わせて、設計書が前提にしている `.github/workflows/ci.yml` の **CI実装プラン** を別ドキュメント（`docs/ci-implementation-plan.md`）として用意

なお、PR #1 自体への対応コミットは `claude/csharp-best-practices-research-I7pFt` ブランチ側で行う。本ブランチ（`claude/pr-review-ci-plan-6JnSb`）は分析とCIプランの確定までを担当する。
