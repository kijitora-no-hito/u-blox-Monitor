package jp.kjtr.ubloxmonitor.update

import org.json.JSONArray
import org.json.JSONObject

/*
 * 更新の確認（0.5.0）。自転車ルートロガーの update/UpdateCheck.kt と同じ仕組み・同じ挙動で、取得先だけ違う:
 * android-apps の releases.json ではなく、このアプリの公開リポジトリ（kijitora-no-hito/u-blox-Monitor）の
 * GitHub API のリリース一覧（prerelease を含む。draft は除く）から `android-v<版>` のタグの一番新しい版を選ぶ
 * （Windows 版のタグ `v<版>` は無視）。新しければメイン画面の上に控えめな帯を出す（Android の通知は使わない）。
 * 取得そのものは [UpdateManager]（HttpURLConnection の GET 1 回）。設定で OFF なら一切通信しない。
 * Android・通信に依存しない（org.json は JVM のテストでは org.json:json を使う）。
 */

/**
 * 版（`0.4.4` など）。[nums] = 数の組（`0.9` < `0.10`）、[pre] = `-` の後ろ（`dev` など。空 = 正式版）。
 * 同じ数の組なら正式版の方が新しい（`0.5.0-dev` < `0.5.0`）。足りない桁は 0（`0.5` = `0.5.0`）。
 */
data class AppVersion(val nums: List<Int>, val pre: String) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(nums.size, other.nums.size)) {
            val c = nums.getOrElse(i) { 0 }.compareTo(other.nums.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (pre.isEmpty() != other.pre.isEmpty()) return if (pre.isEmpty()) 1 else -1
        return pre.compareTo(other.pre)
    }

    /** 画面に出す形。 */
    override fun toString(): String = nums.joinToString(".") + if (pre.isEmpty()) "" else "-$pre"

    companion object {
        /** 頭の `v` の有無を問わずに読む。読めなければ null（数でない・空・数が 5 つ以上）。 */
        fun parse(text: String): AppVersion? {
            val s = text.trim().removePrefix("v").removePrefix("V")
            if (s.isEmpty()) return null
            val head = s.substringBefore('-')
            val pre = if (s.contains('-')) s.substringAfter('-') else ""
            val parts = head.split('.')
            if (parts.size > 4) return null
            val nums = parts.map { p -> if (p.isEmpty() || !p.all { it.isDigit() } || p.length > 6) return null else p.toInt() }
            return AppVersion(nums, pre)
        }
    }
}

/**
 * Android 版の 1 リリース。[version] = タグの `android-v` の後ろ、[notes] = リリースの本文（Markdown）、
 * [downloadUrl] = APK（`ublox_monitor_android_<版>.apk`。無ければリリースのページ）、[pageUrl] = リリースのページ。
 */
data class UpdateRelease(val tag: String, val version: String, val notes: String, val downloadUrl: String, val pageUrl: String) {
    val parsed: AppVersion? get() = AppVersion.parse(version)
}

/** GitHub API のリリース一覧の読み取り。 */
object Releases {
    /** Android 版のタグの頭（Windows 版は `v0.3.0` なので当たらない） */
    const val TAG_PREFIX = "android-v"

    /** APK の名前（`ublox_monitor_android_0.4.4.apk`） */
    fun apkName(version: String) = "ublox_monitor_android_$version.apk"

    /** タグに使ってよい文字（URL に入るので、それ以外を含むタグは読まない）。 */
    private val TAG_OK = Regex("[A-Za-z0-9._-]+")

    /** リンクは GitHub の中だけ（API の応答が何であってもほかのサイトは開かない）。 */
    private const val GITHUB = "https://github.com/"

    /** 本文の長さの上限（帯の中で広げて読む分には十分） */
    const val MAX_NOTES = 4000

    /**
     * リリース一覧（JSON の配列）から、draft でない `android-v<版>` の一番新しい版を読む（prerelease も含む。並び順によらない）。
     * 無い・壊れた JSON → null。
     */
    fun parse(text: String): UpdateRelease? = try {
        val list = JSONArray(text)
        var best: UpdateRelease? = null
        for (i in 0 until list.length()) {
            val r = list.optJSONObject(i)?.let { entry(it) } ?: continue
            if (best == null || r.parsed!! > best.parsed!!) best = r
        }
        best
    } catch (e: Exception) {
        null
    }

