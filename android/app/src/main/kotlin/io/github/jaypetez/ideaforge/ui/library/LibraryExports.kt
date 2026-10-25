package io.github.jaypetez.ideaforge.ui.library

import android.net.Uri
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.exportFilename
import io.github.jaypetez.ideaforge.core.sessionDisplayTitle
import io.github.jaypetez.ideaforge.ui.result.DocumentSink
import io.github.jaypetez.ideaforge.ui.result.exportFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Immutable canonical bytes and identity, captured before yielding to another activity. */
data class LibraryExport(val ideaId: String, val title: String, val filename: String, val markdown: String)

enum class LibraryExportResult { Saved, Cancelled, SaveUnavailable, SaveFailed, ShareUnavailable, ShareFailed, SourceLost }

data class LibraryExportNotice(val title: String, val result: LibraryExportResult)

data class LibraryExportState(val busy: Boolean = false, val notice: LibraryExportNotice? = null)

/**
 * One outstanding document picker/write. It never writes a session or owns selection/focus.
 * Its notice is separate from mutation errors, so a late document result cannot clear them.
 */
class LibraryExports(
    private val source: (String) -> Session?,
    private val sink: DocumentSink,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(LibraryExportState())
    val state = _state.asStateFlow()
    private var pending: LibraryExport? = null

    private fun snapshot(id: String): LibraryExport? {
        if (_state.value.busy) return null
        val session = source(id) ?: run {
            _state.value = LibraryExportState(notice = LibraryExportNotice("", LibraryExportResult.SourceLost))
            return null
        }
        if (!LibraryViewModel.item(session).canExport) return null
        return LibraryExport(id, sessionDisplayTitle(session), exportFilename(session), exportFor(session))
    }

    fun share(id: String): LibraryExport? = snapshot(id)?.also {
        _state.value = LibraryExportState()
    }

    fun shareFailed(export: LibraryExport, unavailable: Boolean) {
        _state.value = LibraryExportState(notice = LibraryExportNotice(export.title,
            if (unavailable) LibraryExportResult.ShareUnavailable else LibraryExportResult.ShareFailed))
    }

    fun beginSave(id: String): LibraryExport? = snapshot(id)?.also {
        pending = it
        _state.value = LibraryExportState(busy = true)
    }

    fun saveUnavailable(export: LibraryExport) {
        if (pending !== export) return
        pending = null
        _state.value = LibraryExportState(notice = LibraryExportNotice(export.title, LibraryExportResult.SaveUnavailable))
    }

    fun saveResult(uri: Uri?) {
        val export = pending
        pending = null
        if (export == null) {
            // A recreated process must not use the current selection for an old picker.
            if (uri != null && !_state.value.busy && _state.value.notice == null) {
                _state.value = LibraryExportState(notice = LibraryExportNotice("", LibraryExportResult.SourceLost))
            }
            return
        }
        if (uri == null) {
            _state.value = LibraryExportState(notice = LibraryExportNotice(export.title, LibraryExportResult.Cancelled))
            return
        }
        scope.launch {
            val result = try {
                withContext(io) { sink.openOutput(uri).use { it.write(export.markdown.encodeToByteArray()) } }
                LibraryExportResult.Saved
            } catch (_: IOException) {
                LibraryExportResult.SaveFailed
            } catch (_: SecurityException) {
                LibraryExportResult.SaveFailed
            } catch (_: IllegalArgumentException) {
                LibraryExportResult.SaveFailed
            }
            _state.value = LibraryExportState(notice = LibraryExportNotice(export.title, result))
        }
    }

    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }
}
