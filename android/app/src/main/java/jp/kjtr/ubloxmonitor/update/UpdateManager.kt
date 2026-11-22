package jp.kjtr.ubloxmonitor.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import jp.kjtr.ubloxmonitor.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 画面に渡す更新の確認の様子。[banner] = メインの帯に出す版（OFF・［あとで］・［この版は通知しない］なら null）。 */
data class UpdateUi(
    val enabled: Boolean,
    val status: UpdateStatus,
    val newer: UpdateRelease?,
    val banner: UpdateRelease?,
    val lastSuccessMs: Long,
    val lastAttemptMs: Long,
)

/**
 * 更新の確認の Android 側（0.5.0。自転車ルートロガーの UpdateManager と同じ形）。[UpdateChecker] をメインスレッドで動かし、取得だけ IO スレッドで行う。
 * 覚えておく版と時刻は SharedPreferences `update`（設定の `settings` とは別のファイル。バックアップの対象外）。
 * [enabled] = 「このアプリについて」の「更新の確認」（OFF の間は一切通信しない）。
 */
class UpdateManager(context: Context, private val scope: CoroutineScope, private val enabled: () -> Boolean) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val checker = UpdateChecker(
        current = BuildConfig.VERSION_NAME,
        fetch = ::fetchAsync,
        load = ::readMemo,
        save = ::writeMemo,
    )

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<UpdateUi> = _state

    /** 起動時の確認はプロセスごとに 1 回。 */
    private var launchDone = false

    /** メインが出たときに呼ぶ: 起動して最初の 1 回だけ、裏でリリース一覧を読む（OFF・12 時間以内なら何もしない）。 */
    fun checkOnLaunch() {
        if (launchDone) return
        launchDone = true
        val started = checker.onLaunch(enabled())
        Log.i(TAG, "launch check: fetch=$started status=${checker.status}")
        publish()
    }

    /** ［今すぐ確認］。 */
    fun checkNow() {
        val started = checker.checkNow(enabled())
        Log.i(TAG, "check now: fetch=$started")
        publish()
    }

    /** 帯の［あとで］。 */
    fun dismiss() {
        checker.dismiss()
        publish()
    }

    /** 帯の［この版は通知しない］。 */
    fun skip() {
        checker.skip()
        publish()
    }

    /** debug ビルドだけ: いまの版を [version] として比べてすぐ確かめる（古い版として動かす確認用）。 */
    fun pretend(version: String) {
        if (!BuildConfig.DEBUG) return
        val started = checker.pretend(version, enabled())
        Log.i(TAG, "pretend $version -> ${checker.compareAs} fetch=$started")
        publish()
    }

    /** 設定の ON/OFF が変わったときなどに、画面の様子を作り直す。 */
    fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot(): UpdateUi {
        val on = enabled()
        return UpdateUi(on, checker.status, checker.newer, checker.banner(on), checker.memo.lastSuccessMs, checker.memo.lastAttemptMs)
    }

    private fun fetchAsync(done: (String?) -> Unit) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { fetchText(UpdateLinks.RELEASES_URL) }
            done(text)
            Log.i(TAG, "status=${checker.status} newer=${checker.newer?.version}")
            publish()
        }
    }

    private fun readMemo(): UpdateMemo = UpdateMemo(
        prefs.getLong(K_ATTEMPT, 0L), prefs.getLong(K_SUCCESS, 0L), prefs.getString(K_TAG, "") ?: "", prefs.getString(K_NOTES, "") ?: "",
        prefs.getString(K_DOWNLOAD, "") ?: "", prefs.getString(K_PAGE, "") ?: "", prefs.getString(K_SKIPPED, "") ?: "",
    )

    private fun writeMemo(m: UpdateMemo) {
        prefs.edit()
            .putLong(K_ATTEMPT, m.lastAttemptMs)
            .putLong(K_SUCCESS, m.lastSuccessMs)
            .putString(K_TAG, m.tag)
            .putString(K_NOTES, m.notes)
            .putString(K_DOWNLOAD, m.downloadUrl)
            .putString(K_PAGE, m.pageUrl)
            .putString(K_SKIPPED, m.skippedTag)
            .apply()
    }

    companion object {
        private const val TAG = "UbloxMon-Update"
        const val PREFS = "update"
        private const val K_ATTEMPT = "last_attempt"
        private const val K_SUCCESS = "last_success"
        private const val K_TAG = "tag"
        private const val K_NOTES = "notes"
        private const val K_DOWNLOAD = "download_url"
        private const val K_PAGE = "page_url"
        private const val K_SKIPPED = "skipped_tag"

        /**
         * HttpURLConnection の GET 1 回（https だけ・`useCaches = false`・`Accept: application/vnd.github+json`・
         * User-Agent `ublox_monitor_android/版`（GitHub API は User-Agent が必須）・タイムアウト各 8 秒・2xx 以外と 1 MB 超えは失敗）。
         * 認証は使わない。失敗は null。IO スレッドで呼ぶ。
         */
        fun fetchText(url: String): String? {
            if (!url.startsWith("https://")) return null
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = UpdatePolicy.TIMEOUT_MS
                    conn.readTimeout = UpdatePolicy.TIMEOUT_MS
                    conn.useCaches = false
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    conn.setRequestProperty("User-Agent", "ublox_monitor_android/" + BuildConfig.VERSION_NAME)
                    val code = conn.responseCode
                    if (code !in 200..299) error("HTTP $code")
                    val bytes = conn.inputStream.use { readCapped(it, UpdatePolicy.MAX_BYTES) }
                    String(bytes, Charsets.UTF_8).also { Log.i(TAG, "fetched ${it.length} chars") }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.i(TAG, "fetch failed: ${e.javaClass.simpleName} ${e.message ?: ""}")
                null
            }
        }

        /** [max] バイトまで読む（超えたら例外）。 */
        private fun readCapped(input: InputStream, max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > max) error("too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
