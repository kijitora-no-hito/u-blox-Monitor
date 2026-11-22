package jp.kjtr.ublox.android

/**
 * データ源。どれも「UBX のバイト列を Pipeline.feed に流す」ものとして扱う。
 *
 * - [UsbSerialSource]: u-blox 受信機（USB 直結・USB シリアル変換）。ふつうは [UbloxReceiver] 経由で使う
 * - [InternalGnssSource]: スマホ内蔵 GNSS。Android の GnssStatus / GnssMeasurementsEvent / Location から
 *   NAV-PVT・NAV-SAT・RXM-RAWX を合成して流す（組み立ては ublox-core の PhoneUbx / PhoneRawx）。
 *   擬似ロケーション ON 中は GPS_PROVIDER の位置が自分の擬似位置になるので、
 *   Location.isMock（API 31+）/ isFromMockProvider の位置は必ず捨てる（ループ防止）。
 * - 記録済みの .ubx の再生（ublox-core の ReplayRunner。アプリ側の ReplaySource）
 *
 * 受信は各データ源のスレッドで行い、Pipeline（集計・記録）はデータ源ごとに 1 つ持つ。
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
