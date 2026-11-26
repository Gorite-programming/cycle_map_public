package com.gorite.cyclemap.ui.map

import com.gorite.cyclemap.ui.theme.CycleMapTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorite.cyclemap.R
import com.gorite.cyclemap.net.Link

data class CategoryChipData(
    val id: String,
    val label: String,
    val iconRes: Int
)

val DEFAULT_CATEGORY_CHIPS = listOf(
    CategoryChipData("restaurant", "レストラン", R.drawable.ic_lucide_utensils),
    CategoryChipData("convenience", "コンビニ", R.drawable.ic_lucide_store),
    CategoryChipData("rest_area", "道の駅", R.drawable.ic_lucide_map_pin),
    CategoryChipData("bicycle_parking", "駐輪場", R.drawable.ic_lucide_square_parking),
    CategoryChipData("toilet", "トイレ", R.drawable.ic_lucide_toilet),
    CategoryChipData("hospital", "病院", R.drawable.ic_lucide_hospital),
    CategoryChipData("onsen", "温泉・銭湯", R.drawable.ic_lucide_map_pin),
    CategoryChipData("hotel", "宿泊", R.drawable.ic_lucide_map_pin),
    CategoryChipData("sightseeing", "観光・景観", R.drawable.ic_lucide_mountain)
)

@Composable
fun NetworkStatusIndicator(link: Link) {
    val iconRes = when (link) {
        is Link.Offline -> R.drawable.ic_lucide_cloud_off
        is Link.OnlineMetered, is Link.OnlineUnmetered -> R.drawable.ic_lucide_cloud
    }
    val tint = when (link) {
        is Link.Offline -> MaterialTheme.colorScheme.onSurfaceVariant
        is Link.OnlineMetered -> CycleMapTheme.palette.warning
        is Link.OnlineUnmetered -> CycleMapTheme.palette.success
    }
    Icon(
        painter = painterResource(iconRes),
        contentDescription = when (link) {
            is Link.Offline -> "オフライン"
            is Link.OnlineMetered -> "従量制オンライン"
            is Link.OnlineUnmetered -> "オンライン"
        },
        tint = tint,
        modifier = Modifier.size(18.dp)
    )
}
