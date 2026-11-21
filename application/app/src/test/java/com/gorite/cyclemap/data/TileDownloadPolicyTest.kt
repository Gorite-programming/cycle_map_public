package com.gorite.cyclemap.data

import com.gorite.cyclemap.utils.MapLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OpenStreetMap の公式タイルサーバーは一括保存 (プリフェッチ) を禁じているので、保存は国土地理院のタイルだけに限る。 */
class TileDownloadPolicyTest {
    @Test
    fun osmTilesCannotBeBulkDownloaded() {
        assertFalse(TileDownloader.isBulkDownloadAllowed(MapSourceType.OSM))
        // 種別が別でも、配信元の名前が OpenStreetMap なら保存しない (取り違えの保険)
        assertFalse(TileDownloader.isBulkDownloadAllowed(MapSourceType.GSI, "OpenStreetMap"))
    }

    @Test
    fun gsiTilesCanBeBulkDownloaded() {
        assertTrue(TileDownloader.isBulkDownloadAllowed(MapSourceType.GSI))
        assertTrue(TileDownloader.isBulkDownloadAllowed(MapSourceType.GSI, "GSI Standard"))
        assertTrue(TileDownloader.isBulkDownloadAllowed(MapSourceType.GSI, "GSI Relief"))
    }

    @Test
    fun osmLayerIsLabeledAsPlainOpenStreetMapNotCycling() {
        // 使っているのは tile.openstreetmap.org の標準スタイルで、自転車向けではない
        assertEquals("OpenStreetMap 標準", MapLayer.OSM.label)
    }
}
