package com.gorite.cyclemap.tracking

/**
 * GPX journal と統計を結び付けるための純粋な退避・復元ポリシー。
 * recordingId は journal ファイル名の UUID を想定する。開始時刻の近さでは照合しない。
 * 現行の UI/サービスには未接続。呼び出し側が選択した journal の ID を渡す必要がある。
 */
object RideRecordingRestorePolicy {
    private const val RECORDING_ID = "recordingId"

    private fun validId(id: String?): Boolean =
        id != null && id.isNotBlank() && id == id.trim() && id.none { it.isISOControl() }

    fun matches(savedId: String?, recordingId: String?): Boolean =
        validId(savedId) && validId(recordingId) && savedId == recordingId

    /** RideSession の状態とは別に ID を保存する。ID が未確定なら退避しない。 */
    fun encode(session: RideSession, recordingId: String, nowMs: Long): String {
        require(validId(recordingId)) { "A recording ID is required" }
        return RideSessionStore.encode(session.toState() + (RECORDING_ID to recordingId), nowMs)
    }

    /**
     * ID のない旧形式・別の走行は復元しない。期限・破損の検証と一時停止化は既存の規則を使う。
     * 不一致でも保存データは消さない。他の未保存 journal に属する可能性がある。
     */
    fun decideRestore(text: String, recordingId: String?, nowMs: Long): RideSession? {
        val state = RideSessionStore.decode(text) ?: return null
        if (!matches(state[RECORDING_ID], recordingId)) return null
        return RideSessionStore.decideRestore(text, nowMs)
    }
}
