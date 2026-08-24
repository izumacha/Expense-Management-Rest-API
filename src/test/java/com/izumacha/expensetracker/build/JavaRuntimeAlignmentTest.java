// ビルド構成（動かす Java の版など）の整合性を検査するテストのパッケージ
package com.izumacha.expensetracker.build;

// ファイルを読むための入出力ユーティリティ
import java.nio.file.Files;
// 読み込み時の文字コードを明示するための型
import java.nio.charset.StandardCharsets;
// 読み取るファイルの場所を表す型
import java.nio.file.Path;
// 見つけた宣言を並べておくための入れ物
import java.util.ArrayList;
// 宣言の一覧を扱うインターフェース
import java.util.List;
// 「あるかもしれない値」を安全に扱うための型
import java.util.Map;
// 正規表現のパターン（Dockerfile の FROM 行などを読むのに使う）
import java.util.regex.Matcher;
// 正規表現のパターンを表す型
import java.util.regex.Pattern;

// XML（pom.xml）を読むためのパーサ工場
import javax.xml.parsers.DocumentBuilderFactory;
// XML の安全な処理を有効にするための設定キー
import javax.xml.XMLConstants;

// 解析した XML の要素を扱う型
import org.w3c.dom.Element;
// 解析した XML 文書を扱う型
import org.w3c.dom.Document;
// 同じ名前の要素をまとめて受け取るための型
import org.w3c.dom.NodeList;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;
// テストに人が読める名前を付けるアノテーション
import org.junit.jupiter.api.DisplayName;

// YAML を読むためのパーサ
import org.yaml.snakeyaml.Yaml;
// YAML パーサの読み込み設定（安全な読み込みに使う）
import org.yaml.snakeyaml.LoaderOptions;
// 任意の Java 型を組み立てさせない安全なコンストラクタ（共通規約 §9「YAML は safe_load」）
import org.yaml.snakeyaml.constructor.SafeConstructor;

// 検証用の assertThat / fail を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 「動かす Java の major」を宣言している 4 か所が食い違っていないことを機械的に保証する整合性テスト。
 *
 * <p>【何を守るテストか】このリポジトリは Java 21 のアプリだが、その事実は 4 か所に分かれて
 * 書かれている。
 *
 * <ol>
 *   <li>{@code pom.xml} の {@code <java.version>} — コンパイル対象のバイトコード版</li>
 *   <li>{@code .github/workflows/ci.yml} の {@code java-version} — ビルドとテストを実際に流す JDK</li>
 *   <li>{@code Dockerfile} のビルドステージ {@code maven:<maven>-eclipse-temurin-<major>}</li>
 *   <li>{@code Dockerfile} の実行ステージ {@code eclipse-temurin:<major>-jre}</li>
 * </ol>
 *
 * <p>Dependabot の docker エコシステムが更新するのは 3 と 4 だけで、1 と 2 は動かない。
 * そのためベースイメージだけが上がると「JDK 21 でビルドしてテストした成果物を、別 major の
 * JRE で動かす」構成が黙って出来上がる。
 *
 * <p><b>この食い違いは CI では絶対に捕まらない。</b>CI は {@code build-test} ジョブ 1 本で
 * Temurin 21 上の {@code ./mvnw -B verify} を流すだけで、{@code Dockerfile} はビルドすらしない。
 * ベースイメージを変えても CI の結果は 1 ビットも動かないので、緑は「Java 21 のビルドがまだ
 * 通る」ことの証明であって「新しい JRE で動く」ことの証明ではない（fail-open）。壊れるとしたら
 * 本番の起動時で、しかも手元では再現しない。だから CI の緑の代わりに、この整合性テストが
 * 4 か所のずれ自体を落とす。
 *
 * <p>【検査するもの】
 * <ul>
 *   <li>(a) 4 か所が同じ major を宣言していること</li>
 *   <li>(b) どれか 1 つでも読めない／形が変わって読み取れないときは fail-closed で落とすこと
 *       （読めないまま素通りさせると、検出網があるのに何も見ていない状態になる）</li>
 *   <li>(c) {@code dependabot.yml} の docker major 保留が消えていないこと</li>
 *   <li>(d) その保留が効きすぎていないこと（{@code update-types} の欠落＝全更新の無視、
 *       {@code versions} による追加の絞り込み、同じ依存の重複エントリ）。Dependabot は
 *       同じ依存に対する複数エントリを<b>すべて適用</b>するため、重複や書き方の崩れが
 *       「minor / patch の更新まで止まる」に化ける</li>
 * </ul>
 *
 * <p>【落ちたときの直し方】Java を次の LTS へ上げる意図があるなら、4 か所を同じ major へ
 * 揃える（{@code dependabot.yml} の保留は残したままでよい）。意図していないなら、
 * ベースイメージだけを上げた変更を戻す。
 */
