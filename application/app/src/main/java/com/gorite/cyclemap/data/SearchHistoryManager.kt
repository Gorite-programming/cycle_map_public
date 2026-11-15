package com.gorite.cyclemap.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class SearchHistoryEntry(
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
)

/** Entries are stored newest first; selecting an existing place also refreshes its details. */
internal object SearchHistoryStore {
    private const val MAX_ENTRIES = 10

    private fun samePlace(a: SearchHistoryEntry, b: SearchHistoryEntry): Boolean =
        a.name == b.name &&
            abs(a.latitude - b.latitude) <= 0.0001 + 1e-12 &&
            abs(a.longitude - b.longitude) <= 0.0001 + 1e-12

    fun add(entries: List<SearchHistoryEntry>, entry: SearchHistoryEntry): List<SearchHistoryEntry> =
        (listOf(entry) + remove(entries, entry)).take(MAX_ENTRIES)

    fun remove(entries: List<SearchHistoryEntry>, entry: SearchHistoryEntry): List<SearchHistoryEntry> =
        entries.filterNot { samePlace(it, entry) }

    fun toJson(entries: List<SearchHistoryEntry>): String = JsonArray().apply {
        entries.take(MAX_ENTRIES).forEach { entry ->
            add(JsonObject().apply {
                addProperty("name", entry.name)
                addProperty("category", entry.category)
                addProperty("lat", entry.latitude)
                addProperty("lon", entry.longitude)
                entry.address?.let { addProperty("address", it) }
            })
        }
    }.toString()

    fun fromJson(json: String?): List<SearchHistoryEntry> {
        if (json == null) return emptyList()
        return runCatching {
            val entries = mutableListOf<SearchHistoryEntry>()
            JsonParser.parseString(json).asJsonArray.forEach { element ->
                // A damaged row must not discard the remaining usable history.
                runCatching {
                    val obj = element.asJsonObject
                    val entry = SearchHistoryEntry(
                        name = obj.get("name").asString,
                        category = obj.get("category").asString,
                        latitude = obj.get("lat").asDouble,
                        longitude = obj.get("lon").asDouble,
                        address = obj.get("address")?.takeUnless { it.isJsonNull }?.asString,
                    )
                    require(entry.latitude.isFinite() && entry.latitude in -90.0..90.0)
                    require(entry.longitude.isFinite() && entry.longitude in -180.0..180.0)
                    if (entries.size < MAX_ENTRIES && entries.none { samePlace(it, entry) }) {
                        entries.add(entry)
                    }
                }
            }
            entries.toList()
        }.getOrDefault(emptyList())
    }
}

class SearchHistoryManager private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val _history = MutableStateFlow(SearchHistoryStore.fromJson(prefs.getString(KEY_HISTORY, null)))
    val history: StateFlow<List<SearchHistoryEntry>> = _history.asStateFlow()

    @Synchronized
    fun add(entry: SearchHistoryEntry) = persist(SearchHistoryStore.add(_history.value, entry))

    @Synchronized
    fun remove(entry: SearchHistoryEntry) = persist(SearchHistoryStore.remove(_history.value, entry))

    @Synchronized
    fun clear() = persist(emptyList())

    private fun persist(entries: List<SearchHistoryEntry>) {
        prefs.edit().putString(KEY_HISTORY, SearchHistoryStore.toJson(entries)).apply()
        _history.value = entries
    }

    companion object {
        private const val KEY_HISTORY = "destinations"

        @Volatile
        private var instance: SearchHistoryManager? = null

        fun getInstance(context: Context): SearchHistoryManager = instance ?: synchronized(this) {
            instance ?: SearchHistoryManager(context.applicationContext).also { instance = it }
        }
    }
}
