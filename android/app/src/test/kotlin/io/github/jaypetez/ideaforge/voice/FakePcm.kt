package io.github.jaypetez.ideaforge.voice

/**
 * A scripted microphone: [plan] is a list of (milliseconds, amplitude) stretches, then silence
 * for ever. Time is samples, exactly as WhisperListener counts it, so nothing here waits.
 */
class FakePcm(
    private val plan: List<Pair<Long, Int>>,
    override val sampleRate: Int = 16_000,
    private val startOk: Boolean = true,
) : PcmSource {
    var starts = 0
    var stops = 0
    var open = false
        private set
    private var at = 0L // samples delivered

    override fun start(): Boolean {
        starts++
        open = startOk
        return startOk
    }

    override fun read(buffer: ShortArray): Int {
        check(open) { "read from a closed microphone" }
        for (i in buffer.indices) {
            val ms = (at + i) * 1000 / sampleRate
            var edge = 0L
            var amp = 40 // room noise
            for ((len, a) in plan) {
                if (ms < edge + len) { amp = a; break }
                edge += len
            }
            // A square-ish wave: RMS of |amp|.
            buffer[i] = (if (i % 2 == 0) amp else -amp).toShort()
        }
        at += buffer.size
        return buffer.size
    }

    override fun stop() {
        stops++
        open = false
    }
}

/** Room noise, then [speechMs] of speech, then silence. */
fun speech(speechMs: Long = 1500, leadMs: Long = 600): List<Pair<Long, Int>> =
    listOf(leadMs to 40, speechMs to 8000)
