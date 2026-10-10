package io.github.jaypetez.ideaforge.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * THE ONE PLACE this app opens a microphone capture of its own (RecognizerAudioTest allows
 * exactly this file). Everywhere else the recognition service holds the microphone, and a
 * second capture beside it starves recognition silently (meterStarvesRecognition on the web).
 * This source exists for the Whisper fallback, which runs only when the native recogniser is
 * unavailable: DriveController never constructs one while a Recognizer capture is open, and
 * releases the recogniser before the first recording.
 *
 * 16 kHz mono 16-bit PCM, the format Whisper wants and the smallest upload for it.
 */
class WhisperMicrophone(override val sampleRate: Int = 16_000) : PcmSource {
    private var record: AudioRecord? = null

    // RECORD_AUDIO is requested before any voice start (the same grant the recogniser uses).
    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sampleRate / 5 * 2),
            )
        }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            return false
        }
        record = r
        return true
    }

    override fun read(buffer: ShortArray): Int = record?.read(buffer, 0, buffer.size) ?: -1

    override fun stop() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        r.release()
    }
}
