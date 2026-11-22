package jp.kjtr.ubloxmonitor.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import jp.kjtr.ubloxmonitor.domain.Link

/** 接続候補の USB 機器。 */
data class UsbCandidate(
    val device: UsbDevice,
    val driver: UsbSerialDriver,
    val label: String,
    /** 受信機側のポートの推定（u-blox 内蔵 USB なら USB、変換チップなら UART1） */
    val receiverPortGuess: String,
) {
    val key: String get() = device.deviceName
}

object UsbDevices {
    private const val ACTION_USB_PERMISSION = "jp.kjtr.ubloxmonitor.USB_PERMISSION"

    /** u-blox 内蔵 USB の既知の PID（M8: 0x01A8 など、F9: 0x01A9）。ここに無い PID もフォールバックで扱う。 */
    private val UBLOX_PIDS = listOf(0x01A4, 0x01A5, 0x01A6, 0x01A7, 0x01A8, 0x01A9, 0x01AA, 0x0502, 0x0503)

    private val ubloxProber: UsbSerialProber by lazy {
        val table = ProbeTable()
        UBLOX_PIDS.forEach { table.addProduct(Link.UBLOX_USB_VID, it, CdcAcmSerialDriver::class.java) }
        UsbSerialProber(table)
    }

    /** ドライバを決める。既定の prober（FTDI・CP210x・CH34x・PL2303・CDC-ACM）→ u-blox の表 → VID 0x1546 なら CDC-ACM。 */
    fun findDriver(device: UsbDevice): UsbSerialDriver? {
        UsbSerialProber.getDefaultProber().probeDevice(device)?.let { return it }
        if (device.vendorId != Link.UBLOX_USB_VID) return null
        ubloxProber.probeDevice(device)?.let { return it }
        return try {
            CdcAcmSerialDriver(device).takeIf { it.ports.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun label(device: UsbDevice): String {
        val product = try {
            device.productName
        } catch (_: SecurityException) {
            null
        }
        val maker = try {
            device.manufacturerName
        } catch (_: SecurityException) {
            null
        }
        val name = listOfNotNull(maker, product).joinToString(" ").ifBlank { device.deviceName }
        return "%s  (%04X:%04X)".format(name, device.vendorId, device.productId)
    }

    fun candidate(device: UsbDevice): UsbCandidate? {
        val driver = findDriver(device) ?: return null
        return UsbCandidate(device, driver, label(device), Link.guessReceiverPort(device.vendorId))
    }

    /** 接続中の USB 機器のうち、シリアルとして使えるもの。u-blox を先に並べる。 */
    fun list(context: Context): List<UsbCandidate> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.mapNotNull { candidate(it) }
            .sortedWith(compareBy({ it.device.vendorId != Link.UBLOX_USB_VID }, { it.device.deviceName }))
    }

    /**
     * USB の使用許可を求める。結果は onResult(許可されたか)。
     * PendingIntent は FLAG_MUTABLE（システムが EXTRA_PERMISSION_GRANTED を入れるため）で、
     * Android 14 以降の制約に合わせて setPackage した明示的な intent にする。
     */
    fun requestPermission(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val target = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (target != null && target.deviceName != device.deviceName) return
                try {
                    app.unregisterReceiver(this)
                } catch (_: IllegalArgumentException) {
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                    manager.hasPermission(device)
                onResult(granted)
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        manager.requestPermission(device, PendingIntent.getBroadcast(app, 0, intent, flags))
    }
}
