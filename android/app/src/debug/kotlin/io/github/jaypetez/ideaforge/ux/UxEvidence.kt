package io.github.jaypetez.ideaforge.ux

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** A requested run is fail-closed; ordinary instrumented walkthroughs need no capture args. */
data class UxCaptureRequest(val sha: String, val window: String, val states: List<String>?, val mode: String = "matrix") {
    companion object {
        fun from(args: Map<String, String>): UxCaptureRequest? {
            val captureArgs = args.keys.filter { it.startsWith("uxCapture") }
            if (captureArgs.isEmpty()) return null
            val mode = args["uxCapture"]
            require(mode in setOf("matrix", "walkthrough")) { "requested UX evidence needs uxCapture=matrix or walkthrough" }
            val sha = args["uxCaptureSha"].orEmpty()
            require(Regex("[0-9a-fA-F]{40}").matches(sha)) { "requested UX evidence needs an explicit 40-character uxCaptureSha" }
            val window = args["uxCaptureWindow"] ?: "all"
            require(window in setOf("all", "compact", "expanded")) { "invalid uxCaptureWindow: $window" }
            // AGP parses commas in runner-property values. '+' survives Gradle, adb and
            // PowerShell unchanged, so a multi-state request cannot silently become one.
            val states = args["uxCaptureStates"]?.split('+')?.also {
                require(it.isNotEmpty() && it.all { id -> Regex("[a-z0-9-]+").matches(id) } && it.distinct().size == it.size) {
                    "uxCaptureStates must be nonempty unique fixture ids"
                }
            }
            require(mode != "walkthrough" || states == null) { "walkthrough captures do not accept uxCaptureStates" }
            require(captureArgs.all { it in setOf("uxCapture", "uxCaptureSha", "uxCaptureWindow", "uxCaptureStates") }) {
                "unknown UX capture arguments: $captureArgs"
            }
            return UxCaptureRequest(sha.lowercase(), window, states, checkNotNull(mode))
        }
    }
}

data class UxBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(other: UxBounds, tolerance: Float = 1f): Boolean =
        other.width > 0 && other.height > 0 &&
            other.left >= left - tolerance && other.top >= top - tolerance &&
            other.right <= right + tolerance && other.bottom <= bottom + tolerance
    fun intersect(other: UxBounds) = UxBounds(
        max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom),
    )
}

data class UxPixels(val colours: Int, val meanLuma: Double, val dominantRgb: Int, val width: Int = 0, val height: Int = 0)

/** Shared assertions used by the device recorder and regression tests, not screenshot goldens. */
object UxEvidence {
    fun finish(primaryError: Throwable?, cleanup: List<() -> Unit>, finalize: (List<Throwable>) -> Unit) {
        val errors = mutableListOf<Throwable>()
        primaryError?.let(errors::add)
        for (operation in cleanup) {
            try { operation() } catch (error: Throwable) { errors += error }
        }
        try { finalize(errors.toList()) } catch (error: Throwable) { errors += error }
        errors.firstOrNull()?.let { first ->
            errors.drop(1).filter { it !== first }.forEach(first::addSuppressed)
            throw first
        }
    }

    fun unrecorded(expected: Set<String>, recorded: Set<String>): Set<String> = expected - recorded

    fun requireControl(label: String, bounds: UxBounds, viewport: UxBounds, semanticLabel: String, actionable: Boolean) {
        check(semanticLabel.isNotBlank()) { "required control $label has no semantic label" }
        check(actionable) { "required control $label has no action/edit semantics" }
        check(viewport.contains(bounds)) { "required control $label is clipped or unreachable: $bounds outside $viewport" }
    }

    fun requireLabel(label: String, width: Float, height: Float, measuredWidth: Int, measuredHeight: Int, ellipsized: Boolean) {
        check(!ellipsized && width <= measuredWidth + 1f && height <= measuredHeight + 1f) {
            "required control $label has a clipped/ellipsized text label"
        }
    }

    fun requireRendered(bitmap: Bitmap, content: UxBounds): UxPixels {
        check(UxBounds(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()).contains(content)) {
            "screenshot pixels do not cover the measured app viewport"
        }
        val left = max(0, content.left.toInt())
        val top = max(0, content.top.toInt())
        val right = min(bitmap.width, content.right.toInt())
        val bottom = min(bitmap.height, content.bottom.toInt())
        check(right > left && bottom > top) { "no app pixels in screenshot" }
        // Sample the app area, not the status bar: SystemUI can render on an otherwise blank
        // ATD screen. Quantisation discards compression/noise without relying on theme colours.
        val colours = HashMap<Int, Int>()
        var luma = 0.0
        var count = 0
        val dx = max(1, (right - left) / 160)
        val dy = max(1, (bottom - top) / 160)
        for (y in top until bottom step dy) {
            for (x in left until right step dx) {
                val pixel = bitmap.getPixel(x, y)
                val colour = pixel and 0x00F8F8F8
                colours[colour] = (colours[colour] ?: 0) + 1
                luma += ((pixel shr 16) and 255) * 0.2126 + ((pixel shr 8) and 255) * 0.7152 + (pixel and 255) * 0.0722
                count++
            }
        }
        check(colours.size >= 8) { "blank/unrendered app screenshot (${colours.size} distinct sampled colours); ATD is not visual evidence" }
        return UxPixels(colours.size, luma / count, colours.maxBy { it.value }.key, bitmap.width, bitmap.height)
    }

    fun requireTheme(theme: String, pixels: UxPixels) {
        check(if (theme == "dark") pixels.meanLuma < 130 else pixels.meanLuma > 130) {
            "requested $theme theme did not reach the app pixels (mean luma ${pixels.meanLuma})"
        }
    }

    fun requireComplete(expected: Set<String>, captured: Set<String>, failures: List<String>) {
        check(expected == captured) { "missing requested UX evidence: ${unrecorded(expected, captured)}; unexpected: ${captured - expected}" }
        check(failures.isEmpty()) { "invalid requested UX evidence:\n${failures.joinToString("\n")}" }
    }
}