    private fun entry(o: JSONObject): UpdateRelease? {
        if (o.optBoolean("draft", false)) return null
        val tag = o.str("tag_name")?.trim() ?: return null
        if (!tag.startsWith(TAG_PREFIX) || !TAG_OK.matches(tag)) return null
        val ver = tag.removePrefix(TAG_PREFIX)
        if (AppVersion.parse(ver) == null) return null
        val page = o.str("html_url")?.takeIf { it.startsWith(GITHUB) } ?: (PAGE_BASE + tag)
        val assets = o.optJSONArray("assets")
        var apk: String? = null
        var anyApk: String? = null
        for (j in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.optJSONObject(j) ?: continue
            val name = a.str("name") ?: continue
            val url = a.str("browser_download_url")?.takeIf { it.startsWith(GITHUB) } ?: continue
            if (name == apkName(ver)) apk = url
            if (name.endsWith(".apk") && anyApk == null) anyApk = url
        }
        val notes = (o.str("body") ?: "").take(MAX_NOTES)
        return UpdateRelease(tag, ver, notes, apk ?: anyApk ?: page, page)
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) (opt(key) as? String) else null

    /**
     * 帯で広げて読む本文（Markdown を軽く整える: 見出しの `#`・引用の `>`・強調の `**`・`` ` `` を外し、空行を詰める）。
     */
    fun plainNotes(notes: String): String = notes.replace("\r\n", "\n").lines()
        .map { line ->
            line.trim().trimStart('#', '>').trim().replace("**", "").replace("`", "").let {
                if (it.startsWith("- ") || it.startsWith("* ")) "・" + it.substring(2) else it
            }
        }
        .fold(mutableListOf<String>()) { acc, l -> if (!(l.isEmpty() && (acc.isEmpty() || acc.last().isEmpty()))) acc.add(l); acc }
        .joinToString("\n").trim()

    private const val PAGE_BASE = "https://github.com/kijitora-no-hito/u-blox-Monitor/releases/tag/"
}

/** 取得先・リンク。 */
object UpdateLinks {
    /** GitHub API のリリース一覧（認証なしの GET。prerelease を含む。Windows 版も混ざる） */
    const val RELEASES_URL = "https://api.github.com/repos/kijitora-no-hito/u-blox-Monitor/releases?per_page=20"
    /** リリースの一覧のページ */
    const val PAGE_URL = "https://github.com/kijitora-no-hito/u-blox-Monitor/releases"
}

/** いつ取りに行くか。 */
object UpdatePolicy {
    /** 前回の試行からこれ以内なら起動時の確認を省く（時間）。GitHub API の認証なしの上限は 1 時間 60 回 */
    const val INTERVAL_HOURS = 12
    const val INTERVAL_MS = INTERVAL_HOURS * 3_600_000L
    /** 接続・読み取りのタイムアウト（ミリ秒。どちらも）。 */
    const val TIMEOUT_MS = 8_000
    /** これより大きい応答は読まない（20 件の一覧は数十 KB）。 */
    const val MAX_BYTES = 1 shl 20

    /** 起動時に取りに行ってよいか（前回の試行から [INTERVAL_MS] 経った。時計が戻っていたら取りに行く）。 */
    fun isDue(now: Long, lastAttempt: Long): Boolean = lastAttempt <= 0L || now < lastAttempt || now - lastAttempt >= INTERVAL_MS
}

/**
 * 端末に覚えておくもの（前回の試行・成功の時刻と、最後に読めた版。オフラインでも帯を出せるように）と、
 * ［この版は通知しない］を押した版のタグ [skippedTag]。
 */
data class UpdateMemo(
    val lastAttemptMs: Long = 0L,
    val lastSuccessMs: Long = 0L,
    val tag: String = "",
    val notes: String = "",
    val downloadUrl: String = "",
    val pageUrl: String = "",
    val skippedTag: String = "",
) {
    /** 覚えている版（無ければ null）。 */
    val release: UpdateRelease?
        get() = if (tag.startsWith(Releases.TAG_PREFIX)) {
            UpdateRelease(tag, tag.removePrefix(Releases.TAG_PREFIX), notes, downloadUrl.ifEmpty { pageUrl }, pageUrl).takeIf { it.parsed != null }
        } else {
            null
        }
}

/** 確認の状態（設定の状態の 1 行）。 */
enum class UpdateStatus { UNKNOWN, CHECKING, LATEST, AVAILABLE, FAILED }

