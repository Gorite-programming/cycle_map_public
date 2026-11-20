package com.gorite.cyclemap.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.utils.MapLayer

/** Reserved map-edge strip so panels and SDK info buttons cannot hide offline attribution. */
@Composable
internal fun MapAttribution(layer: MapLayer, modifier: Modifier = Modifier) {
    Text(
        text = mapAttributionText(layer),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        modifier = modifier.background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

internal fun mapAttributionText(layer: MapLayer): String = when (layer) {
    MapLayer.VECTOR_LIBERTY -> "© OpenStreetMap contributors · Protomaps · 国土地理院"
    MapLayer.GSI, MapLayer.TERRAIN -> "© OpenStreetMap contributors · 国土地理院"
    MapLayer.OSM -> "© OpenStreetMap contributors"
}
