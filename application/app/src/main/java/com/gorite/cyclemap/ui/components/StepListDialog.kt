package com.gorite.cyclemap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.navigation.UpcomingStep
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.ui.map.toGuidanceTurnType

/** 今の経路の案内指示。出発前の確認 (経路プレビュー) から曲がり角の一覧を開くのに使う。 */
val LocalRouteInstructions = compositionLocalOf<List<RouteInstruction>> { emptyList() }

/** 曲がり角を、矢印・名前・道路名・距離で並べる (Google マップのステップ一覧に相当)。 */
@Composable
internal fun StepListDialog(title: String, steps: List<UpcomingStep>, emptyText: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text(title) },
        text = {
            if (steps.isEmpty()) {
                Text(emptyText)
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    steps.forEach { step ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(
                                    com.gorite.cyclemap.ui.map.turnTypeToDrawableRes(step.instruction.toGuidanceTurnType()),
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(step.label, fontWeight = FontWeight.Bold)
                                (step.instruction.outgoingRoadName
                                    ?: com.gorite.cyclemap.ui.map.roadClassLabel(step.instruction.outgoingRoadType))
                                    ?.let { Text(it, fontSize = 13.sp) }
                            }
                            val (num, unit) = com.gorite.cyclemap.ui.map.splitDistance(step.distanceMeters)
                            Text(num + unit, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
    )
}
