package io.github.jaypetez.ideaforge.probe

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A spoken phrase as raw PCM, for feeding a recognizer through EXTRA_AUDIO_SOURCE (API 33+)
 * where there is no microphone to speak into: the emulator's virtual mic hears silence unless
 * host audio is switched on. It exercises the recognizer's text, partial and confidence
 * behaviour on real speech; it does NOT exercise the microphone path, so restart, RMS and
 * screen-off findings from it say nothing about a phone's mic.
 */
class TtsClip(val pcm: ByteArray, val sampleRate: Int, val channels: Int) {
    /** Silence on either side, so the recognizer sees an onset and an endpoint. */
    fun pipe(leadMs: Int, tailMs: Int, onDone: (String) -> Unit): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread({
            val bytesPerMs = sampleRate * channels * 2 / 1000
            val chunk = bytesPerMs * 20
            var written = 0
            var outcome = "fed"
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    fun paced(bytes: ByteArray) {
                        var off = 0
                        while (off < bytes.size) {
                            val n = minOf(chunk, bytes.size - off)
                            out.write(bytes, off, n)
                            off += n
                            written += n
                            Thread.sleep(20) // real time: a recognizer endpoints on wall time
                        }
                    }
                    paced(ByteArray(leadMs * bytesPerMs))
                    paced(pcm)
                    paced(ByteArray(tailMs * bytesPerMs))
                }
            } catch (e: IOException) {
                outcome = "reader closed the pipe (${e.message})"
            }
            onDone("audio source: $outcome after ${written / bytesPerMs} ms of audio")
        }, "probe-audio-feed").start()
        return read
    }

    companion object {
        /** Synthesises [text] to a WAV file and calls back with the clip or a reason, off the main thread. */
        fun synthesize(context: Context, text: String, done: (TtsClip?, String) -> Unit) {
            val out = File(context.cacheDir, "probe-tts.wav")
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context.applicationContext) { status ->
                val engine = tts!!
                if (status != TextToSpeech.SUCCESS) {
                    done(null, "TTS init failed ($status)"); engine.shutdown(); return@TextToSpeech
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        val clip = runCatching { parseWav(out.readBytes()) }
                        done(clip.getOrNull(), clip.exceptionOrNull()?.message ?: "synthesised via ${engine.defaultEngine}")
                        engine.shutdown()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { done(null, "TTS synthesis failed"); engine.shutdown() }
                    override fun onError(id: String?, code: Int) { done(null, "TTS synthesis failed ($code)"); engine.shutdown() }
                })
                val rc = engine.synthesizeToFile(text, Bundle(), out, "probe")
                if (rc != TextToSpeech.SUCCESS) { done(null, "synthesizeToFile returned $rc"); engine.shutdown() }
            }
        }

        /** The PCM of a canonical 16-bit WAV, wherever its fmt and data chunks sit. */
        fun parseWav(bytes: ByteArray): TtsClip {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF") { "not a WAV file" }
            var pos = 12
            var rate = 0
            var channels = 0
            var bits = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val len = b.getInt(pos + 4)
                val body = pos + 8
                when (id) {
                    "fmt " -> { channels = b.getShort(body + 2).toInt(); rate = b.getInt(body + 4); bits = b.getShort(body + 14).toInt() }
                    "data" -> {
                        require(bits == 16) { "WAV is $bits-bit, not 16" }
                        val end = minOf(bytes.size, body + if (len <= 0) bytes.size else len)
                        return TtsClip(bytes.copyOfRange(body, end), rate, channels)
                    }
                }
                pos = body + len + (len and 1)
            }
            error("WAV has no data chunk")
        }
    }
}
