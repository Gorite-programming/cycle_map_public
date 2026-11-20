package com.gorite.cyclemap.ui.layout

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The measured content excludes the status inset and the top margin. */
internal fun homeControlsTop(topInset: Dp, overlayHeight: Dp): Dp =
    topInset + 8.dp + overlayHeight + 12.dp

internal fun landscapeHomeMaxHeight(height: Dp, topInset: Dp, overlayHeight: Dp): Dp =
    (height - homeControlsTop(topInset, overlayHeight)).coerceAtLeast(0.dp)
