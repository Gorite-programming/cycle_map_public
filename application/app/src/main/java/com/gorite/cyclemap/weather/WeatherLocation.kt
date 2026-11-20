package com.gorite.cyclemap.weather

import com.gorite.cyclemap.data.AddressDisplayController

/** Only resolved place names may be used to choose a weather area. */
internal fun weatherCityHint(label: String?): String? = label?.trim()?.takeIf {
    it.isNotEmpty() && it != AddressDisplayController.ADDRESS_LOADING &&
        it != AddressDisplayController.ADDRESS_UNKNOWN && it != "名称なし"
}
