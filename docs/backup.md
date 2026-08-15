# データベースバックアップ運用ガイド

支出データ（金額・日付・メモ等の個人の家計情報）を失わないための定期バックアップ手順。
helpdesk-hub / incident-insight のバックアップ自動化（`backup.yml` + `scripts/backup-db.sh`）を移植したもので、
PostgreSQL（`docker-compose.yml` の `db` サービス / DB 名 `expensetracker`）を `pg_dump` で定期ダンプし、世代管理しながら保管する。

> `docker compose exec` を使った**手動バックアップ・リストアの対話手順**（一時ファイル方式・cron の
> 落とし穴など）は [README の運用手順](../README.md#運用手順ログの確認db-バックアップ) を参照。
> このガイドはスクリプト / GitHub Actions による**自動化**を扱う。

## スクリプト

| スクリプト | 役割 |
| --- | --- |
| `scripts/backup-db.sh` | `pg_dump` で custom 形式 (圧縮込み) のダンプを作成し、古い自動取得分を削除する |
| `scripts/restore-db.sh` | `pg_restore` でダンプから復元する（**既存データを上書き**） |

```bash
DATABASE_URL="postgresql://user:***@localhost:5432/expensetracker" bash scripts/backup-db.sh
# 実行内容だけ確認（dump しない）。事前チェックは dry-run 分岐より先に走るため、
# DATABASE_URL の設定と pg_dump の存在は dry-run でも必要
DATABASE_URL="postgresql://user:***@localhost:5432/expensetracker" bash scripts/backup-db.sh --dry-run
DATABASE_URL="..." bash scripts/restore-db.sh backup/auto_expensetracker_YYYYMMDD_HHMMSS.dump
```

ダンプのファイル名に付くタイムスタンプは**実行ホストのローカル時刻**になる（GitHub Actions では
`backup.yml` が `TZ=Asia/Tokyo` を設定するため JST）。

## 設定（環境変数）

| 変数 | 既定 | 説明 |
| --- | --- | --- |
| `DATABASE_URL` | （必須） | ダンプ対象 PostgreSQL の接続文字列 |
| `BACKUP_DIR` | `./backup` | ダンプの保存先。本番では永続ボリュームを指定する |
| `BACKUP_RETENTION_DAYS` | `14` | 保持日数。これより古い `auto_expensetracker_*.dump` は自動削除される |

ダンプは支出データを含むため、`backup/` は `.gitignore` 済み（リポジトリにコミットしない）。

### ファイル名の名前空間（手動と自動の分離）

自動取得分は `auto_expensetracker_` プレフィックスを付け、README の手動バックアップ
（`expensetracker_*`）と名前空間を分ける。世代削除は `auto_expensetracker_*.dump` だけを対象に
するため、アップグレード前に取っておいた手動スナップショットが保持日数の経過で消えることはない
（README の cron 例で `daily_` を分けているのと同じ方針）。

### 保存先の権限

`backup-db.sh` は先頭で `umask 077` を設定するため、**新規に作成する**ダンプは `0600`、新規に作成する
保存先ディレクトリは `0700`（作成した本人だけが読める）になる。`pg_dump` は `--file` の出力を既定の
umask のまま作るので、これが無いと多くのホストの既定 umask `022` では `0644` = 同じホストの全ユーザーが
支出データを読める状態になってしまう。

ただし **既に存在するディレクトリの権限は umask では変わらない**。永続ボリューム等の既存ディレクトリを
`BACKUP_DIR` に指定する場合は、運用側で明示的に絞ること:

```bash
install -d -m 700 -o backup -g backup /var/backups/expense-tracker
```

### 接続情報の渡し方

`DATABASE_URL` は `pg_dump` / `pg_restore` の**引数**として渡るため、同じホストにログインできる他の
ユーザーからは `ps` や `/proc/<pid>/cmdline` で見える。URL にパスワードを埋め込むと、そのパスワードも
一緒に見えてしまう。共有ホストではパスワードを URL から外し、環境変数かパスワードファイルで渡すこと
（libpq が自動的に補完するため、スクリプト側の変更は不要）:

```bash
# URL からパスワードを外し、PGPASSWORD で渡す（環境変数は cmdline と違い他ユーザーから読めない）
DATABASE_URL="postgresql://backup_ro@localhost:5432/expensetracker" \
    PGPASSWORD="..." bash scripts/backup-db.sh

# あるいは ~/.pgpass（0600 必須。ファイルなので cron 定義にも履歴にも残らない）
printf 'localhost:5432:expensetracker:backup_ro:...\n' > ~/.pgpass && chmod 600 ~/.pgpass
DATABASE_URL="postgresql://backup_ro@localhost:5432/expensetracker" bash scripts/backup-db.sh
```

GitHub Actions のランナーは 1 ジョブ専有の使い捨て VM で他ユーザーが同居しないため、`backup.yml` は
`BACKUP_DATABASE_URL` にパスワードを含めた形のままで問題ない。

## 自動化の選択肢

### 1. GitHub Actions（`.github/workflows/backup.yml`）

毎日 JST 03:00（cron `0 18 * * *` UTC）に実行。手動実行も可。次の 2 つのリポジトリ Secret を
両方設定すると有効化され、**GPG（AES256 対称鍵）で暗号化したダンプ**を artifact として 7 日間保持する。

| Secret | 説明 |
| --- | --- |
| `BACKUP_DATABASE_URL` | ダンプ対象 PostgreSQL の接続文字列（読み取り専用ロール推奨）。未設定ならジョブは安全に no-op で終了する |
| `BACKUP_ENCRYPTION_PASSPHRASE` | ダンプの GPG 暗号化パスフレーズ。`BACKUP_DATABASE_URL` があるのにこれが無い場合、**平文の支出データを artifact に載せないためジョブは失敗する**（fail-closed） |

暗号化済み artifact（`*.dump.gpg`）の復号:

```bash
gpg --batch --decrypt --pinentry-mode loopback --passphrase-fd 0 \
    --output auto_expensetracker_YYYYMMDD_HHMMSS.dump auto_expensetracker_YYYYMMDD_HHMMSS.dump.gpg
# (標準入力からパスフレーズを渡す。echo でシェル履歴に残さないこと)
```

> ⚠️ このリポジトリは **public** のため、平文の artifact は事実上誰でもダウンロードできてしまう。
> ワークフローが暗号化を必須にしているのはこのため。パスフレーズは十分に長いランダム値を使い、
> パスワードマネージャ等で管理する。支出データ（暗号化済みであっても）を GitHub に一切置けない
> 運用要件がある場合は本ワークフローを使わず、次のホスト cron を使う。
>
> なお GitHub Actions のランナーから `BACKUP_DATABASE_URL` の DB に到達できる必要がある。
> `docker-compose.yml` 既定の `127.0.0.1:5432` 公開のままでは外部から接続できないため、
> ローカル完結の運用では次のホスト cron を使う。

### 2. ホスト cron（支出データを外部に出さない運用）

本番サーバー（または DB に到達できるホスト）の crontab に登録する:

パスワードは URL に埋め込まず `~/.pgpass`（`0600`）に置く（「接続情報の渡し方」参照）。
crontab に書くとバックアップ実行中の `ps` 出力にも載ってしまうため。
なお **cron のエントリは 1 行で書く**（crontab はシェルと違いバックスラッシュによる行継続を
解釈しないため、複数行に分けると登録に失敗するか別のコマンドとして解釈される）:

```cron
# 毎日 03:00 にバックアップ（出力は syslog/logger 等へ）
0 3 * * * cd /path/to/Expense-Management-Rest-API && DATABASE_URL="postgresql://backup_ro@localhost:5432/expensetracker" BACKUP_DIR=/var/backups/expense-tracker BACKUP_RETENTION_DAYS=30 bash scripts/backup-db.sh >> /var/log/expense-tracker-backup.log 2>&1
```

cron は失敗を画面に出さないため、README の運用手順にある通り**失敗に気づける経路**
（`MAILTO=`・監視サービス・最新更新時刻の監視など）を必ず用意する。

### 3. Docker 環境（`pg_dump` をホストに入れない運用）

`docker compose` 構成では `db` コンテナに `pg_dump` が同梱されているため、ホストに
postgresql-client を入れずにバックアップできる。一時ファイル方式・cron の PATH の
落とし穴を含む具体的な手順は [README の運用手順](../README.md#運用手順ログの確認db-バックアップ) を参照。

## 復元手順

1. 復元先 `DATABASE_URL` を確認する（**上書きされる**ので接続先を間違えない）。
2. アプリを停止してから復元を実行する:

   ```bash
   docker compose stop app
   DATABASE_URL="postgresql://expensetracker:***@localhost:5432/expensetracker" \
       bash scripts/restore-db.sh backup/auto_expensetracker_YYYYMMDD_HHMMSS.dump
   docker compose start app
   ```

3. 復元後にアプリを起動して動作確認する。**注意**: `docker-compose.yml` は app サービスに
   `SPRING_JPA_HIBERNATE_DDL_AUTO=update` を設定しているため、復元直後のスキーマは検証されず
   Hibernate によって**黙って変更されうる**（`validate` は既定値であって compose 経由の既定ではない）。
   古いダンプを復元した直後の起動では、必要に応じて `validate` に切り替えてスキーマ整合を確認する。

## 検証（リストアテスト）

バックアップは「復元できて初めて有効」。定期的に空 DB へリストアして整合を確認する:

```bash
createdb expensetracker_restore_test
DATABASE_URL="postgresql://postgres:***@localhost:5432/expensetracker_restore_test" \
    bash scripts/restore-db.sh backup/<最新>.dump
dropdb expensetracker_restore_test
```
