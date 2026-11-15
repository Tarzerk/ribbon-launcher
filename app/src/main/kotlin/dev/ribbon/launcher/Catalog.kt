package dev.ribbon.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings

enum class Theme { GAME, MEDIA }

object Palette {
    private fun g(a: Long, b: Long) = intArrayOf(a.toInt(), b.toInt())
    val green = g(0xFF3EE07A, 0xFF33C6C9)
    val blue = g(0xFF4B6CF7, 0xFF2AA7F4)
    val red = g(0xFFFF4D6D, 0xFFE5173F)
    val coral = g(0xFFFF6A3D, 0xFFFF2E63)
    val yellow = g(0xFFFFD54A, 0xFFFF9F2B)
    val violet = g(0xFF9B5CFF, 0xFFE14EFF)
    val cyan = g(0xFF00E5FF, 0xFF3D5AFE)
    val dark = g(0xFF2A2D3D, 0xFF1A1C27)
    val cycle = listOf(green, blue, red, coral, yellow, violet, cyan)
}

sealed class Action {
    data class App(val intent: Intent) : Action()
    data class Input(val inputId: String, val fallbackId: String?, val label: String) : Action()
    /** Tries each intent in order. allowAmazon lets screens hosted by Amazon's home app open without Ribbon bouncing back. */
    data class OpenSettings(val intents: List<Intent>, val allowAmazon: Boolean = false) : Action()
    object AmazonHome : Action()
    object ToggleSounds : Action()
    object NextSoundLevel : Action()
    /** Settings → Hidden apps: lists the apps you hid, to open or unhide. */
    class HiddenApps(val items: List<Item>) : Action()
    /** Opens Ribbon's web viewer; null url asks for an address. */
    data class Web(val url: String?, val tv: Boolean = false) : Action()
}

class Item(
    val key: String,
    val label: String,
    val glyph: Glyph,
    val colors: IntArray,
    val action: Action,
    val sub: String? = null,
    val glyphColor: Int = Color.WHITE,
)

class Category(
    val name: String,
    val glyph: Glyph,
    val colors: IntArray,
    val theme: Theme,
    val items: List<Item>,
)

class Home(val categories: List<Category>, val status: String?)

object Catalog {
    const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"

    private enum class Slot { VIDEO, SPORTS, MUSIC, APPS }

    /** The category Ribbon opens on, and that Back and Home return to. */
    const val HOME_CATEGORY = "Inputs"
    /** Tabs you can rearrange and hide things from. */
    val ARRANGEABLE = setOf("Video", "Sports", "Music", "Apps")

    private class Known(val slot: Slot, val glyph: Glyph, val colors: IntArray, val label: String? = null, val glyphColor: Int = Color.WHITE)

    private val known = mapOf(
        "com.netflix.ninja" to Known(Slot.VIDEO, Glyph.Letter("N"), Palette.red),
        "com.amazon.firetv.youtube" to Known(Slot.VIDEO, Glyph.Play, Palette.coral),
        "com.amazon.firetv.youtube.tv" to Known(Slot.VIDEO, Glyph.Letter("TV"), Palette.yellow),
        "com.peacock.peacockfiretv" to Known(Slot.VIDEO, Glyph.Letter("P"), Palette.violet),
        "com.apple.atve.amazon.appletv" to Known(Slot.VIDEO, Glyph.Letter("tv"), Palette.dark, glyphColor = 0xFF5EF0C4.toInt()),
        "com.espn.gtv" to Known(Slot.SPORTS, Glyph.Ball, Palette.blue),
        "com.dazn" to Known(Slot.SPORTS, Glyph.Trophy, Palette.coral),
        "com.spotify.tv.android" to Known(Slot.MUSIC, Glyph.Music, Palette.green),
        "com.nordvpn.android" to Known(Slot.APPS, Glyph.Shield, Palette.blue),
        "com.expressvpn.vpn" to Known(Slot.APPS, Glyph.Shield, Palette.coral),
        "com.amazon.venezia" to Known(Slot.APPS, Glyph.Grid, Palette.yellow, label = "Appstore"),
    )

    /** Package names that are hidden from the app lists (system screens that have their own entries). */
    private val hidden = setOf(
        AMAZON_LAUNCHER, "com.amazon.tv.settings.v2", "com.android.tv.settings", "com.amazon.tv.livetv", // Live TV: not used
        "com.spocky.projengmenu",
    )

