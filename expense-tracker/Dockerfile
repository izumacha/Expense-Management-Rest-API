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
# ビルドステージから生成済み JAR をコピーする
COPY --from=build /app/target/*.jar app.jar
# アプリの待受ポートを公開する
EXPOSE 8080
# コンテナ起動時に JAR を実行する
ENTRYPOINT ["java", "-jar", "app.jar"]
