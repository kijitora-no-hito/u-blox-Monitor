package jp.kjtr.ubloxmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.ublox.core.ChartMath
import jp.kjtr.ublox.core.ChartPoint
import jp.kjtr.ubloxmonitor.domain.Display
import jp.kjtr.ublox.core.SatView
import jp.kjtr.ublox.core.Ubx
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private val GRID = Color(0xFFB0B0B0)

/** 文字を (x, y) に置く。ax・ay は 0 が左/上揃え、0.5 が中央、1 が右/下揃え。 */
private fun DrawScope.label(
    tm: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle, ax: Float = 0f, ay: Float = 0f,
) {
    val layout = tm.measure(text, style)
    drawText(layout, topLeft = Offset(x - layout.size.width * ax, y - layout.size.height * ay))
}

/** 衛星の配置図。中心が天頂、外周が地平線、上が北。測位に使っている衛星は塗りつぶす。 */
@Composable
fun SkyPlot(satellites: Map<String, SatView>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurface
    val small = TextStyle(fontSize = 10.sp, color = textColor)
    val axis = TextStyle(fontSize = 12.sp, color = textColor)
    Canvas(modifier.fillMaxWidth().aspectRatio(1f)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = minOf(size.width, size.height) / 2 - 24.dp.toPx()
        for (elevation in listOf(0, 30, 60)) {
            drawCircle(GRID, radius * (90 - elevation) / 90, center, style = Stroke(1.dp.toPx()))
        }
        drawLine(GRID, center + Offset(0f, -radius), center + Offset(0f, radius), 1.dp.toPx())
        drawLine(GRID, center + Offset(-radius, 0f), center + Offset(radius, 0f), 1.dp.toPx())
        val off = radius + 12.dp.toPx()
        for ((name, dx, dy) in listOf(Triple("N", 0, -1), Triple("E", 1, 0), Triple("S", 0, 1), Triple("W", -1, 0))) {
            label(tm, name, center.x + dx * off, center.y + dy * off, axis, 0.5f, 0.5f)
        }
        val dot = 5.dp.toPx()
        for ((name, sat) in satellites.toSortedMap()) {
            val az = sat.azim ?: continue
            val el = sat.elev ?: continue
            if (el < 0) continue
            val distance = radius * (90 - el) / 90
            val angle = Math.toRadians(az.toDouble())
            val p = center + Offset((distance * sin(angle)).toFloat(), (-distance * cos(angle)).toFloat())
            val color = argb(Display.systemColor(name))
            if (sat.used) drawCircle(color, dot, p)
            drawCircle(color, dot, p, style = Stroke(1.5.dp.toPx()))
            label(tm, name, p.x + 7.dp.toPx(), p.y + 4.dp.toPx(), small, 0f, 1f)
        }
    }
}

/** 衛星ごとの C/N0 の棒グラフ。測位に使っている衛星は濃く、それ以外は薄く描く。 */
@Composable
fun Cn0Bars(satellites: Map<String, SatView>, modifier: Modifier = Modifier, height: Dp = 200.dp) {
    val tm = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurface
    val style = TextStyle(fontSize = 9.sp, color = textColor)
    val sats = satellites.filter { it.value.cno > 0 }.toSortedMap()
    Canvas(modifier.fillMaxWidth().height(height)) {
        val left = 30.dp.toPx()
        val top = 12.dp.toPx()
        val right = 8.dp.toPx()
        val bottom = 34.dp.toPx()
        val width = size.width - left - right
        val h = size.height - top - bottom
        for (cno in 0..ChartMath.CN0_MAX step 10) {
            val y = top + h * (1 - cno.toFloat() / ChartMath.CN0_MAX)
            drawLine(Color(0xFFC8C8C8), Offset(left, y), Offset(left + width, y), 1f)
            label(tm, cno.toString(), left - 4.dp.toPx(), y, style, 1f, 0.5f)
        }
        if (sats.isEmpty()) return@Canvas
        val slot = width / sats.size
        val bar = maxOf(2.dp.toPx(), minOf(slot * 0.8f, 22.dp.toPx()))
        sats.entries.forEachIndexed { index, (name, sat) ->
            val x = left + slot * index + (slot - bar) / 2
            val bh = h * minOf(sat.cno, ChartMath.CN0_MAX) / ChartMath.CN0_MAX
            var color = argb(Display.systemColor(name))
            if (!sat.used) color = color.copy(alpha = 90 / 255f)
            drawRect(color, Offset(x, top + h - bh), Size(bar, bh))
            if (slot >= 16.dp.toPx()) label(tm, sat.cno.toString(), x + bar / 2, top + h - bh - 1.dp.toPx(), style, 0.5f, 1f)
            val px = x + bar / 2
            val py = top + h + 4.dp.toPx()
            rotate(90f, Offset(px, py)) {
                label(tm, name, px, py, style, 0f, 0.5f)
            }
        }
    }
}

