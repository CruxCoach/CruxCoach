package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.domain.board.IntensityZones

@Composable
internal fun ZoneLegend(zones: IntensityZones, gradeScale: GradeScale) {
    val warmUpLabel = GradeDisplayHelper.formatDifficulty(zones.warmUpCeiling, gradeScale)
    val optimalLabel = GradeDisplayHelper.formatDifficulty(zones.optimalCeiling, gradeScale)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ZoneLegendItem(GradeEasy, stringResource(R.string.board_zone_warmup), "\u2264$warmUpLabel")
            ZoneLegendItem(GradeMedium, stringResource(R.string.board_zone_optimal), "$warmUpLabel\u2013$optimalLabel")
            ZoneLegendItem(GradeHard, stringResource(R.string.board_zone_limit), "\u2265$optimalLabel")
        }
    }
}

@Composable
internal fun ZoneLegendItem(color: Color, label: String, range: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            color = color,
            shape = CircleShape,
            modifier = Modifier.size(10.dp)
        ) {}
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(range, style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ZoneBar(label: String, count: Int, total: Int, color: Color) {
    val pct = if (total > 0) count.toFloat() / total else 0f
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = color,
            shape = CircleShape,
            modifier = Modifier.size(8.dp)
        ) {}
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(60.dp)
        )
        Text(
            "$count",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp)
        )
        LinearProgressIndicator(
            progress = { pct },
            modifier = Modifier.weight(1f).height(6.dp),
            color = color,
            trackColor = color.copy(alpha = 0.15f),
            strokeCap = StrokeCap.Round
        )
        Text(
            "${(pct * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.End
        )
    }
}
