package dev.ribbon.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-site "hide this element" rules made with the picker in the web viewer.
 * Stored only in Ribbon's private storage on the TV; never logged.
 */
object HideRules {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hide_rules", Context.MODE_PRIVATE)

    private fun key(host: String) = host.lowercase().removePrefix("www.")

    fun forHost(ctx: Context, host: String?): List<String> {
        if (host == null) return emptyList()
        val arr = prefs(ctx).getString(key(host), null)?.let { JSONArray(it) } ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun add(ctx: Context, host: String, selector: String) {
        val list = (forHost(ctx, host) + selector).distinct()
        prefs(ctx).edit().putString(key(host), JSONArray(list).toString()).apply()
    }

    fun clear(ctx: Context, host: String) = prefs(ctx).edit().remove(key(host)).apply()

    /** JavaScript that installs (or updates) a style sheet hiding [selectors]. */
    fun applyJs(selectors: List<String>): String {
        val css = selectors.joinToString("\n") { "$it{display:none!important;visibility:hidden!important}" }
        return "(function(css){var s=document.getElementById('__rb_hide');" +
            "if(!s){s=document.createElement('style');s.id='__rb_hide';(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=css;})(" + JSONObject.quote(css) + ")"
    }

    /** Picker helpers injected into the page while choosing what to hide. */
    const val PICKER_JS = """(function(){if(window.__rbPick)return;
var o=document.createElement('div');
o.style.cssText='position:fixed;pointer-events:none;z-index:2147483647;border:3px solid #00e5ff;background:rgba(0,229,255,.18);border-radius:6px;display:none';
document.documentElement.appendChild(o);
window.__rbPick={o:o,el:null};
window.__rbTarget=function(x,y){var e=document.elementFromPoint(x,y);if(!e||e===o||e===document.body||e===document.documentElement)return null;
 var t=e,c=e;while(c&&c!==document.body&&c!==document.documentElement){var p=getComputedStyle(c).position;if(p==='fixed'||p==='sticky')t=c;c=c.parentElement;}return t;};
window.__rbHover=function(fx,fy){var t=__rbTarget(fx*innerWidth,fy*innerHeight);__rbPick.el=t;if(!t){o.style.display='none';return}
 var r=t.getBoundingClientRect();o.style.display='block';o.style.left=r.left+'px';o.style.top=r.top+'px';o.style.width=r.width+'px';o.style.height=r.height+'px';};
window.__rbSel=function(e){var ok=function(id){return id&&!/\d{3,}/.test(id)};if(ok(e.id))return '#'+CSS.escape(e.id);
 var parts=[],c=e;while(c&&c.nodeType===1&&c!==document.body&&c!==document.documentElement&&parts.length<5){
  var s=c.tagName.toLowerCase();if(ok(c.id)){parts.unshift('#'+CSS.escape(c.id));break;}
  var cls=[].slice.call(c.classList).filter(function(k){return !/\d{3,}|^(active|show|open|visible|hidden|in|fade)$/.test(k)}).slice(0,3);
  if(cls.length)s+='.'+cls.map(CSS.escape).join('.');else{var i=1,sb=c;while(sb=sb.previousElementSibling)if(sb.tagName===c.tagName)i++;s+=':nth-of-type('+i+')';}
  parts.unshift(s);c=c.parentElement;}
 return parts.join(' > ');};
})()"""

    const val PICK_JS = "(function(){var p=window.__rbPick;if(!p)return '';p.o.remove();delete window.__rbPick;var t=p.el;if(!t)return '';" +
        "var s=__rbSel(t);if(!s)return '';t.style.setProperty('display','none','important');return s;})()"

    const val CANCEL_JS = "(function(){var p=window.__rbPick;if(p){p.o.remove();delete window.__rbPick;}})()"
}
