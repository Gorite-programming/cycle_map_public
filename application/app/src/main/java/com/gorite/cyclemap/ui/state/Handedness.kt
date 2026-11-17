package com.gorite.cyclemap.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment

/** 利き手。左手のときは、右側にある地図の操作ボタンの列を左側へ移す。 */
enum class Handedness(val label: String) {
    RIGHT("右手"),
    LEFT("左手"),
}

class HandednessController(
    val value: State<Handedness>,
    val set: (Handedness) -> Unit,
)

val LocalHandednessController = compositionLocalOf<HandednessController?> { null }

internal const val PREF_HANDEDNESS = "handedness"

/** 保存された値から復元する。壊れた値・未設定は右手。 */
fun handednessFromPref(saved: String?): Handedness =
    Handedness.entries.firstOrNull { it.name == saved } ?: Handedness.RIGHT

/** 左手モードか。コントローラが無い (プレビューなど) ときは右手として扱う。 */
@Composable
fun isLeftHanded(): Boolean = LocalHandednessController.current?.value?.value == Handedness.LEFT

/** 操作ボタンの列を置く側の、上・下の位置。 */
fun controlsTopAlignment(left: Boolean): Alignment = if (left) Alignment.TopStart else Alignment.TopEnd
fun controlsBottomAlignment(left: Boolean): Alignment = if (left) Alignment.BottomStart else Alignment.BottomEnd
