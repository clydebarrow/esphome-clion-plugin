package io.esphome.clion.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.openapi.util.Key

/**
 * Run-process handler that keeps esptool's in-place upload progress visible.
 *
 * esptool draws its progress bar by emitting a frame per update — `\r` (return to
 * column 0), then the bar — with no trailing newline. By the time those frames
 * reach the console, a read often ends on the *next* frame's leading `\r`, so the
 * chunk handed to the console ends in a carriage return. IntelliJ's console
 * renders a `\r`-overwrite by keeping only the text **after the last** carriage
 * return, so a chunk ending in `\r` collapses the line to empty — the bar shows
 * as a blank line until the upload's final newline. (A real terminal leaves the
 * glyphs in place, which is why the same run looks fine in a shell.)
 *
 * The fix is to never let the console see a chunk that ends in a lone `\r`: hold
 * that carriage return back and prepend it to the next chunk — the same CRLF
 * carry [com.intellij.util.io.BaseOutputReader] does, which the terminal/PTY read
 * path skips. This preserves true in-place redraw (and colour), unlike turning
 * `\r` into `\n`, which would scroll a line per update.
 */
class EsphomeProcessHandler(commandLine: GeneralCommandLine) : KillableColoredProcessHandler(commandLine) {

    private val coalescer = CarriageReturnCoalescer()

    // notifyTextAvailable is final in ColoredProcessHandler (it runs the ANSI
    // decoder); coloredTextAvailable is the post-decode hook. The `\r` is still
    // present here — the decoder consumes only escape sequences — so the carry
    // applies just before the text reaches the console.
    override fun coloredTextAvailable(text: String, attributes: Key<*>) {
        val emit = coalescer.feed(text, attributes)
        if (emit.isNotEmpty()) super.coloredTextAvailable(emit, attributes)
    }
}

/**
 * Holds back a single trailing `\r` per stream so no emitted chunk ends in a
 * carriage return; the held `\r` is prepended to that stream's next chunk. Pure
 * and stream-keyed (stdout/stderr are independent) so it's unit-testable without
 * a live process. See [EsphomeProcessHandler] for why this matters.
 */
class CarriageReturnCoalescer {

    private val carried = HashMap<Any, Boolean>()

    /** Returns the text to emit for [stream], stripping/holding a trailing `\r`. */
    @Synchronized
    fun feed(text: String, stream: Any): String {
        var t = if (carried.remove(stream) == true) "\r$text" else text
        if (t.endsWith('\r')) {
            t = t.substring(0, t.length - 1)
            carried[stream] = true
        }
        return t
    }
}
