package com.gorite.cyclemap.ui.mapview

/**
 * 地図データが端末に無いときの案内文。画面 (バナーと手順ダイアログ) と単体テストで共有する純粋なモデル。
 *
 * データは APK に含まれず、`Documents/CycleMap/` に県ごとのファイルを置く運用のため、
 * 初めて使う人が「何を・どこに・どう入れるか」で迷わないよう、必要なファイル名とフォルダを示す。
 */
data class OfflineDataGuide(
    /** バナーの見出し。 */
    val title: String,
    /** バナーの本文 (短く)。 */
    val summary: String,
    /** 手順ダイアログに出す、置くべきファイル名。県が対応外のときは空。 */
    val requiredFiles: List<String>,
    /** 手順ダイアログの手順 (番号は画面側で付ける)。 */
    val steps: List<String>,
    /** データを置くフォルダの絶対パス。 */
    val folderPath: String,
    /** 手順を出せる (= 対応県で、ファイル名を示せる) か。 */
    val hasInstallSteps: Boolean,
)

/** 現在対応している県。ここに無い県では、導入手順ではなく「対応外」を案内する。 */
val SUPPORTED_PREFECTURE_LABELS: List<String> = listOf("山口県", "広島県", "岡山県", "島根県", "鳥取県")

/**
 * @param prefectureName 現在地の県名 (判定できなければ null)
 * @param pmtilesFileName その県のタイルのファイル名 (例: `hiroshima.pmtiles`)。対応外の県は null
 * @param folderPath データを置くフォルダの絶対パス
 */
fun buildOfflineDataGuide(
    prefectureName: String?,
    pmtilesFileName: String?,
    folderPath: String,
): OfflineDataGuide {
    if (pmtilesFileName == null) {
        val label = prefectureName ?: "この地域"
        return OfflineDataGuide(
            title = "この地域のデータはまだありません",
            summary = "${label}は、まだ地図データに対応していません。" +
                "対応しているのは ${SUPPORTED_PREFECTURE_LABELS.joinToString("・")} です。",
            requiredFiles = emptyList(),
            steps = emptyList(),
            folderPath = folderPath,
            hasInstallSteps = false,
        )
    }
    val base = pmtilesFileName.removeSuffix(".pmtiles").replaceFirstChar { it.uppercase() }
    val label = prefectureName ?: "この地域"
    return OfflineDataGuide(
        title = "${label}の地図データが入っていません",
        summary = "オフライン地図・検索・経路には、データのファイルが必要です。入れ方は「導入方法」から確認できます。",
        requiredFiles = listOf(
            pmtilesFileName,
            "$base.graph",
            "$base.graph.idx",
            "$base.search.db",
        ),
        steps = listOf(
            "配布された「$label」のデータ (ZIP と地図タイル) を受け取り、ZIP を展開する。",
            "上のファイルを、下のフォルダへそのまま入れる (フォルダを作らず、ファイル名も変えない)。",
            "このアプリを一度完全に終了して、開き直す。",
        ),
        folderPath = folderPath,
        hasInstallSteps = true,
    )
}

/** フォルダの補足。端末によって見え方が違うので、断定せず案内する。 */
const val OFFLINE_DATA_FOLDER_NOTE: String =
    "Android 11 以降は、標準のファイルアプリからこのフォルダが見えないことがあります。" +
        "その場合は、パソコンと USB ケーブルでつないで転送してください。"
