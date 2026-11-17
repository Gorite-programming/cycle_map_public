package com.gorite.cyclemap.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapNotificationBannersTest {
    @Test
    fun detectsMissingOfflineDataWarnings() {
        assertTrue(isOfflineDataWarning("広島県のルートデータが端末に入っていません。メニュー「オフラインデータ」で確認できます"))
        assertTrue(isOfflineDataWarning("ルートデータが端末に入っていません。メニュー「オフラインデータ」で確認できます"))
        assertFalse(isOfflineDataWarning("現在地を取得できていないため、山口市役所付近を出発地にして計算します"))
    }
}
