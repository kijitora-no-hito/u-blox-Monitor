package jp.kjtr.ubloxmonitor

import android.app.Application
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import java.io.File

class UbloxApp : Application() {
    lateinit var repository: MonitorRepository
        private set

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
