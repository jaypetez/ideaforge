package io.github.jaypetez.ideaforge.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The recognition service holds the microphone. A second capture of our own (an AudioRecord
 * for a level meter, say) is how Android Chrome starved its recogniser silently: the bars
 * moved and no word ever arrived (meterStarvesRecognition in src/voice/vad.js). The meter
 * here is onRmsChanged and nothing else, so nothing in the app may open a recorder.
 */
class RecognizerAudioTest {
    private val banned = listOf("AudioRecord", "MediaRecorder", "android.media.audiofx")

    @Test
    fun nothingInTheAppOpensItsOwnMicrophoneCapture() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/kotlin"), File(it, "app/src/main/kotlin")) }
            .first { File(it, "io/github/jaypetez/ideaforge/voice").isDirectory }
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("found the voice package", sources.any { it.name == "Recognizer.kt" })
        val offences = sources.flatMap { file ->
            codeOnly(file.readText()).lines().withIndex().flatMap { (i, line) ->
                banned.filter { it in line }.map { "${file.name}:${i + 1}: $it" }
            }
        }
        assertTrue("the app opens a capture of its own:\n${offences.joinToString("\n")}", offences.isEmpty())
    }

    /** Comments may explain the rule; code may not break it. */
    private fun codeOnly(src: String): String =
        src.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
            .lines().joinToString("\n") { it.substringBefore("//") }
}