/**
 * 共通の衛星の C/N0 を 2 本の棒で並べる（比較画面）。rows は (衛星名, 基準の C/N0, 内蔵の C/N0)。
 * 色はデータ源の地図の軌跡の色（u-blox 青・再生 紫・内蔵 赤）。
 */
@Composable
fun Cn0CompareBars(
    rows: List<Triple<String, Int, Int>>,
    baseColor: Color,
    otherColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
) {
    val tm = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurface
    val style = TextStyle(fontSize = 9.sp, color = textColor)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val left = 30.dp.toPx()
        val top = 12.dp.toPx()
        val right = 8.dp.toPx()
        val bottom = 34.dp.toPx()
        val width = size.width - left - right
        val h = size.height - top - bottom
        for (cno in 0..ChartMath.CN0_MAX step 10) {
            val y = top + h * (1 - cno.toFloat() / ChartMath.CN0_MAX)
            drawLine(Color(0xFFC8C8C8), Offset(left, y), Offset(left + width, y), 1f)
            label(tm, cno.toString(), left - 4.dp.toPx(), y, style, 1f, 0.5f)
        }
        if (rows.isEmpty()) return@Canvas
        val slot = width / rows.size
        val bar = maxOf(1.5.dp.toPx(), minOf(slot * 0.4f, 11.dp.toPx()))
        rows.forEachIndexed { index, (name, a, b) ->
            val x = left + slot * index + slot / 2 - bar
            for ((k, v) in listOf(a, b).withIndex()) {
                val bh = h * minOf(v, ChartMath.CN0_MAX) / ChartMath.CN0_MAX
                drawRect(if (k == 0) baseColor else otherColor, Offset(x + bar * k, top + h - bh), Size(bar, bh))
            }
            val px = x + bar
            val py = top + h + 4.dp.toPx()
            rotate(90f, Offset(px, py)) {
                label(tm, name, px, py, style, 0f, 0.5f)
            }
        }
    }
}

private val SERIES_COLORS =listOf(Color(0xFF2980B9), Color(0xFF27AE60), Color(0xFFE67E22), Color(0xFF8E44AD))

/**
 * 複数系列の時系列グラフ（Python 版 LineChart / ModeChart）。
 * window [秒] があれば右端を現在にする。categories があれば縦軸に実際に現れた測位状態だけを並べる。
 */
