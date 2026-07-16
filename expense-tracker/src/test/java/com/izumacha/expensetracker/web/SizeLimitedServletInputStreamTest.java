// Web 横断ユーティリティのテストパッケージ
package com.izumacha.expensetracker.web;

// 本文サイズ超過時に送出される専用例外を参照する
import com.izumacha.expensetracker.exception.RequestBodyTooLargeException;
// RequestBodySizeLimitFilter のパッケージプライベートな内部クラス群を参照する
import com.izumacha.expensetracker.web.RequestBodySizeLimitFilter.SizeLimitedRequest;
import com.izumacha.expensetracker.web.RequestBodySizeLimitFilter.SizeLimitedServletInputStream;

// サーブレットの入力ストリームの読み取り完了通知を受け取るリスナー型
import jakarta.servlet.ReadListener;
// サーブレットの入力ストリーム基底クラス
import jakarta.servlet.ServletInputStream;
// HTTP リクエストを表す型
import jakarta.servlet.http.HttpServletRequest;

// バイト配列を入力ストリームとして読むための型
import java.io.ByteArrayInputStream;
// 文字コードを定数で扱うためのクラス
import java.nio.charset.StandardCharsets;

// テストメソッドを宣言するアノテーション
import org.junit.jupiter.api.Test;

// 値を検証する assertThat を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThat;
// 例外送出を検証する assertThatThrownBy を取り込む（AssertJ）
import static org.assertj.core.api.Assertions.assertThatThrownBy;
// 戻り値を設定する when を取り込む（Mockito）
import static org.mockito.Mockito.when;
// モックを生成する mock を取り込む（Mockito）
import static org.mockito.Mockito.mock;

// RequestBodySizeLimitFilter が内部で使う SizeLimitedServletInputStream / SizeLimitedRequest の
// 境界値（ちょうど上限・上限+1バイト・0バイト要求）を、HTTP レイヤ（MockMvc）を介さず直接検証する。
// 【MockMvc ではなくここで直接テストする理由】MockHttpServletRequest は content から Content-Length を
// 自動導出するため、「Content-Length を偽って小さく申告しつつ実体は大きい」という stage 2（フェイル
// セーフ側）の状況を HTTP レイヤで安定的に再現するのが難しい。内部クラスを直接インスタンス化すれば、
// 元ストリーム（delegate）のバイト数と申告上限を独立に制御でき、境界条件を確実に検証できる（§11）。
class SizeLimitedServletInputStreamTest {

    // 上限バイト数ちょうどの本文は、上限超過ではなく正常な EOF として読み切れることを検証する
    // （境界の誤判定で「ちょうど上限サイズの正常な本文」まで誤って拒否しないことの確認）
    @Test
    void 上限ちょうどのサイズは正常に読み切れる() throws Exception {
        // 上限と同じ3バイトのデータを用意する
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        // 上限を3バイトに設定した上限付きストリームを生成する
        SizeLimitedServletInputStream stream = new SizeLimitedServletInputStream(fakeServletInputStream(data), 3);
        // 1バイトずつ読み取り、末尾が正しく EOF (-1) になることを検証する
        assertThat(stream.read()).isEqualTo('a');
        assertThat(stream.read()).isEqualTo('b');
        assertThat(stream.read()).isEqualTo('c');
        // 上限ちょうどで元データも尽きているため、例外ではなく EOF が返ることを検証する
        assertThat(stream.read()).isEqualTo(-1);
    }

    // 上限を1バイトでも超えるデータは、読み取ろうとした時点で RequestBodyTooLargeException を送出することを検証する
    @Test
    void 上限を超えるサイズは例外を送出する() {
        // 上限（3バイト）を1バイト超える4バイトのデータを用意する
        byte[] data = "abcd".getBytes(StandardCharsets.UTF_8);
        // 上限を3バイトに設定した上限付きストリームを生成する
        SizeLimitedServletInputStream stream = new SizeLimitedServletInputStream(fakeServletInputStream(data), 3);
        // 4バイト目まで読み進めようとすると例外が送出されることを検証する
        assertThatThrownBy(() -> {
            // 上限までの3バイトは正常に読める
            stream.read();
            stream.read();
            stream.read();
            // 4バイト目の読み取りで上限超過が判明する
            stream.read();
        }).isInstanceOf(RequestBodyTooLargeException.class);
    }

    // read(byte[], off, 0) は EOF/例外ではなく、InputStream の契約どおり 0 を返すことを検証する
    @Test
    void ゼロバイト要求は上限到達後でも0を返す() throws Exception {
        // 上限（1バイト）ちょうどのデータを用意する
        byte[] data = "a".getBytes(StandardCharsets.UTF_8);
        // 上限を1バイトに設定した上限付きストリームを生成する
        SizeLimitedServletInputStream stream = new SizeLimitedServletInputStream(fakeServletInputStream(data), 1);
        // 1バイト読み切り、上限到達状態にする
        assertThat(stream.read()).isEqualTo('a');
        // 上限到達後でも、0バイト要求は例外や-1ではなく0を返すことを検証する（InputStream 契約）
        assertThat(stream.read(new byte[0], 0, 0)).isEqualTo(0);
    }

    // read(byte[], off, len) のまとめ読みでも、上限超過は例外として送出されることを検証する
    @Test
    void まとめ読みでも上限超過は例外を送出する() {
        // 上限（3バイト）を超える5バイトのデータを用意する
        byte[] data = "abcde".getBytes(StandardCharsets.UTF_8);
        // 上限を3バイトに設定した上限付きストリームを生成する
        SizeLimitedServletInputStream stream = new SizeLimitedServletInputStream(fakeServletInputStream(data), 3);
        // バッファへまとめて読み取ろうとすると例外が送出されることを検証する
        assertThatThrownBy(() -> {
            byte[] buf = new byte[10];
            // 1回目の読み取りで上限（3バイト）まで消費される
            int n = stream.read(buf, 0, buf.length);
            assertThat(n).isEqualTo(3);
            // 2回目の読み取りで上限超過が判明し例外になる
            stream.read(buf, 0, buf.length);
        }).isInstanceOf(RequestBodyTooLargeException.class);
    }

    // SizeLimitedRequest.getInputStream() が複数回呼ばれても、同一インスタンス（同じカウンタ）を
    // 使い回すことを検証する（呼び出しごとにカウンタがリセットされ実質上限が緩む不具合の回帰防止）
    @Test
    void getInputStreamを複数回呼んでも同じストリームを再利用する() throws Exception {
        // 上限（3バイト）ちょうどのデータを持つ元リクエストのモックを用意する
        HttpServletRequest original = mock(HttpServletRequest.class);
        // getInputStream() が呼ばれたら固定のストリームを返すようモックする
        when(original.getInputStream()).thenReturn(fakeServletInputStream("abc".getBytes(StandardCharsets.UTF_8)));

        // 上限3バイトの SizeLimitedRequest を生成する
        SizeLimitedRequest wrapped = new SizeLimitedRequest(original, 3);
        // 1回目の getInputStream() 呼び出しでストリームを取得する
        ServletInputStream first = wrapped.getInputStream();
        // 2回目の getInputStream() 呼び出しでも、新規生成されず同一インスタンスが返ることを検証する
        ServletInputStream second = wrapped.getInputStream();
        assertThat(second).isSameAs(first);

        // 元リクエストの getInputStream() がキャッシュにより1回しか呼ばれていないことを検証する
        // （呼び出しごとに新規ストリームを作ると、カウンタがリセットされ上限が実質緩んでしまうため）
        org.mockito.Mockito.verify(original, org.mockito.Mockito.times(1)).getInputStream();
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
