package jp.kjtr.ubloxmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.ublox.core.Pipeline
import jp.kjtr.ublox.core.Spectrum
import jp.kjtr.ublox.core.SpectrumSet
import jp.kjtr.ublox.core.SpectrumTrace
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ubloxmonitor.data.source.Channel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val CURRENT = Color(0xFF1F77B4)
private val MAXIMUM = Color(0xFFD35400)
private val MEAN = Color(0xFF7F8C8D)
private val MARKER = Color(0xFF27AE60)
private val SPEC_GRID = Color(0xFFD0D0D0)

/**
 * スペクトル（Python 版 SpectrumPanel）。表示中だけ 1 秒ごとに MON-SPAN を要求する（Poll なので受信機の設定は変えない）。
 * RF ブロックごとのグラフを縦に並べ、現在値・最大値ホールド・平均と主な信号の中心周波数を重ねる。
 * PC 版のマウスオーバーの代わりに、グラフをタッチ・横にドラッグするとその周波数とレベルを出す。
 * データ源はメインタブと同じ選択（u-blox / 再生。再生でもログに MON-SPAN があれば出す）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpectrumScreen(repo: MonitorRepository, padding: PaddingValues) {
    val views by repo.views.collectAsState()
    val selected by repo.selected.collectAsState()
    val channel = (views[selected] ?: views.values.firstOrNull())?.channel ?: selected
    val connected = channel == Channel.UBLOX && views[Channel.UBLOX]?.canSend == true

    var autoPoll by rememberSaveable { mutableStateOf(true) }
    var showMax by rememberSaveable { mutableStateOf(true) }
    var showMean by rememberSaveable { mutableStateOf(false) }
    val set = remember(channel) { SpectrumSet() }
    var polls by remember(channel) { mutableIntStateOf(0) }
    var count by remember(channel) { mutableIntStateOf(0) }
    // 集計を描き直すための印（SpectrumSet は Compose の状態ではないので）
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(channel) {
        var tick = 0
        var current: Pipeline? = null
        while (true) {
            val p = repo.pipeline(channel)
            if (p !== current) {
                // データ源が作り直された（接続し直し・別のファイルの再生）
                set.reset()
                polls = 0
                current = p
                revision++
            }
            if (tick % POLL_TICKS == 0 && channel == Channel.UBLOX && autoPoll && repo.canPollSpectrum()) {
                if (repo.pollSpectrum()) polls++
            }
            if (p != null && set.update(p.spectrum, p.spectrumCount)) revision++
            count = p?.spectrumCount ?: 0
            tick++
            delay(TICK_MS)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChannelSelector(views, channel) { repo.selected.value = it }
        FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CheckLabel(stringResource(R.string.spectrum_auto), autoPoll) { autoPoll = it }
            CheckLabel(stringResource(R.string.spectrum_max), showMax) { showMax = it }
            CheckLabel(stringResource(R.string.spectrum_mean), showMean) { showMean = it }
            OutlinedButton(onClick = {
                set.clear()
                polls = 0
                revision++
            }, modifier = Modifier.align(Alignment.CenterVertically)) { Text(stringResource(R.string.spectrum_clear)) }
        }
        Legend()
        val status = if (channel.isPhone) stringResource(R.string.spectrum_internal)
        else Spectrum.statusText(count, connected, polls)
        Text(status, style = MaterialTheme.typography.bodySmall)

        val traces = remember(revision) { set.traces.toList() }
        if (traces.isEmpty()) {
            Text(
                stringResource(R.string.spectrum_no_data), Modifier.fillMaxWidth().padding(vertical = 40.dp),
                style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        } else {
            // 縦画面でも横画面でも RF ブロックを縦に並べる（PC 版と同じ）
            traces.forEachIndexed { index, trace ->
                SpectrumChart(trace, showMax, showMean, revision, index)
            }
        }
    }
}

@Composable
private fun CheckLabel(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("■ " + stringResource(R.string.spectrum_legend_current), color = CURRENT, fontSize = 12.sp)
        Text("■ " + stringResource(R.string.spectrum_legend_max), color = MAXIMUM, fontSize = 12.sp)
        Text("■ " + stringResource(R.string.spectrum_legend_mean), color = MEAN, fontSize = 12.sp)
        Text("┊ " + stringResource(R.string.spectrum_legend_marker), color = MARKER, fontSize = 12.sp)
    }
}

/** 1 つの RF ブロック（Python 版 SpectrumPlot._draw_block）。タッチ・横ドラッグでカーソル。 */
@Composable
private fun SpectrumChart(trace: SpectrumTrace, showMax: Boolean, showMean: Boolean, revision: Int, index: Int) {
    val block = trace.block ?: return
    val tm = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val small = TextStyle(fontSize = 9.sp, color = textColor)
    val markerStyle = TextStyle(fontSize = 9.sp, color = MARKER)
    val cursorStyle = TextStyle(fontSize = 11.sp, color = textColor)
    // カーソルの x [px]（負は出さない）
    var cursorX by remember(index) { mutableFloatStateOf(-1f) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(Spectrum.blockTitle(block), style = MaterialTheme.typography.bodySmall)
        Canvas(
            Modifier.fillMaxWidth().height(230.dp)
                .pointerInput(index) { detectTapGestures { cursorX = it.x } }
                .pointerInput(index) {
                    // 縦のスクロールを妨げないよう横方向のドラッグだけ受ける
                    detectHorizontalDragGestures(onDragStart = { cursorX = it.x }) { change, _ -> cursorX = change.position.x }
                },
        ) {
            // 集計は同じオブジェクトのまま中身が変わるので、revision を読んで描き直させる
            if (revision < 0) return@Canvas
            drawBlock(tm, trace, showMax, showMean, cursorX, small, markerStyle, cursorStyle, textColor, surface)
        }
    }
}

