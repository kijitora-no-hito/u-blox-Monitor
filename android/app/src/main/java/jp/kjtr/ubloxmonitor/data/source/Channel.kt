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
    /** 2 本同時の再生（0.5.0）の内蔵 GNSS の記録。u-blox の記録は REPLAY。比較の相手になる */
    REPLAY_INTERNAL(R.string.source_replay_internal, 0xFFD35400, "internal", false),
    ;

    /** スマホ内蔵 GNSS のデータ（MEASX・SFRBX・MON-SPAN が無い） */
    val isPhone: Boolean get() = this == INTERNAL || this == REPLAY_INTERNAL
}
