package jp.kjtr.ubloxmonitor.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import jp.kjtr.ubloxmonitor.domain.Pvt

/**
 * u-blox の NAV-PVT を Android の位置情報（テスト用プロバイダ）として流す。
 * 開発者オプションの「仮の現在地情報アプリ」に選ばれていないと SecurityException になる。
 * 流すのは GPS_PROVIDER と NETWORK_PROVIDER（後者は失敗しても無視する）。
 */
class MockLocation(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val lock = Any()
    private var active = emptyList<String>()

    val isActive: Boolean get() = synchronized(lock) { active.isNotEmpty() }

    /** 送った点の数 */
    @Volatile
    var published = 0L
        private set

    /** テスト用プロバイダを用意する。許可が無ければ SecurityException。 */
    fun enable() = synchronized(lock) {
        if (active.isNotEmpty()) return
        val added = ArrayList<String>()
        for (provider in PROVIDERS) {
            try {
                add(provider)
                lm.setTestProviderEnabled(provider, true)
                added.add(provider)
            } catch (e: SecurityException) {
                added.forEach { remove(it) }
                throw e
            } catch (_: Exception) {
                // NETWORK_PROVIDER が無い端末などは GPS だけで続ける
                if (provider == LocationManager.GPS_PROVIDER) {
                    added.forEach { remove(it) }
                    throw SecurityException("GPS のテスト用プロバイダを作れません")
                }
            }
        }
        active = added
        published = 0
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    private fun add(provider: String) {
        try {
            lm.removeTestProvider(provider)   // 前回の残り
        } catch (_: Exception) {
        }
        val network = provider == LocationManager.NETWORK_PROVIDER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val props = ProviderProperties.Builder()
                .setHasNetworkRequirement(network)
                .setHasSatelliteRequirement(!network)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_HIGH)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            lm.addTestProvider(provider, props)
        } else {
            lm.addTestProvider(
                provider, network, !network, false, false, true, true, true,
                Criteria.POWER_HIGH, Criteria.ACCURACY_FINE,
            )
        }
    }

    private fun remove(provider: String) {
        try {
            lm.setTestProviderEnabled(provider, false)
        } catch (_: Exception) {
        }
        try {
            lm.removeTestProvider(provider)
        } catch (_: Exception) {
        }
    }

    /** 片付ける（OFF・切断時は必ず呼ぶ）。 */
    fun disable() = synchronized(lock) {
        active.forEach { remove(it) }
        active = emptyList()
    }

    /** 1 点流す。位置が無い（fix が無い）ときは流さない。 */
    fun publish(pvt: Pvt) {
        val lat = pvt.latitude ?: return
        val lon = pvt.longitude ?: return
        val providers = synchronized(lock) { active }
        if (providers.isEmpty()) return
        for (provider in providers) {
            val loc = Location(provider).apply {
                latitude = lat
                longitude = lon
                pvt.height?.let { altitude = it }   // Android の altitude は WGS84 楕円体高
                accuracy = (pvt.hacc ?: 10.0).toFloat()
                pvt.speed?.let { speed = it.toFloat() }
                pvt.heading?.let { bearing = (((it % 360) + 360) % 360).toFloat() }
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    pvt.vacc?.let { verticalAccuracyMeters = it.toFloat() }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    pvt.heightMsl?.let { mslAltitudeMeters = it }
                    pvt.vacc?.let { mslAltitudeAccuracyMeters = it.toFloat() }
                }
            }
            try {
                lm.setTestProviderLocation(provider, loc)
            } catch (_: Exception) {
                // 許可が外された・プロバイダが消えたなど。次の点で再び試す
            }
        }
        published++
    }

    companion object {
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
