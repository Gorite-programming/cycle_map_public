package com.gorite.cyclemap.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable

data class FavoriteSpot(
    val id: String,
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
) : Serializable

/** 地図上の好きな地点を、自分で名前をつけて登録したお気に入りの区分。 */
const val CUSTOM_FAVORITE_CATEGORY = "custom"

private const val MAX_FAVORITE_NAME_LENGTH = 30
const val DEFAULT_FAVORITE_NAME = "登録した地点"

/** 入力された名前を整える (前後の空白を除き、空なら既定の名前、長すぎれば切る)。 */
fun normalizeFavoriteName(input: String): String =
    input.trim().take(MAX_FAVORITE_NAME_LENGTH).trim().ifEmpty { DEFAULT_FAVORITE_NAME }

/** 地図上の任意の地点を、お気に入りの 1 件にする。同じ場所を登録し直すと名前だけが入れ替わる (id が場所で決まるため)。 */
fun customFavorite(name: String, latitude: Double, longitude: Double, now: Long = System.currentTimeMillis()): FavoriteSpot =
    FavoriteSpot(
        id = String.format(java.util.Locale.US, "custom_%.5f_%.5f", latitude, longitude),
        name = normalizeFavoriteName(name),
        category = CUSTOM_FAVORITE_CATEGORY,
        latitude = latitude,
        longitude = longitude,
        addedAt = now,
    )

class FavoritesManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _favorites = MutableStateFlow<List<FavoriteSpot>>(emptyList())
    val favorites: StateFlow<List<FavoriteSpot>> = _favorites.asStateFlow()

    init {
        loadFavorites()
    }

    private fun loadFavorites() {
        val jsonStr = prefs.getString(KEY_FAVORITES, null) ?: "[]"
        try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<FavoriteSpot>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    FavoriteSpot(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        category = obj.optString("category", "spot"),
                        latitude = obj.getDouble("lat"),
                        longitude = obj.getDouble("lon"),
                        address = if (obj.has("address") && !obj.isNull("address")) obj.getString("address") else null,
                        addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
                    ),
                )
            }
            _favorites.value = list.sortedByDescending { it.addedAt }
        } catch (e: Exception) {
            _favorites.value = emptyList()
        }
    }

    private fun persist(list: List<FavoriteSpot>) {
        try {
            val jsonArray = JSONArray()
            list.forEach { spot ->
                val obj = JSONObject().apply {
                    put("id", spot.id)
                    put("name", spot.name)
                    put("category", spot.category)
                    put("lat", spot.latitude)
                    put("lon", spot.longitude)
                    spot.address?.let { put("address", it) }
                    put("addedAt", spot.addedAt)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_FAVORITES, jsonArray.toString()).apply()
            _favorites.value = list.sortedByDescending { it.addedAt }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun isFavorite(id: String): Boolean {
        return _favorites.value.any { it.id == id }
    }

    fun isFavorite(lat: Double, lon: Double): Boolean {
        return _favorites.value.any {
            kotlin.math.abs(it.latitude - lat) < 0.0001 && kotlin.math.abs(it.longitude - lon) < 0.0001
        }
    }

    fun addFavorite(spot: FavoriteSpot) {
        val current = _favorites.value.toMutableList()
        current.removeAll { it.id == spot.id || (kotlin.math.abs(it.latitude - spot.latitude) < 0.0001 && kotlin.math.abs(it.longitude - spot.longitude) < 0.0001) }
        current.add(0, spot)
        persist(current)
        notify("「${spot.name}」を保存しました")
    }

    fun removeFavorite(id: String) {
        val current = _favorites.value.toMutableList()
        if (current.removeAll { it.id == id }) notify("保存を解除しました")
        persist(current)
    }

    private fun notify(message: String) {
        // UI 操作から呼ばれる。ワーカースレッドからでも落ちないよう、メインスレッドで出す。
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    fun removeFavorite(lat: Double, lon: Double) {
        val current = _favorites.value.toMutableList()
        if (current.removeAll { kotlin.math.abs(it.latitude - lat) < 0.0001 && kotlin.math.abs(it.longitude - lon) < 0.0001 }) notify("保存を解除しました")
        persist(current)
    }

    fun toggleFavorite(spot: FavoriteSpot): Boolean {
        return if (isFavorite(spot.id) || isFavorite(spot.latitude, spot.longitude)) {
            removeFavorite(spot.id)
            removeFavorite(spot.latitude, spot.longitude)
            false
        } else {
            addFavorite(spot)
            true
        }
    }

    companion object {
        private const val PREFS_NAME = "cyclemap_favorites"
        private const val KEY_FAVORITES = "favorites_list"

        @Volatile
        private var instance: FavoritesManager? = null

        fun getInstance(context: Context): FavoritesManager {
            return instance ?: synchronized(this) {
                instance ?: FavoritesManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
