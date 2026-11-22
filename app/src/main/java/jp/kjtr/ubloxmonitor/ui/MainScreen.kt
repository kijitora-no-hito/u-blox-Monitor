package jp.kjtr.ubloxmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.ChannelView
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ubloxmonitor.data.source.Channel
import jp.kjtr.ublox.core.ChartMath
import jp.kjtr.ubloxmonitor.domain.Display
import jp.kjtr.ublox.core.PvtSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 測位欄の行（Python 版 STATUS_ROWS） */
private val STATUS_ROWS = listOf(
    "utc" to R.string.row_utc, "lat" to R.string.row_lat, "lon" to R.string.row_lon,
    "height" to R.string.row_height, "msl" to R.string.row_msl, "nrsv" to R.string.row_nrsv,
    "hacc" to R.string.row_hacc, "vacc" to R.string.row_vacc, "pdop" to R.string.row_pdop,
    "speed" to R.string.row_speed, "heading" to R.string.row_heading, "correction" to R.string.row_correction,
    "messages" to R.string.row_messages, "errors" to R.string.row_errors, "record" to R.string.row_record,
)

@Composable
fun MainScreen(repo: MonitorRepository, padding: PaddingValues) {
    val views by repo.views.collectAsState()
    val selected by repo.selected.collectAsState()
    val logs by repo.logs.collectAsState()
    val correction by repo.correction.view.collectAsState()
    val view = views[selected] ?: views.values.firstOrNull()
    // RTK 補正の受信状態は u-blox のときだけ（補正を送っている先）
    val corr = if (view?.channel == Channel.UBLOX && correction.running) correction.snapshot else null
    val corrText = Display.correctionText(view?.snapshot?.pvt, corr != null, (corr?.rate ?: 0.0) * 8 / 1000)

    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val wide = maxWidth >= 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChannelSelector(views, view?.channel ?: selected) { repo.selected.value = it }
            if (view == null) {
                Text(stringResource(R.string.no_source), style = MaterialTheme.typography.bodyMedium)
                LogSection(logs)
                return@Column
            }
            if (view.info.isNotEmpty()) Text(view.info, style = MaterialTheme.typography.bodySmall)
            view.progress?.let { if (view.running) LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusSection(view, corrText)
                        Section(stringResource(R.string.sky_title)) { SkyPlot(view.snapshot.satellites) }
                    }
                    Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Section(stringResource(R.string.cn0_title)) { Cn0Bars(view.snapshot.satellites) }
                        SignalSection(view)
                        LowerSection(repo, view, wide = true)
                        LogSection(logs)
                    }
                }
            } else {
                StatusSection(view, corrText)
                Section(stringResource(R.string.sky_title)) { SkyPlot(view.snapshot.satellites) }
                Section(stringResource(R.string.cn0_title)) { Cn0Bars(view.snapshot.satellites) }
                SignalSection(view)
                LowerSection(repo, view, wide = false)
                LogSection(logs)
            }
        }
    }
}

@Composable
private fun StatusSection(view: ChannelView, correctionText: String) {
    val snap = view.snapshot
    val pvt = snap.pvt
    val text = Display.statusText(pvt)
    val values = text + mapOf(
        "correction" to correctionText,
        "messages" to Display.messageText(snap.counts),
        "errors" to view.checksumErrors.toString(),
        "record" to Display.recordText(view.record),
    )
    Section(stringResource(R.string.status_box)) {
        Box(
            Modifier.fillMaxWidth().background(argb(Display.fixColor(pvt?.fixRank ?: 0)), RoundedCornerShape(4.dp))
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text.getValue("mode"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        SelectionContainer {
            Column {
                for ((key, res) in STATUS_ROWS) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(stringResource(res), Modifier.weight(0.9f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(values[key] ?: "—", Modifier.weight(1.6f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalSection(view: ChannelView) {
    Section(stringResource(R.string.signals_title)) {
        val rows = Display.signalRows(view.snapshot)
        if (rows.isEmpty()) Text("—") else SimpleTable(Display.SIGNAL_COLUMNS, rows, listOf(2f, 1f, 1f))
    }
}

@Composable
private fun LowerSection(repo: MonitorRepository, view: ChannelView, wide: Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var windowIndex by remember { mutableIntStateOf(repo.prefs.chartWindow) }
    Section("") {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.tab_history)) })
            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.tab_satellites)) })
        }
        if (tab == 0) {
            Dropdown(
                stringResource(R.string.chart_window),
                ChartMath.TIME_WINDOWS.mapIndexed { i, w -> w.first to i },
                windowIndex,
                { windowIndex = it; repo.prefs.chartWindow = it },
            )
            val window = ChartMath.TIME_WINDOWS[windowIndex].second
            val history = view.snapshot.pvtHistory
            // 系列づくりは重いので画面の外のスレッドで（Python 版も 1 秒ごと）
            var series by remember { mutableStateOf<PvtSeries?>(null) }
            LaunchedEffect(history, window) {
                series = withContext(Dispatchers.Default) { ChartMath.pvtSeries(history, window) }
            }
            series?.let { s -> HistoryCharts(s, window, wide) }
        } else {
            val rows = Display.satelliteRows(view.snapshot)
            SimpleTable(
                Display.SATELLITE_COLUMNS, rows,
                listOf(1.1f, 1.1f, 1.1f, 0.8f, 0.9f, 0.8f, 0.8f, 0.8f, 0.8f), fontSize = 11,
            )
        }
    }
}

@Composable
private fun HistoryCharts(s: PvtSeries, window: Double?, wide: Boolean) {
    val charts: List<@Composable (Modifier) -> Unit> = listOf(
        { m -> TimeChart(stringResource(R.string.chart_mode), "", s.mode, window, m, fixModes = true) },
        { m -> TimeChart(stringResource(R.string.chart_offset), "[m]", s.offset, window, m) },
        { m -> TimeChart(stringResource(R.string.chart_accuracy), "[m]", s.accuracy, window, m, yFloor = 0.0) },
        { m -> TimeChart(stringResource(R.string.chart_nrsv), "[機]", s.nrsv, window, m, yFloor = 0.0, integer = true) },
    )
    if (wide) {
        for (pair in charts.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { pair.forEach { it(Modifier.weight(1f)) } }
        }
    } else {
        charts.forEach { it(Modifier) }
    }
}

@Composable
private fun LogSection(logs: List<String>) {
    Section(stringResource(R.string.log_title)) {
        val shown = if (logs.size > 300) logs.subList(logs.size - 300, logs.size) else logs
        val state = rememberLazyListState()
        LaunchedEffect(shown.size, logs.lastOrNull()) { if (shown.isNotEmpty()) state.scrollToItem(shown.size - 1) }
        if (shown.isEmpty()) {
            Text("—", textAlign = TextAlign.Start)
        } else {
            SelectionContainer {
                LazyColumn(Modifier.fillMaxWidth().height(200.dp), state = state) {
                    items(shown) { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}
