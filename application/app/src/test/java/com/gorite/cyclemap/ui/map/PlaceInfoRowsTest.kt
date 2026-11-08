package com.gorite.cyclemap.ui.map

import com.gorite.cyclemap.contract.PlaceDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceInfoRowsTest {
    private fun detail(
        hours: String? = null, phone: String? = null, website: String? = null, wheelchair: String? = null,
    ) = PlaceDetail(
        id = "1", name = "店", category = "cafe", address = "", distanceMeters = null, lat = 34.0, lon = 132.0,
        openingHoursText = hours, phone = phone, website = website, wheelchair = wheelchair,
    )

    @Test
    fun allFieldsInOrder() {
        val rows = placeInfoRows(detail("24/7", "082-247-8633", "https://www.lawson.co.jp/", "yes"))
        assertEquals(listOf(PlaceInfoKind.HOURS, PlaceInfoKind.PHONE, PlaceInfoKind.WEBSITE, PlaceInfoKind.WHEELCHAIR), rows.map { it.kind })
        assertEquals("24時間営業", rows[0].text)
        assertEquals("082-247-8633", rows[1].text)
        assertEquals("tel:0822478633", rows[1].uri)
        assertEquals("lawson.co.jp", rows[2].text)
        assertEquals("https://www.lawson.co.jp/", rows[2].uri)
        assertEquals("車椅子で利用可", rows[3].text)
    }

    @Test
    fun emptyFieldsAreOmitted() {
        assertTrue(placeInfoRows(detail()).isEmpty())
        assertTrue(placeInfoRows(detail(hours = " ", phone = "", website = "ftp://x", wheelchair = "unknown")).isEmpty())
    }

    @Test
    fun multiplePhonesUseFirstForDial() {
        val row = placeInfoRows(detail(phone = "082-111-2222; +81 82 333 4444")).single()
        assertEquals("082-111-2222 / +81 82 333 4444", row.text)
        assertEquals("tel:0821112222", row.uri)
        assertEquals("tel:+81823334444", telUri("+81 82 333 4444"))
        assertNull(telUri("--"))
    }

    @Test
    fun humanizesOsmOpeningHours() {
        assertEquals(
            "月-金 11:30-14:00,17:00-24:00 / 土 17:00-24:00 / 祝日 休み",
            humanizeOpeningHours("Mo-Fr 11:30-14:00,17:00-24:00; Sa 17:00-24:00; PH off"),
        )
        assertEquals("09:00-21:00", humanizeOpeningHours("09:00-21:00"))
        assertEquals("日,祝日 休み", humanizeOpeningHours("Su,PH closed"))
    }

    @Test
    fun longWebsiteIsShortened() {
        val label = websiteLabel("http://as.chizumaru.com/famima/k/detail?accmd=0&bid=38040")
        assertEquals(40, label.length)
        assertTrue(label.endsWith("…"))
    }

    @Test
    fun wheelchairValues() {
        assertEquals("車椅子は一部利用可", wheelchairLabel("limited"))
        assertEquals("車椅子は利用不可", wheelchairLabel("no"))
        assertEquals("車椅子で利用可", wheelchairLabel("designated"))
        assertNull(wheelchairLabel(null))
    }
}
