package com.gorite.cyclemap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gorite.cyclemap.data.DEFAULT_FAVORITE_NAME

/** 名前の候補。押すと入力欄に入る。 */
private val FAVORITE_NAME_PRESETS = listOf("自宅", "職場", "学校")

/**
 * 地図上の好きな地点を、名前をつけてお気に入りに登録するダイアログ。
 * 名前を空のまま保存すると [DEFAULT_FAVORITE_NAME] になる。
 */
@Composable
internal fun SaveFavoriteDialog(
    title: String = "お気に入りに追加",
    initialName: String = "",
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ホームの近道や「保存済み」に、この名前で並びます。")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("名前") },
                    placeholder = { Text(DEFAULT_FAVORITE_NAME) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FAVORITE_NAME_PRESETS.forEach { preset ->
                        AssistChip(onClick = { name = preset }, label = { Text(preset) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
