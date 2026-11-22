package jp.kjtr.ublox.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import jp.kjtr.ublox.android.R
import jp.kjtr.ublox.core.Pipeline
import jp.kjtr.ublox.core.PhoneClock
import jp.kjtr.ublox.core.PhoneFix
import jp.kjtr.ublox.core.PhoneMeas
import jp.kjtr.ublox.core.PhoneRawx
import jp.kjtr.ublox.core.PhoneSat
import jp.kjtr.ublox.core.PhoneUbx
import java.util.concurrent.Executor

/**
 * スマホ内蔵 GNSS。Location / GnssStatus / GnssMeasurementsEvent の値を domain のデータクラスに詰め替え、
 * PhoneUbx / PhoneRawx で NAV-PVT・NAV-SAT・RXM-RAWX にして Pipeline.feed に流す（記録も同じバイト列）。
 * コールバックはすべて専用の HandlerThread で受けるので、feed は 1 本のスレッドから呼ばれる。
 * 呼ぶ前に ACCESS_FINE_LOCATION が許可されていること（無ければ error にして止まる）。
 */
class InternalGnssSource(
    private val context: Context,
    private val pipeline: Pipeline,
    override val title: String,
) : Source {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val rawx = PhoneRawx()

    @Volatile
    private var running = false

    @Volatile
    override var error: String? = null
        private set

    override val canSend = false

    override val isAlive: Boolean get() = running

    // ---- 状態（画面の表示用） ----
    @Volatile
    private var usedCount = 0
    @Volatile
    private var leapSecond: Int? = null
    @Volatile
    private var lastSatSent = 0L
    @Volatile
    private var lastMockAt = 0L
    @Volatile
    private var lastRawxAt = 0L
    /** 直近の GnssStatus（NAV-SAT は NAV-PVT / RXM-RAWX の epoch に合わせて出す） */
    @Volatile
    private var latestSats: List<PhoneSat>? = null
    @Volatile
    private var lastFixAt = 0L
    @Volatile
    private var providerEnabled = true
    /** 生の測定値: null = まだ分からない、true = 対応、false = 非対応 */
    @Volatile
    private var measSupported: Boolean? = null
    @Volatile
    private var measStatus: Int? = null
    @Volatile
    private var measEvents = 0
    @Volatile
    private var rawxSent = 0

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)

        // API 29 以前はインタフェースの既定実装が無いので、3 つとも実装しておく（無いと AbstractMethodError）
        override fun onProviderEnabled(provider: String) {
            providerEnabled = true
        }

        override fun onProviderDisabled(provider: String) {
            providerEnabled = false
        }

        @Deprecated("API 29 以前のため")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) = onStatus(status)
    }

    private val measCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) = onMeasurements(eventArgs)

        @Deprecated("API 31 からは GnssCapabilities で判定する")
        override fun onStatusChanged(status: Int) {
            measStatus = status
            if (status == MEAS_NOT_SUPPORTED) measSupported = false
            if (status == MEAS_READY && measSupported == null) measSupported = true
        }
    }

    @SuppressLint("MissingPermission")   // 下で checkSelfPermission してから登録する
    override fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            error = context.getString(R.string.ublox_internal_no_permission)
            return
        }
        val t = HandlerThread("internal-gnss").apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        running = true
        try {
            providerEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0f, locationListener, t.looper)
            lm.registerGnssStatusCallback(statusCallback, h)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                measSupported = lm.gnssCapabilities.hasMeasurements()
                // 完全な追跡（開発者オプションの「GNSS 測定の完全な追跡を強制」が要らない）
                val request = GnssMeasurementRequest.Builder().setFullTracking(true).build()
                lm.registerGnssMeasurementsCallback(request, Executor { h.post(it) }, measCallback)
            } else {
                lm.registerGnssMeasurementsCallback(measCallback, h)
            }
            pipeline.log(context.getString(R.string.ublox_internal_started))
        } catch (e: Exception) {
            // SecurityException（権限が途中で取り消された）など
            error = e.message ?: e.javaClass.simpleName
            stop()
        }
    }

    override fun stop() {
        if (!running && thread == null) return
        running = false
        try {
            lm.removeUpdates(locationListener)
            lm.unregisterGnssStatusCallback(statusCallback)
            lm.unregisterGnssMeasurementsCallback(measCallback)
        } catch (_: Exception) {
        }
        thread?.quitSafely()
        thread?.join(2000)
        thread = null
        handler = null
    }

    @Suppress("DEPRECATION")   // isFromMockProvider は API 31 からの非推奨（それ以前の端末用）
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider

    private fun onLocation(location: Location) {
        if (!running) return
        // 擬似ロケーション（自分の u-blox の位置など）は捨てる（ループ防止）
        if (isMock(location)) {
            lastMockAt = SystemClock.elapsedRealtime()
            return
        }
        lastFixAt = SystemClock.elapsedRealtime()
        val fix = PhoneFix(
            utcMillis = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            mslAltitude = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasMslAltitude())
                location.mslAltitudeMeters else null,
            accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            verticalAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy())
                location.verticalAccuracyMeters.toDouble() else null,
            speed = if (location.hasSpeed()) location.speed.toDouble() else null,
            speedAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy())
                location.speedAccuracyMetersPerSecond.toDouble() else null,
            bearing = if (location.hasBearing()) location.bearing.toDouble() else null,
            bearingAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy())
                location.bearingAccuracyDegrees.toDouble() else null,
        )
        val leap = leapSecond ?: PhoneUbx.DEFAULT_LEAP_SECONDS
        pipeline.feed(PhoneUbx.navPvt(fix, usedCount, leap))
        // NAV-SAT は同じ epoch（NAV-PVT と同じ iTOW）で続けて出す。端末の時計の iTOW だと u-center が
        // 別の epoch とみなし、衛星の履歴が 1 回おきに抜けた（Pixel 10 Pro の記録で約 1.6 秒先だった）
        sendSats(PhoneUbx.itow(fix.utcMillis, leap))
    }

    /** 直近の GnssStatus を、指定の epoch の NAV-SAT として出す。 */
    private fun sendSats(itow: Long) {
        val sats = latestSats ?: return
        lastSatSent = SystemClock.elapsedRealtime()
        pipeline.feed(PhoneUbx.navSat(itow, sats))
    }

    private fun onStatus(status: GnssStatus) {
        if (!running) return
        val sats = (0 until status.satelliteCount).map { i ->
            PhoneSat(
                constellation = status.getConstellationType(i),
                svid = status.getSvid(i),
                cn0 = status.getCn0DbHz(i).toDouble(),
                elevation = status.getElevationDegrees(i).toDouble(),
                azimuth = status.getAzimuthDegrees(i).toDouble(),
                usedInFix = status.usedInFix(i),
                hasEphemeris = status.hasEphemerisData(i),
                hasAlmanac = status.hasAlmanacData(i),
                carrierFrequencyHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && status.hasCarrierFrequencyHz(i))
                    status.getCarrierFrequencyHz(i).toDouble() else null,
            )
        }
        usedCount = PhoneUbx.usedCount(sats)
        latestSats = sats
        // NAV-SAT はふだん NAV-PVT（位置が無ければ RXM-RAWX）と同じ epoch で出す。
        // どちらも来ていないとき（測位前・生の測定値に非対応）だけ、端末の時計の iTOW で 1 Hz 程度に間引いて出す
        val now = SystemClock.elapsedRealtime()
        if (now - lastFixAt < EPOCH_HOLD_MS || now - lastRawxAt < EPOCH_HOLD_MS) return
        if (now - lastSatSent < SAT_INTERVAL_MS) return
        sendSats(PhoneUbx.itow(System.currentTimeMillis(), leapSecond ?: PhoneUbx.DEFAULT_LEAP_SECONDS))
    }

    private fun onMeasurements(event: GnssMeasurementsEvent) {
        if (!running) return
        measEvents++
        if (measSupported == null) measSupported = true
        val c = event.clock
        val clock = PhoneClock(
            timeNanos = c.timeNanos,
            fullBiasNanos = if (c.hasFullBiasNanos()) c.fullBiasNanos else null,
            biasNanos = if (c.hasBiasNanos()) c.biasNanos else 0.0,
            leapSecond = if (c.hasLeapSecond()) c.leapSecond else null,
            discontinuityCount = c.hardwareClockDiscontinuityCount,
        )
        clock.leapSecond?.let { leapSecond = it }
        val meas = event.measurements.map { m ->
            PhoneMeas(
                constellation = m.constellationType,
                svid = m.svid,
                state = m.state,
                receivedSvTimeNanos = m.receivedSvTimeNanos,
                receivedSvTimeUncertaintyNanos = m.receivedSvTimeUncertaintyNanos,
                cn0 = m.cn0DbHz,
                pseudorangeRate = m.pseudorangeRateMetersPerSecond,
                pseudorangeRateUncertainty = m.pseudorangeRateUncertaintyMetersPerSecond,
                adrState = m.accumulatedDeltaRangeState,
                adr = m.accumulatedDeltaRangeMeters,
                adrUncertainty = m.accumulatedDeltaRangeUncertaintyMeters,
                carrierFrequencyHz = if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else null,
                codeType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && m.hasCodeType()) m.codeType else null,
                timeOffsetNanos = m.timeOffsetNanos,
            )
        }
        val packet = rawx.encode(clock, meas) ?: return
        rawxSent++
        pipeline.feed(packet)
        val now = SystemClock.elapsedRealtime()
        lastRawxAt = now
        // 位置がまだ無いときは、NAV-SAT を RAWX と同じ epoch（rcvTow）で出す
        if (now - lastFixAt >= EPOCH_HOLD_MS) {
            val rcvTow = java.nio.ByteBuffer.wrap(packet, 6, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).double
            sendSats(Math.round(rcvTow * 1000.0))
        }
    }

    override val statusText: String
        get() {
            val lines = ArrayList<String>()
            val now = SystemClock.elapsedRealtime()
            if (!providerEnabled) lines.add(context.getString(R.string.ublox_internal_provider_off))
            if (now - lastMockAt < MOCK_HOLD_MS) lines.add(context.getString(R.string.ublox_internal_mock))
            else if (lastFixAt == 0L) lines.add(context.getString(R.string.ublox_internal_waiting_fix))
            val measText = when {
                measSupported == false || measStatus == MEAS_NOT_SUPPORTED -> context.getString(R.string.ublox_internal_meas_unsupported)
                measStatus == MEAS_NOT_ALLOWED -> context.getString(R.string.ublox_internal_meas_not_allowed)
                measEvents == 0 -> context.getString(R.string.ublox_internal_meas_waiting)
                rawxSent == 0 -> context.getString(R.string.ublox_internal_meas_no_clock, rawx.skipReason ?: "")
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> context.getString(R.string.ublox_internal_meas_full, rawxSent)
                else -> context.getString(R.string.ublox_internal_meas_ok, rawxSent)
            }
            lines.add(measText)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && measSupported != false) {
                lines.add(context.getString(R.string.ublox_internal_full_tracking_hint))
            }
            return lines.joinToString("\n")
        }

    private companion object {
        const val LOCATION_INTERVAL_MS = 1000L
        const val SAT_INTERVAL_MS = 900L
        /** NAV-PVT / RXM-RAWX がこの時間内に来ていれば、NAV-SAT はその epoch に合わせる */
        const val EPOCH_HOLD_MS = 2500L
        /** 擬似位置を捨ててからこの間は「擬似ロケーション中」と出す */
        const val MOCK_HOLD_MS = 5000L
        // GnssMeasurementsEvent.Callback の STATUS_*（API 31 で非推奨になったので値で持つ）
        const val MEAS_NOT_SUPPORTED = 0
        const val MEAS_READY = 1
        const val MEAS_NOT_ALLOWED = 3
    }
}
