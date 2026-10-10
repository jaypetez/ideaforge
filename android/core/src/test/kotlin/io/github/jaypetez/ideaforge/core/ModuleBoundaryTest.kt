package io.github.jaypetez.ideaforge.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModuleBoundaryTest {
    // :core must stay buildable and testable without Android. If someone adds an Android
    // dependency to this module, the framework classes become loadable and this fails.
    @Test
    fun androidFrameworkIsNotOnTheCoreClasspath() {
        assertFailsWith<ClassNotFoundException> { Class.forName("android.app.Activity") }
        assertFailsWith<ClassNotFoundException> { Class.forName("android.content.Context") }
    }

    // The schema number is owned by the JS core. A port that silently disagrees about it
    // would mis-migrate every session it imports.
    @Test
    fun schemaMatchesTheJsCore() {
        val session = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/core/session.js") }
            .first { it.isFile }
            .readText()
        val js = Regex("""export const SCHEMA = (\d+);""").find(session)?.groupValues?.get(1)
        assertEquals(Core.SESSION_SCHEMA.toString(), js, "SCHEMA in src/core/session.js")
    }
}
