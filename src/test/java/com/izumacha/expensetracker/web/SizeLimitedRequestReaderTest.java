// Web 横断ユーティリティのテストパッケージ
package com.izumacha.expensetracker.web;

// RequestBodySizeLimitFilter のパッケージプライベートな内部クラスを参照する
import com.izumacha.expensetracker.web.RequestBodySizeLimitFilter.SizeLimitedRequest;

// サーブレットの入力ストリームの読み取り完了通知を受け取るリスナー型
import jakarta.servlet.ReadListener;
// サーブレットの入力ストリーム基底クラス
import jakarta.servlet.ServletInputStream;
// HTTP リクエストを表す型
import jakarta.servlet.http.HttpServletRequest;

// 文字列読み取り用のリーダー型
import java.io.BufferedReader;
// バイト配列を入力ストリームとして読むための型
import java.io.ByteArrayInputStream;
// 文字コードを定数で扱うためのクラス
import java.nio.charset.StandardCharsets;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 値を検証する assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// モックを生成する mock を取り込む（Mockito）
import static org.mockito.Mockito.mock;

// SizeLimitedRequest.getReader() の文字コード解決を検証するユニットテスト。
//
// 【何を守るテストか】getCharacterEncoding() は Content-Type ヘッダの charset トークンを
// 未検証のまま返すため、旧実装では "bogus!" のような不正名がそのまま Charset.forName() に渡り、
// IllegalCharsetNameException（未検査例外）でクライアント入力起因なのに 500 になっていた。
// 本テストは「不正・未対応の charset 指定でも例外にせず ISO-8859-1 へフォールバックして
// 読み取れる」フェイルセーフ挙動（§9 壊れたデータでクラッシュさせない）をピン留めする。
class SizeLimitedRequestReaderTest {

    // 指定した charset 名と本文を持つ元リクエストのモックを組み立てるヘルパー
    private static HttpServletRequest requestWithEncoding(String encoding, byte[] body) throws Exception {
        // 元リクエストのモックを生成する
        HttpServletRequest original = mock(HttpServletRequest.class);
        // getCharacterEncoding() が指定の charset 名を返すようにする
        when(original.getCharacterEncoding()).thenReturn(encoding);
        // getInputStream() が本文バイト列を返すようにする
        when(original.getInputStream()).thenReturn(fakeServletInputStream(body));
        // 組み立てたモックを返す
        return original;
    }

    // 不正な charset 名（IllegalCharsetNameException 相当）でも例外にならず読み取れることを検証する
    @Test
    void 不正なcharset名は例外にせずISO88591で読み取る() throws Exception {
        // 不正な charset 名 "bogus!"（'!' は charset 名に使えない文字）を指定した元リクエストを用意する
        HttpServletRequest original = requestWithEncoding("bogus!", "abc".getBytes(StandardCharsets.ISO_8859_1));
        // 上限 10 バイトの SizeLimitedRequest で包む
        SizeLimitedRequest wrapped = new SizeLimitedRequest(original, 10);
        // getReader() が例外を投げずにリーダーを返すことを検証する
        BufferedReader reader = wrapped.getReader();
        // フォールバック（ISO-8859-1）で本文が正しく読めることを検証する
        assertThat(reader.readLine()).isEqualTo("abc");
    }

    // 名前としては合法だが未対応の charset（UnsupportedCharsetException 相当）でもフォールバックすることを検証する
    @Test
    void 未対応のcharset名は例外にせずISO88591で読み取る() throws Exception {
        // 合法な名前だが実在しない charset を指定した元リクエストを用意する
        HttpServletRequest original =
                requestWithEncoding("x-definitely-not-a-charset", "abc".getBytes(StandardCharsets.ISO_8859_1));
        // 上限 10 バイトの SizeLimitedRequest で包む
        SizeLimitedRequest wrapped = new SizeLimitedRequest(original, 10);
        // getReader() が例外を投げずにリーダーを返し、本文が読めることを検証する
        assertThat(wrapped.getReader().readLine()).isEqualTo("abc");
    }

    // 正しい charset 指定（UTF-8）が従来どおりそのまま使われることを検証する（フォールバック導入の退行防止）
    @Test
    void 正しいcharset指定は指定どおりに読み取る() throws Exception {
        // UTF-8 でエンコードした日本語本文を持つ元リクエストを用意する
        HttpServletRequest original = requestWithEncoding("UTF-8", "経費".getBytes(StandardCharsets.UTF_8));
        // 上限 10 バイトの SizeLimitedRequest で包む
        SizeLimitedRequest wrapped = new SizeLimitedRequest(original, 10);
        // UTF-8 として正しくデコードされることを検証する（ISO-8859-1 に落ちていれば文字化けする）
        assertThat(wrapped.getReader().readLine()).isEqualTo("経費");
    }

    // charset 未指定（null）はサーブレット既定の ISO-8859-1 で読み取ることを検証する（既存挙動の維持）
    @Test
    void charset未指定はISO88591で読み取る() throws Exception {
        // charset 未指定（null）の元リクエストを用意する
        HttpServletRequest original = requestWithEncoding(null, "abc".getBytes(StandardCharsets.ISO_8859_1));
        // 上限 10 バイトの SizeLimitedRequest で包む
        SizeLimitedRequest wrapped = new SizeLimitedRequest(original, 10);
        // 既定の ISO-8859-1 で本文が読めることを検証する
        assertThat(wrapped.getReader().readLine()).isEqualTo("abc");
    }

    // バイト配列を Servlet の入力ストリームとして読めるようにする最小限のテスト用実装を組み立てる
    private static ServletInputStream fakeServletInputStream(byte[] data) {
        // バイト配列を読むだけの単純なストリームを土台にする
        ByteArrayInputStream backing = new ByteArrayInputStream(data);
        // ServletInputStream は抽象クラスのため、テストに必要な最小実装を無名クラスで用意する
        return new ServletInputStream() {
            // 1バイト読み取りを土台のストリームへ委譲する
            @Override
            public int read() {
                // 土台のストリームから1バイト読み取って返す
                return backing.read();
            }

            // 読み終わっているかは土台のストリームの残量で判定する
            @Override
            public boolean isFinished() {
                // 残りバイト数が0ならtrue
                return backing.available() == 0;
            }

            // 常に読み取り可能とみなす（テスト用の同期的な実装のため）
            @Override
            public boolean isReady() {
                // 常に true を返す
                return true;
            }

            // 非同期読み取りリスナーはテストでは使わないため何もしない
            @Override
            public void setReadListener(ReadListener readListener) {
                // 何もしない（テストでは非同期読み取りを検証しないため）
            }
        };
    }
}
