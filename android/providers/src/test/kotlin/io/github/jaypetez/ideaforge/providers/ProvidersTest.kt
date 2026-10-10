package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.Core
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProvidersTest {
    @Test
    fun readsTheSchemaFromCore() {
        assertEquals(Core.SESSION_SCHEMA, Providers.sessionSchema)
    }

    @Test
    fun androidFrameworkIsNotOnTheProvidersClasspath() {
        assertFailsWith<ClassNotFoundException> { Class.forName("android.app.Activity") }
    }
}
