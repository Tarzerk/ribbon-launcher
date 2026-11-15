package dev.ribbon.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File

enum class Sfx(val file: String) {
    MOVE("snd_move"), CATEGORY("snd_category"), SELECT("snd_select"), BACK("snd_back"), EDGE("snd_edge")
}

/**
 * Low-latency menu sounds. Uses the built-in sounds (see tools/make_sounds.py) unless a custom
 * sound pack is present on the device:
 *
 *   /sdcard/Android/data/dev.ribbon.launcher/files/sounds/snd_move.wav (…snd_category, snd_select, snd_back, snd_edge)
 *
 * When that folder has any snd_*.wav file, it replaces the built-in set; sounds missing from the
 * pack stay silent. Remembers the on/off choice.
 */
class Sounds(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ribbon", Context.MODE_PRIVATE)
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val loaded = HashSet<Int>()
    private val ids = HashMap<Sfx, Int>()

    /** True when a custom sound pack is in use. */
    val custom: Boolean

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        val dir = ctx.getExternalFilesDir("sounds")
        val packFiles = Sfx.entries.associateWith { File(dir, it.file + ".wav") }.filterValues { it.canRead() }
        custom = packFiles.isNotEmpty()
        if (custom) {
            for ((sfx, f) in packFiles) ids[sfx] = pool.load(f.absolutePath, 1)
        } else {
            val raw = mapOf(
                Sfx.MOVE to R.raw.snd_move, Sfx.CATEGORY to R.raw.snd_category, Sfx.SELECT to R.raw.snd_select,
                Sfx.BACK to R.raw.snd_back, Sfx.EDGE to R.raw.snd_edge,
            )
            for ((sfx, res) in raw) ids[sfx] = pool.load(ctx, res, 1)
        }
    }

    var enabled: Boolean
        get() = prefs.getBoolean("sounds", true)
        set(value) { prefs.edit().putBoolean("sounds", value).apply() }

    /** Volume steps, cycled from Settings. Index into [levels]; default "Low". */
    val levels = listOf("Quiet" to 0.08f, "Low" to 0.18f, "Medium" to 0.35f, "Loud" to 0.7f)
    var level: Int
        get() = prefs.getInt("sound_level", 1).coerceIn(0, levels.lastIndex)
        set(value) { prefs.edit().putInt("sound_level", value).apply() }
    fun nextLevel() { level = (level + 1) % levels.size }

    fun play(s: Sfx) {
        if (!enabled) return
        val id = ids[s] ?: return
        val v = levels[level].second
        if (id in loaded) pool.play(id, v, v, 1, 0, 1f)
    }

    fun release() = pool.release()
}
