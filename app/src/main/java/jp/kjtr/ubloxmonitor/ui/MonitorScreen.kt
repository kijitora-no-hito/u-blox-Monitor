package jp.kjtr.ubloxmonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.ublox.core.MONITOR_SYSTEMS
import jp.kjtr.ublox.core.MonitorMath
import jp.kjtr.ublox.core.MonitorSnapshot
import jp.kjtr.ublox.core.MonitorSystem
import jp.kjtr.ublox.core.TrendPoint
import jp.kjtr.ublox.core.TrendSnapshot
import jp.kjtr.ublox.core.Ubx
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ubloxmonitor.data.source.Channel
import kotlinx.coroutines.delay

private val MON_BG = Color(0xFF121212)
private val MPATH_COLORS = mapOf(0 to Color(0xFF7F8C8D), 1 to Color(0xFF2ECC71), 2 to Color(0xFFF1C40F), 3 to Color(0xFFE74C3C))
private val SV_COLORS = listOf(
    Color(0xFFE74C3C), Color(0xFF3498DB), Color(0xFF2ECC71), Color(0xFFF1C40F), Color(0xFF9B59B6), Color(0xFF1ABC9C), Color(0xFFE67E22),
)

/** "#2ecc71" → Color */
private fun hex(s: String): Color = Color(0xFF000000 or s.removePrefix("#").toLong(16))

/** 縦画面で 1 論理ピクセル（PC 版の座標）を何 dp で描くか */
private const val PHONE_SCALE = 0.85f

/**
 * u-blox モニター（Python 版 MonitorPanel）: 信号（RAWX / MEASX）、SFRBX モニター、トレンド（5 分）。
 * データ源はメインタブと同じ選択（u-blox / 内蔵 / 再生）。表示中のタブだけ更新する（信号・SFRBX は 0.5 秒、トレンドは 1 秒ごと）。
 * 横長（幅 600dp 以上の横向き・タブレット）は PC 版と同じ 2 段の配置を幅に合わせて縮小、
 * 縦画面は衛星系ごとの段に分けて横にスクロール（使っている枠だけ）、トレンドは帯域を切り替えて 1 つずつ出す。
 * 縦画面では、MON-VER で受信機が対応しないと分かる衛星系（X20P の GLONASS）は出さず、衛星が 1 機も無い衛星系は「衛星なし」の 1 行に縮める。
 */
@Composable
fun MonitorScreen(repo: MonitorRepository, padding: PaddingValues) {
    val views by repo.views.collectAsState()
    val selected by repo.selected.collectAsState()
    val channel = (views[selected] ?: views.values.firstOrNull())?.channel ?: selected
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var snap by remember { mutableStateOf<MonitorSnapshot?>(null) }
    var trend by remember { mutableStateOf<TrendSnapshot?>(null) }
    var hidden by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(channel, tab) {
        var tick = 0
        while (true) {
            val pipeline = repo.pipeline(channel)
            val state = pipeline?.state
            // 受信機が対応しない衛星系（MON-VER が無い再生などでは空 → 「衛星なし」の 1 行になる）
            hidden = MonitorMath.unsupportedGroups(pipeline?.version)
            if (tab == TAB_TREND) {
                // トレンドは重いので 1 秒ごと（Python 版 slow_tick）
                if (tick % 2 == 0) trend = state?.trendSnapshot()
            } else {
                snap = state?.monitorSnapshot()
            }
            tick++
            delay(500)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChannelSelector(views, channel) { repo.selected.value = it }
                when {
                    views.isEmpty() -> Text(stringResource(R.string.monitor_no_source), style = MaterialTheme.typography.bodySmall)
                    channel == Channel.INTERNAL ->
                        Text(stringResource(R.string.monitor_internal_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            TabRow(selectedTabIndex = tab) {
                // 縦画面は短い名前（「信号（RAWX /」で切れたため）
                val labels = if (wide) listOf(R.string.monitor_tab_signal, R.string.monitor_tab_sfrbx, R.string.monitor_tab_trend)
                else listOf(R.string.monitor_tab_signal_short, R.string.monitor_tab_sfrbx_short, R.string.monitor_tab_trend_short)
                labels.forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label), maxLines = 1) })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(MON_BG)) {
                when (tab) {
                    TAB_SIGNAL -> snap?.let { SignalView(it, wide, hidden) }
                    TAB_SFRBX -> snap?.let { SfrbxView(it, wide, hidden) }
                    else -> trend?.let { TrendView(it, wide, hidden) }
                }
            }
        }
    }
}

private const val TAB_SIGNAL = 0
private const val TAB_SFRBX = 1
private const val TAB_TREND = 2

