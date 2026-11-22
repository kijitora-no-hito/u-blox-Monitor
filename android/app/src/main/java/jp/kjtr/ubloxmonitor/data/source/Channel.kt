package jp.kjtr.ubloxmonitor.data.source

import jp.kjtr.ubloxmonitor.R

/**
 * データ源の区分。集計（Pipeline / GnssState）は区分ごとに 1 つずつ持ち、同時に動かせる。
 * trackColor は地図の軌跡の色。logPrefix は記録ファイル名の接頭辞。
 * データ源そのもの（Source インタフェース・UsbSerialSource・InternalGnssSource）は ublox-android。
 */
enum class Channel(val labelRes: Int, val trackColor: Long, val logPrefix: String, val live: Boolean) {
    UBLOX(R.string.source_ublox, 0xFF2980B9, "ublox", true),
    INTERNAL(R.string.source_internal, 0xFFC0392B, "internal", true),
    REPLAY(R.string.source_replay, 0xFF8E44AD, "ublox", false),
}
