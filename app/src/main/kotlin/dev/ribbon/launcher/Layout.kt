package dev.ribbon.launcher

import android.content.Context
import org.json.JSONArray

/**
 * How you arranged the home screen: your own order per tab and the apps you hid. Stored only on the TV.
 *
 * A tab you never rearranged is simply A–Z. Once you move something, that tab keeps your order,
 * and anything new (a freshly installed app, say) goes to the bottom.
 */
object Layout {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("layout", Context.MODE_PRIVATE)

    private fun readList(ctx: Context, name: String): List<String>? =
        prefs(ctx).getString(name, null)?.let { s -> JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }

    private fun writeList(ctx: Context, name: String, keys: Collection<String>) =
        prefs(ctx).edit().putString(name, JSONArray(keys).toString()).apply()

    fun setOrder(ctx: Context, category: String, keys: List<String>) = writeList(ctx, "order:$category", keys)

    /** Forgets your order for [category], so it's A–Z again. */
    fun resetOrder(ctx: Context, category: String) = prefs(ctx).edit().remove("order:$category").apply()

    /** [items] in your saved order for [category], or A–Z if you haven't rearranged it. */
    fun arrange(ctx: Context, category: String, items: List<Item>): List<Item> {
        val az = items.sortedBy { it.label.lowercase() }
        val saved = readList(ctx, "order:$category") ?: return az
        val byKey = items.associateBy { it.key }
        val placed = saved.mapNotNull { byKey[it] }
        val placedKeys = placed.mapTo(HashSet()) { it.key }
        return placed + az.filterNot { it.key in placedKeys }
    }

    fun hidden(ctx: Context): Set<String> = readList(ctx, "hidden")?.toSet() ?: emptySet()

    fun hide(ctx: Context, key: String) = writeList(ctx, "hidden", hidden(ctx) + key)

    fun unhide(ctx: Context, key: String) = writeList(ctx, "hidden", hidden(ctx) - key)
}
