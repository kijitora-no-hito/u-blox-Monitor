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
import androidx.compose.ui.draw.clipToBounds
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
import com.google.maps.android.compose.MapType as GoogleMapType
import jp.kjtr.ubloxmonitor.BuildConfig
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ubloxmonitor.data.source.Channel
import jp.kjtr.ublox.core.ChartMath
import jp.kjtr.ubloxmonitor.domain.Display
import jp.kjtr.ubloxmonitor.domain.MapMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay

/**
 * 地図の種類。地理院タイル（API キー不要。出典表示が利用条件）と、Google の地図（0.5.0。MAPS_API_KEY を入れてビルドしたときだけ）。
 * 地理院のタイルは z18 まで（RTK の cm 単位には粗い）、Google は z21 前後まで寄れる。
 */
enum class MapType(val labelRes: Int, val source: XYTileSource?, val google: GoogleMapType?) {
    STD(R.string.map_type_std, gsi("gsi_std", "std", ".png"), null),
    PALE(R.string.map_type_pale, gsi("gsi_pale", "pale", ".png"), null),
    PHOTO(R.string.map_type_photo, gsi("gsi_seamlessphoto", "seamlessphoto", ".jpg"), null),
    G_NORMAL(R.string.map_type_g_normal, null, GoogleMapType.NORMAL),
    G_SATELLITE(R.string.map_type_g_satellite, null, GoogleMapType.SATELLITE),
    G_HYBRID(R.string.map_type_g_hybrid, null, GoogleMapType.HYBRID),
    ;

    val isGoogle: Boolean get() = google != null

    companion object {
        /** 選べる種類（キーが無いビルドは地理院だけ） */
        fun available(): List<MapType> = entries.filter { !it.isGoogle || BuildConfig.HAS_MAPS_KEY }

        /** 保存した名前から（無い・選べない種類は標準地図） */
        fun fromName(name: String): MapType = entries.firstOrNull { it.name == name }?.takeIf { it in available() } ?: STD
    }
}

private fun gsi(name: String, path: String, ext: String) =
    XYTileSource(name, 2, MAX_TILE_ZOOM, 256, ext, arrayOf("https://cyberjapandata.gsi.go.jp/xyz/$path/"), "出典：国土地理院")

/**
 * 地図に重ねるデータ源 1 つ分。[track] = 軌跡（間引き・1e-7° に丸め）、[recent] = 直近の測位（丸めない。点で出してばらつきを見る）、
 * [fixColor] = 測位状態の色、[hacc] = 水平精度 [m]（円で出す）、[scatter] = 直近の点の DRMS と最大 [m]。
 */
class MapLayer(
    val channel: Channel,
    val track: List<Pair<Double, Double>>,
    val recent: List<Pair<Double, Double>>,
    val fixColor: Int,
    val hacc: Double?,
    val scatter: Pair<Double, Double>?,
) {
    val last: Pair<Double, Double>? get() = recent.lastOrNull() ?: track.lastOrNull()
}

/**
 * 地理院と Google の地図で共有する表示位置（切り替えても同じ場所・縮尺のまま続ける）。[zoom] は Google の単位（タイル 256 dp）。
 * [centered] = 最初の 1 点で寄せた（どちらの地図で寄せても、切り替えたあとは寄せ直さない）。
 * State にはしない（地図を動かすたびに画面を作り直さないため）。MonitorRepository が 1 つ持つ（タブを離れても覚えておく）。
 */
class MapCamera {
    var lat = 36.0
    var lon = 138.0
    var zoom = 4.0
    var centered = false
}

