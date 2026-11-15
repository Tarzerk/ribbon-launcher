package dev.ribbon.launcher

import android.os.SystemClock
import android.util.Log

/**
 * Private diagnostics for the web viewer.
 *
 * Events are kept in memory only (last 80). Nothing reaches the system log until the user picks
 * ☰ → Report a problem, and even then every line is scrubbed: pages are "page #N", anything that
 * looks like an address becomes [url] and anything that looks like a domain becomes [host].
 */
object Diag {
    private const val TAG = "RibbonDiag"
    private const val MAX = 80
    private val events = ArrayDeque<String>()
    private val start = SystemClock.uptimeMillis()
    private var pageCounter = 0

    private val urlRe = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")
    private val hostRe = Regex("""\b(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}\b""")

    private val ipRe = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b""")

    fun scrub(s: String): String = ipRe.replace(hostRe.replace(urlRe.replace(s, "[url]"), "[host]"), "[ip]").take(240)

    @Synchronized fun event(s: String) {
        val t = (SystemClock.uptimeMillis() - start) / 1000.0
        events.addLast("%8.1fs  %s".format(t, scrub(s)))
        while (events.size > MAX) events.removeFirst()
    }

    @Synchronized fun newPage(): Int { pageCounter += 1; return pageCounter }

    /** Writes the snapshot plus recent events to the system log under the RibbonDiag tag. */
    @Synchronized fun report(snapshot: List<String>) {
        Log.i(TAG, "===== REPORT START =====")
        snapshot.forEach { Log.i(TAG, "now: " + scrub(it)) }
        events.forEach { Log.i(TAG, it) }
        Log.i(TAG, "===== REPORT END =====")
    }
}
