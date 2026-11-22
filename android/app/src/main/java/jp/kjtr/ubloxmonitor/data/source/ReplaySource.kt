package jp.kjtr.ubloxmonitor.data.source

import jp.kjtr.ublox.android.Source
import jp.kjtr.ublox.core.MergedReplayRunner

/**
 * UBX ログを再生するスレッド（Python 版 ReplaySource）。0.5.0 から ublox-core の MergedReplayRunner で、
 * 1 本（従来どおり）か 2 本（u-blox と内蔵 GNSS の記録を GPS 時刻で合わせる）を流す。速度は倍率で 0 は待たない。
 * 一時停止・速度の変更・先へのシークは runner に直接。2 本目の Channel には [Companion] を入れる。
 */
class ReplaySource(
    val runner: MergedReplayRunner,
    /** ファイルの大きさ（不明なら 0。2 本なら合計） */
    val size: Long,
    override val title: String,
) : Source {
    private var thread: Thread? = null

    @Volatile
    override var error: String? = null
        private set

    override val canSend = false

    override val isAlive: Boolean get() = thread?.isAlive == true

    val position: Long get() = runner.positions.sum()

    /** 進み具合 0〜1（大きさが分からなければ null） */
    val progress: Float? get() = if (size > 0) (position.toFloat() / size).coerceIn(0f, 1f) else null

    override fun start() {
        thread = Thread({
            try {
                runner.run()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }, "ubx-replay").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        runner.stop()
        thread?.join(2000)
    }

    /** 2 本同時の再生の 2 本目（スレッドは持たない。止めると両方止まる）。 */
    class Companion(val primary: ReplaySource, override val title: String) : Source {
        override val error: String? get() = primary.error
        override val canSend = false
        override val isAlive: Boolean get() = primary.isAlive
        override fun start() = Unit
        override fun stop() = primary.stop()
    }
}
