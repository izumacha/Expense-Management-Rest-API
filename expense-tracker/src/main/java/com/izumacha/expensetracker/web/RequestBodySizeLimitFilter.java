// Web 横断のユーティリティ・フィルタを置くパッケージ
package com.izumacha.expensetracker.web;

// JSON 応答を書き出すための Jackson の中心クラス
import com.fasterxml.jackson.databind.ObjectMapper;
// 外部向けエラーメッセージ定数を参照する
import com.izumacha.expensetracker.exception.ErrorMessages;
// サーブレットの入力ストリームの読み取り完了通知を受け取るリスナー型
import jakarta.servlet.ReadListener;
// サーブレットのフィルタ連鎖を表す型
import jakarta.servlet.FilterChain;
// サーブレット例外型
import jakarta.servlet.ServletException;
// サーブレットの入力ストリーム基底クラス
import jakarta.servlet.ServletInputStream;
// HTTP リクエストを表す型
import jakarta.servlet.http.HttpServletRequest;
// HTTP リクエストをラップ（差し替え）するための基底クラス
import jakarta.servlet.http.HttpServletRequestWrapper;
// HTTP レスポンスを表す型
import jakarta.servlet.http.HttpServletResponse;
// 入出力例外型
import java.io.IOException;
// プロパティ値を注入するアノテーション
import org.springframework.beans.factory.annotation.Value;
// Bean の優先順位（フィルタの適用順）を指定するアノテーション
import org.springframework.core.annotation.Order;
// 「最優先」を表す定数 Ordered.HIGHEST_PRECEDENCE を参照する
import org.springframework.core.Ordered;
// HTTP ステータスを表す列挙
import org.springframework.http.HttpStatus;
// Spring に管理させるためのコンポーネント宣言
import org.springframework.stereotype.Component;
// 1 リクエストにつき一度だけ実行されることを保証するフィルタ基底
import org.springframework.web.filter.OncePerRequestFilter;

