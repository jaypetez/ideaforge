package io.github.jaypetez.ideaforge.probe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Debug-only recognizer probe (issue #89). Reached with
 *
 *     adb shell am start -n io.github.jaypetez.ideaforge.debug/io.github.jaypetez.ideaforge.MainActivity --es probe recognizer
 *
 * The activity itself is not exported. Trial settings in the launch extras only pre-fill the
 * toggles (see [configFrom]); nothing listens until someone taps Start, because MainActivity
 * is exported and any installed app could send it these extras. `--ez autostart true` starts
 * a trial at once only while the shell has set `adb shell setprop debug.ideaforge.probe 1`,
 * which no app can do. The release build has neither this class nor the RECORD_AUDIO
 * permission it needs; RecognizerProbeTest checks the release merged manifest for that.
 */
class RecognizerProbeActivity : ComponentActivity() {
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        ProbeRunner.log("permissions: $result", 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ProbeRunner.init(this)
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) {
            askPermissions.launch(wanted.toTypedArray())
            // A relaunch during a running trial must not interleave a report into it.
            if (ProbeRunner.running.value) ProbeRunner.log("activity created while a trial runs", 0) else ProbeRunner.report(this)
        }
        val initial = configFrom(intent)
        if (savedInstanceState == null && intent.getBooleanExtra("autostart", false)) {
            if (shellAllowsAutostart()) ProbeRunner.start(this, initial)
            else ProbeRunner.log("autostart ignored: run `adb shell setprop $AUTOSTART_PROP 1` first", 0)
        }
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ProbeScreen(initial) } } }
    }

    override fun onStart() { super.onStart(); ProbeRunner.log("activity onStart", 0) }
    override fun onStop() { ProbeRunner.log("activity onStop", 0); super.onStop() }

    private fun copy() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("recognizer probe", shareable()))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "IdeaForge recognizer probe log")
            .putExtra(Intent.EXTRA_TEXT, shareable())
        startActivity(Intent.createChooser(send, "Share probe log"))
    }

    /** A binder transaction tops out near 1 MB; the file always has the whole log. */
    private fun shareable(): String {
        // The file spans every run since the last Clear; memory holds only this process's lines.
        val all = ProbeRunner.logFile(this)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } ?: ProbeRunner.text()
        return if (all.length <= MAX_SHARE) all else "[first ${all.length - MAX_SHARE} chars trimmed; adb pull the file for all of it]\n" + all.takeLast(MAX_SHARE)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ProbeScreen(initial: TrialConfig) {
        var cfg by remember { mutableStateOf(initial) }
        val lines by ProbeRunner.lines.collectAsState()
        val running by ProbeRunner.running.collectAsState()
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp).testTag(SCREEN)) {
            Text("Recognizer probe" + if (running) " (running)" else "", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle("On-device", cfg.onDevice, !running) { cfg = cfg.copy(onDevice = it) }
                Toggle("Partials", cfg.partials, !running) { cfg = cfg.copy(partials = it) }
                Toggle("Prefer offline", cfg.preferOffline, !running) { cfg = cfg.copy(preferOffline = it) }
                Toggle("Silence extras", cfg.silenceExtras, !running) { cfg = cfg.copy(silenceExtras = it) }
                Toggle("Segmented", cfg.segmented, !running) { cfg = cfg.copy(segmented = it) }
                Toggle("Recreate on restart", cfg.recreateOnRestart, !running) { cfg = cfg.copy(recreateOnRestart = it) }
                Toggle("Mic FGS", cfg.foregroundService, !running) { cfg = cfg.copy(foregroundService = it) }
                Toggle("Wake lock", cfg.wakeLock, !running) { cfg = cfg.copy(wakeLock = it) }
                Toggle("Delay 10 s", cfg.delayMs > 0, !running) { cfg = cfg.copy(delayMs = if (it) 10_000 else 0) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Restarts:", Modifier.padding(top = 14.dp))
                for ((label, n) in listOf("0" to 0, "1" to 1, "3" to 3, "Until stop" to TrialConfig.UNTIL_STOP)) {
                    FilterChip(selected = cfg.restarts == n, enabled = !running, onClick = { cfg = cfg.copy(restarts = n) }, label = { Text(label) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Audio:", Modifier.padding(top = 14.dp))
                FilterChip(selected = cfg.ttsPhrase == null, enabled = !running, onClick = { cfg = cfg.copy(ttsPhrase = null) }, label = { Text("Mic") })
                PHRASES.forEachIndexed { i, p ->
                    FilterChip(selected = cfg.ttsPhrase == p, enabled = !running, onClick = { cfg = cfg.copy(ttsPhrase = p) }, label = { Text("TTS ${i + 1}") })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { ProbeRunner.start(this@RecognizerProbeActivity, cfg) }, enabled = !running) { Text("Start trial") }
                OutlinedButton(onClick = { ProbeRunner.stopListening() }, enabled = running) { Text("Stop") }
                OutlinedButton(onClick = { ProbeRunner.cancel() }, enabled = running) { Text("Cancel") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { ProbeRunner.report(this@RecognizerProbeActivity) }, enabled = !running) { Text("Report") }
                OutlinedButton(onClick = { ProbeRunner.downloadModel(this@RecognizerProbeActivity) }, enabled = !running) { Text("Get model") }
                OutlinedButton(onClick = { copy() }) { Text("Copy") }
                OutlinedButton(onClick = { share() }) { Text("Share") }
                OutlinedButton(onClick = { ProbeRunner.clear(this@RecognizerProbeActivity) }, enabled = !running) { Text("Clear") }
            }
            cfg.ttsPhrase?.let { Text("TTS phrase: \"$it\"", style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag(LOG), state = listState) {
                items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp) }
            }
            Row { Text("${lines.size} lines; file: Android/data/$packageName/files/${ProbeRunner.LOG_FILE}", fontSize = 10.sp) }
        }
    }

    companion object {
        /** Only the shell (or root) can set debug.* properties; an installed app cannot. */
        const val AUTOSTART_PROP = "debug.ideaforge.probe"

        /** Read through getprop: SystemProperties is hidden API. */
        @androidx.annotation.VisibleForTesting
        var shellAllowsAutostart: () -> Boolean = {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("getprop", AUTOSTART_PROP)).inputStream.bufferedReader().use { it.readText().trim() } == "1"
            }.getOrDefault(false)
        }

        const val SCREEN = "recognizer-probe"
        const val LOG = "recognizer-probe-log"
        private const val MAX_SHARE = 200_000

        /** Phrases for the TTS audio source: the trigger-word cases from CLAUDE.md's driving rules. */
        val PHRASES = listOf(
            "we went over budget last year",
            "I want to make a game like Tetris over",
            "skip this",
        )

        /**
         * Trial settings from launch extras, for scripted runs:
         * `--ez ondevice true --ez partials false --ez offline true --ez silence true --ez segmented true
         *  --ei restarts 3 --ez recreate true --ei delay 10000 --ez fgs true --ez wakelock true --ei tts 1`
         * (`tts` is 1-based into [PHRASES]; `restarts -1` means until stop).
         */
        fun configFrom(intent: Intent?): TrialConfig {
            val d = TrialConfig()
            if (intent == null) return d
            return TrialConfig(
                onDevice = intent.getBooleanExtra("ondevice", d.onDevice),
                partials = intent.getBooleanExtra("partials", d.partials),
                preferOffline = intent.getBooleanExtra("offline", d.preferOffline),
                silenceExtras = intent.getBooleanExtra("silence", d.silenceExtras),
                segmented = intent.getBooleanExtra("segmented", d.segmented),
                restarts = intent.getIntExtra("restarts", d.restarts),
                recreateOnRestart = intent.getBooleanExtra("recreate", d.recreateOnRestart),
                delayMs = intent.getIntExtra("delay", 0).toLong(),
                foregroundService = intent.getBooleanExtra("fgs", d.foregroundService),
                wakeLock = intent.getBooleanExtra("wakelock", d.wakeLock),
                ttsPhrase = intent.getIntExtra("tts", 0).takeIf { it in 1..PHRASES.size }?.let { PHRASES[it - 1] },
            )
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    FilterChip(selected = on, enabled = enabled, onClick = { change(!on) }, label = { Text(label) })
}
