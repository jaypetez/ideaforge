package io.github.jaypetez.ideaforge.probe

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.MainThread
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
        /** Calls back on main with the clip or a reason; cancellation releases this request without a callback. */
        @MainThread
        fun synthesize(context: Context, text: String, done: (TtsClip?, String) -> Unit): CancellationSignal {
            val main = Handler(Looper.getMainLooper())
            val cancellation = CancellationSignal()
            val out = runCatching { File.createTempFile("probe-tts-", ".wav", context.cacheDir) }.getOrElse {
                main.post { if (!cancellation.isCanceled) done(null, "TTS output file failed: ${it.message}") }
                return cancellation
            }
            var tts: TextToSpeech? = null
            var finished = false
            fun finish(clip: TtsClip?, why: String) {
                val notify = !finished && !cancellation.isCanceled
                finished = true
                runCatching { tts?.shutdown() }
                tts = null
                // A late terminal callback may arrive after cancellation; its file is still
                // ours to remove, never a later request's output.
                out.delete()
                if (notify) done(clip, why)
            }
            cancellation.setOnCancelListener {
                if (Looper.myLooper() == Looper.getMainLooper()) finish(null, "cancelled")
                else main.post { finish(null, "cancelled") }
            }
            try {
                tts = TextToSpeech(context.applicationContext) { status ->
                    // Even onInit(ERROR) may run inside the constructor. Queue all terminal
                    // work on main so assignment, cancellation and file cleanup cannot race.
                    main.post initialize@{
                        if (finished || cancellation.isCanceled) { finish(null, "cancelled"); return@initialize }
                        if (status != TextToSpeech.SUCCESS) { finish(null, "TTS init failed ($status)"); return@initialize }
                        val engine = tts ?: run { finish(null, "TTS init ran before the engine existed"); return@initialize }
                        try {
                            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(id: String?) {}
                                override fun onDone(id: String?) {
                                    main.post complete@{
                                        if (finished || cancellation.isCanceled) { finish(null, "cancelled"); return@complete }
                                        val clip = runCatching { parseWav(out.readBytes()) }
                                        finish(clip.getOrNull(), clip.exceptionOrNull()?.message ?: "synthesised via ${engine.defaultEngine}")
                                    }
                                }
                                @Deprecated("Deprecated in Java")
                                override fun onError(id: String?) { main.post { finish(null, "TTS synthesis failed") } }
                                override fun onError(id: String?, code: Int) { main.post { finish(null, "TTS synthesis failed ($code)") } }
                                override fun onStop(id: String?, interrupted: Boolean) { main.post { finish(null, "TTS synthesis stopped") } }
                            })
                            val rc = engine.synthesizeToFile(text, Bundle(), out, "probe")
                            if (rc != TextToSpeech.SUCCESS) finish(null, "synthesizeToFile returned $rc")
                        } catch (e: Exception) {
                            finish(null, "TTS synthesis failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                main.post { finish(null, "TTS init failed: ${e.message}") }
            }
            return cancellation
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
