package io.github.jaypetez.ideaforge.ui.settings

import android.net.Uri
import io.github.jaypetez.ideaforge.core.archiveSession
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.renameSession
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import io.github.jaypetez.ideaforge.core.setSessionTags
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Android half of the backup interop fixtures (see tools/backup-fixtures.mjs for the
 * pair). Known ideas go through the real export path, store to SAF document, and the bytes
 * must equal test/fixtures/backup-from-android.json, which test/backup.test.mjs imports
 * through the web's parseBackup and mergeImported. If this fails because the export changed
 * on purpose, regenerate it and let the web test judge the new file:
 *
 *     IDEAFORGE_UPDATE_FIXTURES=1 ./gradlew :app:testDebugUnitTest --rerun-tasks \
 *         --tests '*BackupInteropTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupInteropTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_760_100_000_000L
    private fun at(seconds: Int) = num(t0 + seconds * 1000L)

    private fun answered(id: String, device: String, answer: String, start: Int) =
        submitAnswer(seedTurn(createSession(id, at(start), str(device)), at(start)), str(answer), now = at(start + 30))

    /** Three ideas as a phone would hold them: renamed and tagged, archived, and plain. */
    private fun phoneLibrary() = listOf(
        setSessionTags(
            renameSession(
                answered(
                    "s_android_cafe_rota", "phone",
                    "A rota app for the café so Saturday staff can swap shifts without a group chat, in under a minute.",
                    0,
                ),
                str("Café shift swaps ☕"), at(40),
            ),
            arr(listOf(str("work"), str("rota"))), at(41),
        ),
        archiveSession(
            answered(
                "s_android_bike_log", "phone",
                "A log of every repair on my bike, so I know when the chain was last replaced.",
                100,
            ),
            at(160),
        ),
        answered(
            "s_android_reading", "tablet",
            "Something that turns the highlights in my e-reader into a weekly \"what did I learn\" note.",
            200,
        ),
    )

    @Test
    fun theAndroidExportMatchesTheFixtureTheWebImports() {
        val store = SessionStore(File(tmp.root, "sessions"))
        runBlocking { for (s in phoneLibrary()) store.save(s) }
        val docs = FakeDocuments()
        val uri = Uri.parse("content://test/export.json")
        val clock = Clock { t0 + 3_600_000L }
        val vm = BackupViewModel(store, docs, clock, IdGen(clock), BackupJobs(CoroutineScope(SupervisorJob() + Dispatchers.Default)))

        vm.export(uri)
        val state = vm.settled()
        assertFalse(state.message, state.failed)
        assertEquals("Backed up 3 ideas.", state.message)
        assertEquals("ideaforge-backup-2025-10-10.json", vm.exportFilename())

        val actual = docs.outputs.getValue(uri).toByteArray().decodeToString()
        val fixture = File(fixturesDir, "backup-from-android.json")
        if (System.getenv("IDEAFORGE_UPDATE_FIXTURES") == "1") fixture.writeText(actual)
        val expected = if (fixture.exists()) fixture.readText() else ""
        assertEquals(
            "the Android export no longer matches test/fixtures/backup-from-android.json; " +
                "if that is intended, regenerate it (see this class's comment) and run npm test",
            expected, actual,
        )
    }
}