// リクエスト本文（body）のサイズに上限を設け、巨大なボディによるリソース枯渇を防ぐフィルタ
// （§9 公開エンドポイントを保護する：リクエストサイズの上限）。
//
// 【なぜ必要か】
// server.tomcat.max-http-form-post-size（application.yml）は Tomcat の FORM/multipart パース
// （request.getParameter() 経由）にのみ適用され、本 API の @RequestBody（Jackson が
// request.getInputStream() を直接読む生の JSON ボディ）の読み取りには一切効かない。
// そのため対策なしでは Content-Length・実サイズともに無制限の巨大な JSON ボディを受け付けてしまい、
// パース時のメモリ確保がリクエストごとに際限なく増える DoS の起点になる。
//
// 【二段構えで防ぐ理由】
// (1) 宣言された Content-Length が上限超過なら、ボディを読む前に即座に 413 で拒否する（安価で確実）。
// (2) Content-Length を送らない、または偽って小さく申告するクライアント（chunked 転送等）に備え、
//     実際に読み取れるバイト数そのものを上限で打ち切るストリームへ差し替える（フェイルセーフ、§9）。
//     上限超過分は EOF 相当として切り捨てるため、下位の JSON パーサは通常の「不正な JSON」として
//     扱い、既存の HttpMessageNotReadableException 経由の 400 応答にフォールバックする
//     （新たな例外伝播経路を持ち込まず、既存の {status, message} 契約と整合させる）。
@Component
// 認証・レート制限より手前で、ボディを読み取る前にサイズを弾く。RateLimitFilter と同じ理由
// （Spring Boot は Security のフィルタチェーンを既定 order = -100 で登録するため、確実に先に
// 実行させるには指定可能な最小値 Ordered.HIGHEST_PRECEDENCE を使う必要がある）。
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    // 許可するリクエスト本文の最大バイト数（application.yml の app.request.max-body-size-bytes）
    private final long maxBodySizeBytes;
    // JSON 直列化に使う ObjectMapper
    private final ObjectMapper objectMapper;

    // 設定値と ObjectMapper をコンストラクタで受け取る
    public RequestBodySizeLimitFilter(
            // 本文サイズの上限（バイト）
            @Value("${app.request.max-body-size-bytes}") long maxBodySizeBytes,
            // JSON 直列化に使う ObjectMapper
            ObjectMapper objectMapper) {
        // 上限値をフィールドに保持する
        this.maxBodySizeBytes = maxBodySizeBytes;
        // ObjectMapper をフィールドに保持する
        this.objectMapper = objectMapper;
    }

    // 各リクエストで本文サイズを判定する本体
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // クライアントが宣言した本文サイズを取得する（不明なら -1）
        long declaredLength = request.getContentLengthLong();
        // 宣言された時点で上限超過が分かる場合は、ボディを読み込む前に即座に拒否する（安価な早期リジェクト）
        if (declaredLength > maxBodySizeBytes) {
            // 上限超過の安全な文言で 413 応答を書き出す
            ApiErrorWriter.write(response, objectMapper, HttpStatus.PAYLOAD_TOO_LARGE, ErrorMessages.PAYLOAD_TOO_LARGE);
            // 後続のフィルタ・コントローラへは進ませない
            return;
        }
        // Content-Length が無い／実サイズと食い違う場合に備え、実際に読み取れるバイト数自体を
        // 上限で打ち切るリクエストへ差し替えたうえで後続へ進める（§9 fail-safe）
        filterChain.doFilter(new SizeLimitedRequest(request, maxBodySizeBytes), response);
    }

    // getInputStream() が返すストリームを上限付きに差し替える HttpServletRequest ラッパー
    private static final class SizeLimitedRequest extends HttpServletRequestWrapper {

        // 許可する最大バイト数
        private final long maxBodySizeBytes;

        // 元のリクエストと上限バイト数を受け取るコンストラクタ
        private SizeLimitedRequest(HttpServletRequest request, long maxBodySizeBytes) {
            // 親クラスに元のリクエストを渡す（委譲先として保持される）
            super(request);
            // 上限バイト数をフィールドに保持する
            this.maxBodySizeBytes = maxBodySizeBytes;
        }

        // 元のストリームを上限付きストリームでラップして返す
        @Override
        public ServletInputStream getInputStream() throws IOException {
            // 元のリクエストの入力ストリームを取得し、上限付きストリームへ包んで返す
            return new SizeLimitedServletInputStream(super.getInputStream(), maxBodySizeBytes);
        }
    }

    // 読み取り済みバイト数が上限に達したら EOF（-1）として打ち切る ServletInputStream ラッパー。
    // 例外を投げて伝播経路を新設するのではなく EOF を返す設計により、下位の JSON パーサは
    // 「途中で終わった不正な JSON」として扱い、既存の 400 応答経路へ自然にフォールバックする。
    private static final class SizeLimitedServletInputStream extends ServletInputStream {

        // 委譲先の元ストリーム
        private final ServletInputStream delegate;
        // 許可する最大バイト数
        private final long maxBodySizeBytes;
        // ここまでに読み取ったバイト数
        private long bytesRead;

        // 元ストリームと上限バイト数を受け取るコンストラクタ
        private SizeLimitedServletInputStream(ServletInputStream delegate, long maxBodySizeBytes) {
            // 委譲先ストリームを保持する
            this.delegate = delegate;
            // 上限バイト数を保持する
            this.maxBodySizeBytes = maxBodySizeBytes;
        }

        // 1バイト読み取る（上限到達後は EOF を返す）
        @Override
        public int read() throws IOException {
            // 既に上限へ達していれば、これ以上読み取らせず EOF として扱う
            if (bytesRead >= maxBodySizeBytes) {
                // ストリーム終端を表す -1 を返す
                return -1;
            }
            // 元ストリームから1バイト読み取る
            int b = delegate.read();
            // 実際にバイトを読み取れた場合だけカウントを増やす（EOF の -1 はカウントしない）
            if (b != -1) {
                // 読み取り済みバイト数を1増やす
                bytesRead++;
            }
            // 読み取った値（または EOF）を返す
            return b;
        }

        // 複数バイトをまとめて読み取る（上限を超える範囲は読み取らせない）
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            // 既に上限へ達していれば EOF として扱う
            if (bytesRead >= maxBodySizeBytes) {
                // ストリーム終端を表す -1 を返す
                return -1;
            }
            // 上限に収まる範囲だけ読み取るよう、要求サイズを残り許容量にクランプする
            int allowed = (int) Math.min(len, maxBodySizeBytes - bytesRead);
            // 元ストリームからクランプ後のサイズで読み取る
            int n = delegate.read(b, off, allowed);
            // 実際に読み取れたバイト数があればカウントへ加算する
            if (n > 0) {
                // 読み取り済みバイト数へ加算する
                bytesRead += n;
            }
            // 読み取ったバイト数（または EOF の -1）を返す
            return n;
        }

        // ストリームが読み終わっているかを返す（元ストリームの終端 or 上限到達のどちらかで真になる）
        @Override
        public boolean isFinished() {
            // 上限に達しているか、元ストリームが終端に達していれば true
            return bytesRead >= maxBodySizeBytes || delegate.isFinished();
        }

        // ノンブロッキング読み取りが可能かを元ストリームへそのまま委譲する
        @Override
        public boolean isReady() {
            // 元ストリームの準備状態をそのまま返す
            return delegate.isReady();
        }

        // ノンブロッキング読み取り用のリスナー登録を元ストリームへそのまま委譲する
        @Override
        public void setReadListener(ReadListener readListener) {
            // 元ストリームへリスナー登録を委譲する
            delegate.setReadListener(readListener);
        }

        // ストリームを閉じる処理を元ストリームへそのまま委譲する
        @Override
        public void close() throws IOException {
            // 元ストリームを閉じる
            delegate.close();
        }
    }
}