class JavaRuntimeAlignmentTest {

    /** コンパイル対象のバイトコード版を宣言している Maven のビルド定義。 */
    private static final Path POM_PATH = Path.of("pom.xml");

    /** ビルドとテストを実際に流す JDK を宣言している CI 定義。 */
    private static final Path CI_WORKFLOW_PATH = Path.of(".github/workflows/ci.yml");

    /** ビルド用・実行用のベースイメージを宣言しているコンテナ定義。 */
    private static final Path DOCKERFILE_PATH = Path.of("Dockerfile");

    /** ベースイメージの更新方針（major 保留）を宣言している Dependabot 定義。 */
    private static final Path DEPENDABOT_PATH = Path.of(".github/dependabot.yml");

    /**
     * Dockerfile のビルドステージが使う Maven イメージから Java の major を取り出す正規表現。
     * 例: {@code FROM maven:3.9-eclipse-temurin-21 AS build} → 21。
     */
    private static final Pattern MAVEN_IMAGE_PATTERN =
            Pattern.compile("^\\s*FROM\\s+maven:\\S*?-eclipse-temurin-(\\d+)\\b", Pattern.MULTILINE);

    /**
     * Dockerfile の実行ステージが使う JRE イメージから Java の major を取り出す正規表現。
     * 例: {@code FROM eclipse-temurin:21-jre} → 21。
     */
    private static final Pattern JRE_IMAGE_PATTERN =
            Pattern.compile("^\\s*FROM\\s+eclipse-temurin:(\\d+)-jre\\b", Pattern.MULTILINE);

    /**
     * major 更新の保留が必要なベースイメージの依存名。
     * Dependabot の docker エコシステムはイメージ名をそのまま依存名として扱う。
     */
    private static final List<String> BASE_IMAGES_REQUIRING_MAJOR_HOLD = List.of("eclipse-temurin", "maven");

    /** Dependabot で「major 更新」を表す update-type の名前。 */
    private static final String SEMVER_MAJOR = "version-update:semver-major";

