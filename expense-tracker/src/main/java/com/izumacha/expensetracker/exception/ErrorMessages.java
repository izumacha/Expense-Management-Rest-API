// 例外パッケージ
package com.izumacha.expensetracker.exception;

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

    // 月の形式が不正なときの安全な文言（生の入力値は含めない）
    public static final String INVALID_MONTH_FORMAT = "月の形式が正しくありません（YYYY-MM 形式で指定してください）";

    // 必須パラメータが不足しているときの文言の接頭辞（後ろにパラメータ名を付ける）
    public static final String MISSING_PARAMETER = "必須パラメータが不足しています: ";

    // パラメータの型が不正なときの文言の接頭辞（後ろにパラメータ名を付ける）
    public static final String INVALID_PARAMETER = "パラメータの形式が正しくありません: ";

    // リクエスト自体が不正（不正 JSON・未対応メソッド・未対応メディアタイプ等）なときの汎用的な安全文言
    // ステータスを問わず誤誘導しないよう「形式」に限定しない中立な表現にする
    public static final String BAD_REQUEST = "リクエストが正しくありません";

    // 入力検証で個別のフィールドエラーが取得できなかった場合の汎用文言（§6 一元管理）
    public static final String VALIDATION_ERROR = "入力値が正しくありません";

    // 想定外のサーバ内部エラー時に返す汎用文言（内部詳細・スタックトレースは含めない）
    public static final String INTERNAL_ERROR = "サーバー内部でエラーが発生しました";

    // API キーが無い・誤っているときの安全な文言（正解のキーや内部詳細は含めない）
    public static final String UNAUTHORIZED = "認証に失敗しました（有効な API キーが必要です）";

    // レート制限を超過したときの安全な文言（しきい値の詳細は含めない）
    public static final String TOO_MANY_REQUESTS = "リクエストが多すぎます。しばらく待って再試行してください";

    // 使用中（支出が紐づく）カテゴリを削除しようとしたときの安全な文言（内部 ID は含めない）
    public static final String CATEGORY_IN_USE = "このカテゴリには支出が紐づいているため削除できません";
}
