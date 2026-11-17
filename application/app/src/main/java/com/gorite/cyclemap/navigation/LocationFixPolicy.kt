package com.gorite.cyclemap.navigation

internal object LocationFixPolicy {
    // 再起動直後のキャッシュでも、2 分を超える古い位置は現在地として扱わない。
    const val MAX_AGE_NANOS = 120_000_000_000L

    fun isFresh(fixNanos: Long, nowNanos: Long): Boolean =
        fixNanos > 0L && fixNanos <= nowNanos && nowNanos - fixNanos <= MAX_AGE_NANOS

    fun accepts(fixNanos: Long, nowNanos: Long, previousNanos: Long?): Boolean =
        isFresh(fixNanos, nowNanos) && (previousNanos == null || fixNanos > previousNanos)
}
