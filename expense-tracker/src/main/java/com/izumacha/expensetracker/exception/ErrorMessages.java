// 例外パッケージ
package com.izumacha.expensetracker.exception;

// カテゴリ名の最大文字数定数（Category.NAME_MAX_LENGTH）を文言へ埋め込むために参照する
import com.izumacha.expensetracker.domain.Category;

// 外部へ返すエラーメッセージを一元管理する定数クラス（§6 一元管理 / §9 内部情報を漏らさない）
public final class ErrorMessages {

    // インスタンス化を防ぐための private コンストラクタ（定数だけを提供するため）
    private ErrorMessages() {
    }

    // 指定された支出が存在しないときの安全な文言（内部 ID は含めない）
    public static final String EXPENSE_NOT_FOUND = "指定された支出が見つかりません";

    // 指定されたカテゴリが存在しないときの安全な文言（内部 ID は含めない）
    public static final String CATEGORY_NOT_FOUND = "指定されたカテゴリが見つかりません";

    // 同名カテゴリが既に存在するときの安全な文言（入力値は含めない）
    public static final String CATEGORY_NAME_DUPLICATE = "指定された名前のカテゴリは既に存在します";

    // カテゴリ名が保存可能な最大文字数を超えたときの安全な文言（入力値は含めない。
    // 上限値は Category.NAME_MAX_LENGTH を参照して一元管理し、裸の数値を書かない）
    public static final String CATEGORY_NAME_TOO_LONG =
            "カテゴリ名は" + Category.NAME_MAX_LENGTH + "文字以内で指定してください";

    // 支出から参照中のカテゴリを削除しようとしたときの安全な文言（内部 ID は含めない）
    public static final String CATEGORY_IN_USE = "このカテゴリは支出で使用されているため削除できません";

    // 月の形式が不正なときの安全な文言（生の入力値は含めない）
    public static final String INVALID_MONTH_FORMAT = "月の形式が正しくありません（YYYY-MM 形式で指定してください）";

    // 支出日の年が受け付け範囲外のときの安全な文言（生の入力値は含めない）
    public static final String INVALID_SPENT_ON = "支出日が正しくありません";

    // 必須パラメータが不足しているときの文言の接頭辞（後ろにパラメータ名を付ける）
    public static final String MISSING_PARAMETER = "必須パラメータが不足しています: ";

    // パラメータの型が不正なときの文言の接頭辞（後ろにパラメータ名を付ける）
    public static final String INVALID_PARAMETER = "パラメータの形式が正しくありません: ";

    // リクエスト自体が不正（不正 JSON・未対応メソッド・未対応メディアタイプ等）なときの汎用的な安全文言
    // ステータスを問わず誤誘導しないよう「形式」に限定しない中立な表現にする
    public static final String BAD_REQUEST = "リクエストが正しくありません";

    // 未定義パス（存在しない URL）へのリクエスト時の安全な文言（内部のルーティング情報は含めない）
    public static final String PATH_NOT_FOUND = "指定されたパスは存在しません";

    // 入力検証で個別のフィールドエラーが取得できなかった場合の汎用文言（§6 一元管理）
    public static final String VALIDATION_ERROR = "入力値が正しくありません";

    // 想定外のサーバ内部エラー時に返す汎用文言（内部詳細・スタックトレースは含めない）
    public static final String INTERNAL_ERROR = "サーバー内部でエラーが発生しました";

    // レート制限を超過したときの安全な文言（しきい値の詳細は含めない）
    public static final String TOO_MANY_REQUESTS = "リクエストが多すぎます。しばらく待って再試行してください";

    // リクエスト本文が上限サイズを超えたときの安全な文言（しきい値の詳細は含めない）
    public static final String PAYLOAD_TOO_LARGE = "リクエスト本文が大きすぎます";
}