// ---- 描画の共通 ----

/** 論理座標（PC 版のピクセル）で描くための文字。size は論理ピクセル。 */
private class MonText(val tm: TextMeasurer) {
    fun DrawScope.mid(text: String, x: Float, y: Float, color: Color, size: Float, bold: Boolean = false, mono: Boolean = false) {
        val layout = tm.measure(text, style(color, size, bold, mono))
        drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
    }

    fun DrawScope.left(text: String, x: Float, y: Float, color: Color, size: Float) {
        drawText(tm.measure(text, style(color, size, bold = true, mono = false)), topLeft = Offset(x, y))
    }

    private fun DrawScope.style(color: Color, size: Float, bold: Boolean, mono: Boolean) = TextStyle(
        color = color, fontSize = size.toSp(),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
    )
}

/** 論理座標の図を、論理の幅・高さに合わせて拡大縮小して描く（Python 版 ScaledCanvas）。 */
private fun DrawScope.scaled(logicalWidth: Float, logicalHeight: Float, draw: DrawScope.() -> Unit) {
    val scale = minOf(size.width / logicalWidth, size.height / logicalHeight)
    withTransform({ scale(scale, scale, pivot = Offset.Zero) }) { draw() }
}

/** 縦画面の 1 衛星系の段: 見出しと、横にスクロールする図。 */
@Composable
private fun SystemRow(
    name: String, cfg: MonitorSystem, slots: Int, logicalHeight: Float,
    labels: (DrawScope.(MonText) -> Unit)? = null,
    draw: DrawScope.(MonText) -> Unit,
) {
    val tm = rememberTextMeasurer()
    val text = remember(tm) { MonText(tm) }
    val logicalWidth = 20f + slots * MonitorMath.SLOT
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(name, color = hex(cfg.color), fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
        Row {
            // 項目名の列はスクロールしても左端に残す
            if (labels != null) {
                Canvas(Modifier.size(width = (LABEL_WIDTH * PHONE_SCALE).dp, height = (logicalHeight * PHONE_SCALE).dp)) {
                    scaled(LABEL_WIDTH, logicalHeight) { labels(text) }
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Canvas(Modifier.size(width = (logicalWidth * PHONE_SCALE).dp, height = (logicalHeight * PHONE_SCALE).dp)) {
                    scaled(logicalWidth, logicalHeight) { draw(text) }
                }
            }
        }
    }
}

/** 項目名の列の幅（論理 px） */
private const val LABEL_WIDTH = 54f

/** 縦画面で衛星が 1 機も無い衛星系の 1 行。 */
@Composable
private fun EmptySystemRow(name: String, cfg: MonitorSystem) {
    Text(
        stringResource(R.string.monitor_no_satellites, name), color = hex(cfg.color).copy(alpha = 0.7f), fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** 縦画面に出す衛星系（対応しない衛星系は除く）と、衛星が 1 機でもあるか。 */
private fun phoneSystems(seen: Map<String, List<Int>>, hidden: Set<String>): List<Triple<String, MonitorSystem, Boolean>> =
    MONITOR_SYSTEMS.entries.filter { it.key !in hidden }.map { (name, cfg) -> Triple(name, cfg, !seen[name].isNullOrEmpty()) }

// ---- 信号（RAWX / MEASX） ----

@Composable
private fun SignalView(data: MonitorSnapshot, wide: Boolean, hidden: Set<String>) {
    if (wide) {
        val tm = rememberTextMeasurer()
        val text = remember(tm) { MonText(tm) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // 棒の下の 5 行の凡例
            Text(
                stringResource(R.string.monitor_measx_legend), color = Color(0xFFAAAAAA), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            val w = MonitorMath.SIGNAL_WIDTH.toFloat()
            val h = MonitorMath.SIGNAL_HEIGHT.toFloat()
            Canvas(Modifier.fillMaxWidth().aspectRatio(w / h)) {
                scaled(w, h) {
                    for ((name, cfg) in MONITOR_SYSTEMS) {
                        val offsetX = MonitorMath.systemOffset(name).toFloat()
                        val yBase = 360f + cfg.row * 430
                        with(text) { left(name, offsetX, yBase - 335, hex(cfg.color), 16f) }
                        drawSignalSystem(text, name, cfg, data, offsetX, yBase, cfg.max)
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            for ((name, cfg, any) in phoneSystems(data.seen, hidden)) {
                if (!any) {
                    EmptySystemRow(name, cfg)
                    continue
                }
                val slots = data.seen.getValue(name).size
                // 枠の上（帯域の小枠）から MEASX の 5 行の下まで。左端に項目名の列
                SystemRow(name, cfg, slots, logicalHeight = 400f, labels = { text -> drawSignalLabels(text, 305f) }) { text ->
                    drawSignalSystem(text, name, cfg, data, 10f, 305f, slots)
                }
            }
            Text(
                stringResource(R.string.monitor_signal_help), color = Color(0xFFAAAAAA), fontSize = 11.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** 縦画面の左端の項目名（帯域・C/N0・MEASX の 5 行）。 */
private fun DrawScope.drawSignalLabels(text: MonText, yBase: Float) {
    val gray = Color(0xFFAAAAAA)
    with(text) {
        mid("帯域", LABEL_WIDTH / 2, yBase - 293, gray, 10f)
        mid("C/N0", LABEL_WIDTH / 2, yBase - 140, gray, 10f)
        mid("衛星", LABEL_WIDTH / 2, yBase + 15, gray, 10f)
        MonitorMath.MEASX_FIELDS.forEachIndexed { i, f ->
            mid(if (f == "rmse") "RMSE" else f, LABEL_WIDTH / 2, yBase + 35 + i * 12, gray, 10f)
        }
    }
}

/** 1 つの衛星系の枠を slots 個描く（Python 版 SignalCanvas.draw の 1 衛星系分）。 */
private fun DrawScope.drawSignalSystem(
    text: MonText, name: String, cfg: MonitorSystem, data: MonitorSnapshot, offsetX: Float, yBase: Float, slots: Int,
) {
    val now = data.now
    val seen = data.seen[name].orEmpty()
    val color = hex(cfg.color)
    for (index in 0 until slots) {
        val x = offsetX + index * MonitorMath.SLOT
        drawRect(Color(0xFF222222), Offset(x, yBase - 280), Size(MonitorMath.SLOT_BOX.toFloat(), 280f), style = Stroke(1f))
        if (index >= seen.size) continue
        val sv = seen[index]
        val entry = data.tracks[name]?.get(sv) ?: continue
        val active = MonitorMath.active(entry, now)
        with(text) { mid(sv.toString(), x + 22, yBase + 15, if (active) color else Color(0xFF444444), 12f, bold = true, mono = true) }
        if (!active) continue
        // 帯域ごとの追尾状態（緑の地 = 追尾中、明るい緑の数字 = 搬送波位相が有効）
        val status = MonitorMath.bandStatus(cfg.id, entry.signals, now)
        Ubx.BANDS.forEachIndexed { i, band ->
            val (tracked, carrier) = status[band] ?: (false to false)
            val sx = x + 2 + i * 11
            drawRect(if (tracked) Color(0xFF1B5E20) else Color(0xFF1A1A1A), Offset(sx, yBase - 300), Size(10f, 15f))
            drawRect(Color(0xFF333333), Offset(sx, yBase - 300), Size(10f, 15f), style = Stroke(1f))
            with(text) { mid(band.substring(1), sx + 5, yBase - 293, if (carrier) Color(0xFF00FF00) else Color(0xFF555555), 9f, bold = true) }
        }
        // 棒の高さ = C/N0、色 = マルチパスの指標
        val cno = MonitorMath.barCno(entry)
        val h = cno / 63f * 240
        drawRect(MPATH_COLORS[entry.mpath ?: 0] ?: MPATH_COLORS.getValue(0), Offset(x + 12, yBase - h), Size(28f, h))
        drawRect(Color.White, Offset(x + 12, yBase - h), Size(28f, h), style = Stroke(1f))
        with(text) { mid(cno.toString(), x + 26, yBase - h - 10, Color.White, 11f, mono = true) }
        // 棒の下に MEASX の値
        MonitorMath.measxValues(entry).forEachIndexed { i, v ->
            val warn = MonitorMath.MEASX_FIELDS[i] == "rmse" && (v.toIntOrNull() ?: 0) > MonitorMath.RMSE_WARN
            with(text) { mid(v, x + 22, yBase + 35 + i * 12, if (warn) Color(0xFFE74C3C) else Color(0xFFAAAAAA), 9f, mono = true) }
        }
    }
}

// ---- SFRBX ----

@Composable
private fun SfrbxView(data: MonitorSnapshot, wide: Boolean, hidden: Set<String>) {
    if (wide) {
        val tm = rememberTextMeasurer()
        val text = remember(tm) { MonText(tm) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val w = MonitorMath.SFRBX_WIDTH.toFloat()
            val h = MonitorMath.SFRBX_HEIGHT.toFloat()
            Canvas(Modifier.fillMaxWidth().aspectRatio(w / h)) {
                scaled(w, h) {
                    for ((name, cfg) in MONITOR_SYSTEMS) {
                        val offsetX = MonitorMath.systemOffset(name).toFloat()
                        val yBase = 250f + cfg.row * 280
                        with(text) { left("$name Navigation Subframes", offsetX, yBase - 215, hex(cfg.color), 15f) }
                        drawSfrbxSystem(text, name, cfg, data, offsetX, yBase, cfg.max)
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            for ((name, cfg, any) in phoneSystems(data.seen, hidden)) {
                if (!any) {
                    EmptySystemRow(name, cfg)
                    continue
                }
                val slots = data.seen.getValue(name).size
                SystemRow("$name Navigation Subframes", cfg, slots, logicalHeight = 230f) { text ->
                    drawSfrbxSystem(text, name, cfg, data, 10f, 195f, slots)
                }
            }
            Text(
                stringResource(R.string.monitor_sfrbx_help), color = Color(0xFFAAAAAA), fontSize = 11.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** Python 版 SfrbxCanvas.draw の 1 衛星系分。 */
private fun DrawScope.drawSfrbxSystem(
    text: MonText, name: String, cfg: MonitorSystem, data: MonitorSnapshot, offsetX: Float, yBase: Float, slots: Int,
) {
    val seen = data.seen[name].orEmpty()
    val color = hex(cfg.color)
    for (index in 0 until slots) {
        val x = offsetX + index * MonitorMath.SLOT
        drawRect(Color(0xFF333333), Offset(x, yBase - 180), Size(MonitorMath.SLOT_BOX.toFloat(), 180f), style = Stroke(1f))
        if (index >= seen.size) continue
        val sv = seen[index]
        with(text) { mid(sv.toString(), x + 22, yBase + 15, color, 12f, bold = true, mono = true) }
        val frames = data.sfrbx[name]?.get(sv) ?: continue
        val h = MonitorMath.sfrbxHeight(frames).toFloat()
        drawRect(Color(0xFFF39C12), Offset(x + 15, yBase - h), Size(27f, h))
        drawRect(Color.White, Offset(x + 15, yBase - h), Size(27f, h), style = Stroke(1f))
        for (number in 1..5) {
            val by = yBase - 170 + (number - 1) * 25
            val received = number in frames.subframes
            val fill = if (!received) Color(0xFF1A1A1A) else if (number <= 3) Color(0xFF27AE60) else Color(0xFF2980B9)
            drawRect(fill, Offset(x + 2, by), Size(10f, 20f))
            drawRect(Color(0xFF333333), Offset(x + 2, by), Size(10f, 20f), style = Stroke(1f))
            with(text) { mid(number.toString(), x + 7, by + 10, if (received) Color.White else Color(0xFF444444), 9f, bold = true) }
        }
    }
}

// ---- トレンド（5 分） ----

@Composable
private fun TrendView(data: TrendSnapshot, wide: Boolean, hidden: Set<String>) {
    // 衛星の絞り込み（衛星系・帯域ごと。"All" か衛星番号）
    val filters = remember { mutableStateMapOf<String, String>() }
    var phoneBand by rememberSaveable { mutableStateOf(Ubx.BANDS.first()) }
    val bandsWithData = MonitorMath.bandsWithData(data)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!wide) {
            // 縦画面は帯域を 1 つずつ（PC 版は 4 帯域を横に並べる）
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (band in Ubx.BANDS) {
                    // データが無い帯域だけ押せなくする。暗い背景でも読めるよう色を決めておく
                    FilterChip(
                        selected = band == phoneBand, onClick = { phoneBand = band }, label = { Text(band) },
                        enabled = band in bandsWithData || band == phoneBand,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFF2C3E50), labelColor = Color(0xFFECF0F1),
                            selectedContainerColor = Color(0xFF2980B9), selectedLabelColor = Color.White,
                            disabledContainerColor = Color(0xFF1E1E1E), disabledLabelColor = Color(0xFF666666),
                            disabledSelectedContainerColor = Color(0xFF1F4E6E),
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = band in bandsWithData || band == phoneBand, selected = band == phoneBand,
                            borderColor = Color(0xFF7F8C8D), disabledBorderColor = Color(0xFF333333),
                        ),
                    )
                }
            }
        }
        for ((name, cfg) in MONITOR_SYSTEMS) {
            if (name == "OTHER") continue
            if (!wide && name in hidden) continue
            if (!wide && data.seen[name].isNullOrEmpty()) {
                EmptySystemRow(name, cfg)
                continue
            }
            val history = data.history[name].orEmpty()
            val choices = listOf(ALL) + data.seen[name].orEmpty().map { it.toString() }
            Column(
                Modifier.fillMaxWidth().border(BorderStroke(1.dp, Color(0xFF34495E))).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(name, color = hex(cfg.color), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val bands = if (wide) Ubx.BANDS else listOf(phoneBand)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (wide) MetricLegend(Modifier.padding(top = 36.dp).width(76.dp).height(150.dp))
                    for (band in bands) {
                        val key = "$name/$band"
                        val chosen = filters[key]?.takeIf { it in choices } ?: ALL
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(band, color = Color(0xFFECF0F1), fontSize = 13.sp, modifier = Modifier.weight(1f))
                                SatSelector(chosen, choices) { filters[key] = it }
                            }
                            TrendPlot(history, band, data.now, chosen, if (wide) 150.dp else 170.dp, labels = !wide)
                            Row(Modifier.fillMaxWidth()) {
                                Text("−5 分", color = Color(0xFF7F8C8D), fontSize = 10.sp, modifier = Modifier.weight(1f))
                                Text("今", color = Color(0xFF7F8C8D), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.monitor_trend_help), color = Color(0xFFAAAAAA), fontSize = 11.sp)
    }
}

private const val ALL = "All"

/** トレンドの 4 段の名前（単位付き）と、段の上端・下端の目盛り（trendLines の縦の範囲） */
private val TREND_AXES = listOf(
    "C/N0 [dB-Hz]" to ("60" to "0"),
    "MPath" to ("3" to "0"),
    "RMSE" to ("63" to "0"),
    "TrackStatus" to ("PR+CP" to "なし"),
)

/** 左の凡例（4 段の名前）。 */
@Composable
private fun MetricLegend(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.SpaceEvenly) {
        for ((m, _) in TREND_AXES) {
            Text(m, color = Color(0xFFECF0F1), fontSize = 11.sp)
        }
    }
}

/** 衛星の絞り込みのプルダウン（小さいボタン）。 */
@Composable
private fun SatSelector(chosen: String, choices: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text("$chosen ▼", fontSize = 12.sp, color = Color(0xFFECF0F1))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in choices) {
                DropdownMenuItem(text = { Text(c) }, onClick = {
                    open = false
                    onSelect(c)
                })
            }
        }
    }
}

/** 1 つの衛星系・帯域のトレンド（Python 版 TrendPlot）。labels は段の名前を図の中に出す（縦画面）。 */
@Composable
private fun TrendPlot(history: Map<Int, List<TrendPoint>>, band: String, now: Double, chosen: String, height: Dp, labels: Boolean) {
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color(0xFF7F8C8D), fontSize = 9.sp)
    val tickStyle = TextStyle(color = Color(0xFF95A5A6), fontSize = 8.sp)
    Canvas(Modifier.fillMaxWidth().height(height).background(MON_BG)) {
        val w = size.width
        val h = size.height
        for (i in 1..3) drawLine(Color(0xFF222222), Offset(0f, i * h / 4), Offset(w, i * h / 4), 1f)
        drawRect(Color(0xFF34495E), Offset.Zero, size, style = Stroke(1f))
        if (labels) {
            TREND_AXES.forEachIndexed { i, (m, _) ->
                drawText(tm.measure(m, labelStyle), topLeft = Offset(3.dp.toPx(), i * h / 4 + 1.dp.toPx()))
            }
        }
        // 各段の上端・下端の目盛り（右端）
        TREND_AXES.forEachIndexed { i, (_, range) ->
            val topText = tm.measure(range.first, tickStyle)
            val bottomText = tm.measure(range.second, tickStyle)
            // TrackStatus の目盛りは線の位置（PR+CP 0.1、なし 0.8）に合わせる
            val (yTop, yBottom) = if (i == 3) (i + 0.1f) * h / 4 to (i + 0.8f) * h / 4 else i * h / 4 to (i + 1) * h / 4
            drawText(topText, topLeft = Offset(w - topText.size.width - 2.dp.toPx(), yTop + 1f))
            drawText(bottomText, topLeft = Offset(w - bottomText.size.width - 2.dp.toPx(), yBottom - bottomText.size.height - 1f))
        }
        clipRect {
            for ((sv, items) in history) {
                if (chosen != ALL && chosen != sv.toString()) continue
                val color = SV_COLORS[sv % SV_COLORS.size]
                for (line in MonitorMath.trendLines(items, band, now)) {
                    if (line.size < 2) continue
                    val path = Path()
                    line.forEachIndexed { j, (fx, fy) ->
                        val x = (fx * w).toFloat()
                        val y = (fy * h).toFloat()
                        if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}
