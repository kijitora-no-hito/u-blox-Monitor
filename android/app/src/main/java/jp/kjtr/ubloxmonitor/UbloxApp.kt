package jp.kjtr.ubloxmonitor

import android.app.Application
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ubloxmonitor.update.UpdateManager
import kotlinx.coroutines.MainScope
import java.io.File

class UbloxApp : Application() {
    lateinit var repository: MonitorRepository
        private set

    /** 更新の確認（0.5.0）。設定 OFF の間は一切通信しない */
    val updates: UpdateManager by lazy { UpdateManager(this, MainScope()) { repository.prefs.updateCheck } }

    override fun onCreate() {
        super.onCreate()
        // osmdroid: User-Agent は applicationId、タイルのキャッシュはアプリ専用の cache に置く（ストレージ権限は使わない）。
        // ここでは設定だけで、地図タブで［地図を表示］を押すまでタイルは取りに行かない。
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = BuildConfig.APPLICATION_ID
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        repository = MonitorRepository(this)
    }
}
