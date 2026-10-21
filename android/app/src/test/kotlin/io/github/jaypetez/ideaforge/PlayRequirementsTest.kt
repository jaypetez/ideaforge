package io.github.jaypetez.ideaforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PlayRequirementsTest {
    private fun checkTarget(variant: String) {
        val property = "ideaforge.${variant}Manifest"
        val path = System.getProperty(property) ?: error("$property is not set")
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(path))
        val sdk = manifest.getElementsByTagName("uses-sdk")
        assertEquals("$variant merged manifest must declare uses-sdk", 1, sdk.length)
        val target = (sdk.item(0) as Element)
            .getAttributeNS("http://schemas.android.com/apk/res/android", "targetSdkVersion")
            .toIntOrNull() ?: error("$variant merged manifest has no numeric targetSdkVersion")
        assertTrue("$variant effective targetSdk $target is below the Play minimum 36", target >= 36)
    }

    @Test
    fun theDebugMergedManifestTargetsAtLeastApi36() = checkTarget("debug")

    @Test
    fun theReleaseMergedManifestTargetsAtLeastApi36() = checkTarget("release")
}
