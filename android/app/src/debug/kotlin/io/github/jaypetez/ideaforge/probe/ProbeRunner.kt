package io.github.jaypetez.ideaforge.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The slice of SpeechRecognizer the probe drives. A seam so a test can run trials against a
 * fake engine; [ProbeRunner.engineFactory] makes the real one.
 */
interface ProbeEngine {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
}

private class SpeechRecognizerEngine(private val r: SpeechRecognizer) : ProbeEngine {
    override fun setRecognitionListener(listener: RecognitionListener) = r.setRecognitionListener(listener)
    override fun startListening(intent: Intent) = r.startListening(intent)
    override fun stopListening() = r.stopListening()
    override fun cancel() = r.cancel()
    override fun destroy() = r.destroy()
}

/** One trial's settings: what the probe screen's toggles say. */
data class TrialConfig(
    val onDevice: Boolean = false,
    val partials: Boolean = true,
    val preferOffline: Boolean = false,
    val silenceExtras: Boolean = false,
    val segmented: Boolean = false,
    /** Immediate restarts after onResults/onError; [UNTIL_STOP] keeps going up to [MAX_SESSIONS]. */
    val restarts: Int = 0,
    val recreateOnRestart: Boolean = false,
    val delayMs: Long = 0,
    val foregroundService: Boolean = false,
    val wakeLock: Boolean = false,
    /** Feed this phrase, spoken by the device TTS, through EXTRA_AUDIO_SOURCE instead of the mic. */
    val ttsPhrase: String? = null,
) {
    companion object {
        const val UNTIL_STOP = -1
        const val MAX_SESSIONS = 60
    }
}

