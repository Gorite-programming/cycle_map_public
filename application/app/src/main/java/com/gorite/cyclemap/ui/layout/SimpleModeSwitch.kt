package com.gorite.cyclemap.ui.layout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gorite.cyclemap.R
import com.gorite.cyclemap.ui.state.DisplayMode
import com.gorite.cyclemap.ui.state.LocalDisplayModeController
import com.gorite.cyclemap.ui.theme.CycleMapTheme

/**
 * シンプルモードの左上に置く、小さな半透明のモード切替ボタン。押すと「通常に戻る」「サイコン」を選べる。
 * 地図のじゃまにならないよう小さく、手袋でも押せる 48dp にする。
 */
@Composable
fun SimpleModeSwitch(modifier: Modifier = Modifier) {
    val controller = LocalDisplayModeController.current ?: return
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            shape = CircleShape,
            color = CycleMapTheme.palette.mapControlSurface.copy(alpha = 0.85f),
            shadowElevation = 4.dp,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "表示モードの切り替え" },
        ) {
            Box(
                modifier = Modifier.size(48.dp).clickable { open = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_menu),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = CycleMapTheme.palette.mapControlIcon,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("通常に戻る") },
                onClick = { open = false; controller.set(DisplayMode.NORMAL) },
            )
            DropdownMenuItem(
                text = { Text("サイコン") },
                onClick = { open = false; controller.set(DisplayMode.CYCLE_COMPUTER) },
            )
        }
    }
}
