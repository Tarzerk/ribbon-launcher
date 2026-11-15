package dev.ribbon.launcher

import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process

/**
 * Home button for older Fire OS (6.x), where third-party accessibility services never get started.
 *
 * Polls Android's usage-events log a few times a second; when Amazon's home screen comes to the
 * front (and you didn't pick "Amazon home" in Ribbon), it opens Ribbon instead. Only runs when the
 * regular accessibility override isn't working, and only with usage access granted once via:
 *
 *   adb shell appops set dev.ribbon.launcher GET_USAGE_STATS allow
 */
class HomeWatchService : Service() {

    companion object {
        fun hasUsageAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
        }

        /** Starts the watcher if it's needed (no working accessibility override) and allowed. */
        fun startIfNeeded(ctx: Context) {
            if (HomeOverrideService.connected || !hasUsageAccess(ctx)) return
            try { ctx.startService(Intent(ctx, HomeWatchService::class.java)) } catch (_: Exception) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastEventTs = 0L
    private var checks = 0

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, 350)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(tick)
        lastEventTs = System.currentTimeMillis()
        handler.post(tick)
        return START_STICKY
    }

    private fun check() {
        // Every ~10 s, step aside if the regular override started working or access was removed.
        if (++checks % 30 == 0 && (HomeOverrideService.connected || !hasUsageAccess(this))) { stopSelf(); return }
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = try { usm.queryEvents(maxOf(lastEventTs + 1, now - 10_000), now + 1) } catch (_: Exception) { return }
        val e = UsageEvents.Event()
        var amazonHomeAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            lastEventTs = maxOf(lastEventTs, e.timeStamp)
            amazonHomeAt = if (e.packageName == Catalog.AMAZON_LAUNCHER && e.className?.contains("HomeActivity") == true) e.timeStamp else 0L
        }
        if (amazonHomeAt > 0 && now >= HomeOverrideService.allowAmazonUntil) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        }
    }

    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
