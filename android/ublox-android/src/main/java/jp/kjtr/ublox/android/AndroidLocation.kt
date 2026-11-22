package jp.kjtr.ublox.android

import android.location.Location
import android.os.Build
import android.os.Bundle
import jp.kjtr.ublox.core.GnssFix

/** [toAndroidLocation] の既定の provider 名 */
const val UBLOX_PROVIDER = "ublox"

/** Location.extras のキー（[toAndroidLocation] が入れる） */
object UbloxExtras {
    /** NAV-PVT の fixType（Int） */
    const val FIX_TYPE = "fixType"
    /** 使用衛星数（Int。Android の慣例の "satellites" にも同じ値を入れる） */
    const val NUM_SV = "numSV"
    /** carrSoln（Int。0 なし / 1 RTK Float / 2 RTK Fixed） */
    const val CARR_SOLN = "carrSoln"
    /** 測位状態の表示名（String。No Fix / 3D / DGNSS / RTK Float / RTK Fixed など） */
    const val FIX_LABEL = "fixLabel"
    /** DGNSS / RTK の補正を使っているか（Boolean） */
    const val DIFF_SOLN = "diffSoln"
    /** PDOP（Double） */
    const val PDOP = "pdop"
    /** 標高（平均海面高）[m]（Double。API 34 未満でも読めるように） */
    const val HEIGHT_MSL = "heightMsl"
}

/**
 * Android の Location にする（ロガーの既存の位置の処理・GPX 書き出しにそのまま渡せる）。位置が無いときは null。
 *
 * - altitude は WGS84 の楕円体高（Android の定義と同じ）。標高は API 34+ の mslAltitudeMeters と extras の heightMsl
 * - accuracy = hAcc、verticalAccuracyMeters = vAcc、speedAccuracy・bearingAccuracy も入れる（API 26+）
 * - time は受信機の UTC（有効なとき。useReceiverTime = false なら端末の時刻）、elapsedRealtimeNanos は受け取った時刻
 * - extras に fixType・numSV・carrSoln・fixLabel・diffSoln・pdop・heightMsl（[UbloxExtras]）
 *
 * provider は既定 "ublox"。ロガーが provider 名で GPS かどうかを見ているなら LocationManager.GPS_PROVIDER を渡す。
 */
fun GnssFix.toAndroidLocation(provider: String = UBLOX_PROVIDER, useReceiverTime: Boolean = true): Location? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    return Location(provider).apply {
        latitude = lat
        longitude = lon
        this@toAndroidLocation.height?.let { altitude = it }
        hAcc?.let { accuracy = it.toFloat() }
        this@toAndroidLocation.speed?.let { speed = it.toFloat() }
        heading?.let { bearing = (((it % 360) + 360) % 360).toFloat() }
        time = if (useReceiverTime) this@toAndroidLocation.time else receivedAtMillis
        elapsedRealtimeNanos = receivedElapsedNanos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vAcc?.let { verticalAccuracyMeters = it.toFloat() }
            speedAcc?.let { speedAccuracyMetersPerSecond = it.toFloat() }
            headingAcc?.let { bearingAccuracyDegrees = it.coerceAtMost(360.0).toFloat() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            heightMsl?.let { mslAltitudeMeters = it }
            vAcc?.let { mslAltitudeAccuracyMeters = it.toFloat() }
        }
        extras = Bundle().apply {
            putInt(UbloxExtras.FIX_TYPE, fixType)
            putInt(UbloxExtras.NUM_SV, numSv)
            putInt("satellites", numSv)
            putInt(UbloxExtras.CARR_SOLN, carrSoln)
            putString(UbloxExtras.FIX_LABEL, fixLabel)
            putBoolean(UbloxExtras.DIFF_SOLN, diffSoln)
            putDouble(UbloxExtras.PDOP, pdop)
            heightMsl?.let { putDouble(UbloxExtras.HEIGHT_MSL, it) }
        }
    }
}
