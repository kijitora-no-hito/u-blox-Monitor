package jp.kjtr.ubloxmonitor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.UbloxApp
import jp.kjtr.ubloxmonitor.data.MonitorRepository

/**
 * 画面は下のナビ（接続 / メイン / 比較 / 地図 / その他）。「その他」から 受信機設定・RTK 補正・スペクトル・u-blox モニター・記録 に入る。
 * 横向き・タブレット（幅 600dp 以上の横長）は NavigationRail。
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
            UbloxTheme { AppRoot(repo, startTab = if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) 0 else TAB_MAIN) }
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

/** 下のナビ（5 個まで） */
private val DESTS = listOf(
    Dest(R.string.nav_connect, Icons.Filled.Settings),
    Dest(R.string.nav_main, Icons.Filled.Home),
    Dest(R.string.nav_compare, Icons.Filled.Info),
    Dest(R.string.nav_map, Icons.Filled.Place),
    Dest(R.string.nav_more, Icons.Filled.Menu),
)
private const val TAB_MAIN = 1
private const val TAB_MORE = 4

/** 「その他」の中の画面 */
private enum class More(val label: Int, val help: Int, val icon: ImageVector) {
    SETTINGS(R.string.nav_settings, R.string.more_settings_help, Icons.Filled.Build),
    CORRECTION(R.string.nav_correction, R.string.more_correction_help, Icons.AutoMirrored.Filled.Send),
    SPECTRUM(R.string.nav_spectrum, R.string.more_spectrum_help, Icons.Filled.Search),
    MONITOR(R.string.nav_monitor, R.string.more_monitor_help, Icons.Filled.Star),
    LOGS(R.string.nav_logs, R.string.more_logs_help, Icons.AutoMirrored.Filled.List),
}

@Composable
private fun AppRoot(repo: MonitorRepository, startTab: Int) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    var more by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = tab == TAB_MORE && more != null) { more = null }
    val select: (Int) -> Unit = { i ->
        // 「その他」を開いているときにもう一度押したら一覧に戻る
        if (i == TAB_MORE && tab == TAB_MORE) more = null
        tab = i
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 横向き・タブレットは左の NavigationRail（項目は同じ 5 個）
        val rail = maxWidth >= 600.dp && maxWidth > maxHeight
        Row(Modifier.fillMaxSize()) {
            if (rail) {
                NavigationRail {
                    DESTS.forEachIndexed { i, d ->
                        NavigationRailItem(
                            selected = tab == i,
                            onClick = { select(i) },
                            icon = { Icon(d.icon, contentDescription = null) },
                            label = { Text(stringResource(d.label)) },
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                // edge-to-edge（API 35+ で強制）。横向きのカットアウトも避ける（左はレールが受け持つ）
                contentWindowInsets = if (rail) {
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End)
                } else {
                    WindowInsets.safeDrawing
                },
                bottomBar = {
                    if (!rail) {
                        NavigationBar {
                            DESTS.forEachIndexed { i, d ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { select(i) },
                                    icon = { Icon(d.icon, contentDescription = null) },
                                    label = { Text(stringResource(d.label)) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                when (tab) {
                    0 -> ConnectScreen(repo, padding)
                    1 -> MainScreen(repo, padding)
                    2 -> CompareScreen(repo, padding)
                    3 -> MapScreen(repo, padding)
                    else -> {
                        val page = more?.let { name -> More.entries.firstOrNull { it.name == name } }
                        if (page == null) {
                            MoreScreen(repo, padding) { more = it.name }
                        } else {
                            MorePage(page, padding, onBack = { more = null }) { inner ->
                                when (page) {
                                    More.SETTINGS -> ReceiverSettingsScreen(repo, inner)
                                    More.CORRECTION -> CorrectionScreen(repo, inner)
                                    More.SPECTRUM -> SpectrumScreen(repo, inner)
                                    More.MONITOR -> MonitorScreen(repo, inner)
                                    More.LOGS -> LogsScreen(repo, inner, onPlay = { tab = TAB_MAIN })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 「その他」の一覧。 */
@Composable
private fun MoreScreen(repo: MonitorRepository, padding: PaddingValues, onOpen: (More) -> Unit) {
    val correction by repo.correction.view.collectAsState()
    val settings by repo.settings.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (page in More.entries) {
            val status = when (page) {
                More.CORRECTION -> if (correction.running) repo.correction.statusLine() else null
                More.SETTINGS -> if (settings.connected) settings.status else null
                More.SPECTRUM, More.MONITOR, More.LOGS -> null
            }
            Card(Modifier.fillMaxWidth().clickable { onOpen(page) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(page.icon, contentDescription = null)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(page.label), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(page.help), style = MaterialTheme.typography.bodySmall)
                        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

/** 「その他」の中の画面。上に戻るボタンと見出しを付ける。 */
@Composable
private fun MorePage(page: More, padding: PaddingValues, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(page.label), style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        Box(Modifier.weight(1f)) { content(PaddingValues()) }
    }
}
