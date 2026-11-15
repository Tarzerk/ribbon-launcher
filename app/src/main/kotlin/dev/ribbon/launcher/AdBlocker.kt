package dev.ribbon.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Host-based ad and tracker blocking for Ribbon's web viewer.
 *
 * Uses the public StevenBlack hosts list (github.com/StevenBlack/hosts, MIT licensed), downloaded to
 * the TV and refreshed weekly, plus a small built-in list so blocking works before the first download.
 * Never logs which sites are visited or blocked — only counts.
 */
object AdBlocker {
    private const val TAG = "RibbonAdBlock"
    private const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val builtIn = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagservices.com", "googletagmanager.com", "pagead2.googlesyndication.com",
        "adnxs.com", "adsrvr.org", "amazon-adsystem.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com", "smartadserver.com", "moatads.com",
        "scorecardresearch.com", "quantserve.com", "zedo.com", "yieldmo.com", "sharethrough.com", "33across.com",
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "onclickads.net", "onclkds.com",
        "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net", "adsterra.com", "adsterratools.com",
        "hilltopads.net", "clickadu.com", "clickaine.com", "adcash.com", "admaven.com", "ad-maven.com",
        "mgid.com", "revcontent.com", "adskeeper.com", "adskeeper.co.uk", "richpush.co", "pushame.com",
        "a-ads.com", "bidvertiser.com", "infolinks.com", "media.net", "adform.net",
        "2mdn.net", "serving-sys.com", "imasdk.googleapis.com", "ads.yahoo.com", "advertising.com",
        "popmyads.com", "popunder.net", "tsyndicate.com", "realsrv.com", "dtscout.com", "dtscdn.com",
        "acint.net", "bebi.com", "zeroredirect1.com", "zeroredirect2.com", "deloplen.com", "whos.amung.us",
    )

    @Volatile private var hosts: Set<String> = builtIn
    @Volatile private var loading = false
    val blockedCount = AtomicInteger()

    fun init(ctx: Context) {
        if (loading) return
        loading = true
        val file = File(ctx.filesDir, "adblock-hosts.txt")
        thread(name = "adblock-load", isDaemon = true) {
            try {
                if (file.exists()) hosts = parse(file.readLines()) + builtIn
                if (!file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) {
                    val conn = URL(LIST_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 20_000
                    if (conn.responseCode == 200) {
                        val tmp = File(ctx.filesDir, "adblock-hosts.tmp")
                        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        tmp.renameTo(file)
                        hosts = parse(file.readLines()) + builtIn
                    }
                    conn.disconnect()
                }
                Log.i(TAG, "block list ready: ${hosts.size} hosts")
            } catch (e: Exception) {
                Log.w(TAG, "block list update failed, using ${hosts.size} hosts (${e.javaClass.simpleName})")
            } finally {
                loading = false
            }
        }
    }

    private fun parse(lines: List<String>): Set<String> {
        val out = HashSet<String>(lines.size)
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2 || (parts[0] != "0.0.0.0" && parts[0] != "127.0.0.1")) continue
            val h = parts[1].lowercase()
            if (h != "localhost" && h != "0.0.0.0" && h.contains('.')) out += h
        }
        return out
    }

    /** True if [host] or any parent domain is on the list. */
    fun isBlocked(host: String?): Boolean {
        var h = host?.lowercase()?.trimEnd('.') ?: return false
        val set = hosts
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
