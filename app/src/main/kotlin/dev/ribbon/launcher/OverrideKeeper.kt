package dev.ribbon.launcher

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

/**
 * Keeps Ribbon's Home-button override switched on. Fire TV turns accessibility services off when
 * their app is updated, so after updates, reboots and whenever Ribbon opens we check and, if needed,
 * re-add our service. This only works after the user has run, once:
 *
 *   adb shell pm grant dev.ribbon.launcher android.permission.WRITE_SECURE_SETTINGS
 *
 * Only Ribbon's own entry is touched; any other enabled services are kept as they are.
 */
object OverrideKeeper {
    private const val TAG = "RibbonKeeper"

    fun canRepair(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun ours(ctx: Context) = ComponentName(ctx, HomeOverrideService::class.java)

    fun isRunning(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val me = ours(ctx)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.let { s -> s.packageName == me.packageName && s.name == me.className } }
    }

    fun ensure(ctx: Context) {
        if (!canRepair(ctx) || isRunning(ctx)) return
        val cr = ctx.contentResolver
        val me = ours(ctx)
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val others = current.split(':').filter { it.isNotBlank() }.filter {
            ComponentName.unflattenFromString(it)?.let { c -> c.packageName == me.packageName } != true
        }
        try {
            // Write the list without us, then with us, so the system notices a change and binds the service.
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me.flattenToShortString()).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Home override re-enabled")
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't re-enable Home override", e)
        }
    }
}

/** Re-enables the override after Ribbon is updated and after the TV boots. */
class OverrideKeeperReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OverrideKeeper.ensure(context)
        HomeWatchService.startIfNeeded(context)
    }
}
