package dev.ribbon.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var ribbon: RibbonView
    private lateinit var sounds: Sounds
    private val handler = Handler(Looper.getMainLooper())
    private var tim: TvInputManager? = null

    // Set when Ribbon opens something; on return we play the Back sound unless Home brought us here.
    private var leftForSomething = false
    private var returnedViaHome = false

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = refreshSoon()
        override fun onInputAdded(inputId: String) = refreshSoon()
        override fun onInputRemoved(inputId: String) = refreshSoon()
        override fun onInputUpdated(inputId: String) = refreshSoon()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshSoon()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sounds = Sounds(this)
        ribbon = RibbonView(this)
        ribbon.onActivate = ::open
        ribbon.onSound = sounds::play
        ribbon.onOptions = ::itemOptions
        ribbon.onReordered = { tab, keys -> Layout.setOrder(this, tab, keys) }
        setContentView(ribbon)
        ribbon.requestFocus()
        // The ribbon paints every pixel itself; drop the window background once it's on screen so
        // the GPU doesn't paint a hidden full-screen layer every frame.
        ribbon.post { window.setBackgroundDrawable(null) }
        hideSystemBars()

        tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        tim?.registerCallback(inputCallback, handler)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(packageReceiver, filter)
        refresh()
    }

    override fun onDestroy() {
        tim?.unregisterCallback(inputCallback)
        unregisterReceiver(packageReceiver)
        sounds.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        HomeOverrideService.allowAmazonUntil = 0L
        OverrideKeeper.ensure(this)
        HomeWatchService.startIfNeeded(this) // older Fire OS fallback for the Home button
        if (leftForSomething && !returnedViaHome) sounds.play(Sfx.BACK)
        leftForSomething = false
        returnedViaHome = false
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while already here jumps back to Inputs.
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            returnedViaHome = true
            ribbon.focusHome()
        }
    }

    // Send remote and controller input straight to the ribbon so no press is lost to focus or touch-mode changes.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && ribbon.onKeyDown(event.keyCode, event)) return true
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        ribbon.onGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    @Deprecated("Launcher stays put")
    override fun onBackPressed() {
        // Never leave the home screen with Back; RibbonView handles Back itself.
    }

    private val refreshTask = Runnable { refresh() }

    private fun refreshSoon() {
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, 300)
    }

    private fun refresh() = ribbon.setHome(Catalog.build(this, sounds.enabled, sounds.custom, sounds.levels[sounds.level].first))

    private fun hideSystemBars() {
        Compat.hideSystemBars(window)
    }

    /**
     * ☰ on a tile: Move / Hide in the app tabs, Unhide in Hidden, plus Remove for saved sites and
     * TV channels. Settings and Inputs have no options (just a soft bump).
     */
    private fun itemOptions(tab: Category, item: Item) {
        val savedUrl = (item.action as? Action.Web)?.url?.takeIf { item.key.startsWith("site:") || item.key.startsWith("channel:") }
        val options = mutableListOf<Pair<String, () -> Unit>>()
        when (tab.name) {
            in Catalog.ARRANGEABLE -> {
                options += "Move" to { ribbon.startMove() }
                options += "Hide" to {
                    Layout.hide(this, item.key); refresh()
                    toast("${item.label} hidden · it's in the Hidden tab at the end")
                }
                options += "Sort this tab A–Z" to { Layout.resetOrder(this, tab.name); refresh(); toast("${tab.name} sorted A–Z") }
            }
            Catalog.HIDDEN_CATEGORY -> options += "Unhide" to { Layout.unhide(this, item.key); refresh(); toast("${item.label} is back") }
        }
        if (savedUrl != null) options += "Remove from Ribbon" to { confirmRemove(item, savedUrl) }
        if (options.isEmpty()) { sounds.play(Sfx.EDGE); return }
        sounds.play(Sfx.SELECT)
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(item.label)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> sounds.play(Sfx.SELECT); options[which].second() }
            .setOnCancelListener { sounds.play(Sfx.BACK) }
            .show()
    }

    private fun confirmRemove(item: Item, url: String) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(item.label)
            .setMessage("Remove this tile from Ribbon?")
            .setPositiveButton("Remove") { _, _ ->
                Sites.remove(this, url)
                Layout.unhide(this, item.key)
                refresh()
                sounds.play(Sfx.BACK)
                toast("Removed ${item.label}")
            }
            .setNegativeButton("Cancel") { _, _ -> sounds.play(Sfx.BACK) }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun open(item: Item) {
        if (item.action != Action.ToggleSounds && item.action != Action.NextSoundLevel) leftForSomething = true
        try {
            when (val a = item.action) {
                is Action.App -> startActivity(a.intent)
                is Action.Input -> startActivity(
                    Intent(this, InputActivity::class.java)
                        .putExtra(InputActivity.EXTRA_INPUT_ID, a.inputId)
                        .putExtra(InputActivity.EXTRA_FALLBACK_ID, a.fallbackId)
                        .putExtra(InputActivity.EXTRA_LABEL, a.label)
                )
                is Action.OpenSettings -> {
                    if (a.allowAmazon) HomeOverrideService.allowAmazonUntil = System.currentTimeMillis() + 4000
                    openFirst(a.intents)
                }
                is Action.Web -> startActivity(
                    Intent(this, WebActivity::class.java).putExtra(WebActivity.EXTRA_URL, a.url).putExtra(WebActivity.EXTRA_TV, a.tv)
                        .putExtra(WebActivity.EXTRA_TITLE, item.label).putExtra(WebActivity.EXTRA_SUB, item.sub)
                        .putExtra(WebActivity.EXTRA_COLORS, item.colors)
                        .putExtra(WebActivity.EXTRA_BADGE, (item.glyph as? Glyph.Letter)?.text)
                )
                Action.NextSoundLevel -> {
                    sounds.nextLevel()
                    refresh()
                    ribbon.postDelayed({ sounds.play(Sfx.MOVE) }, 120) // preview the new level
                }
                Action.ToggleSounds -> {
                    sounds.enabled = !sounds.enabled
                    refresh()
                }
                Action.AmazonHome -> {
                    HomeOverrideService.allowAmazonUntil = System.currentTimeMillis() + 4000
                    val i = packageManager.getLaunchIntentForPackage(Catalog.AMAZON_LAUNCHER)
                        ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(Catalog.AMAZON_LAUNCHER)
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open ${item.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFirst(intents: List<Intent>) {
        for (intent in intents) {
            try {
                // Fresh task each time, so a settings screen left open earlier doesn't come back instead.
                startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        throw ActivityNotFoundException()
    }
}