/** データ源ごとの現在位置の印（中は測位状態の色、縁はデータ源の色）・水平精度の円・直近の点。 */
private class PositionOverlay(private val density: Float) : Overlay() {
    var layers: List<MapLayer> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        for (l in layers) {
            val last = l.last ?: continue
            val ring = l.channel.trackColor.toInt()
            // 水平精度の円（m を画面の px に）
            proj.toPixels(GeoPoint(last.first, last.second), p)
            val acc = l.hacc
            if (acc != null && acc > 0) {
                val r = proj.metersToPixels(acc.toFloat(), last.first, mapView.zoomLevelDouble)
                if (r >= 2 * density) {
                    paint.style = Paint.Style.FILL
                    paint.color = (ring and 0x00FFFFFF) or 0x20000000
                    canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.5f * density
                    paint.color = ring
                    canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, paint)
                }
            }
            // 直近の点
            paint.style = Paint.Style.FILL
            paint.color = ring
            for ((lat, lon) in l.recent) {
                proj.toPixels(GeoPoint(lat, lon), p)
                canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 2.5f * density, paint)
            }
            // 現在位置
            proj.toPixels(GeoPoint(last.first, last.second), p)
            paint.style = Paint.Style.FILL
            paint.color = l.fixColor
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 7 * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.color = ring
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 7 * density, paint)
        }
    }
}

