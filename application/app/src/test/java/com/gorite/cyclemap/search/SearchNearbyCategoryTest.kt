package com.gorite.cyclemap.search

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import java.io.File

/** Android SQLiteを使う回帰テスト。既存のRealPlaceDetailProviderTestと同じ実行条件。 */
@Ignore("Requires Robolectric or instrumented test on Android runtime")
class SearchNearbyCategoryTest {
    @Test
    fun categoryIsFilteredBeforeNearestCandidateLimit() {
        val file = File.createTempFile("nearby_categories", ".db")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE places (name TEXT, category TEXT, lat REAL, lon REAL)")
                // 旧実装の候補上限60×3件を超える他カテゴリで埋める。
                repeat(200) { index ->
                    db.execSQL("INSERT INTO places VALUES (?, ?, ?, ?)",
                        arrayOf("店$index", "shop:convenience", 0.0, index * 0.000001))
                }
                db.execSQL("INSERT INTO places VALUES ('トイレ', 'amenity:toilets', 0.0, ?)",
                    arrayOf(Math.toDegrees(500.0 / 6_371_000.0)))
                db.execSQL("INSERT INTO places VALUES ('自転車店', 'shop:bicycle', 0.0, ?)",
                    arrayOf(Math.toDegrees(550.0 / 6_371_000.0)))
            }
            for ((prefix, name) in listOf("amenity:toilets" to "トイレ", "shop:bicycle" to "自転車店")) {
                val results = searchNearbyPlaces(file, 0.0, 0.0, 3000.0, limit = 60,
                    categoryPrefixes = listOf(prefix))
                assertEquals(listOf(name), results.map { it.name })
            }
            assertEquals(60, searchNearbyPlaces(file, 0.0, 0.0, 3000.0, limit = 60).size)
            assertEquals(emptyList<Any>(), searchNearbyPlaces(file, 0.0, 0.0, 3000.0,
                categoryPrefixes = emptyList()))
        } finally {
            file.delete()
        }
    }
}
