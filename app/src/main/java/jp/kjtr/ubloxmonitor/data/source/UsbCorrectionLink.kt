package jp.kjtr.ubloxmonitor.data.source

import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import jp.kjtr.ubloxmonitor.data.UsbDevices
import jp.kjtr.ubloxmonitor.domain.CorrectionLink
import java.io.IOException

/**
 * RTK 補正の受信元としての USB シリアル（基準局の無線機など。u-blox をつないでいる機器とは別の USB 機器）。
 * Python 版の「シリアルポート（無線機など）」。開くたびに deviceName で機器を探し直す（抜き差し後の再接続のため）。
 * 読み取りは CorrectionClient のスレッドだけが行う。
 */
class UsbCorrectionLink(manager: UsbManager, deviceName: String, baud: Int) : CorrectionLink {
    private val port: UsbSerialPort
    private val chunk = ByteArray(READ_BUFFER)

    init {
        val device = manager.deviceList.values.firstOrNull { it.deviceName == deviceName }
            ?: throw IOException("USB 機器が見つかりません")
        if (!manager.hasPermission(device)) throw IOException("USB の使用が許可されていません")
        val driver = UsbDevices.findDriver(device) ?: throw IOException("USB シリアルとして使えません")
        port = driver.ports.firstOrNull() ?: throw IOException("ポートがありません")
        val connection = manager.openDevice(device) ?: throw IOException("USB を開けません（権限がありません）")
        port.open(connection)
        try {
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (_: UnsupportedOperationException) {
        }
        try {
            port.setDTR(true)
            port.setRTS(true)
        } catch (_: Exception) {
        }
    }

    override fun read(buf: ByteArray): Int {
        val n = port.read(chunk, READ_TIMEOUT_MS)
        if (n <= 0) return 0
        val size = minOf(n, buf.size)
        chunk.copyInto(buf, 0, 0, size)
        return size
    }

    override fun write(data: ByteArray) = port.write(data, WRITE_TIMEOUT_MS)

    override fun close() {
        try {
            port.close()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 200
        const val READ_BUFFER = 16384
        const val WRITE_TIMEOUT_MS = 1000
    }
}
