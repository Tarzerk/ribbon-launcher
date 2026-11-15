package dev.ribbon.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Fire TV ignores the "default home app" setting, so this service watches for the Amazon home
 * screen coming to the front and opens Ribbon instead. It only receives window-change events from
 * the Amazon launcher package (see res/xml/home_override.xml) and reads no screen content.
 */
class HomeOverrideService : AccessibilityService() {

    companion object {
        /** Set when the user picks "Amazon home" in Ribbon, so we don't bounce them straight back. */
        @Volatile var allowAmazonUntil = 0L

        /** True only while the system has really started this service (older Fire OS may list it but never start it). */
        @Volatile var connected = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        Log.d("RibbonHome", "event type=${event.eventType} pkg=${event.packageName} cls=${event.className}")
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != Catalog.AMAZON_LAUNCHER) return
        val cls = event.className?.toString() ?: return
        if (!cls.contains("HomeActivity")) return
        if (System.currentTimeMillis() < allowAmazonUntil) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }

    override fun onServiceConnected() { connected = true }
    override fun onDestroy() { connected = false; super.onDestroy() }
    override fun onInterrupt() {}
}