    @Test
    @DisplayName("動かす Java の major を宣言する 4 か所が一致している")
    void javaMajorIsConsistentAcrossAllDeclarationSites() {
        // 1 か所目: Maven のビルド定義が宣言するバイトコード版を読む
        int pomJavaMajor = readPomJavaVersion();
        // 2 か所目: CI が実際にセットアップする JDK の版を読む
        int ciJavaMajor = readCiJavaVersion();
        // Dockerfile は 2 つのステージで別々にイメージを指定しているので本文を 1 度だけ読む
        String dockerfile = readRequiredFile(DOCKERFILE_PATH);
        // 3 か所目: ビルドステージの Maven イメージに含まれる Java の major を読む
        int dockerBuildJavaMajor = matchSingleInt(MAVEN_IMAGE_PATTERN, dockerfile, DOCKERFILE_PATH,
                "ビルドステージの FROM maven:<maven>-eclipse-temurin-<major>");
        // 4 か所目: 実行ステージの JRE イメージに含まれる Java の major を読む
        int dockerRuntimeJavaMajor = matchSingleInt(JRE_IMAGE_PATTERN, dockerfile, DOCKERFILE_PATH,
                "実行ステージの FROM eclipse-temurin:<major>-jre");

        // CI が使う JDK が pom の対象版と違うと、本番と違うバイトコードを検証したことになる
        assertThat(ciJavaMajor)
                .as("CI (%s の java-version) が使う JDK の major は、pom.xml の <java.version> (%d) と一致していること",
                        CI_WORKFLOW_PATH, pomJavaMajor)
                .isEqualTo(pomJavaMajor);

        // ビルドステージの JDK がずれると、CI が検証していない JDK で成果物を作ることになる
        assertThat(dockerBuildJavaMajor)
                .as("Dockerfile のビルドステージが使う JDK の major は、pom.xml の <java.version> (%d) と一致していること",
                        pomJavaMajor)
                .isEqualTo(pomJavaMajor);

        // 実行ステージの JRE がずれると、テストしていないランタイムで本番が動くことになる
        assertThat(dockerRuntimeJavaMajor)
                .as("Dockerfile の実行ステージが使う JRE の major は、pom.xml の <java.version> (%d) と一致していること。"
                        + " ここだけ上がると、CI が緑のまま本番だけ別ランタイムで動く", pomJavaMajor)
                .isEqualTo(pomJavaMajor);
    }

    @Test
    @DisplayName("ベースイメージの major 保留が dependabot.yml に残っていて、効きすぎてもいない")
    void dependabotHoldsBaseImageMajorUpdates() {
        // docker エコシステム（ルートディレクトリ）の設定ブロックを取り出す
        Map<String, Object> dockerEcosystem = findDockerEcosystemEntry();

        // 保留の一覧を取り出す。無ければ「保留が消えた」ので落とす
        Object rawIgnore = dockerEcosystem.get("ignore");
        if (!(rawIgnore instanceof List<?> ignoreEntries)) {
            // 保留ごと消えると major 更新の PR が再び自動で現れるため、消失そのものを検出する
            fail("%s の docker エコシステムに ignore が無い。ベースイメージの major 保留が消えている"
                    .formatted(DEPENDABOT_PATH));
            return;
        }

        // 保留が必要なイメージそれぞれについて、エントリの有無と形を検査する
        for (String imageName : BASE_IMAGES_REQUIRING_MAJOR_HOLD) {
            // 同じ依存名のエントリを集める（Dependabot は複数エントリをすべて適用するため重複も見る）
            List<Map<String, Object>> matching = new ArrayList<>();
            // 保留の一覧を 1 件ずつ確認する
            for (Object entry : ignoreEntries) {
                // マップ以外が混ざっていたら形が壊れているので読み飛ばさず落とす
                if (!(entry instanceof Map<?, ?> map)) {
                    fail("%s の ignore に、キーと値の組でない要素がある。設定の形が壊れている".formatted(DEPENDABOT_PATH));
                    return;
                }
                // 依存名が対象イメージと一致するものだけを集める
                if (imageName.equals(map.get("dependency-name"))) {
                    // 型を揃えて集める（以降の検査で update-types / versions を読むため）
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    matching.add(typed);
                }
            }

            // エントリが 1 件だけであること（0 件＝保留の消失、2 件以上＝重複適用で効きすぎる）
            assertThat(matching)
                    .as("%s の docker ignore に \"%s\" の major 保留がちょうど 1 件あること。"
                            + " 0 件なら保留の消失、2 件以上は Dependabot が全部適用するため効きすぎになる",
                            DEPENDABOT_PATH, imageName)
                    .hasSize(1);

            // 唯一のエントリを取り出して中身を検査する
            Map<String, Object> entry = matching.get(0);

            // update-types が無いエントリは「全バージョンを無視」を意味し、minor / patch まで止まる
            Object rawUpdateTypes = entry.get("update-types");
            assertThat(rawUpdateTypes)
                    .as("\"%s\" の ignore には update-types が必要。省くと全更新の無視になり、"
                            + " セキュリティ修正を含む minor / patch まで届かなくなる", imageName)
                    .isInstanceOf(List.class);

            // major だけを止めていること（他の update-type まで並べると効きすぎる）
            assertThat((List<?>) rawUpdateTypes)
                    .as("\"%s\" の ignore が止めるのは major だけであること", imageName)
                    .isEqualTo(List.of(SEMVER_MAJOR));

            // versions による追加の絞り込みは、保留の範囲を静かに変えるので置かせない
            assertThat(entry)
                    .as("\"%s\" の ignore に versions を足さないこと。update-types だけで major を止める",
                            imageName)
                    .doesNotContainKey("versions");
        }
    }