@Composable
fun MapScreen(repo: MonitorRepository, padding: PaddingValues) {
    val views by repo.views.collectAsState()
    val selected by repo.selected.collectAsState()
    // 表示中かどうかは設定に保存する（0.5.0。タブを切り替えても・起動し直しても［地図を閉じる］まで表示のまま）
    var shown by remember { mutableStateOf(repo.prefs.mapShown) }
    var follow by rememberSaveable { mutableStateOf(repo.prefs.mapFollow) }
    val types = remember { MapType.available() }
    var mapType by remember { mutableStateOf(MapType.fromName(repo.prefs.mapType)) }
    val hidden = remember { mutableStateMapOf<Channel, Boolean>() }
    val camera = repo.mapCamera

    // データ源ごとの軌跡（多いときは間引く）・直近の点・精度
    var layers by remember { mutableStateOf<Map<Channel, MapLayer>>(emptyMap()) }
    LaunchedEffect(views) {
        layers = withContext(Dispatchers.Default) {
            views.mapValues { (ch, v) ->
                val history = v.snapshot.pvtHistory
                val recent = MapMath.recentPoints(history)
                MapLayer(
                    ch, ChartMath.trackPoints(history), recent,
                    argbInt(Display.fixColor(v.snapshot.pvt?.fixRank ?: 0)), v.snapshot.pvt?.hacc, MapMath.scatter(recent),
                )
            }
        }
    }
    val main = views[selected]?.channel ?: views.keys.firstOrNull()
    val mainLayer = main?.let { layers[it] }

    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shown = !shown; repo.prefs.mapShown = shown }) {
                    Text(
                        stringResource(
                            when {
                                shown -> R.string.map_close
                                mapType.isGoogle -> R.string.map_show_google
                                else -> R.string.map_show
                            },
                        ),
                    )
                }
                Checkbox(follow, { follow = it; repo.prefs.mapFollow = it })
                Text(stringResource(R.string.map_follow))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                types.forEach { t ->
                    FilterChip(mapType == t, { mapType = t; repo.prefs.mapType = t.name }, label = { Text(stringResource(t.labelRes)) })
                }
                views.keys.forEach { ch ->
                    FilterChip(
                        hidden[ch] != true, { hidden[ch] = hidden[ch] != true },
                        label = { Text(stringResource(ch.labelRes), color = argb(ch.trackColor)) },
                    )
                }
            }
            mainLayer?.last?.let { (lat, lon) ->
                Text(stringResource(R.string.map_position, lat, lon, mainLayer.track.size), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        R.string.map_accuracy, MapMath.formatDistance(mainLayer.hacc), mainLayer.recent.size,
                        MapMath.formatDistance(mainLayer.scatter?.first), MapMath.formatDistance(mainLayer.scatter?.second),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // MapView（Android の View）がチップの行の上にはみ出して描かれないよう、地図の領域で切る
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            if (!shown) {
                // 通信の説明は表示する前だけ（地理院・Google のどちらに接続するか）
                val notice = stringResource(R.string.map_notice) +
                    if (BuildConfig.HAS_MAPS_KEY) "\n\n" + stringResource(R.string.map_notice_google) else ""
                Text(notice, Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
            } else {
                val visible = layers.filterKeys { hidden[it] != true }.values.toList()
                val google = mapType.google
                if (google != null) {
                    // Google は自分のロゴ・著作権表示を地図の中に出す（地理院の出典は出さない）
                    GoogleMapPane(visible, main, google, follow, camera)
                } else {
                    OsmMap(visible, main, mapType.source!!, follow, camera)
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
}

private fun argbInt(v: Long) = v.toInt()

/** 追従の対象（選んでいるデータ源、無ければ位置のある最初のもの）の現在位置。 */
internal fun followTarget(layers: List<MapLayer>, main: Channel?): Pair<Double, Double>? =
    layers.firstOrNull { it.channel == main }?.last ?: layers.firstNotNullOfOrNull { it.last }

@Composable
private fun OsmMap(
    layers: List<MapLayer>,
    main: Channel?,
    source: XYTileSource,
    follow: Boolean,
    camera: MapCamera,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val positions = remember { PositionOverlay(density) }
    val lines = remember { HashMap<Channel, Polyline>() }
    val mapView = remember {
        MapView(context).apply {
            // 既定（OSM）のタイルを取りに行かないよう、最初から地理院タイルにする
            setTileSource(source)
            setMultiTouchControls(true)
            // DPI に合わせた拡大はしない（高密度の画面では z17 でも z18 のタイルを 2.6 倍に引き伸ばし、文字が巨大になる）
            isTilesScaledToDpi = false
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            // タイルは z18 まで。それより先はオーバーズーム
            maxZoomLevel = MAX_VIEW_ZOOM
            isVerticalMapRepetitionEnabled = false
            // 前に見ていた場所・縮尺から（Google の地図から切り替えたときも同じ縮尺に換算）
            controller.setZoom(MapMath.googleToOsmZoom(camera.zoom, density).coerceIn(3.0, MAX_VIEW_ZOOM))
            controller.setCenter(GeoPoint(camera.lat, camera.lon))
            overlays.add(positions)
            // 縮尺（左上）
            overlays.add(
                ScaleBarOverlay(this).apply {
                    setScaleBarOffset((10 * density).toInt(), (10 * density).toInt())
                    setTextSize(12 * density)
                    setLineWidth(2 * density)
                },
            )
            val view = this
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean = save()
                override fun onZoom(event: ZoomEvent?): Boolean = save()

                private fun save(): Boolean {
                    val c = view.mapCenter
                    camera.lat = c.latitude
                    camera.lon = c.longitude
                    camera.zoom = MapMath.osmToGoogleZoom(view.zoomLevelDouble, density)
                    return false
                }
            })
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
    LaunchedEffect(source) { mapView.setTileSource(source) }
    LaunchedEffect(layers, main, follow) {
        // 軌跡
        val channels = layers.map { it.channel }.toSet()
        for (ch in lines.keys.toList()) {
            if (ch !in channels) mapView.overlays.remove(lines.remove(ch))
        }
        for (l in layers) {
            val line = lines.getOrPut(l.channel) {
                Polyline(mapView).also {
                    it.outlinePaint.color = l.channel.trackColor.toInt()
                    it.outlinePaint.strokeWidth = 3 * density
                    it.isGeodesic = false
                    mapView.overlays.add(0, it)
                }
            }
            line.setPoints(l.track.map { GeoPoint(it.first, it.second) })
        }
        positions.layers = layers
        // 追従（最初の 1 点で寄せ、あとは画面の外に出たら移動）
        val last = followTarget(layers, main)
        if (last != null) {
            val g = GeoPoint(last.first, last.second)
            if (!camera.centered) {
                camera.centered = true
                mapView.controller.setZoom(INITIAL_ZOOM)
                mapView.controller.setCenter(g)
            } else if (follow && mapView.width > 0 && !mapView.boundingBox.contains(g)) {
                // 中心だけ動かす（ズームは利用者が選んだまま）
                mapView.controller.animateTo(g, mapView.zoomLevelDouble, null)
            }
        }
        mapView.invalidate()
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().clipToBounds())
}

/** 最初に現在位置へ寄せるときのズーム（地理院） */
private const val INITIAL_ZOOM = 17.0
/** 地理院タイルの最大ズーム（標準・淡色・写真とも） */
private const val MAX_TILE_ZOOM = 18
/** 表示の最大ズーム（タイルより先はオーバーズーム。0.5.0 で 20 → 22。RTK のばらつきを見るため） */
private const val MAX_VIEW_ZOOM = 22.0
