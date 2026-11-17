package com.gorite.cyclemap.ui.map

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavPanelFormatTest {
    @Test
    fun remainingUsesMetersBelowOneKilometer() {
        assertEquals("850" to "m", splitRemaining(850.0))
        assertEquals("0" to "m", splitRemaining(-5.0))
    }

    @Test
    fun remainingKeepsOneDecimalBelowTenKilometers() {
        assertEquals("9.8" to "km", splitRemaining(9_840.0))
    }

    @Test
    fun remainingIsIntegerFromTenKilometersSoItFitsOnOneLine() {
        assertEquals("102" to "km", splitRemaining(102_057.0))
        assertEquals("10" to "km", splitRemaining(10_000.0))
    }

    @Test
    fun gradeShowsZeroWithoutSign() {
        assertEquals("0", formatGradePct(-0.2))
        assertEquals("0", formatGradePct(0.4))
    }

    @Test
    fun gradeKeepsSignForNonZeroValues() {
        assertEquals("+4", formatGradePct(3.6))
        assertEquals("-3", formatGradePct(-2.7))
    }

    @Test
    fun gradeUnknownIsDashes() {
        assertEquals("--", formatGradePct(null))
        assertEquals("--", formatGradePct(Double.NaN))
    }

    @Test
    fun navPanelExpandedDefaultsTrueAndPersists() {
        val prefs = InMemoryPrefs()
        assertTrue(readNavPanelExpanded(prefs))
        saveNavPanelExpanded(prefs, false)
        assertFalse(readNavPanelExpanded(prefs))
        saveNavPanelExpanded(prefs, true)
        assertTrue(readNavPanelExpanded(prefs))
    }

    private class InMemoryPrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                for ((k, v) in pending) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }
}
