# --- ビルドステージ ---
# Maven と JDK 21 を含むイメージをビルド用に使用する
FROM maven:3.9-eclipse-temurin-21 AS build
# 作業ディレクトリを設定する
WORKDIR /app
# 依存解決を先に行うため pom.xml を先にコピーする
COPY pom.xml .
# 依存ライブラリを事前にダウンロードする（キャッシュ活用）
RUN mvn -B -q dependency:go-offline
# ソースコードをコピーする
COPY src ./src
# テストを省略して実行可能 JAR をビルドする
RUN mvn -B -q clean package -DskipTests

# --- 実行ステージ ---
# 実行には軽量な JRE 21 イメージを使用する
FROM eclipse-temurin:21-jre
# 作業ディレクトリを設定する
WORKDIR /app
# 最小権限の原則に従い、アプリ実行用の非 root ユーザーとグループを作成する
RUN groupadd --system app && useradd --system --gid app --no-create-home app
# ビルドステージから生成済み JAR を非 root ユーザー所有でコピーする
COPY --from=build --chown=app:app /app/target/*.jar app.jar
# アプリの待受ポートを公開する
EXPOSE 8080
# 以降のプロセスを非 root ユーザーで実行する（コンテナ侵害時の権限を最小化する）
USER app
# コンテナ起動時に JAR を実行する
ENTRYPOINT ["java", "-jar", "app.jar"]
