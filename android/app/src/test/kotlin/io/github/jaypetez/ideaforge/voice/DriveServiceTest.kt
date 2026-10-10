package io.github.jaypetez.ideaforge.voice

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import io.github.jaypetez.ideaforge.installTestGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notification's Pause/Resume and Exit, delivered as the system would: the PendingIntent
 * each action carries is started against DriveService, which turns it into the same
 * controller call the stage's buttons make.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rig: DriveRig
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        rig = DriveRig(tmp.newFolder())
        installTestGraph().driveOverride = rig.controller
    }

    @After
    fun tearDown() = rig.close()

    private fun action(title: String): Intent {
        val n = DriveService.notification(context, rig.state)
        val a = n.actions.firstOrNull { it.title.toString() == title }
        assertNotNull("action $title in ${n.actions.map { it.title }}", a)
        return shadowOf(a!!.actionIntent).savedIntent
    }

    private fun deliver(intent: Intent): DriveService =
        Robolectric.buildService(DriveService::class.java, intent).create().startCommand(0, 1).get()

    @Test
    fun theServiceRunsInTheForegroundAsAMicrophoneServiceWithAnOngoingNotification() {
        rig.scripts += listOf(At(300, Ev.Ready), At(400, Ev.Rms(3f)))
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.listening })
        val service = deliver(Intent(context, DriveService::class.java).setAction(DriveService.ACTION_START))
        val shadow = shadowOf(service)
        val n = shadow.lastForegroundNotification
        assertNotNull(n)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
    }

    @Test
    fun pauseResumeAndExitFromTheNotificationDriveTheSameState() {
        rig.scripts += listOf(
            At(300, Ev.Ready), At(400, Ev.Rms(3f)), At(800, Ev.Partial("from the car", unstable = " park")),
            At(5000, Ev.Error(RecognizerError.NO_MATCH)),
        )
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.caption.contains("park") })

        deliver(action("Pause"))
        assertEquals(VoiceMode.PAUSED, rig.state.mode)
        rig.scheduler.runCurrent()
        assertEquals(CaptureState.IDLE, rig.recognizer.state.value)
        assertTrue("Pause keeps the draft: ${rig.draft()}", rig.until { rig.draft() == "from the car park" })
        val starts = rig.starts()
        val said = rig.speaker.said.size
        rig.advance(60_000)
        assertEquals("nothing starts after Pause", starts, rig.starts())
        assertEquals(said, rig.speaker.said.size)

        // Paused, the notification offers Resume, and Resume is a deliberate tap on it.
        deliver(action("Resume"))
        assertEquals(VoiceMode.ACTIVE, rig.state.mode)
        assertTrue(rig.until { rig.state.listening })

        val service = deliver(action("Exit"))
        assertEquals(VoiceExit.TYPED, rig.state.exit)
        assertTrue(shadowOf(service).isStoppedBySelf)
        val after = rig.starts()
        val spoken = rig.speaker.said.size
        rig.advance(60_000)
        assertEquals("nothing starts after Exit", after, rig.starts())
        assertEquals(spoken, rig.speaker.said.size)
        assertTrue(rig.model.requests.none { it.first == "complex" })
    }

    @Test
    fun aDriveEndedBeforeTheServiceStartsStillEntersTheForegroundThenStopsItself() {
        // serviceOn, then serviceOff at once: the START arrives after the drive has ended.
        rig.controller.start(rig.seeded, "typed")
        rig.controller.exit()
        val service = deliver(Intent(context, DriveService::class.java).setAction(DriveService.ACTION_START))
        assertEquals("entered the foreground first", 1, service.foregroundEntries)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun aStoodDownDriveStopsTheServiceFromInside() {
        rig.scripts += listOf(At(300, Ev.Ready), At(400, Ev.Rms(3f)))
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.listening })
        val service = deliver(Intent(context, DriveService::class.java).setAction(DriveService.ACTION_START))
        rig.speaker.next = { SpeakResult(SpeakStatus.FAILED, SpeakFailure.ENGINE_ERROR, "engine error") }
        rig.scripts += emptyList<At>()
        // A word-less miss makes the loop speak again, and that speech fails fatally.
        assertTrue(rig.until { rig.state.mode == VoiceMode.BLOCKED })
        rig.advance(100)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