    /**
     * {@code dependabot.yml} からルートディレクトリの docker エコシステム設定を取り出す。
     * 見つからない・形が違う場合は fail-closed で落とす。
     *
     * @return docker エコシステムの設定ブロック。
     */
    private static Map<String, Object> findDockerEcosystemEntry() {
        // 設定ファイル本文を読む（読めなければ readRequiredFile が落とす）
        String yamlText = readRequiredFile(DEPENDABOT_PATH);
        // 任意の Java 型を組み立てさせない安全な設定で YAML を読む（共通規約 §9）
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        // 文字列を解析して、トップレベルのマップとして受け取る
        Object root = yaml.load(yamlText);
        // トップレベルがマップでなければ設定として読めないので落とす
        if (!(root instanceof Map<?, ?> rootMap)) {
            fail("%s のトップレベルがマップとして読めない".formatted(DEPENDABOT_PATH));
        }
        // updates（エコシステムごとの設定の並び）を取り出す
        Object rawUpdates = ((Map<?, ?>) root).get("updates");
        // 並びとして読めなければ設定の形が違うので落とす
        if (!(rawUpdates instanceof List<?> updates)) {
            fail("%s の updates が一覧として読めない".formatted(DEPENDABOT_PATH));
        }
        // 1 件ずつ見て、docker エコシステムかつルートディレクトリのものを探す
        for (Object update : (List<?>) rawUpdates) {
            // マップ以外は設定ブロックではないので読み飛ばす
            if (!(update instanceof Map<?, ?> updateMap)) continue;
            // エコシステム名とディレクトリの両方が一致したものだけを採用する
            // (別エコシステム・別ディレクトリへ置いた ignore は docker の更新に効かないため、
            //  置き場所違いをここで弾く)
            if ("docker".equals(updateMap.get("package-ecosystem"))
                    && "/".equals(updateMap.get("directory"))) {
                // 型を揃えて返す
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) updateMap;
                return typed;
            }
        }
        // 見つからなければ、保留を置くべき場所ごと消えているので落とす
        fail("%s にルートディレクトリの docker エコシステム設定が無い".formatted(DEPENDABOT_PATH));
        // fail が例外を投げるためここへは到達しないが、コンパイルのために返り値を書く
        throw new AssertionError("到達しない");
    }

    /**
     * {@code pom.xml} の {@code <java.version>} を読み取る。
     *
     * @return 宣言されている Java の major。
     */
    private static int readPomJavaVersion() {
        // 外部実体参照（XXE）を無効にした安全な設定でパーサを作る（共通規約 §9）
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            // 安全な XML 処理を有効にする
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // DOCTYPE 宣言自体を禁止する（外部実体を持ち込む入口を塞ぐ）
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            // 解析結果の文書を組み立てる
            Document document = factory.newDocumentBuilder().parse(POM_PATH.toFile());
            // <java.version> 要素をすべて取り出す
            NodeList nodes = document.getElementsByTagName("java.version");
            // ちょうど 1 つでなければ、読み取り前提が崩れているので落とす
            if (nodes.getLength() != 1) {
                fail("%s の <java.version> が 1 つだけ見つかることを前提にしているが %d 個あった"
                        .formatted(POM_PATH, nodes.getLength()));
            }
            // 要素の文字列を取り出して前後の空白を落とす
            String text = ((Element) nodes.item(0)).getTextContent().trim();
            // major だけを数値として取り出す（"21" のような単純な形を前提にする）
            return parseMajor(text, POM_PATH, "<java.version>");
        } catch (Exception e) {
            // 読めないまま素通りさせると検出網が黙って死ぬので、例外は必ず失敗に変える（fail-closed）
            throw new AssertionError("%s から <java.version> を読み取れなかった".formatted(POM_PATH), e);
        }
    }

    /**
     * CI 定義から {@code java-version} を読み取る。
     *
     * @return CI がセットアップする JDK の major。
     */
    private static int readCiJavaVersion() {
        // 設定ファイル本文を読む（読めなければ readRequiredFile が落とす）
        String yamlText = readRequiredFile(CI_WORKFLOW_PATH);
        // java-version の値（引用符の有無を問わない）を探す正規表現を組み立てる
        Pattern pattern = Pattern.compile("java-version:\\s*['\"]?(\\d+)", Pattern.MULTILINE);
        // ちょうど 1 つ見つかることを確かめつつ major を取り出す
        return matchSingleInt(pattern, yamlText, CI_WORKFLOW_PATH, "java-version");
    }

    /**
     * 指定のパターンが本文中でちょうど 1 回だけ一致することを確かめ、その数値を返す。
     *
     * <p>「1 回だけ」を要求するのは、宣言が増えたのに片方しか見ていない状態を防ぐため。
     * 例えば Dockerfile にステージが増えて FROM が 2 つになったとき、黙って先頭だけを
     * 見続けると新しいステージのずれを見逃す。
     *
     * @param pattern 数値を 1 つ取り出す正規表現（第 1 グループが数値）。
     * @param text 走査する本文。
     * @param source 失敗メッセージに出すファイルのパス。
     * @param what 失敗メッセージに出す「何を探していたか」。
     * @return 一致した数値。
     */
    private static int matchSingleInt(Pattern pattern, String text, Path source, String what) {
        // 本文に対してパターンを当てる
        Matcher matcher = pattern.matcher(text);
        // 最初の一致が無ければ、宣言の形が変わって読めなくなっているので落とす
        if (!matcher.find()) {
            fail("%s から「%s」を読み取れなかった。宣言の書き方が変わっていないか確認する".formatted(source, what));
        }
        // 見つかった数値を控える
        String found = matcher.group(1);
        // 2 つ目の一致があれば、どれを見るべきか決められないので落とす（fail-closed）
        if (matcher.find()) {
            fail("%s の「%s」が複数見つかった。どれを正とするか決められないので検査を止める".formatted(source, what));
        }
        // 控えた数値を major として返す
        return parseMajor(found, source, what);
    }

    /**
     * 文字列を major の数値に変換する。数値として読めなければ fail-closed で落とす。
     *
     * @param text 変換する文字列。
     * @param source 失敗メッセージに出すファイルのパス。
     * @param what 失敗メッセージに出す「何を読んでいたか」。
     * @return 変換した数値。
     */
    private static int parseMajor(String text, Path source, String what) {
        try {
            // 数値として解釈する
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            // 数値でなければ前提が崩れているので落とす
            throw new AssertionError(
                    "%s の「%s」が数値として読めない: \"%s\"".formatted(source, what, text), e);
        }
    }

    /**
     * 検査に必要なファイルを読む。読めなければ fail-closed で落とす。
     *
     * @param path 読み取るファイルのパス。
     * @return ファイルの本文。
     */
    private static String readRequiredFile(Path path) {
        try {
            // UTF-8 として本文をすべて読み込む
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 読めないまま検査を飛ばすと、検出網があるのに何も見ていない状態になる
            throw new AssertionError("%s を読み取れなかった".formatted(path), e);
        }
    }
}
