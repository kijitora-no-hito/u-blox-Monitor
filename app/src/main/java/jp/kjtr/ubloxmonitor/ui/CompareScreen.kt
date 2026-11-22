package jp.kjtr.ubloxmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.CompareView
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ubloxmonitor.data.source.Channel
import jp.kjtr.ubloxmonitor.domain.ChartPoint
import jp.kjtr.ubloxmonitor.domain.Compare
import jp.kjtr.ubloxmonitor.domain.Display
import jp.kjtr.ubloxmonitor.domain.SideSummary
import jp.kjtr.ubloxmonitor.domain.Ubx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 比較の基準に選べるデータ源 */
private val BASES = listOf(Channel.UBLOX, Channel.REPLAY)

/** u-blox（または再生）とスマホ内蔵 GNSS の比較。 */
@Composable
fun CompareScreen(repo: MonitorRepository, padding: PaddingValues) {
    val compare by repo.compare.collectAsState()
    val base by repo.compareBase.collectAsState()
    // views を購読して、画面を開いている間 publishViews（compare の更新）を回す
    repo.views.collectAsState()
    val c = compare
    val baseName = stringResource(base.labelRes)

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section(stringResource(R.string.compare_base_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BASES.forEach { ch ->
                    FilterChip(ch == base, { repo.setCompareBase(ch) }, label = { Text(stringResource(ch.labelRes)) })
                }
            }
            Text(stringResource(R.string.compare_base_help), style = MaterialTheme.typography.bodySmall)
            val notice = when {
                c == null || (c.baseSummary == null && c.internalSummary == null) -> stringResource(R.string.compare_none)
                c.internalSummary == null -> stringResource(R.string.compare_only_base)
                c.baseSummary == null -> stringResource(R.string.compare_only_internal, baseName)
                else -> null
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        if (c == null) return@Column
        CompareTable(c, baseName)
        DiffSection(repo, c, baseName)
        Cn0Section(c, baseName)
    }
}

private fun fmt(v: Double?, digits: Int, unit: String) = Display.formatValue(v, digits, unit)

@Composable
private fun CompareTable(c: CompareView, baseName: String) {
    Section(stringResource(R.string.compare_table_title)) {
        val b = c.baseSummary
        val i = c.internalSummary
        fun fix(s: SideSummary?) = s?.fixRank?.let { Ubx.FIX_LABELS[it] + if (s.fresh) "" else "（古い）" } ?: "—"
        val rows = listOf(
            stringResource(R.string.compare_fix) to (fix(b) to fix(i)),
            stringResource(R.string.compare_used) to ((b?.numSv?.toString() ?: "—") to (i?.numSv?.toString() ?: "—")),
            stringResource(R.string.compare_tracked) to ((b?.tracked?.toString() ?: "—") to (i?.tracked?.toString() ?: "—")),
            stringResource(R.string.compare_hacc) to (fmt(b?.hacc, 3, " m") to fmt(i?.hacc, 3, " m")),
            stringResource(R.string.compare_vacc) to (fmt(b?.vacc, 3, " m") to fmt(i?.vacc, 3, " m")),
            stringResource(R.string.compare_pdop) to (fmt(b?.pdop, 2, "") to fmt(i?.pdop, 2, "")),
            stringResource(R.string.compare_speed) to (fmt(b?.speed, 2, " m/s") to fmt(i?.speed, 2, " m/s")),
            stringResource(R.string.compare_cn0) to (fmt(b?.meanCn0Used, 1, "") to fmt(i?.meanCn0Used, 1, "")),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.compare_item), Modifier.weight(1.3f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(baseName, Modifier.weight(1f), fontSize = 13.sp, color = argb(c.base.trackColor))
            Text(stringResource(Channel.INTERNAL.labelRes), Modifier.weight(1f), fontSize = 13.sp, color = argb(Channel.INTERNAL.trackColor))
        }
        for ((name, values) in rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(name, Modifier.weight(1.3f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(values.first, Modifier.weight(1f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Text(values.second, Modifier.weight(1f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun DiffSection(repo: MonitorRepository, c: CompareView, baseName: String) {
    var windowIndex by remember { mutableIntStateOf(repo.prefs.compareWindow) }
    Section(stringResource(R.string.compare_diff_title)) {
        val d = c.diff
        if (d == null && c.baseSummary != null && c.internalSummary != null) {
            val stale = if (c.baseSummary.fresh) stringResource(Channel.INTERNAL.labelRes) else baseName
            Text(stringResource(R.string.compare_stale, stale), style = MaterialTheme.typography.bodySmall)
        }
        val rows = listOf(
            stringResource(R.string.compare_horizontal) to fmt(d?.horizontal, 2, " m"),
            stringResource(R.string.compare_bearing) to (d?.let { "%.0f°（%s）".format(it.bearing, Compare.bearingName(it.bearing)) } ?: "—"),
            stringResource(R.string.compare_up) to (d?.up?.let { "%+.2f m".format(it) } ?: "—"),
        )
        for ((name, value) in rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(name, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, Modifier.weight(1.4f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Dropdown(
            stringResource(R.string.chart_window),
            Compare.WINDOWS.mapIndexed { idx, w -> w.first to idx },
            windowIndex,
            { windowIndex = it; repo.prefs.compareWindow = it },
        )
        val window = Compare.WINDOWS[windowIndex].second
        var series by remember { mutableStateOf<Map<String, List<ChartPoint>>>(emptyMap()) }
        LaunchedEffect(c.history, window) {
            series = withContext(Dispatchers.Default) { Compare.diffSeries(c.history, window) }
        }
        TimeChart(stringResource(R.string.compare_chart), "[m]", series, window)
    }
}

@Composable
private fun Cn0Section(c: CompareView, baseName: String) {
    Section(stringResource(R.string.compare_cn0_title)) {
        val baseColor = argb(c.base.trackColor)
        val otherColor = argb(Channel.INTERNAL.trackColor)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Legend(baseColor, baseName)
            Legend(otherColor, stringResource(Channel.INTERNAL.labelRes))
        }
        val cn0 = c.cn0
        if (cn0 == null || cn0.rows.isEmpty()) {
            Text(stringResource(R.string.compare_cn0_none), style = MaterialTheme.typography.bodySmall)
        } else {
            Cn0CompareBars(cn0.rows, baseColor, otherColor)
        }
        if (cn0 != null) {
            Text(stringResource(R.string.compare_cn0_only, baseName, cn0.onlyBase, cn0.onlyOther), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(12.dp).background(color))
        Text(text, fontSize = 12.sp)
    }
}
