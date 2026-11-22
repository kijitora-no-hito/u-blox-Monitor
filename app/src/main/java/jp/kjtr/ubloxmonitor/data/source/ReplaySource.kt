package jp.kjtr.ubloxmonitor.data.source

import jp.kjtr.ubloxmonitor.domain.Pipeline
import jp.kjtr.ubloxmonitor.domain.ReplayClock
import jp.kjtr.ubloxmonitor.domain.ReplayRunner
import java.io.InputStream

/** UBX ログを再生するスレッド（Python 版 ReplaySource）。speed は倍率で、0 は待たずに読む。 */
class ReplaySource(
    pipeline: Pipeline,
    input: InputStream,
    speed: Double,
    clock: ReplayClock,
    /** ファイルの大きさ（不明なら 0） */
    val size: Long,
    override val title: String,
) : Source {
    private val runner = ReplayRunner(pipeline, input, speed, clock)
    private var thread: Thread? = null

    @Volatile
    override var error: String? = null
        private set

    override val canSend = false

    override val isAlive: Boolean get() = thread?.isAlive == true

    val position: Long get() = runner.position

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
}
