package jp.kjtr.ubloxmonitor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType as GoogleMapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.widgets.ScaleBar
import jp.kjtr.ubloxmonitor.data.source.Channel

/**
 * Google の地図（0.5.0）。地理院の地図（OsmMap）と同じものを重ねる: 軌跡・水平精度の円（m）・直近の点・現在位置の印・追従・縮尺。
 * ズームは Google の上限まで（RTK の cm 単位のばらつきを見るため）。表示位置は [camera] で地理院の地図と共有する。
 */
@Composable
fun GoogleMapPane(
    layers: List<MapLayer>,
    main: Channel?,
    type: GoogleMapType,
    follow: Boolean,
    camera: MapCamera,
) {
    val density = LocalDensity.current.density
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(camera.lat, camera.lon), camera.zoom.toFloat())
    }
    // 表示位置を覚える（地理院に切り替えたとき・タブに戻ったときに同じ場所から）
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.position }.collect {
            camera.lat = it.target.latitude
            camera.lon = it.target.longitude
            camera.zoom = it.zoom.toDouble()
        }
    }
    // 追従（最初の 1 点で寄せ、あとは画面の外に出たら中心だけ動かす。ズームは利用者が選んだまま）
    val target = followTarget(layers, main)
    LaunchedEffect(target, follow) {
        val t = target ?: return@LaunchedEffect
        val g = LatLng(t.first, t.second)
        if (!camera.centered) {
            camera.centered = true
            cameraState.move(CameraUpdateFactory.newLatLngZoom(g, INITIAL_ZOOM_GOOGLE))
        } else if (follow && !cameraState.isMoving) {
            val bounds = cameraState.projection?.visibleRegion?.latLngBounds
            if (bounds != null && !bounds.contains(g)) cameraState.animate(CameraUpdateFactory.newLatLng(g), 500)
        }
    }
    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            properties = MapProperties(mapType = type, isBuildingEnabled = false, isIndoorEnabled = false),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false, mapToolbarEnabled = false, tiltGesturesEnabled = false,
                myLocationButtonEnabled = false, indoorLevelPickerEnabled = false,
            ),
        ) {
            // アイコンは地図ができてから作る（BitmapDescriptorFactory は Maps の初期化が要る）
            val icons = remember { HashMap<Long, BitmapDescriptor>() }
            for (l in layers) key(l.channel) {
                val ring = l.channel.trackColor.toInt()
                val color = Color(ring)
                if (l.track.size >= 2) {
                    Polyline(points = l.track.map { LatLng(it.first, it.second) }, color = color, width = 3 * density)
                }
                val last = l.last
                val acc = l.hacc
                if (last != null && acc != null && acc > 0) {
                    Circle(
                        center = LatLng(last.first, last.second), radius = acc,
                        strokeColor = color, strokeWidth = 1.5f * density, fillColor = color.copy(alpha = 0.12f),
                    )
                }
                // 直近の点（RTK のばらつきを見る）
                val dot = icons.getOrPut(dotKey(ring)) { dotIcon(ring, 2.5f * density) }
                l.recent.forEachIndexed { i, p ->
                    key(i) {
                        val st = remember { MarkerState(LatLng(p.first, p.second)) }
                        st.position = LatLng(p.first, p.second)
                        Marker(state = st, icon = dot, anchor = Offset(0.5f, 0.5f), flat = true, zIndex = 1f, onClick = { true })
                    }
                }
                // 現在位置（中は測位状態の色、縁はデータ源の色）
                if (last != null) {
                    val mark = icons.getOrPut(markKey(l.fixColor, ring)) { markIcon(l.fixColor, ring, density) }
                    val st = remember { MarkerState(LatLng(last.first, last.second)) }
                    st.position = LatLng(last.first, last.second)
                    Marker(state = st, icon = mark, anchor = Offset(0.5f, 0.5f), flat = true, zIndex = 2f, onClick = { true })
                }
            }
        }
        // 縮尺（左上）
        ScaleBar(modifier = Modifier.align(Alignment.TopStart).padding(8.dp), cameraPositionState = cameraState)
    }
}

private fun dotKey(color: Int) = color.toLong() and 0xFFFFFFFFL
private fun markKey(fill: Int, ring: Int) = (fill.toLong() shl 32) or (ring.toLong() and 0xFFFFFFFFL) or (1L shl 63)

private fun dotIcon(color: Int, radius: Float): BitmapDescriptor {
    val size = (radius * 2 + 2).toInt().coerceAtLeast(3)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    Canvas(bmp).drawCircle(size / 2f, size / 2f, radius, paint)
    return BitmapDescriptorFactory.fromBitmap(bmp)
}

/** 地理院の地図の印と同じ形（半径 7 dp、縁 2.5 dp）。 */
private fun markIcon(fill: Int, ring: Int, density: Float): BitmapDescriptor {
    val r = 7 * density
    val stroke = 2.5f * density
    val size = (2 * (r + stroke)).toInt() + 2
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.style = Paint.Style.FILL
    paint.color = fill
    c.drawCircle(size / 2f, size / 2f, r, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = stroke
    paint.color = ring
    c.drawCircle(size / 2f, size / 2f, r, paint)
    return BitmapDescriptorFactory.fromBitmap(bmp)
}

/** 最初に現在位置へ寄せるときのズーム（Google。地理院の z17 より寄せる） */
private const val INITIAL_ZOOM_GOOGLE = 18f