/**
 * 更新の確認の流れ（メインスレッドだけで使う）。
 *
 * - [onLaunch]: 起動時（メインが出た後に 1 回）。設定 OFF・前回の試行から [UpdatePolicy.INTERVAL_HOURS] 時間以内なら何もしない。
 * - [checkNow]: ［今すぐ確認］（間隔は見ない。OFF なら何もしない）。［あとで］・［この版は通知しない］も取り消す。
 * - 失敗は黙って無視（状態が [UpdateStatus.FAILED] になるだけ。覚えている版はそのまま）。
 * - [dismiss]: ［あとで］。その版は次に起動するまで出さない（メモリだけ）。
 * - [skip]: ［この版は通知しない］。その版は覚えておいて出さない（もっと新しい版が出たらまた出す）。
 *
 * [current] = いまの versionName、[fetch] = 取得を始めて結果（失敗 = null）をメインスレッドで返す、[load] / [save] = [UpdateMemo] の読み書き。
 */
class UpdateChecker(
    current: String,
    private val fetch: ((String?) -> Unit) -> Unit,
    load: () -> UpdateMemo,
    private val save: (UpdateMemo) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 比べるいまの版（debug ビルドの確認用に [pretend] で下げられる）。 */
    var compareAs: AppVersion? = AppVersion.parse(current)
        private set

    var memo: UpdateMemo = load()
        private set

    var status: UpdateStatus = UpdateStatus.UNKNOWN
        private set

    /** いまの版より新しい版（無ければ null）。 */
    var newer: UpdateRelease? = null
        private set

    /** ［あとで］を押した版のタグ。 */
    var dismissedTag: String? = null
        private set

    /** 取りに行った回数（テスト・ログ用）。 */
    var fetches = 0
        private set

    init {
        evaluate(memo.release, fromCache = true)
    }

    /** 起動時の確認。取りに行ったら true。 */
    fun onLaunch(enabled: Boolean): Boolean {
        if (!enabled || status == UpdateStatus.CHECKING) return false
        if (!UpdatePolicy.isDue(clock(), memo.lastAttemptMs)) return false
        start()
        return true
    }

    /** ［今すぐ確認］。取りに行ったら true。押したら［あとで］・［この版は通知しない］も取り消す（また出す）。 */
    fun checkNow(enabled: Boolean): Boolean {
        if (!enabled || status == UpdateStatus.CHECKING) return false
        dismissedTag = null
        if (memo.skippedTag.isNotEmpty()) {
            memo = memo.copy(skippedTag = "")
            save(memo)
        }
        start()
        return true
    }

    /** 帯に出す版（OFF・無い・［あとで］・［この版は通知しない］の版なら null）。 */
    fun banner(enabled: Boolean): UpdateRelease? =
        if (!enabled) null else newer?.takeIf { it.tag != dismissedTag && it.tag != memo.skippedTag }

    fun dismiss() {
        dismissedTag = newer?.tag
    }

    fun skip() {
        val tag = newer?.tag ?: return
        memo = memo.copy(skippedTag = tag)
        save(memo)
    }

    /**
     * debug ビルドだけ: いまの版を [version] として比べ直し、すぐ取りに行く（古い版として動かす確認用）。
     * 読めない版なら何もしない。取りに行ったら true。
     */
    fun pretend(version: String, enabled: Boolean): Boolean {
        compareAs = AppVersion.parse(version) ?: return false
        dismissedTag = null
        evaluate(memo.release, fromCache = true)
        return checkNow(enabled)
    }

    private fun start() {
        val now = clock()
        status = UpdateStatus.CHECKING
        fetches++
        memo = memo.copy(lastAttemptMs = now)
        save(memo)
        fetch { text ->
            val r = text?.let { Releases.parse(it) }
            if (r == null) {
                status = UpdateStatus.FAILED
            } else {
                memo = memo.copy(lastSuccessMs = clock(), tag = r.tag, notes = r.notes, downloadUrl = r.downloadUrl, pageUrl = r.pageUrl)
                save(memo)
                evaluate(r, fromCache = false)
            }
        }
    }

    private fun evaluate(r: UpdateRelease?, fromCache: Boolean) {
        val cur = compareAs
        val v = r?.parsed
        newer = if (r != null && v != null && cur != null && v > cur) r else null
        status = when {
            newer != null -> UpdateStatus.AVAILABLE
            fromCache -> UpdateStatus.UNKNOWN
            else -> UpdateStatus.LATEST
        }
    }
}