/**
 * Drives SpeechRecognizer for the debug-only probe (issue #89) and logs every callback with
 * a monotonic timestamp. Process-wide rather than owned by the activity so a trial keeps
 * running, and keeps logging, while the activity is stopped by the screen going off.
 * Everything touching the recognizer runs on the main thread, as SpeechRecognizer requires.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object ProbeRunner {
    const val LOG_FILE = "recognizer-probe.log"
    private const val RMS_WINDOW_MS = 250L
    private const val SESSION_WATCHDOG_MS = 60_000L
    private const val LEAD_MS = 800
    private const val TAIL_MS = 4_000

    private val main = Handler(Looper.getMainLooper())
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private var app: Context? = null
    private var t0 = SystemClock.elapsedRealtimeNanos()
    private var trial = 0
    private var session = 0
    private var endedSession = 0
    private var restartsDone = 0
    private var stopRequested = false
    private var config = TrialConfig()
    private var recognizer: ProbeEngine? = null

    /** Makes the engine for a session: (context, onDevice). Tests swap in a fake. */
    @VisibleForTesting
    var engineFactory: (Context, Boolean) -> ProbeEngine = { ctx, onDevice ->
        if (onDevice) {
            if (Build.VERSION.SDK_INT < 31) error("createOnDeviceSpeechRecognizer needs API 31")
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) error("isOnDeviceRecognitionAvailable is false")
            SpeechRecognizerEngine(SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx))
        } else SpeechRecognizerEngine(SpeechRecognizer.createSpeechRecognizer(ctx))
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var clip: Pair<String, TtsClip>? = null

    // onRmsChanged arrives ~every 10-50 ms; it is summarised per window, not logged per call.
    private var rmsCount = 0
    private var rmsMin = Float.MAX_VALUE
    private var rmsMax = -Float.MAX_VALUE
    private var rmsLast = 0f
    private var rmsWindowStart = 0L
    private var rmsSeenThisSession = false
    private var buffers = 0

    fun init(context: Context) {
        if (app == null) app = context.applicationContext
    }

    fun logFile(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, LOG_FILE) }

    /** Thread-safe: off-main callers (TTS, the audio feed) are posted to the main thread. */
    fun log(event: String, s: Int = session) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { log(event, s) }; return }
        val line = ProbeFormat.line(SystemClock.elapsedRealtimeNanos() - t0, s, event)
        _lines.value = _lines.value + line
        app?.let { ctx -> runCatching { logFile(ctx)?.appendText(line + "\n") } }
    }

    fun clear(context: Context) {
        _lines.value = emptyList()
        runCatching { logFile(context)?.delete() }
    }

    fun text(): String = _lines.value.joinToString("\n")

    // ---- capability report --------------------------------------------------------------

    fun report(context: Context) {
        init(context)
        // Mid-trial (a relaunch, or Report pressed), the trial's clock must keep running:
        // resetting it would re-time every later line of that trial.
        if (!_running.value) t0 = SystemClock.elapsedRealtimeNanos()
        val pm = context.packageManager
        log("==== report ${wall()} ====", 0)
        log("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}", 0)
        log("build: ${Build.FINGERPRINT}", 0)
        log("app: ${context.packageName}", 0)
        log("RECORD_AUDIO granted: ${granted(context, Manifest.permission.RECORD_AUDIO)}", 0)
        log("isRecognitionAvailable: ${SpeechRecognizer.isRecognitionAvailable(context)}", 0)
        if (Build.VERSION.SDK_INT >= 31) {
            log("isOnDeviceRecognitionAvailable: ${SpeechRecognizer.isOnDeviceRecognitionAvailable(context)}", 0)
        } else log("isOnDeviceRecognitionAvailable: n/a below API 31", 0)
        val secure = runCatching { Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }
        log("Settings.Secure voice_recognition_service: ${secure.getOrElse { "unreadable (${it.javaClass.simpleName})" }}", 0)
        val services = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
        log("RecognitionService resolvers: ${services.size}", 0)
        services.forEach { log("  service ${it.serviceInfo.packageName}/${it.serviceInfo.name} v${version(context, it.serviceInfo.packageName)}", 0) }
        val activities = pm.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
        log("ACTION_RECOGNIZE_SPEECH activities: ${activities.size}", 0)
        activities.forEach { log("  activity ${it.activityInfo.packageName}/${it.activityInfo.name}", 0) }
        log("default TTS engine: ${runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH) }.getOrNull()}", 0)
        if (Build.VERSION.SDK_INT >= 33 && !_running.value && SpeechRecognizer.isRecognitionAvailable(context)) {
            checkSupport(context, onDevice = false)
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) checkSupport(context, onDevice = true)
        }
    }

    private fun checkSupport(context: Context, onDevice: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val label = if (onDevice) "on-device" else "default"
        val r = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrElse { log("checkRecognitionSupport($label): create threw ${it.message}", 0); return }
        var answered = false
        main.postDelayed({ if (!answered) { log("checkRecognitionSupport($label): no callback within 5 s", 0); r.destroy() } }, 5_000)
        try {
            r.checkRecognitionSupport(baseIntent(context), ContextCompat.getMainExecutor(context), object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    answered = true
                    log("checkRecognitionSupport($label): installed=${s.installedOnDeviceLanguages} pending=${s.pendingOnDeviceLanguages} " +
                        "supported(on-device)=${s.supportedOnDeviceLanguages.size} online=${s.onlineLanguages.size}" +
                        (if (s.onlineLanguages.isNotEmpty()) " e.g. ${s.onlineLanguages.take(5)}" else ""), 0)
                    r.destroy()
                }
                override fun onError(error: Int) {
                    answered = true
                    log("checkRecognitionSupport($label): onError ${ProbeFormat.errorName(error)}", 0)
                    r.destroy()
                }
            })
        } catch (e: Exception) {
            log("checkRecognitionSupport($label): threw ${e.javaClass.simpleName}: ${e.message}", 0)
            r.destroy()
        }
    }

    /** Asks the on-device recognizer to fetch its language model (API 33+). */
    fun downloadModel(context: Context) {
        init(context)
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            log("triggerModelDownload: needs API 33 and an on-device recognizer", 0); return
        }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        runCatching { r.triggerModelDownload(baseIntent(context)) }
            .onSuccess { log("triggerModelDownload: requested (watch the notification shade)", 0) }
            .onFailure { log("triggerModelDownload: threw ${it.message}", 0) }
        main.postDelayed({ r.destroy() }, 2_000)
    }

    // ---- trials -------------------------------------------------------------------------

    fun start(context: Context, cfg: TrialConfig) {
        init(context)
        val ctx = app!!
        if (_running.value) { log("start ignored: a trial is already running"); return }
        t0 = SystemClock.elapsedRealtimeNanos()
        trial++
        session = 0; endedSession = 0; restartsDone = 0; stopRequested = false
        config = cfg
        _running.value = true
        log("==== trial $trial ${wall()} ====", 0)
        log("config: $cfg", 0)
        if (!granted(ctx, Manifest.permission.RECORD_AUDIO)) log("warning: RECORD_AUDIO is not granted", 0)
        registerScreenReceiver(ctx)
        if (cfg.wakeLock) {
            wakeLock = ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ideaforge:probe").apply { acquire(15 * 60_000L) }
            log("partial wake lock acquired", 0)
        }
        if (cfg.foregroundService) ProbeMicService.start(ctx)
        val begin = {
            if (cfg.delayMs > 0) log("waiting ${cfg.delayMs} ms before the first startListening (turn the screen off now if testing that)", 0)
            val t = trial
            main.postDelayed({ if (trial == t && _running.value && session == 0) startSession() }, cfg.delayMs)
        }
        val phrase = cfg.ttsPhrase
        when {
            phrase == null -> begin()
            Build.VERSION.SDK_INT < 33 -> { log("EXTRA_AUDIO_SOURCE needs API 33", 0); finish("unsupported") }
            clip?.first == phrase -> begin()
            else -> {
                log("synthesising \"$phrase\" with the device TTS", 0)
                TtsClip.synthesize(ctx, phrase) { c, why ->
                    main.post {
                        log("tts: $why" + (c?.let { " (${it.sampleRate} Hz, ${it.channels} ch, ${it.pcm.size / (it.sampleRate * it.channels * 2 / 1000)} ms)" } ?: ""), 0)
                        if (c == null) finish("no audio") else { clip = phrase to c; begin() }
                    }
                }
            }
        }
    }

    /** The user's Stop: stopListening, and no further restarts. */
    fun stopListening() {
        if (!_running.value) return
        stopRequested = true
        log("user: stopListening()")
        recognizer?.stopListening()
        // A recognizer that never answers stopListening must not leave the trial running.
        // Scoped to this trial: `session` restarts at 1 every trial, so an unscoped timer from
        // an earlier trial could end a later one.
        val t = trial
        main.postDelayed({ if (trial == t && stopRequested && _running.value) finish("no end callback 5 s after stopListening") }, 5_000)
    }

    fun cancel() {
        if (!_running.value) return
        log("user: cancel()")
        recognizer?.cancel()
        finish("cancelled")
    }

    private fun baseIntent(context: Context) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private fun trialIntent(ctx: Context): Intent = baseIntent(ctx).apply {
        val cfg = config
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, cfg.partials)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, cfg.preferOffline)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        if (cfg.silenceExtras) {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 8_000L)
        }
        val phrase = cfg.ttsPhrase
        if (phrase != null && Build.VERSION.SDK_INT >= 33) {
            val c = clip!!.second
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, c.pipe(LEAD_MS, TAIL_MS) { log(it) })
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, c.channels)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, c.sampleRate)
        }
        if (cfg.segmented && Build.VERSION.SDK_INT >= 33) {
            // The value names the extra that bounds the segmented session.
            if (phrase != null) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            else {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 20_000L)
            }
        }
    }

    private fun startSession() {
        val ctx = app ?: return
        if (recognizer == null || config.recreateOnRestart) {
            recognizer?.let { log("destroying the previous recognizer before restart"); it.destroy() }
            recognizer = null
            val created = runCatching { engineFactory(ctx, config.onDevice) }
            val r = created.getOrElse { log("create recognizer failed: ${it.message}"); finish("no recognizer"); return }
            r.setRecognitionListener(listener)
            recognizer = r
            log("created ${if (config.onDevice) "createOnDeviceSpeechRecognizer" else "createSpeechRecognizer"}")
        }
        session++
        rmsSeenThisSession = false; buffers = 0; resetRms()
        val intent = runCatching { trialIntent(ctx) }.getOrElse { log("intent failed: ${it.message}"); finish("intent"); return }
        val extras = intent.extras?.keySet()?.sorted()?.joinToString { k -> k.removePrefix("android.speech.extra.") + "=" + describe(intent.extras!!, k) }
        log("startListening($extras)")
        try {
            recognizer!!.startListening(intent)
        } catch (e: Exception) {
            log("startListening threw ${e.javaClass.simpleName}: ${e.message}")
            finish("startListening threw")
            return
        }
        val mine = session
        val t = trial
        main.postDelayed({
            if (trial == t && _running.value && session == mine && endedSession < mine) {
                log("watchdog: no onResults/onError ${SESSION_WATCHDOG_MS / 1000} s after startListening; cancelling")
                recognizer?.cancel()
                finish("watchdog")
            }
        }, SESSION_WATCHDOG_MS)
    }

    private fun sessionEnded(why: String) {
        flushRms()
        if (!_running.value) { log("($why arrived after the trial finished)"); return }
        if (endedSession >= session) { log("($why for a session already ended)"); return }
        endedSession = session
        val more = if (config.restarts == TrialConfig.UNTIL_STOP) session < TrialConfig.MAX_SESSIONS else restartsDone < config.restarts
        if (more && !stopRequested) {
            restartsDone++
            log("restart #$restartsDone: startListening again, immediately, inside the $why callback")
            startSession()
        } else finish("ended by $why")
    }

    private fun finish(why: String) {
        val ctx = app
        flushRms()
        recognizer?.let { it.destroy(); log("recognizer destroyed") }
        recognizer = null
        if (config.foregroundService && ctx != null) ProbeMicService.stop(ctx)
        wakeLock?.let { if (it.isHeld) it.release(); log("wake lock released", 0) }
        wakeLock = null
        screenReceiver?.let { r -> runCatching { ctx?.unregisterReceiver(r) } }
        screenReceiver = null
        val wasRunning = _running.value
        _running.value = false
        if (wasRunning) log("==== trial $trial finished: $why; sessions=$session restarts=$restartsDone ====", 0)
        if (ctx != null) logFile(ctx)?.let { log("log file: ${it.absolutePath}", 0) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { flushRms(); log("onReadyForSpeech ${dump(params)}") }
        override fun onBeginningOfSpeech() { flushRms(); log("onBeginningOfSpeech") }
        override fun onRmsChanged(rmsdB: Float) {
            if (!rmsSeenThisSession) { rmsSeenThisSession = true; log("first onRmsChanged rmsdB=$rmsdB") }
            val now = SystemClock.elapsedRealtime()
            if (rmsCount == 0) rmsWindowStart = now
            rmsCount++; rmsLast = rmsdB
            if (rmsdB < rmsMin) rmsMin = rmsdB
            if (rmsdB > rmsMax) rmsMax = rmsdB
            if (now - rmsWindowStart >= RMS_WINDOW_MS) flushRms()
        }
        override fun onBufferReceived(buffer: ByteArray?) { buffers++ }
        override fun onEndOfSpeech() { flushRms(); log("onEndOfSpeech") }
        override fun onError(error: Int) {
            flushRms(); log("onError ${ProbeFormat.errorName(error)}")
            sessionEnded("onError")
        }
        override fun onResults(results: Bundle?) {
            flushRms(); log("onResults ${results(results)}")
            sessionEnded("onResults")
        }
        override fun onPartialResults(partialResults: Bundle?) { flushRms(); log("onPartialResults ${results(partialResults)}") }
        override fun onEvent(eventType: Int, params: Bundle?) { log("onEvent type=$eventType ${dump(params)}") }
        override fun onSegmentResults(segmentResults: Bundle) { flushRms(); log("onSegmentResults ${results(segmentResults)}") }
        override fun onEndOfSegmentedSession() {
            flushRms(); log("onEndOfSegmentedSession")
            sessionEnded("onEndOfSegmentedSession")
        }
    }

    private fun resetRms() { rmsCount = 0; rmsMin = Float.MAX_VALUE; rmsMax = -Float.MAX_VALUE }

    private fun flushRms() {
        if (rmsCount == 0) return
        log(String.format(Locale.ROOT, "onRmsChanged x%d min=%.2f max=%.2f last=%.2f%s", rmsCount, rmsMin, rmsMax, rmsLast,
            if (buffers > 0) " buffers=$buffers" else ""))
        resetRms()
    }

    private fun results(b: Bundle?): String {
        if (b == null) return "<null bundle>"
        val texts = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val scores = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        val rest = b.keySet().filter { it != SpeechRecognizer.RESULTS_RECOGNITION && it != SpeechRecognizer.CONFIDENCE_SCORES }
        return ProbeFormat.hypotheses(texts, scores) + if (rest.isEmpty()) "" else " extra{" + rest.sorted().joinToString { "$it=${describe(b, it)}" } + "}"
    }

    private fun dump(b: Bundle?): String =
        if (b == null || b.isEmpty) "{}" else b.keySet().sorted().joinToString(prefix = "{", postfix = "}") { "$it=${describe(b, it)}" }

    @Suppress("DEPRECATION")
    private fun describe(b: Bundle, key: String): String = when (val v = b.get(key)) {
        null -> "null"
        is FloatArray -> v.toList().toString()
        is IntArray -> v.toList().toString()
        is LongArray -> v.toList().toString()
        is Array<*> -> v.toList().toString()
        is Bundle -> dump(v)
        is android.os.ParcelFileDescriptor -> "<pipe>"
        else -> v.toString().take(400)
    }

    private fun registerScreenReceiver(ctx: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { log("broadcast ${i?.action?.substringAfterLast('.')}", 0) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(ctx, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = r
    }

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    private fun version(c: Context, pkg: String) = runCatching { c.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

    private fun wall() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date())
}
