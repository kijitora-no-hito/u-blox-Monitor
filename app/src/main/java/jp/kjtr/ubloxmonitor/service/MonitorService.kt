package jp.kjtr.ubloxmonitor.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.UbloxApp
import jp.kjtr.ubloxmonitor.data.MockStatus
import jp.kjtr.ubloxmonitor.data.source.Channel
import jp.kjtr.ubloxmonitor.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 接続を保つフォアグラウンドサービス。受信・集計・記録は MonitorRepository（Application スコープ）が行い、
 * ここはプロセスを保って通知に測位状態と「切断」を出すだけ。動いている実機・内蔵 GNSS が無くなったら終わる。
 *
 * 種別は USB 接続中なら connectedDevice、位置情報の権限があり擬似ロケーション ON か内蔵 GNSS を使うなら location も。
 */
class MonitorService : LifecycleService() {
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val repo = (application as UbloxApp).repository
        if (intent?.action == ACTION_DISCONNECT) {
            repo.disconnectAll()
        }
        val types = serviceTypes()
        if (!repo.hasLiveSource() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types == 0)) {
            // startForegroundService で起動された場合に備えて一度は前面にしてから止める
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
                startInForeground(types)
            }
            stopSelfNow()
            return START_NOT_STICKY
        }
        startInForeground(types)
        if (loop == null) {
            loop = lifecycleScope.launch {
                while (true) {
                    delay(1000)
                    if (!repo.hasLiveSource()) {
                        stopSelfNow()
                        break
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(types: Int) {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
        } catch (e: Exception) {
            // 種別の前提（USB の許可・位置の権限）を満たさないなど。接続はそのまま、サービスだけ止める
            stopSelfNow()
        }
    }

    private fun stopSelfNow() {
        loop?.cancel()
        loop = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun serviceTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val repo = (application as UbloxApp).repository
        var types = 0
        if (repo.isRunning(Channel.UBLOX)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val wantsLocation = repo.isRunning(Channel.INTERNAL) || repo.mockState.value.status == MockStatus.ACTIVE
        if (locationGranted && wantsLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return types
    }

    private fun buildNotification(): android.app.Notification {
        val repo = (application as UbloxApp).repository
        val lines = ArrayList<String>()
        for (channel in listOf(Channel.UBLOX, Channel.INTERNAL)) {
            if (!repo.isRunning(channel)) continue
            val pvt = repo.latestPvt(channel)
            val state = if (pvt == null) getString(R.string.notif_waiting)
            else getString(R.string.notif_fix, pvt.fixLabel, pvt.numSv)
            val rec = if (repo.recordStatus(channel) != null) getString(R.string.notif_recording) else ""
            lines.add("${getString(channel.labelRes)}: $state$rec")
        }
        if (repo.mockState.value.status == MockStatus.ACTIVE) lines.add(getString(R.string.notif_mock))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = lines.joinToString("\n").ifEmpty { getString(R.string.notif_waiting) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(lines.firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_disconnect), disconnect)
            .build()
    }

    override fun onDestroy() {
        loop?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "jp.kjtr.ubloxmonitor.DISCONNECT"

        /** 実機・内蔵 GNSS を始めたときに呼ぶ（利用者の操作の直後＝前面から）。 */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            } catch (_: Exception) {
                // バックグラウンドからの起動制限など。受信そのものは続く
            }
        }
    }
}