private fun DrawScope.text(tm: TextMeasurer, s: String, x: Float, y: Float, style: TextStyle, ax: Float, ay: Float) {
    val layout = tm.measure(s, style)
    drawText(layout, topLeft = Offset(x - layout.size.width * ax, y - layout.size.height * ay))
}

private fun DrawScope.drawBlock(
    tm: TextMeasurer, trace: SpectrumTrace, showMax: Boolean, showMean: Boolean, cursorX: Float,
    small: TextStyle, markerStyle: TextStyle, cursorStyle: TextStyle, textColor: Color, surface: Color,
) {
    val freqs = trace.frequencies ?: return
    val current = trace.current ?: return
    val maximum = trace.maximum
    val mean = trace.mean
    val left = 40.dp.toPx()
    val right = 8.dp.toPx()
    val top = 4.dp.toPx()
    val bottom = 30.dp.toPx()
    val w = size.width - left - right
    val h = size.height - top - bottom
    if (w < 20 || h < 20) return
    val fLow = freqs.first()
    val fHigh = freqs.last()
    val series = listOfNotNull(current, if (showMax) maximum else null, if (showMean) mean else null)
    val yLow = series.minOf { s -> s.min() } - 2
    val yHigh = series.maxOf { s -> s.max() } + 2

    fun px(f: Double) = (left + (f - fLow) / (fHigh - fLow) * w).toFloat()
    fun py(v: Double) = (top + h - (v - yLow) / (yHigh - yLow) * h).toFloat()

    // 目盛りと枠
    for (level in Spectrum.niceTicks(yLow, yHigh, 4)) {
        val y = py(level)
        drawLine(SPEC_GRID, Offset(left, y), Offset(left + w, y), 1f)
        text(tm, Spectrum.formatG(level), left - 4.dp.toPx(), y, small, 1f, 0.5f)
    }
    // 幅に合わせて目盛りの数を減らす（PC 版は 8）
    val xCount = (w / 64.dp.toPx()).toInt().coerceIn(3, 8)
    for (f in Spectrum.niceTicks(fLow, fHigh, xCount)) {
        val x = px(f)
        drawLine(SPEC_GRID, Offset(x, top), Offset(x, top + h), 1f)
        text(tm, Spectrum.formatG(f), x, top + h + 2.dp.toPx(), small, 0.5f, 0f)
    }
    text(tm, "周波数 [MHz]", left + w / 2, size.height, small, 0.5f, 1f)
    drawRect(textColor, Offset(left, top), Size(w, h), style = Stroke(1f))

    // 信号の中心周波数（破線と、線の右に縦書き）
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
    for ((name, f) in Spectrum.BAND_MARKERS) {
        if (f < fLow || f > fHigh) continue
        val x = px(f)
        drawLine(MARKER, Offset(x, top), Offset(x, top + h), 1.dp.toPx(), pathEffect = dash)
        rotate(90f, Offset(x, top + 4.dp.toPx())) {
            // 回転後の上（-y）が画面の右なので、下揃えで置くと線の右に出る
            text(tm, name, x, top + 4.dp.toPx() - 2.dp.toPx(), markerStyle, 0f, 1f)
        }
    }

    // スペクトル（平均 → 最大 → 現在値の順に重ねる）
    clipRect(left, top, left + w, top + h) {
        for ((data, color, width) in listOf(
            Triple(if (showMean) mean else null, MEAN, 1.dp.toPx()),
            Triple(if (showMax) maximum else null, MAXIMUM, 1.dp.toPx()),
            Triple(current, CURRENT, 1.6.dp.toPx()),
        )) {
            data ?: continue
            val path = Path()
            for (i in freqs.indices) {
                if (i >= data.size) break
                val x = px(freqs[i])
                val y = py(data[i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width))
        }
    }

    // カーソル（タッチ・ドラッグした位置の周波数とレベル）
    if (cursorX < left || cursorX > left + w) return
    val ratio = (cursorX - left) / w
    val i = (ratio * (freqs.size - 1)).roundToInt().coerceIn(0, freqs.size - 1)
    val x = left + i.toFloat() / (freqs.size - 1) * w
    drawLine(textColor, Offset(x, top), Offset(x, top + h), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f)))
    var label = "%.3f MHz  %.2f dB".format(freqs[i], current.getOrElse(i) { 0.0 })
    if (showMax && maximum != null) label += "（最大 %.2f）".format(maximum.getOrElse(i) { 0.0 })
    val layout = tm.measure(label, cursorStyle)
    val bw = layout.size.width + 10.dp.toPx()
    val bh = layout.size.height + 4.dp.toPx()
    val bx = if (x + 6.dp.toPx() + bw < left + w) x + 6.dp.toPx() else maxOf(left, x - 6.dp.toPx() - bw)
    val by = top + 4.dp.toPx()
    drawRect(surface, Offset(bx, by), Size(bw, bh))
    drawRect(textColor, Offset(bx, by), Size(bw, bh), style = Stroke(1f))
    drawText(layout, topLeft = Offset(bx + 5.dp.toPx(), by + 2.dp.toPx()))
}

private const val TICK_MS = 250L
/** 1 秒ごとに要求する（250 ms × 4） */
private const val POLL_TICKS = 4
