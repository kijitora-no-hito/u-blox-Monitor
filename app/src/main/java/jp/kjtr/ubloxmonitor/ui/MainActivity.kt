package jp.kjtr.ubloxmonitor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.UbloxApp
import jp.kjtr.ubloxmonitor.data.MonitorRepository

/**
 * 画面は下のナビ（接続 / メイン / 地図 / 記録）。2 段目（RTK 補正・受信機設定）・
 * 3 段目（スペクトル・u-blox モニター）はここに項目を足す。
 */
class MainActivity : ComponentActivity() {
    private val repo: MonitorRepository get() = (application as UbloxApp).repository

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleUsbIntent(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // 接続中の通知（測位状態と「切断」）を出すため
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            UbloxTheme { AppRoot(repo, startTab = if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) 0 else 1) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            repo.onDeviceAttached(IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java))
        }
    }
}

private data class Dest(val label: Int, val icon: ImageVector)

private val DESTS = listOf(
    Dest(R.string.nav_connect, Icons.Filled.Settings),
    Dest(R.string.nav_main, Icons.Filled.Home),
    Dest(R.string.nav_map, Icons.Filled.Place),
    Dest(R.string.nav_logs, Icons.AutoMirrored.Filled.List),
)

@Composable
private fun AppRoot(repo: MonitorRepository, startTab: Int) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    Scaffold(
        // edge-to-edge（API 35+ で強制）。横向きのカットアウトも避ける
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar {
                DESTS.forEachIndexed { i, d ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(d.icon, contentDescription = null) },
                        label = { Text(stringResource(d.label)) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            0 -> ConnectScreen(repo, padding)
            1 -> MainScreen(repo, padding)
            2 -> MapScreen(repo, padding)
            else -> LogsScreen(repo, padding, onPlay = { tab = 1 })
        }
    }
}
