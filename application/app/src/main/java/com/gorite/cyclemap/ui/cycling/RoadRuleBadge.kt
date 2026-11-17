package com.gorite.cyclemap.ui.cycling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.routing.RoadRuleKind
import com.gorite.cyclemap.ui.theme.CycleMapTheme

/**
 * [RoadRuleKind] に対応する表示文言を返す。
 * 断定を避け、地図データの登録や進行向きに基づく事実として伝える。
 */
fun roadRuleText(kind: RoadRuleKind): String = when (kind) {
    RoadRuleKind.MOTOR_ONLY -> "自動車専用道路 · 自転車は通れません"
    RoadRuleKind.WRONG_WAY -> "一方通行の逆向きです"
    RoadRuleKind.BICYCLE_FORBIDDEN -> "自転車通行不可の登録があります"
    RoadRuleKind.DISMOUNT -> "ここは押して歩く区間です"
    RoadRuleKind.PEDESTRIAN_SHARED -> "歩行者と共有の道 · 歩行者優先で"
    RoadRuleKind.BIKE_FACILITY -> "自転車の通行帯の登録があります"
    RoadRuleKind.CONTRAFLOW_ALLOWED -> "一方通行 · 自転車は逆向きも通れます"
}

/**
 * 今走っている道の通行ルールを知らせる小さなバッジ。
 * 警告 ([RoadRuleKind.isWarning]) は目立つ配色、情報は控えめな面の色で表示する。
 */
@Composable
fun RoadRuleBadge(
    kind: RoadRuleKind,
    modifier: Modifier = Modifier,
) {
    val palette = CycleMapTheme.palette
    val containerColor = if (kind.isWarning) {
        palette.danger
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (kind.isWarning) {
        palette.onDanger
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val dotColor = if (kind.isWarning) {
        palette.onDanger
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val border = if (kind.isWarning) {
        null
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Surface(
        modifier = modifier.defaultMinSize(minHeight = 36.dp),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = roadRuleText(kind),
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = if (kind.isWarning) FontWeight.Bold else FontWeight.Medium,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(name = "警告: 自動車専用道路", showBackground = true)
@Composable
private fun PreviewRoadRuleMotorOnly() {
    CycleMapTheme(darkTheme = false) {
        RoadRuleBadge(kind = RoadRuleKind.MOTOR_ONLY)
    }
}

@Preview(name = "警告: 一方通行の逆向き", showBackground = true)
@Composable
private fun PreviewRoadRuleWrongWay() {
    CycleMapTheme(darkTheme = false) {
        RoadRuleBadge(kind = RoadRuleKind.WRONG_WAY)
    }
}

@Preview(name = "警告: 自転車通行不可", showBackground = true)
@Composable
private fun PreviewRoadRuleBicycleForbidden() {
    CycleMapTheme(darkTheme = false) {
        RoadRuleBadge(kind = RoadRuleKind.BICYCLE_FORBIDDEN)
    }
}

@Preview(name = "警告: 押し歩き区間", showBackground = true)
@Composable
private fun PreviewRoadRuleDismount() {
    CycleMapTheme(darkTheme = false) {
        RoadRuleBadge(kind = RoadRuleKind.DISMOUNT)
    }
}

@Preview(name = "情報: 歩行者共有", showBackground = true)
@Composable
private fun PreviewRoadRulePedestrianShared() {
    CycleMapTheme(darkTheme = false) {
        RoadRuleBadge(kind = RoadRuleKind.PEDESTRIAN_SHARED)
    }
}

@Preview(name = "情報: 自転車の通行帯", showBackground = true)
@Composable
private fun PreviewRoadRuleBikeFacility() {
    CycleMapTheme(darkTheme = false) {
        RoadRuleBadge(kind = RoadRuleKind.BIKE_FACILITY)
    }
}

@Preview(name = "情報: 自転車は逆向きも通行可", showBackground = true)
@Composable
private fun PreviewRoadRuleContraflowAllowed() {
    CycleMapTheme(darkTheme = false) {
        RoadRuleBadge(kind = RoadRuleKind.CONTRAFLOW_ALLOWED)
    }
}

@Preview(name = "全7種 (墨と朱)", showBackground = true, backgroundColor = 0xFF1C1A17)
@Composable
private fun PreviewRoadRuleAllDark() {
    CycleMapTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoadRuleKind.entries.forEach { kind ->
                RoadRuleBadge(kind = kind)
            }
        }
    }
}
