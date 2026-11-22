package jp.kjtr.ubloxmonitor.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ubloxmonitor.data.source.Channel
import jp.kjtr.ubloxmonitor.domain.ChartMath
import jp.kjtr.ubloxmonitor.domain.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

/** 地理院タイル（API キー不要。出典表示が利用条件）。 */
enum class MapType(val labelRes: Int, val source: XYTileSource) {
    STD(R.string.map_type_std, XYTileSource("gsi_std", 2, 18, 256, ".png", arrayOf("https://cyberjapandata.gsi.go.jp/xyz/std/"), "出典：国土地理院")),
    PALE(R.string.map_type_pale, XYTileSource("gsi_pale", 2, 18, 256, ".png", arrayOf("https://cyberjapandata.gsi.go.jp/xyz/pale/"), "出典：国土地理院")),
    PHOTO(R.string.map_type_photo, XYTileSource("gsi_seamlessphoto", 2, 18, 256, ".jpg", arrayOf("https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto/"), "出典：国土地理院")),
}

/** データ源ごとの現在位置の印（中は測位状態の色、縁はデータ源の色）。 */
private class PositionOverlay(private val density: Float) : Overlay() {
    class Mark(val point: GeoPoint, val fill: Int, val ring: Int)

    var marks: List<Mark> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        for (m in marks) {
            mapView.projection.toPixels(m.point, p)
            paint.style = Paint.Style.FILL
            paint.color = m.fill
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 7 * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.color = m.ring
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 7 * density, paint)
        }
    }
}

@Composable
fun MapScreen(repo: MonitorRepository, padding: PaddingValues) {
    val views by repo.views.collectAsState()
    val selected by repo.selected.collectAsState()
    var shown by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(repo.prefs.mapFollow) }
    var mapType by rememberSaveable { mutableStateOf(runCatching { MapType.valueOf(repo.prefs.mapType) }.getOrDefault(MapType.STD)) }
    val hidden = remember { mutableStateMapOf<Channel, Boolean>() }

    // データ源ごとの軌跡（多いときは間引く）
    var tracks by remember { mutableStateOf<Map<Channel, List<Pair<Double, Double>>>>(emptyMap()) }
    LaunchedEffect(views) {
        tracks = withContext(Dispatchers.Default) { views.mapValues { ChartMath.trackPoints(it.value.snapshot.pvtHistory) } }
    }
    val main = views[selected]?.channel ?: views.keys.firstOrNull()
    val mainTrack = main?.let { tracks[it] }.orEmpty()

    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shown = !shown }) {
                    Text(stringResource(if (shown) R.string.map_close else R.string.map_show))
                }
                Checkbox(follow, { follow = it; repo.prefs.mapFollow = it })
                Text(stringResource(R.string.map_follow))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MapType.entries.forEach { t ->
                    FilterChip(mapType == t, { mapType = t; repo.prefs.mapType = t.name }, label = { Text(stringResource(t.labelRes)) })
                }
                views.keys.forEach { ch ->
                    FilterChip(
                        hidden[ch] != true, { hidden[ch] = hidden[ch] != true },
                        label = { Text(stringResource(ch.labelRes), color = argb(ch.trackColor)) },
                    )
                }
            }
            mainTrack.lastOrNull()?.let { (lat, lon) ->
                Text(stringResource(R.string.map_position, lat, lon, mainTrack.size), style = MaterialTheme.typography.bodySmall)
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (!shown) {
                Text(
                    stringResource(R.string.map_notice), Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                val colors = views.mapValues { argbInt(Display.fixColor(it.value.snapshot.pvt?.fixRank ?: 0)) }
                OsmMap(
                    tracks.filterKeys { hidden[it] != true }, colors, main, mapType, follow,
                )
                Text(
                    stringResource(R.string.attribution),
                    Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 4.dp, vertical = 1.dp),
                    color = Color.Black, fontSize = 11.sp,
                )
            }
        }
    }
}

private fun argbInt(v: Long) = v.toInt()

@Composable
private fun OsmMap(
    tracks: Map<Channel, List<Pair<Double, Double>>>,
    fixColors: Map<Channel, Int>,
    main: Channel?,
    mapType: MapType,
    follow: Boolean,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val positions = remember { PositionOverlay(density) }
    val lines = remember { HashMap<Channel, Polyline>() }
    var centered by remember { mutableStateOf(false) }
    val mapView = remember {
        MapView(context).apply {
            // 既定（OSM）のタイルを取りに行かないよう、最初から地理院タイルにする
            setTileSource(mapType.source)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            isVerticalMapRepetitionEnabled = false
            controller.setZoom(5.0)
            controller.setCenter(GeoPoint(36.0, 138.0))
            overlays.add(positions)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mapView) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause()
            mapView.onDetach()
        }
    }
    LaunchedEffect(mapType) { mapView.setTileSource(mapType.source) }
    LaunchedEffect(tracks, fixColors, main, follow) {
        // 軌跡
        for (ch in lines.keys.toList()) {
            if (ch !in tracks) mapView.overlays.remove(lines.remove(ch))
        }
        for ((ch, pts) in tracks) {
            val line = lines.getOrPut(ch) {
                Polyline(mapView).also {
                    it.outlinePaint.color = ch.trackColor.toInt()
                    it.outlinePaint.strokeWidth = 3 * density
                    it.isGeodesic = false
                    mapView.overlays.add(0, it)
                }
            }
            line.setPoints(pts.map { GeoPoint(it.first, it.second) })
        }
        positions.marks = tracks.mapNotNull { (ch, pts) ->
            pts.lastOrNull()?.let { PositionOverlay.Mark(GeoPoint(it.first, it.second), fixColors[ch] ?: 0xFF7F8C8D.toInt(), ch.trackColor.toInt()) }
        }
        // 追従（最初の 1 点で寄せ、あとは画面の外に出たら移動）
        val last = main?.let { tracks[it]?.lastOrNull() } ?: tracks.values.firstNotNullOfOrNull { it.lastOrNull() }
        if (last != null) {
            val g = GeoPoint(last.first, last.second)
            if (!centered) {
                centered = true
                mapView.controller.setZoom(17.0)
                mapView.controller.setCenter(g)
            } else if (follow && mapView.width > 0 && !mapView.boundingBox.contains(g)) {
                mapView.controller.animateTo(g)
            }
        }
        mapView.invalidate()
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth().fillMaxSize())
}
