package io.github.jaypetez.ideaforge.ui.result

import android.net.Uri
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.vectors.Case
import io.github.jaypetez.ideaforge.core.vectors.Vectors
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.File

/** The golden vectors the result screen is held to, by case name. */
object ResultVectors {
    private val markdown by lazy { Vectors.load("markdown").cases.associateBy { it.name } }
    private val session by lazy { Vectors.load("session").cases.associateBy { it.name } }
    val walkthrough: List<Case> by lazy { Vectors.load("walkthrough").cases }

    fun markdown(name: String): Case = markdown.getValue(name)
    fun session(name: String): Case = session.getValue(name)

    /** The walkthrough's written-up idea: the input of `buildExport final`. */
    val written: JsonObject get() = markdown("buildExport final").args[0] as JsonObject

    /** The same idea after a failed wrap-up: the input of `buildExport after a failed synthesis`. */
    val unwritten: JsonObject get() = markdown("buildExport after a failed synthesis").args[0] as JsonObject

    fun expected(case: Case): String = (case.expect["value"] as JsonPrimitive).content
}

/** A SAF document sink in memory: what Save .md wrote, per URI. */
class FakeSink(var fail: IOException? = null) : DocumentSink {
    val outputs = LinkedHashMap<Uri, ByteArrayOutputStream>()

    override fun openOutput(uri: Uri): OutputStream {
        fail?.let { throw it }
        return ByteArrayOutputStream().also { outputs[uri] = it }
    }
}

/** A store holding [sessions], and a ResultViewModel over it, on [dispatcher]. */
class ResultHarness(dir: File, private val dispatcher: CoroutineDispatcher, vararg sessions: JsonObject) {
    // Unconfined: the seeding below runs in runBlocking, which a test dispatcher would stall.
    val store = SessionStore(dir, io = Dispatchers.Unconfined)
    val worker = SessionWorker()
    val sink = FakeSink()
    val writes = CoroutineScope(SupervisorJob() + dispatcher)
    val inference = io.github.jaypetez.ideaforge.data.testInferenceConsent(writes)

    init {
        runBlocking { for (s in sessions) store.save(s) }
    }

    fun vm(id: String = "s_walkthrough", now: Long = 1_760_000_007_000) =
        ResultViewModel(id, store, worker, Clock { now }, sink, writes, inference, io = dispatcher)

    fun stored(id: String = "s_walkthrough"): JsonObject? = store.get(id)
}

fun JsonObject.synthesisText(): String? = (this["synthesis"]["text"] as? JsonPrimitive)?.content