@Composable
fun TimeChart(
    title: String,
    yTitle: String,
    data: Map<String, List<ChartPoint>>,
    window: Double?,
    modifier: Modifier = Modifier,
    yFloor: Double? = null,
    integer: Boolean = false,
    fixModes: Boolean = false,
    height: Dp = 190.dp,
) {
    val tm = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurface
    val small = TextStyle(fontSize = 9.sp, color = textColor)
    val titleStyle = TextStyle(fontSize = 12.sp, color = textColor, fontWeight = FontWeight.Bold)
    val filled = data.filter { it.value.isNotEmpty() }.toSortedMap()
    Canvas(modifier.fillMaxWidth().height(height)) {
        label(tm, title, size.width / 2, 0f, titleStyle, 0.5f, 0f)
        val left = 46.dp.toPx()
        val top = 34.dp.toPx()
        val right = 10.dp.toPx()
        val bottom = 30.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        drawRect(GRID, Offset(left, top), Size(w, h), style = Stroke(1f))
        if (filled.isEmpty()) return@Canvas

        // 横軸
        val span = if (window != null) window / 60 else maxOf(1.0, filled.values.maxOf { it.last().x })
        val (unit, scale) = ChartMath.timeUnit(span)
        val xStep = ChartMath.niceStep(span * scale, ChartMath.X_TICKS)
        val (x0, x1) = if (window != null) -span * scale to 0.0 else 0.0 to ceil(span * scale / xStep) * xStep
        val xTitle = if (window != null) "現在からの時間 [$unit]" else "経過時間 [$unit]"

        // 縦軸
        val ranks = if (fixModes) filled.values.flatten().map { it.y.toInt() }.distinct().sorted() else emptyList()
        val series: Map<String, List<ChartPoint>> = if (fixModes) {
            val index = ranks.withIndex().associate { it.value to it.index.toDouble() }
            filled.mapValues { (_, v) -> v.map { ChartPoint(it.x, index.getValue(it.y.toInt())) } }
        } else filled
        val (y0, y1, yStep) = if (fixModes) Triple(-0.5, ranks.size - 0.5, 1.0)
        else ChartMath.fitY(series.values.flatten().map { it.y }, yFloor, integer)

        fun px(x: Double) = (left + (x - x0) / (x1 - x0) * w).toFloat()
        fun py(y: Double) = (top + h - (y - y0) / (y1 - y0) * h).toFloat()

        val xDigits = ChartMath.labelDigits(xStep)
        var t = ceil(x0 / xStep - 1e-9) * xStep
        while (t <= x1 + 1e-9) {
            val x = px(t)
            drawLine(GRID.copy(alpha = 0.5f), Offset(x, top), Offset(x, top + h), 1f)
            label(tm, "%.${xDigits}f".format(t), x, top + h + 2.dp.toPx(), small, 0.5f, 0f)
            t += xStep
        }
        label(tm, xTitle, left + w / 2, size.height, small, 0.5f, 1f)
        if (fixModes) {
            ranks.forEachIndexed { i, rank ->
                val y = py(i.toDouble())
                drawLine(GRID.copy(alpha = 0.5f), Offset(left, y), Offset(left + w, y), 1f)
                label(tm, Ubx.FIX_LABELS[rank], left - 3.dp.toPx(), y, small, 1f, 0.5f)
            }
        } else {
            val yDigits = ChartMath.labelDigits(yStep)
            var v = floor(y0 / yStep + 1e-9) * yStep
            while (v <= y1 + 1e-9) {
                val y = py(v)
                drawLine(GRID.copy(alpha = 0.5f), Offset(left, y), Offset(left + w, y), 1f)
                label(tm, "%.${yDigits}f".format(v), left - 3.dp.toPx(), y, small, 1f, 0.5f)
                v += yStep
            }
            label(tm, yTitle, 2.dp.toPx(), top - 2.dp.toPx(), small, 0f, 1f)
        }

        clipRect(left, top, left + w, top + h) {
            series.entries.forEachIndexed { i, (_, points) ->
                val color = SERIES_COLORS[i % SERIES_COLORS.size]
                val pts = ChartMath.decimate(points)
                if (pts.size == 1) {
                    drawCircle(color, 2.dp.toPx(), Offset(px(pts[0].x * scale), py(pts[0].y)))
                } else {
                    val path = Path()
                    pts.forEachIndexed { j, p ->
                        val x = px(p.x * scale)
                        val y = py(p.y)
                        if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(1.5.dp.toPx()))
                }
            }
        }

        // 凡例（2 系列以上）
        if (series.size > 1) {
            var lx = left + w
            series.keys.reversed().forEachIndexed { revIndex, name ->
                val i = series.size - 1 - revIndex
                val layout = tm.measure(name, small)
                lx -= layout.size.width
                drawText(layout, topLeft = Offset(lx, top - layout.size.height - 2.dp.toPx()))
                lx -= 12.dp.toPx()
                drawLine(
                    SERIES_COLORS[i % SERIES_COLORS.size], Offset(lx, top - layout.size.height / 2 - 2.dp.toPx()),
                    Offset(lx + 9.dp.toPx(), top - layout.size.height / 2 - 2.dp.toPx()), 2.dp.toPx(),
                )
                lx -= 6.dp.toPx()
            }
        }
    }
}
