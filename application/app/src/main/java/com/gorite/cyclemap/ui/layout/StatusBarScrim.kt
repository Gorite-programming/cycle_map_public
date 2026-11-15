package com.gorite.cyclemap.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ステータスバーの時計・アイコンと地図の地名ラベル・ピンが重ならないよう、上端に敷く縦グラデーションの影。
 * クリックやスクロールを妨げないよう、入力ハンドラは付与しない。
 */
@Composable
internal fun StatusBarScrim(
    topInset: Dp,
    modifier: Modifier = Modifier,
) {
    val scrimBase = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(topInset + 20.dp)
            .background(
                Brush.verticalGradient(
                    // ステータスバーの高さまではほぼ不透明に保ち、その下で薄く消す
                    0f to scrimBase.copy(alpha = 0.92f),
                    0.6f to scrimBase.copy(alpha = 0.85f),
                    1f to Color.Transparent,
                ),
            ),
    )
}
