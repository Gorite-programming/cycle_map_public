package com.gorite.cyclemap.ui.hazard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.gorite.cyclemap.hazard.HazardFile
import com.gorite.cyclemap.hazard.HazardIndex
import com.gorite.cyclemap.hazard.RouteHazard
import com.gorite.cyclemap.hazard.findRouteHazards
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.utils.cycleMapDataDir
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal const val HAZARDS_FILE_NAME = "hazards.bin"

val LocalRouteHazards = compositionLocalOf<List<RouteHazard>> { emptyList() }

/**
 * プロセス内で `hazards.bin` の索引を 1 回だけ構築して使い回すキャッシュ。
 */
internal object HazardIndexCache {
    private val mutex = Mutex()
    private var cachedPath: String? = null
    private var cachedLength: Long = -1L
    private var cachedLastModified: Long = -1L
    private var cachedIndex: HazardIndex? = null

    suspend fun loadOrReuse(
        file: File,
        loader: (File) -> HazardIndex? = ::defaultLoadIndex,
    ): HazardIndex? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) {
                clearLocked()
                return@withContext null
            }
            val path = file.absolutePath
            val length = file.length()
            val lastModified = file.lastModified()
            val existing = cachedIndex
            if (existing != null &&
                cachedPath == path &&
                cachedLength == length &&
                cachedLastModified == lastModified
            ) {
                return@withContext existing
            }
            val loaded = try {
                loader(file)
            } catch (_: Exception) {
                null
            }
            if (loaded != null) {
                cachedPath = path
                cachedLength = length
                cachedLastModified = lastModified
                cachedIndex = loaded
            } else {
                clearLocked()
            }
            loaded
        }
    }

    internal fun clearForTest() {
        clearLocked()
    }

    private fun clearLocked() {
        cachedPath = null
        cachedLength = -1L
        cachedLastModified = -1L
        cachedIndex = null
    }

    private fun defaultLoadIndex(file: File): HazardIndex? =
        HazardFile.read(file)?.let(HazardIndex::build)
}

/**
 * UI スレッド以外で注意地点索引を取得し、経路と照合する純粋計算関数。
 */
internal suspend fun computeRouteHazards(
    route: List<RoutePoint>,
    indexProvider: suspend () -> HazardIndex?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): List<RouteHazard> {
    if (route.size < 2) return emptyList()
    val index = try {
        indexProvider()
    } catch (_: Exception) {
        null
    } ?: return emptyList()
    return withContext(dispatcher) {
        findRouteHazards(route, index)
    }
}

/**
 * 現在の表示用経路 [route] に対応する注意地点一覧を計算して返す。
 * 読み込み前・計算中・`hazards.bin` 不在時は空リストを返す。
 */
@Composable
fun rememberRouteHazards(route: List<RoutePoint>): List<RouteHazard> {
    val appContext = LocalContext.current.applicationContext
    val hazardsState = produceState<List<RouteHazard>>(initialValue = emptyList(), key1 = route) {
        value = emptyList()
        if (route.size < 2) return@produceState
        value = computeRouteHazards(
            route = route,
            indexProvider = {
                val file = File(cycleMapDataDir(appContext), HAZARDS_FILE_NAME)
                HazardIndexCache.loadOrReuse(file)
            },
        )
    }
    return hazardsState.value
}
