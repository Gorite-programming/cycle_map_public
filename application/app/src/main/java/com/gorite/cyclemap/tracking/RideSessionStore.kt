package com.gorite.cyclemap.tracking

import android.content.SharedPreferences

/**
 * [RideSession] の退避と復元。アプリが落ちても (または端末の都合で閉じられても) 走行中の値を失わない。
 * 保存は `cyclemap_prefs` の 1 つの文字列 ([KEY])。文字列化と復元の判断は純粋な関数にして、JVM でテストする。
 */
class RideSessionStore(private val prefs: SharedPreferences) {
    fun save(session: RideSession, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putString(KEY, encode(session.toState(), nowMs)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    /**
     * 保存された走行を戻す。古すぎる・壊れている・まだ走り出していないものは捨てて null を返す。
     * 戻した走行は一時停止にする (再開かリセットを画面で選ぶ)。
     */
    fun restore(nowMs: Long = System.currentTimeMillis()): RideSession? {
        val text = prefs.getString(KEY, null) ?: return null
        val session = decideRestore(text, nowMs)
        if (session == null) clear()
        return session
    }

    companion object {
        const val KEY = "ride_session_v1"
        /** これより古い走行は、別の日の走りとして捨てる。 */
        const val MAX_AGE_MS = 30 * 60_000L
        private const val SAVED_AT = "savedAtMs"

        /** `key=value` を 1 行ずつ並べる。値に改行は入らない (数・名前・コンマ区切りの数字だけ)。 */
        fun encode(state: Map<String, String>, nowMs: Long): String =
            (state + (SAVED_AT to nowMs.toString())).entries.joinToString("\n") { "${it.key}=${it.value}" }

        fun decode(text: String): Map<String, String>? {
            val map = LinkedHashMap<String, String>()
            for (line in text.split('\n')) {
                if (line.isEmpty()) continue
                val i = line.indexOf('=')
                if (i <= 0) return null
                map[line.substring(0, i)] = line.substring(i + 1)
            }
            return map
        }

        fun decideRestore(text: String, nowMs: Long): RideSession? {
            val map = decode(text) ?: return null
            val savedAt = map[SAVED_AT]?.toLongOrNull() ?: return null
            if (nowMs < savedAt || nowMs - savedAt > MAX_AGE_MS) return null
            val session = RideSession.fromState(map) ?: return null
            if (session.snapshot(savedAt).state == RideState.IDLE) return null
            session.pause()
            return session
        }
    }
}
