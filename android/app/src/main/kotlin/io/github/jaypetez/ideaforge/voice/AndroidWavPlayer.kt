package io.github.jaypetez.ideaforge.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays hosted speech from memory through a static AudioTrack: the Android form of the web's
 * bounded src/voice/playback.js. Nothing is written to disk and nothing is cached; the WAV is
 * at most MAX_TTS_AUDIO_BYTES / MAX_TTS_AUDIO_SECONDS (enforced by the provider and again by
 * [wavPcm]), and the wait is bounded by the audio's own length plus a margin, so a track that
 * never reports its end is stopped and reported rather than awaited for ever.
 */
class AndroidWavPlayer : WavPlayer {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false

    override suspend fun play(wav: ByteArray) {
        val pcm = wavPcm(wav)
        stopped = false
        val samples = ShortArray(pcm.length / 2)
        ByteBuffer.wrap(wav, pcm.offset, pcm.length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate)
                    .setChannelMask(if (pcm.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.length)
            .build()
        track = t
        try {
            withContext(Dispatchers.IO) {
                if (t.write(samples, 0, samples.size) != samples.size) error("The speech audio could not be queued.")
                t.play()
                val deadline = System.nanoTime() + (pcm.durationMs + 2000) * 1_000_000
                while (!stopped && t.playbackHeadPosition < pcm.frames) {
                    if (System.nanoTime() > deadline) error("Speech playback did not finish.")
                    delay(40)
                }
            }
        } finally {
            track = null
            runCatching { t.stop() }
            t.release()
        }
    }

    override fun stop() {
        stopped = true
        runCatching { track?.pause() }
    }
}
