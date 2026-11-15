package dev.ribbon.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Websites the user saved from the web viewer, and TV channel tiles. Stored only in Ribbon's
 * private storage on the TV, never in the app itself.
 *
 * A TV channel ([Site.tv]) shows up in Video and opens straight into a full-screen player
 * with sound, behind a loading screen. [Site.badge] is the text on its tile, [Site.sub] the line under its name.
 */
object Sites {
    data class Site(val title: String, val url: String, val tv: Boolean = false, val badge: String? = null, val sub: String? = null)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("web_sites", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Site> {
        val arr = JSONArray(prefs(ctx).getString("sites", "[]"))
        return (0 until arr.length()).map {
            arr.getJSONObject(it).let { o ->
                Site(o.getString("title"), o.getString("url"), o.optBoolean("tv", false),
                    o.optString("badge").takeIf { s -> s.isNotBlank() }, o.optString("sub").takeIf { s -> s.isNotBlank() })
            }
        }
    }

    private fun save(ctx: Context, list: List<Site>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("title", s.title).put("url", s.url).apply {
                if (s.tv) put("tv", true)
                s.badge?.let { put("badge", it) }
                s.sub?.let { put("sub", it) }
            })
        }
        prefs(ctx).edit().putString("sites", arr.toString()).apply()
    }

    fun add(ctx: Context, site: Site) = save(ctx, all(ctx).filterNot { it.url == site.url } + site)

    fun remove(ctx: Context, url: String) = save(ctx, all(ctx).filterNot { it.url == url })

    fun contains(ctx: Context, url: String) = all(ctx).any { it.url == url }
}
