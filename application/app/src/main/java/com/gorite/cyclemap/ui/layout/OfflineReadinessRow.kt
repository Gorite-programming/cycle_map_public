package com.gorite.cyclemap.ui.layout

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gorite.cyclemap.data.*
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.ui.theme.CycleMapTheme
import com.gorite.cyclemap.utils.cycleMapDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint

@Composable
internal fun OfflineReadinessRow(
    context: Context,
    route: List<RoutePoint>,
    waypoints: List<GeoPoint>,
    destination: GeoPoint?,
    onOpenOfflineData: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<OfflineReadiness?>(null) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(context, route, waypoints, destination, refresh) {
        result = null
        failed = false
        try {
            result = withContext(Dispatchers.IO) {
                val points = route.map { ReadinessPoint(it.latitude, it.longitude) } +
                    waypoints.map { ReadinessPoint(it.latitude, it.longitude) } +
                    listOfNotNull(destination?.let { ReadinessPoint(it.latitude, it.longitude) })
                diagnoseOfflineReadiness(points, readinessFileInventory(cycleMapDataDir(context)))
            }
        } catch (e: java.io.IOException) {
            failed = true
        } catch (e: SecurityException) {
            failed = true
        }
    }
    val diagnosis = result
    val warnings = diagnosis?.prefectures.orEmpty().flatMap { pref ->
        pref.items.filter { !it.optional && it.state != ReadinessState.AVAILABLE }
            .map { "${pref.name}: ${it.label}${it.state.label}" }
    } + if ((diagnosis?.unknownPointCount ?: 0) > 0) listOf("県を判定できない地点があります") else emptyList()
    val color = when {
        diagnosis?.isReady == true -> CycleMapTheme.palette.success
        warnings.isNotEmpty() || failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "オフライン準備 ${if (expanded) "▴" else "▾"}",
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            when {
                failed -> "データ一覧を確認できませんでした"
                diagnosis == null -> "端末のデータを確認中…"
                diagnosis.isReady -> "✓ この経路のデータは端末に揃っています"
                warnings.isNotEmpty() -> warnings.joinToString("\n")
                else -> "診断する地点がありません"
            },
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState()),
        )
        if (expanded && diagnosis != null) {
            Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                Text("ファイルの有無・容量のみ確認しています。任意データは無くても使えます。", style = MaterialTheme.typography.bodySmall)
                diagnosis.prefectures.forEach { pref ->
                    pref.items.forEach { item ->
                        Text("${pref.name}: ${item.label}${if (item.optional) "（任意）" else ""} — ${item.state.label}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                diagnosis.portals.forEach { item ->
                    Text("${item.label}（任意） — ${item.state.label}", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { refresh++ }) { Text("再確認") }
        }
        if ((warnings.isNotEmpty() || failed) && onOpenOfflineData != null) {
            TextButton(onClick = onOpenOfflineData) { Text("オフラインデータを管理") }
        }
    }
}
