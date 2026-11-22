package jp.kjtr.ubloxmonitor.data.source

import jp.kjtr.ubloxmonitor.R

/**
 * データ源。どれも「UBX のバイト列を Pipeline.feed に流す」ものとして扱う。
 *
 * - [UsbSerialSource]: u-blox 受信機（USB 直結・USB シリアル変換）
 * - [ReplaySource]: 記録済みの .ubx の再生
 * - [InternalGnssSource]: スマホ内蔵 GNSS。Android の GnssStatus / GnssMeasurementsEvent / Location から
 *   NAV-PVT・NAV-SAT・RXM-RAWX を合成して流す（組み立ては domain の PhoneUbx / PhoneRawx）。
 *   擬似ロケーション ON 中は GPS_PROVIDER の位置が自分の擬似位置になるので、
 *   Location.isMock（API 31+）/ isFromMockProvider の位置は必ず捨てる（ループ防止）。
 *
 * 受信は各データ源のスレッドで行い、Pipeline（集計・記録）はデータ源ごとに 1 つ持つ（[Channel]）。
 */
interface Source {
    /** 画面とログに出す名前 */
    val title: String

    /** 実機などへ送信できるか（再生は false） */
    val canSend: Boolean

    /** 動いているか。止まったら false（error に理由） */
    val isAlive: Boolean

    /** 止まった理由（正常終了・利用者の操作では null） */
    val error: String?

    /** 動いている間に画面に出す状態（複数行可）。null なら既定の表示 */
    val statusText: String? get() = null

    fun start()

    /** 止めて終了を待つ（最大 2 秒）。 */
    fun stop()

    /** 送信するパケットを積む。 */
    fun send(packets: List<ByteArray>) {}
}

/**
 * データ源の区分。集計（Pipeline / GnssState）は区分ごとに 1 つずつ持ち、同時に動かせる。
 * trackColor は地図の軌跡の色。logPrefix は記録ファイル名の接頭辞。
 */
enum class Channel(val labelRes: Int, val trackColor: Long, val logPrefix: String, val live: Boolean) {
    UBLOX(R.string.source_ublox, 0xFF2980B9, "ublox", true),
    INTERNAL(R.string.source_internal, 0xFFC0392B, "internal", true),
    REPLAY(R.string.source_replay, 0xFF8E44AD, "ublox", false),
}
