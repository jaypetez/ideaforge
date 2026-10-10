package io.github.jaypetez.ideaforge

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Nothing leaves the device through Android backup or device-to-device transfer. The
 * keyring's Keystore key cannot follow it, and transcripts belong only in a backup the user
 * exports on purpose. Checked twice: in the source files (so a reviewer sees the rule fail
 * where it was broken) and in the manifest the app actually runs with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRulesTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** Every storage domain data-extraction-rules can name. */
    private val domains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    private fun source(path: String): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/$path") }
        .first { it.isFile }

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file).documentElement

    @Test
    fun theSourceManifestTurnsBackupOff() {
        val app = parse(source("AndroidManifest.xml")).getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(androidNs, "allowBackup"))
        assertEquals("false", app.getAttributeNS(androidNs, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(androidNs, "dataExtractionRules"))
    }

    @Test
    fun theExtractionRulesExcludeEveryDomainFromCloudBackupAndDeviceTransfer() {
        val root = parse(source("res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = root.getElementsByTagName(section)
            assertEquals("exactly one <$section>", 1, nodes.length)
            val el = nodes.item(0) as Element
            assertEquals("<$section> must include nothing", 0, el.getElementsByTagName("include").length)
            val excludes = el.getElementsByTagName("exclude")
            val excluded = (0 until excludes.length).map { excludes.item(it) as Element }
                .filter { it.getAttribute("path") == "." }
                .map { it.getAttribute("domain") }
                .toSet()
            assertEquals("<$section> excludes", domains, excluded)
        }
    }

    @Test
    fun theRunningAppHasBackupDisabled() {
        val info = RuntimeEnvironment.getApplication().applicationInfo
        assertTrue("allowBackup is on in the merged manifest", info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0)
    }
}
