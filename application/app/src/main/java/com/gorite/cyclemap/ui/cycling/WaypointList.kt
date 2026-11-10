package com.gorite.cyclemap.ui.cycling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.osmdroid.util.GeoPoint

/** Moves an item by [delta], leaving the list unchanged when either index is out of range. */
internal fun <T> moveWaypoint(list: List<T>, index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in list.indices || target !in list.indices || delta == 0) return list
    return list.toMutableList().apply {
        add(target, removeAt(index))
    }
}

/** Removes the item at [index], or returns the original list for an invalid index. */
internal fun <T> removeWaypointAt(list: List<T>, index: Int): List<T> {
    if (index !in list.indices) return list
    return list.toMutableList().apply { removeAt(index) }
}

@Composable
internal fun WaypointList(
    waypoints: List<GeoPoint>,
    isEnabled: Boolean,
    onMove: (index: Int, delta: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        waypoints.forEachIndexed { index, point ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "経由地 ${index + 1}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "%.5f, %.5f".format(java.util.Locale.US, point.latitude, point.longitude),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = { onMove(index, -1) },
                        enabled = isEnabled && index > 0,
                    ) {
                        Text("↑", fontSize = 18.sp, color = if (isEnabled && index > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                    }
                    IconButton(
                        onClick = { onMove(index, 1) },
                        enabled = isEnabled && index < waypoints.lastIndex,
                    ) {
                        Text("↓", fontSize = 18.sp, color = if (isEnabled && index < waypoints.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                    }
                    IconButton(
                        onClick = { onRemove(index) },
                        enabled = isEnabled,
                    ) {
                        Text("×", fontSize = 21.sp, color = if (isEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                    }
                }
            }
        }
    }
}
