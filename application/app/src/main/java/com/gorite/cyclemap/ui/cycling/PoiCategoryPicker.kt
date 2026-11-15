package com.gorite.cyclemap.ui.cycling

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.ui.theme.LocalAnimationEnabled
import com.gorite.cyclemap.ui.theme.Motion

internal data class PoiCategoryGroup(
    val label: String,
    val categories: List<PoiCategory>,
)

/**
 * 設定画面で施設カテゴリをグループ見出し付きで並べるための対応表。
 * [CyclingData.kt] の [PoiCategory] の定義順・コメント区分に合わせている。
 */
internal val POI_CATEGORY_GROUPS: List<PoiCategoryGroup> = listOf(
    PoiCategoryGroup(
        label = "交通",
        categories = listOf(
            PoiCategory.STATION,
            PoiCategory.BUS_STOP,
            PoiCategory.TRAM,
            PoiCategory.PARKING,
            PoiCategory.FUEL,
            PoiCategory.EV,
        ),
    ),
    PoiCategoryGroup(
        label = "自転車",
        categories = listOf(
            PoiCategory.BICYCLE,
            PoiCategory.BIKE_SERVICE,
        ),
    ),
    PoiCategoryGroup(
        label = "食・買い物",
        categories = listOf(
            PoiCategory.CONVENIENCE,
            PoiCategory.SUPERMARKET,
            PoiCategory.FOOD,
            PoiCategory.BAKERY,
            PoiCategory.BAR,
        ),
    ),
    PoiCategoryGroup(
        label = "観光・休憩",
        categories = listOf(
            PoiCategory.TOURISM,
            PoiCategory.WORSHIP,
            PoiCategory.LANDMARK,
            PoiCategory.PARK,
            PoiCategory.BATH,
            PoiCategory.HOTEL,
            PoiCategory.CAMP,
            PoiCategory.INFO,
            PoiCategory.SPORTS,
        ),
    ),
    PoiCategoryGroup(
        label = "生活インフラ",
        categories = listOf(
            PoiCategory.TOILET,
            PoiCategory.WATER,
            PoiCategory.HOSPITAL,
            PoiCategory.CLINIC,
            PoiCategory.PHARMACY,
            PoiCategory.POST,
            PoiCategory.BANK,
            PoiCategory.SCHOOL,
            PoiCategory.PUBLIC,
        ),
    ),
)

/**
 * 施設カテゴリ (POI) を一括操作ボタンとグループ別の 4 列チップで表示するピッカー。
 */
@Composable
internal fun PoiCategoryPicker(
    selected: Set<PoiCategory>,
    onToggle: (PoiCategory) -> Unit,
    onSetSelection: (Set<PoiCategory>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onSetSelection(PoiCategory.defaults()) },
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("標準に戻す")
            }
            TextButton(
                onClick = { onSetSelection(PoiCategory.entries.toSet()) },
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("すべて選択")
            }
            TextButton(
                onClick = { onSetSelection(emptySet()) },
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("すべて解除")
            }
        }

        POI_CATEGORY_GROUPS.forEach { group ->
            Text(
                text = group.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            group.categories.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { cat ->
                        PoiSelectChip(
                            cat = cat,
                            selected = cat in selected,
                            onClick = { onToggle(cat) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PoiSelectChip(
    cat: PoiCategory,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animEnabled = LocalAnimationEnabled.current
    val bgColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = Motion.motionTween(Motion.DURATION_SHORT, enabled = animEnabled),
        label = "PoiChipBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = Motion.motionTween(Motion.DURATION_SHORT, enabled = animEnabled),
        label = "PoiChipContent",
    )
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painterResource(cat.iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            Text(
                cat.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                minLines = 2,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp,
                color = contentColor,
            )
        }
    }
}