    private val gameApp = Regex("xbox|xcloud|luna|geforce|nvidia|steamlink|moonlight|retroarch|parsec|chiaki|game", RegexOption.IGNORE_CASE)
    private val videoApp = Regex("hulu|disney|hbo|max|prime|paramount|plex|jellyfin|kodi|tubi|pluto|crunchyroll|twitch|video|movie|stremio", RegexOption.IGNORE_CASE)
    private val musicApp = Regex("music|spotify|pandora|tidal|soundcloud|deezer|audio", RegexOption.IGNORE_CASE)
    private val console = Regex("playstation|ps5|ps4|xbox|nintendo|switch|steam|console|game", RegexOption.IGNORE_CASE)

    fun build(ctx: Context, soundsOn: Boolean = true, customSounds: Boolean = false, soundLevel: String = "Low"): Home {
        val pm = ctx.packageManager
        val slots = Slot.entries.associateWith { mutableListOf<Item>() }

        // Installed apps.
        val seen = HashSet<String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || pkg in hidden || !seen.add(pkg)) continue
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: continue
                val name = ri.loadLabel(pm).toString()
                val k = known[pkg]
                val slot = k?.slot ?: when {
                    gameApp.containsMatchIn(pkg) || gameApp.containsMatchIn(name) -> Slot.APPS
                    videoApp.containsMatchIn(pkg) || videoApp.containsMatchIn(name) -> Slot.VIDEO
                    musicApp.containsMatchIn(pkg) || musicApp.containsMatchIn(name) -> Slot.MUSIC
                    else -> Slot.APPS
                }
                val colors = k?.colors ?: Palette.cycle[(pkg.hashCode() and 0x7fffffff) % Palette.cycle.size]
                val isGame = gameApp.containsMatchIn(pkg) || gameApp.containsMatchIn(name)
                val glyph = k?.glyph ?: if (isGame) Glyph.Gamepad else Glyph.Letter(name.take(1).uppercase())
                slots.getValue(slot) += Item(
                    key = "app:$pkg",
                    label = k?.label ?: name,
                    glyph = glyph,
                    colors = colors,
                    action = Action.App(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
                    glyphColor = k?.glyphColor ?: Color.WHITE,
                )
            }
        }

        // HDMI inputs, consoles first. Kept simple: name plus port, no power status.
        val consoles = mutableListOf<Item>()
        val others = mutableListOf<Item>()
        for (input in hdmiInputs(ctx)) {
            val isConsole = console.containsMatchIn(input.label)
            val item = Item(
                key = "input:${input.id}",
                label = input.label,
                glyph = if (isConsole) Glyph.Gamepad else if (input.cec) Glyph.Cast else Glyph.Plug,
                colors = if (isConsole) Palette.cyan else if (input.cec) Palette.green else Palette.yellow,
                action = Action.Input(input.id, input.parentId, input.label),
                sub = input.port.takeIf { it != input.label },
            )
            if (isConsole) consoles += item else others += item
        }
        val inputs = consoles + others

        // Web viewer first in Apps, then the sites you saved from it; TV channels lead Video.
        // (Both are kept only on the TV, see Sites.)
        val saved = Sites.all(ctx)
        val web = listOf(Item("web", "Web", Glyph.Globe, Palette.cyan, Action.Web(null), sub = "Ad-blocking web viewer · ☰ for options")) +
            saved.filterNot { it.tv }.mapIndexed { i, s ->
                Item("site:${s.url}", s.title, Glyph.Letter(s.title.take(1).uppercase()),
                    Palette.cycle[(s.title.hashCode() and 0x7fffffff) % Palette.cycle.size], Action.Web(s.url), sub = "Saved site")
            }
        slots.getValue(Slot.APPS).addAll(0, web)
        val channels = saved.filter { it.tv }.mapIndexed { i, s ->
            Item("channel:${s.url}", s.title, Glyph.Letter(s.badge ?: s.title.take(1).uppercase()), Palette.red,
                Action.Web(s.url, tv = true), sub = s.sub)
        }
        slots.getValue(Slot.VIDEO).addAll(0, channels)

        fun act(a: String) = Intent(a)
        fun cmp(pkg: String, cls: String, action: String? = null) = Intent(action).setClassName(pkg, cls)
        val settings = listOf(
            Item("wifi", "Wi-Fi", Glyph.Wifi, Palette.blue, Action.OpenSettings(listOf(act(Settings.ACTION_WIFI_SETTINGS)))),
            // Display & Sounds isn't listed: Fire TV only lets Amazon's own apps open it. It's under All settings.
            Item("controllers", "Controllers", Glyph.Bluetooth, Palette.blue, Action.OpenSettings(listOf(
                act("com.amazon.device.settings.action.INPUT_DEVICES"),
                act(Settings.ACTION_BLUETOOTH_SETTINGS),
            )), sub = "Pair a Bluetooth controller"),
            Item("sounds", "Sounds", Glyph.Speaker, if (soundsOn) Palette.green else Palette.dark, Action.ToggleSounds,
                sub = (if (customSounds) "Custom pack · " else "") + if (soundsOn) "On · press to turn off" else "Off · press to turn on"),
            Item("sound-level", "Sound volume", Glyph.Speaker, Palette.coral, Action.NextSoundLevel,
                sub = "$soundLevel · press to change"),
            Item("all-settings", "All settings", Glyph.Gear, Palette.dark, Action.OpenSettings(listOf(
                cmp(AMAZON_LAUNCHER, "com.amazon.tv.launcher.ui.MainSettingsActivity", "amazon.intent.action.MAINSETTINGS"),
                act(Settings.ACTION_SETTINGS),
            ), allowAmazon = true), glyphColor = 0xFF3EE0A0.toInt()),
            Item("amazon", "Amazon home", Glyph.Home, Palette.dark, Action.AmazonHome, sub = "Open the original home screen", glyphColor = 0xFF2AA7F4.toInt()),
        )

        // Your layout: hidden apps leave the tabs (they're listed in Settings → Hidden apps); every other tab is A–Z until you rearrange it.
        val hiddenKeys = Layout.hidden(ctx)
        val hiddenItems = mutableListOf<Item>()
        fun tab(name: String, slot: Slot, glyph: Glyph, colors: IntArray): Category? {
            val (hide, show) = slots.getValue(slot).partition { it.key in hiddenKeys }
            hiddenItems += hide
            return show.takeIf { it.isNotEmpty() }?.let { Category(name, glyph, colors, Theme.MEDIA, Layout.arrange(ctx, name, it)) }
        }
        val media = listOfNotNull(
            tab("Video", Slot.VIDEO, Glyph.Film, Palette.red),
            tab("Sports", Slot.SPORTS, Glyph.Ball, Palette.blue),
            tab("Music", Slot.MUSIC, Glyph.Music, Palette.green),
            tab("Apps", Slot.APPS, Glyph.Grid, Palette.yellow),
        )
        val hiddenEntry = hiddenItems.takeIf { it.isNotEmpty() }?.let {
            Item("hidden-apps", "Hidden apps", Glyph.EyeOff, Palette.dark, Action.HiddenApps(it.sortedBy { i -> i.label.lowercase() }),
                sub = "${it.size} hidden · press to open or unhide")
        }
        val cats = listOfNotNull(
            Category("Settings", Glyph.Gear, Palette.dark, Theme.MEDIA, settings + listOfNotNull(hiddenEntry)),
            inputs.takeIf { it.isNotEmpty() }?.let { Category(HOME_CATEGORY, Glyph.Plug, Palette.cyan, Theme.GAME, it) }, // hidden when nothing is plugged in
        ) + media
        return Home(cats, status = null)
    }

    private class Hdmi(val id: String, val label: String, val port: String, val state: Int, val cec: Boolean, val parentId: String? = null)

    private fun hdmiInputs(ctx: Context): List<Hdmi> {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)) return emptyList()
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        val all = try { tim.tvInputList } catch (_: Exception) { return emptyList() }
        val hdmi = all.filter { it.type == TvInputInfo.TYPE_HDMI }
        val byId = hdmi.associateBy { it.id }
        val parents = hdmi.mapNotNull { it.parentId }.toSet()
        fun label(info: TvInputInfo) = (info.loadCustomLabel(ctx) ?: info.loadLabel(ctx))?.toString()?.trim().orEmpty()
        val out = mutableListOf<Hdmi>()
        for (info in hdmi) {
            val state = try { tim.getInputState(info.id) } catch (_: Exception) { TvInputManager.INPUT_STATE_DISCONNECTED }
            val parent = info.parentId?.let { byId[it] }
            if (parent != null) {
                // A device announced over HDMI-CEC (e.g. "PlayStation 5").
                out += Hdmi(info.id, label(info).ifEmpty { label(parent) }, label(parent), state, cec = true, parentId = parent.id)
            } else if (info.id !in parents && state != TvInputManager.INPUT_STATE_DISCONNECTED) {
                val l = label(info)
                out += Hdmi(info.id, l, l, state, cec = false)
            }
        }
        return out.sortedBy { it.port }
    }
}
