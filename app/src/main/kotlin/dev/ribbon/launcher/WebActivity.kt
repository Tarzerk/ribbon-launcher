package dev.ribbon.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import java.io.ByteArrayInputStream
import kotlin.math.min

/**
 * Ribbon's web viewer: one site at a time, remote-friendly pointer, ad/pop-up blocking.
 *
 * Remote: arrows move the pointer (hold to speed up; push against the top/bottom edge to scroll),
 * OK clicks, Back exits full-screen video → goes back a page → returns to Ribbon,
 * Menu (☰) opens address / save / reload / desktop-mode options, Play/Pause controls the video.
 *
 * Privacy: addresses are never written to the log.
 */
class WebActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"

        /** Tile-launched "TV mode": the page's main player fills the screen, with sound. */
        const val EXTRA_TV = "tv"
        /** Optional tile details for TV mode's loading screen. */
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUB = "sub"
        const val EXTRA_BADGE = "badge"
        const val EXTRA_COLORS = "colors"
        /** OPEN_WEB with a "channel" name saves the address as a TV channel tile in Video. */
        const val EXTRA_CHANNEL = "channel"

        /**
         * TV mode: makes the page's biggest video player fill the screen, keeps the page unscrolled
         * (scrolled pages make the TV draw video in the wrong place), and turns the sound on, since
         * live players usually start muted. Some sites only load the player once it's
         * fully visible, which this also takes care of.
         */
        private const val TV_JS = "(function(){if(window.__rbTv)return;window.__rbTv=1;" +
            "var st=document.createElement('style');st.textContent='html,body{overflow:hidden!important}" +
            ".rb-tv{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;" +
            "max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;transform:none!important;" +
            "z-index:2147483646!important;background:#000!important}" +
            ".rb-tv>*,.rb-tv iframe,.rb-tv video{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important}';" +
            "document.head.appendChild(st);var box=null,n=0,vol=0;" +
            "var t=setInterval(function(){n++;" +
            "if(!box){var vw=innerWidth,area=0;document.querySelectorAll('video,iframe,[class*=player],[id*=player],[class*=Player]').forEach(function(e){" +
            "var r=e.getBoundingClientRect();if(r.width<vw*0.5)return;var a=r.width*r.height;if(a>area){area=a;box=e;}});" +
            "if(box)box.classList.add('rb-tv');}" +
            "[document.scrollingElement,document.body].forEach(function(e){if(e&&e.scrollTop)e.scrollTop=0;});" +
            "if(box&&vol<4){var f=box.tagName=='IFRAME'?box:box.querySelector('iframe');" +
            "if(f&&/mdstrm\\.com/.test(f.src)){vol++;try{f.contentWindow.postMessage({cb:'rb'+vol,eventName:'setVolume',data:1},'https://mdstrm.com');}catch(e){}}" +
            "var v=box.tagName=='VIDEO'?box:box.querySelector('video');if(v&&v.muted){vol++;v.muted=false;}}" +
            "if(n>40)clearInterval(t);},1500);})()"
    }

    private lateinit var root: FrameLayout
    /** Site opened from a TV-mode tile; its player is shown full screen. */
    private var tvHost: String? = null
    /** TV mode's native loading screen, shown until the video plays. */
    private var loader: LoadingView? = null
    private val loaderTimeout = Runnable { hideLoader() }
    private lateinit var web: WebView
    private var lastUrl: String? = null
    private lateinit var pointer: PointerView
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val prefs by lazy { getSharedPreferences("web", Context.MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }
    private var defaultUa = ""
    private lateinit var sounds: Sounds
    private var picking = false
    @Volatile private var pageHost: String? = null
    private val blockedOnPage = java.util.concurrent.atomic.AtomicInteger()

    /** Sites where ad blocking and pop-up filtering are off (stored on the TV only). */
    private fun compat(host: String? = pageHost): Boolean =
        host != null && prefs.getStringSet("compat_hosts", emptySet())!!.contains(host.lowercase().removePrefix("www."))

    private fun setCompat(host: String, on: Boolean) {
        val set = prefs.getStringSet("compat_hosts", emptySet())!!.toMutableSet()
        val h = host.lowercase().removePrefix("www.")
        if (on) set += h else set -= h
        prefs.edit().putStringSet("compat_hosts", set).apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AdBlocker.init(applicationContext)
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) // inspector only in test builds
        sounds = Sounds(this)

        // No opaque backgrounds behind the page: the TV sometimes shows web video on a hardware layer
        // *behind* the window, visible only through a see-through gap. Anything opaque back here hides it.
        root = FrameLayout(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        pointer = PointerView(this)
        createWebView()
        root.addView(pointer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        Compat.hideSystemBars(window)

        saveChannelFrom(intent)
        val url = intent.getStringExtra(EXTRA_URL)
        tvHost = if (intent.getBooleanExtra(EXTRA_TV, false)) url?.let { Uri.parse(it).host } else null
        if (tvHost != null) showLoader(intent)
        if (url.isNullOrBlank()) askForAddress() else web.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK) // drawn by the web engine itself, so video gaps stay see-through
        root.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true) // embedded video players need their own cookies
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            setSupportMultipleWindows(true)          // so pop-ups come to us (and get refused) instead of replacing the page
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE // older http video streams on https pages
            defaultUa = userAgentString
        }
        applyUserAgent()
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        if (android.os.Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        installFrameWatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveChannelFrom(intent)
        intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let {
            tvHost = if (intent.getBooleanExtra(EXTRA_TV, false)) Uri.parse(it).host else null
            if (tvHost != null) showLoader(intent) else hideLoader()
            web.loadUrl(it)
        }
    }

    override fun onPause() { super.onPause(); web.onPause(); root.removeCallbacks(watchdog) }
    override fun onResume() { super.onResume(); web.onResume(); root.removeCallbacks(watchdog); root.postDelayed(watchdog, 2000) }
    override fun onDestroy() { web.destroy(); sounds.release(); super.onDestroy() }

    // ---- desktop / mobile ----------------------------------------------------------------

    private var desktop: Boolean
        get() = prefs.getBoolean("desktop", true)
        set(v) { prefs.edit().putBoolean("desktop", v).apply() }

    private fun applyUserAgent() {
        web.settings.userAgentString = if (desktop) {
            val chrome = Regex("Chrome/[\\d.]+").find(defaultUa)?.value ?: "Chrome/124.0.0.0"
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        } else defaultUa
    }

    // ---- blocking ------------------------------------------------------------------------

    private fun host() = web.url?.let { Uri.parse(it).host }

    private fun applyHideRules() {
        val rules = HideRules.forHost(this, host())
        if (rules.isNotEmpty()) web.evaluateJavascript(HideRules.applyJs(rules), null)
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            lastUrl = url
            pageHost = Uri.parse(url).host
            frames.clear(); frameIds.clear()
            blockedOnPage.set(0)
            if (tvHost != null && pageHost == tvHost) showLoader(null) // reloads get the loading screen too
            Diag.event("page #${Diag.newPage()} started · " + (if (desktop) "desktop" else "mobile") + " · ad blocking " + (if (compat()) "paused" else "on"))
        }
        override fun onPageCommitVisible(view: WebView, url: String) = applyHideRules()

        /** The TV killed the web engine to free memory (or it crashed): rebuild it and reload the page. */
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            if (view !== web) return true
            Diag.event("web engine process gone · crashed=${detail.didCrash()} · priority=${detail.rendererPriorityAtExit()}")
            fullscreenView?.let { root.removeView(it) }
            fullscreenView = null; fullscreenCallback = null
            root.removeView(web)
            web.destroy()
            createWebView()
            pointer.bringToFront()
            lastUrl?.let { web.loadUrl(it) }
            toast("The TV ran low on memory, so the page was reloaded")
            return true
        }
        override fun onPageFinished(view: WebView, url: String) {
            Diag.event("page finished loading · ${blockedOnPage.get()} requests blocked")
            applyHideRules()
            view.postDelayed({ applyHideRules() }, 1500) // late-loading banners
            if (tvHost != null && host() == tvHost) view.evaluateJavascript(TV_JS, null)
        }

        private val empty get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!compat() && AdBlocker.isBlocked(request.url.host)) {
                AdBlocker.blockedCount.incrementAndGet()
                blockedOnPage.incrementAndGet()
                return empty
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            val scheme = u.scheme?.lowercase()
            // App-launch and store links (intent://, market://…) are a favorite ad trick: refuse them.
            if (scheme != "http" && scheme != "https") return true
            if (compat()) return false
            if (AdBlocker.isBlocked(u.host)) { AdBlocker.blockedCount.incrementAndGet(); blockedOnPage.incrementAndGet(); return true }
            // Redirects to another site that you didn't click are refused (ad hijacks); your own clicks go through.
            if (request.isForMainFrame && !request.hasGesture()) {
                val current = view.url?.let { Uri.parse(it).host }
                if (current != null && !sameSite(current, u.host)) { AdBlocker.blockedCount.incrementAndGet(); blockedOnPage.incrementAndGet(); return true }
            }
            return false
        }
    }

    private fun sameSite(a: String?, b: String?): Boolean {
        fun base(h: String?) = h?.lowercase()?.split('.')?.takeLast(2)?.joinToString(".")
        return base(a) == base(b)
    }

    private inner class Chrome : WebChromeClient() {
        /**
         * New tabs/pop-ups: never opened as windows. If you clicked it and it stays on the same site,
         * it opens in place; anything else (pop-up ads go to other sites) is dropped.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) { AdBlocker.blockedCount.incrementAndGet(); return false }
            val probe = WebView(this@WebActivity)
            var handled = false
            fun decide(u: Uri) {
                if (handled) return
                handled = true
                val ok = (u.scheme == "http" || u.scheme == "https") &&
                    (compat() || (!AdBlocker.isBlocked(u.host) && sameSite(web.url?.let { Uri.parse(it).host }, u.host)))
                if (ok) web.loadUrl(u.toString()) else { AdBlocker.blockedCount.incrementAndGet(); blockedOnPage.incrementAndGet() }
                probe.post { probe.stopLoading(); probe.destroy() }
            }
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { decide(r.url); return true }
                override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) { decide(Uri.parse(url)) }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = probe
            resultMsg.sendToTarget()
            return true
        }

        /** Protected (DRM) video needs this; nothing else (camera, mic…) is ever granted. */
        override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
            val wanted = request.resources.filter { it == android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (wanted.isNotEmpty()) request.grant(wanted.toTypedArray()) else request.deny()
        }

        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
            if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING)
                Diag.event("page " + m.messageLevel().name.lowercase() + ": " + m.message())
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            Diag.event("full-screen ON · view=${view.javaClass.simpleName}")
            fullscreenView?.let { onHideCustomView() }
            fullscreenView = view
            fullscreenCallback = callback
            root.addView(view, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            web.visibility = View.INVISIBLE
            toolbar.visibility = View.GONE
        }

        override fun onHideCustomView() {
            if (fullscreenView != null) Diag.event("full-screen OFF")
            fullscreenView?.let { root.removeView(it) }
            fullscreenView = null
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
            web.visibility = View.VISIBLE
        }
    }

    // ---- remote input --------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (picking) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) { move(event.keyCode, event.repeatCount); hover() }
                    else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) scroller.release()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    if (down && event.repeatCount == 0) pick()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_MENU -> {
                    if (down && event.repeatCount == 0) cancelPick()
                    return true
                }
            }
        }
        if (barShown()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) { barFocus = (barFocus + if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0, toolbarActions.lastIndex); sounds.play(Sfx.MOVE); paintBar() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    if (down && event.repeatCount == 0) { val act = toolbarActions[barFocus].second; hideBar(); act() }
                    return true
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (down && event.repeatCount == 0) { hideBar(); showMenu() }
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                    if (down && event.repeatCount == 0) { hideBar(); sounds.play(Sfx.BACK) }
                    return true
                }
            }
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) move(event.keyCode, event.repeatCount)
                else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) scroller.release()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (down && event.repeatCount == 0) click()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                if (down && event.repeatCount == 0) showBar() // ☰ shows the bar; ☰ again opens the full menu
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && event.repeatCount == 0) web.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');if(v){v.paused?v.play():v.pause();}})()", null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down && event.repeatCount == 0) scrollPage(1); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down && event.repeatCount == 0) scrollPage(-1); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> {
                if (!down) return true
                if (event.repeatCount != 0) return true
                when {
                    fullscreenView != null -> { sounds.play(Sfx.BACK); (web.webChromeClient as? Chrome)?.onHideCustomView() }
                    web.canGoBack() -> { sounds.play(Sfx.BACK); web.goBack() }
                    else -> finish() // Ribbon plays the back sound when it reappears
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun move(key: Int, repeat: Int) {
        val step = 9f * dp * min(1f + repeat * 0.35f, 6f)
        val edge = 24f * dp
        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> pointer.x0 -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> pointer.x0 += step
            KeyEvent.KEYCODE_DPAD_UP -> if (pointer.y0 <= edge) scroller.start(-1) else pointer.y0 -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> if (pointer.y0 >= root.height - edge) scroller.start(1) else pointer.y0 += step
        }
        pointer.x0 = pointer.x0.coerceIn(0f, root.width - 1f)
        pointer.y0 = pointer.y0.coerceIn(0f, root.height - 1f)
        pointer.poke()
    }

    /**
     * Smooth scrolling: while an arrow is held at the screen edge we "hold a finger" on the page and
     * move it a little every frame (the browser scrolls touch drags on its fast path). Speed ramps up
     * while held; on release it eases to a stop and lifts without a fling, so it stops where you expect.
     * A quick tap still travels at least [MIN_TAP_DP].
     */
    private inner class SmoothScroller : android.view.Choreographer.FrameCallback {
        private val MIN_TAP_DP = 230f
        private var dir = 0
        private var held = false
        private var active = false
        private var downTime = 0L
        private var y = 0f
        private var x = 0f
        private var speed = 0f          // px per second
        private var heldFor = 0f
        private var travelled = 0f
        private var lastNs = 0L
        private val target get() = fullscreenView ?: web

        fun start(d: Int) {
            if (active && d == dir) { held = true; return }
            if (active) lift()
            dir = d; held = true; active = true
            heldFor = 0f; travelled = 0f; speed = 0f; lastNs = 0L
            grip()
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }

        fun release() { held = false }

        private fun send(action: Int) {
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            target.dispatchTouchEvent(e); e.recycle()
        }

        private fun grip() {
            val h = root.height.toFloat()
            x = pointer.x0.coerceIn(60f * dp, root.width - 60f * dp)
            y = if (dir > 0) h * 0.85f else h * 0.15f
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN)
        }

        /** Lift with no velocity: one still move, then up. */
        private fun lift() {
            send(MotionEvent.ACTION_MOVE)
            send(MotionEvent.ACTION_UP)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return
            val dt = if (lastNs == 0L) 1f / 60f else ((frameTimeNanos - lastNs) / 1e9f).coerceIn(0.001f, 0.05f)
            lastNs = frameTimeNanos
            val minLeft = MIN_TAP_DP * dp - travelled
            val goal = when {
                held -> { heldFor += dt; (1100f + 2200f * (heldFor / 1.2f).coerceAtMost(1f)) * dp / 2f }
                minLeft > 0f -> 900f * dp / 2f
                else -> 0f
            }
            // ease toward the goal speed (quick start, gentle stop)
            speed += (goal - speed) * (1f - kotlin.math.exp(-dt / if (goal > speed) 0.06f else 0.09f))
            if (!held && minLeft <= 0f && speed < 25f * dp) {
                active = false; lift(); return
            }
            val step = speed * dt
            travelled += step
            y -= dir * step
            val h = root.height.toFloat()
            if (y < h * 0.08f || y > h * 0.92f) { lift(); grip() } else send(MotionEvent.ACTION_MOVE)
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val scroller by lazy { SmoothScroller() }

    /** Page jump for ⏩/⏪: a long smooth glide. */
    private fun scrollPage(dir: Int) { scroller.start(dir); root.postDelayed({ scroller.release() }, 450) }

    private fun click() {
        toolbarActions.firstOrNull { hit(it.first) }?.let { pointer.pulse(); it.second(); return }
        val target = fullscreenView ?: web
        val t = SystemClock.uptimeMillis()
        val x = pointer.x0
        val y = pointer.y0
        fun ev(action: Int, time: Long) = MotionEvent.obtain(t, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val press = ev(MotionEvent.ACTION_DOWN, t)
        target.dispatchTouchEvent(press)
        press.recycle()
        target.postDelayed({
            val release = ev(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
            target.dispatchTouchEvent(release)
            release.recycle()
        }, 40)
        pointer.pulse()
        sounds.play(Sfx.SELECT)
    }

    // ---- menu ----------------------------------------------------------------------------

    private fun showMenu() {
        val url = web.url
        val saved = url != null && Sites.contains(this, url)
        val options = mutableListOf("Go to address…")
        if (url != null) options += if (saved) "Remove this site from Ribbon" else "Save this site to Ribbon"
        if (url != null) options += "Hide something on this page"
        val hiddenCount = HideRules.forHost(this, host()).size
        if (hiddenCount > 0) options += "Show hidden items on this site ($hiddenCount)"
        options += "Fix picture (video plays but screen is black)"
        options += "Reload"
        options += if (desktop) "Use mobile version of sites" else "Use desktop version of sites"
        options += "Troubleshoot…"
        options += "Close"
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Web")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Go to address…" -> askForAddress()
                    "Save this site to Ribbon" -> askForName(url!!)
                    "Remove this site from Ribbon" -> { Sites.remove(this, url!!); toast("Removed from Ribbon") }
                    "Hide something on this page" -> startPick()
                    "Fix picture (video plays but screen is black)" -> fixPicture()
                    "Reload" -> web.reload()
                    "Use mobile version of sites", "Use desktop version of sites" -> { desktop = !desktop; applyUserAgent(); web.reload() }
                    "Troubleshoot…" -> showTroubleshoot()
                    "Close" -> finish()
                    else -> if (options[which].startsWith("Show hidden items")) {
                        host()?.let { HideRules.clear(this, it) }
                        web.reload()
                        toast("Hidden items restored on this site")
                    }
                }
            }
            .show()
    }

    /** Diagnostic tools, kept out of the main menu. */
    private fun showTroubleshoot() {
        val url = web.url
        val options = mutableListOf("Where does the internet think I am?")
        if (host() != null) options += if (compat(host())) "Compatibility mode on this site: ON (turn off)" else "Compatibility mode on this site (pauses ad blocking here)"
        options += "Video check"
        if (url != null) options += "Reset this site (clear its cookies and saved data)"
        options += "Report a problem (private diagnostics)"
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Troubleshoot")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Where does the internet think I am?" -> whereAmI()
                    "Video check" -> videoCheck()
                    "Reset this site (clear its cookies and saved data)" -> resetSite()
                    "Report a problem (private diagnostics)" -> report()
                    else -> if (options[which].startsWith("Compatibility mode")) {
                        host()?.let { h -> val on = !compat(h); setCompat(h, on); web.reload(); toast(if (on) "Compatibility mode on for this site: ad blocking paused here" else "Compatibility mode off: ad blocking back on here") }
                    }
                }
            }
            .show()
    }

    /**
     * Shows which country websites see this TV in (handy with a VPN). Runs only when asked,
     * asks Cloudflare's public "trace" page, and stores nothing.
     */
    private fun whereAmI() {
        toast("Checking…")
        kotlin.concurrent.thread(isDaemon = true) {
            val country = try {
                val c = java.net.URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val body = c.inputStream.bufferedReader().readText(); c.disconnect()
                Regex("(?m)^loc=([A-Z]{2})").find(body)?.groupValues?.get(1)
            } catch (_: Exception) { null }
            runOnUiThread {
                val name = country?.let { java.util.Locale("", it).displayCountry }
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Where websites see you")
                    .setMessage(if (country != null) "$name ($country)\n\nIf you use a VPN, this is the country it appears as. Some sites (like live TV streams) only play in certain countries."
                        else "Couldn't check right now. Is the TV online?")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun askForAddress() {
        val input = EditText(this).apply {
            hint = "Type an address or search"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Go to")
            .setView(input)
            .setPositiveButton("Go") { _, _ -> go(input.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> if (web.url == null) finish() }
            .create()
        input.setOnEditorActionListener { _, _, _ -> go(input.text.toString()); dialog.dismiss(); true }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        web.loadUrl(url)
    }

    private fun askForName(url: String) {
        val input = EditText(this).apply {
            setText(web.title?.take(30).orEmpty())
            setSingleLine()
            selectAll()
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Name in Ribbon")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { Uri.parse(url).host ?: "Site" }
                Sites.add(this, Sites.Site(name, url))
                toast("Saved to Ribbon › Apps")
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Saves a TV channel tile when OPEN_WEB carries a "channel" name, then plays it like the tile would. */
    private fun saveChannelFrom(i: Intent) {
        val url = i.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: return
        val name = i.getStringExtra(EXTRA_CHANNEL)?.takeIf { it.isNotBlank() } ?: return
        val badge = i.getStringExtra(EXTRA_BADGE)?.takeIf { it.isNotBlank() } ?: name.take(1).uppercase()
        val sub = i.getStringExtra(EXTRA_SUB)?.takeIf { it.isNotBlank() }
        Sites.add(this, Sites.Site(name, url, tv = true, badge = badge, sub = sub))
        i.putExtra(EXTRA_TV, true).putExtra(EXTRA_TITLE, name).putExtra(EXTRA_BADGE, badge)
        toast("Added \"$name\" to Video")
    }

    // ---- TV mode loading screen ---------------------------------------------------------------

    /** Shows (or restarts) the loading screen; [from] carries the tile's name and colors on first show. */
    private fun showLoader(from: Intent?) {
        val existing = loader
        if (existing != null && from == null) {
            existing.restart()
        } else {
            existing?.let { root.removeView(it) }
            val i = from ?: intent
            val letter = i.getStringExtra(EXTRA_BADGE)
            val view = LoadingView(this,
                title = i.getStringExtra(EXTRA_TITLE) ?: tvHost.orEmpty(),
                sub = i.getStringExtra(EXTRA_SUB),
                colors = i.getIntArrayExtra(EXTRA_COLORS)?.takeIf { it.size >= 2 } ?: Palette.red,
                badge = if (letter.isNullOrBlank()) Glyph.Play else Glyph.Letter(letter))
            view.elevation = 1000f // above the pointer and the bar
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            loader = view
        }
        pointer.visibility = View.INVISIBLE // no cursor floating over the loading screen
        root.removeCallbacks(loaderTimeout)
        root.postDelayed(loaderTimeout, 45_000) // never trap you behind it: after 45 s, show whatever the page has
    }

    private fun hideLoader() {
        val view = loader ?: return
        loader = null
        root.removeCallbacks(loaderTimeout)
        pointer.visibility = View.VISIBLE
        view.dismiss { root.removeView(view) }
    }

    // ---- stuck-video watchdog ----------------------------------------------------------------

    /**
     * Stuck-video watchdog. A tiny script runs in every frame of the page (including embedded
     * players from other sites) and every 2 s reports four numbers about its playing video:
     * time, load state, picture width, frames decoded. If any frame's video plays audio with no new
     * picture frames (~6 s) or claims to play but loads nothing (~10 s), the page is reloaded once
     * (never twice within 2 minutes for the same page). Audio-only media is ignored. Nothing is logged.
     */
    private class VideoState(var t: Double = -1.0, var decoded: Int = -1, var score: Int = 0, var width: Int = 0, var samples: Int = 0, var cover: String = "?")
    private val frameIds = HashMap<Any, Int>()
    private val mainFrameSources = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
    private val frames = HashMap<Any, VideoState>() // cleared on every page load
    private var autoReloadedUrl: String? = null
    private var autoReloadedAt = 0L
    private var frameWatchInstalled = false

    private val WATCH_JS = "(function(){if(window.__rbWatch)return;window.__rbWatch=1;" +
        "function s(){var vs=[].slice.call(document.querySelectorAll('video')).filter(function(v){return !v.paused&&!v.ended});" +
        "if(!vs.length)return;vs.sort(function(a,b){return b.offsetWidth*b.offsetHeight-a.offsetWidth*a.offsetHeight});" +
        "var v=vs[0],q=v.getVideoPlaybackQuality?v.getVideoPlaybackQuality():null;" +
        "var r=v.getBoundingClientRect(),cx=r.left+r.width/2,cy=r.top+r.height/2,c=document.elementFromPoint(cx,cy),cov='none';" +
        "if(c&&c!==v&&!c.contains(v)){var cr=c.getBoundingClientRect(),cs=getComputedStyle(c);" +
        "var pct=Math.round(100*Math.min(1,(cr.width*cr.height)/Math.max(1,r.width*r.height)));" +
        "var inPlayer=!!(v.parentElement&&v.parentElement.contains(c));" +
        "cov=c.tagName+'|'+pct+'%|'+cs.backgroundColor.replace(/,/g,' ')+'|op '+cs.opacity+'|in player '+inPlayer;" +
        // a solid box that isn't part of the player, covering most of a video that is decoding: remove it after 2 samples (~4 s)
        "var bgA=(cs.backgroundColor.match(/rgba?\\(([^)]*)\\)/)||[0,''])[1].split(/[ ,\\/]+/).filter(Boolean);var solid=(bgA.length<4||parseFloat(bgA[3])>0.9)&&cs.backgroundColor!=='transparent'&&parseFloat(cs.opacity)>0.9;" +
        "if(!inPlayer&&pct>=80&&(solid||c.tagName==='IFRAME')&&q&&q.totalVideoFrames>0){window.__rbCov=(window.__rbCov===c?c:(window.__rbCovN=0,c));window.__rbCovN=(window.__rbCovN||0)+1;" +
        "if(window.__rbCovN>=2){c.style.setProperty('display','none','important');cov='REMOVED '+cov;window.__rbCovN=0;}}else{window.__rbCovN=0;}}" +
        "var m=[v.currentTime,v.readyState,v.videoWidth,q?q.totalVideoFrames:-1,cov].join(',');" +
        "if(window.RibbonWatch)RibbonWatch.postMessage(m);else return m;}" +
        "if(window.RibbonWatch)RibbonWatch.onmessage=function(e){" +
        "if(e.data==='vol'){[].slice.call(document.querySelectorAll('video')).forEach(function(v){v.muted=false;v.volume=1;});return;}" +
        "if(typeof e.data==='string'&&e.data.indexOf('seek:')===0){" +
        "var t=parseFloat(e.data.slice(5));var vs=[].slice.call(document.querySelectorAll('video')).sort(function(a,b){return b.offsetWidth*b.offsetHeight-a.offsetWidth*a.offsetHeight});" +
        "if(vs[0]&&t>0)vs[0].currentTime=t;return;}if(e.data==='fix'){[].slice.call(document.querySelectorAll('video')).forEach(function(v){" +
        "var d=v.style.display;v.style.display='none';void v.offsetHeight;requestAnimationFrame(function(){v.style.display=d;});});}};" +
        "window.__rbSample=s;setInterval(function(){if(window.RibbonWatch)s();},2000);})()"

    /** Installs the per-frame watcher (needs a reasonably recent web engine; falls back otherwise). */
    private fun installFrameWatch() {
        frameWatchInstalled = false
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) return
        try {
            androidx.webkit.WebViewCompat.addWebMessageListener(web, "RibbonWatch", setOf("*")) { _, message, _, isMain, proxy ->
                if (isMain) mainFrameSources.add(proxy)
                onVideoSample(proxy, message.data ?: "")
            }
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, WATCH_JS, setOf("*"))
            frameWatchInstalled = true
        } catch (_: Exception) {
        }
    }

    /** Fallback when frames can't be watched: sample the main page only. */
    private val watchdog: Runnable = Runnable {
        if (!frameWatchInstalled && !picking) web.evaluateJavascript(WATCH_JS + ";window.__rbSample&&__rbSample()") { raw ->
            onVideoSample(web, raw.trim('"'))
        }
        root.postDelayed(watchdog, 2000)
    }

    private fun onVideoSample(source: Any, sample: String) {
        if (picking) return
        val p = sample.split(',')
        if (p.size < 4) return
        val t = p[0].toDoubleOrNull() ?: return
        val ready = p[1].toIntOrNull() ?: return
        val width = p[2].toIntOrNull() ?: 0
        val decoded = p[3].toIntOrNull() ?: -1
        val isNew = !frames.containsKey(source)
        val st = frames.getOrPut(source) { VideoState() }
        val idx = frameIds.getOrPut(source) { frameIds.size + 1 }
        val where = if (source === web || source in mainFrameSources) "page" else "embedded frame"
        val cover = p.getOrNull(4) ?: "?"
        if (isNew) Diag.event("video seen in $where (#$idx) · ${width}px wide · ready $ready · covering: $cover")
        if (++st.samples % 5 == 0) Diag.event("#$idx · t=${"%.1f".format(t)} ready=$ready w=$width decoded=$decoded · covering: $cover")
        st.cover = cover
        if (loader != null && width > 0 && t > 0.3 && decoded != 0) runOnUiThread { hideLoader() } // picture is playing
        // TV mode: full volume by default (the first few samples, so the site's own "start muted" can't win).
        if (tvHost != null && host() == tvHost && st.samples <= 4 && source is androidx.webkit.JavaScriptReplyProxy) source.postMessage("vol")
        if (cover.startsWith("REMOVED")) {
            Diag.event("auto-removed a box covering video #$idx · $cover")
            runOnUiThread { toast("Removed a box covering the video") }
        }
        maybeResume(source, t)
        val timeMoving = st.t >= 0 && t > st.t + 0.8
        val noNewFrames = decoded >= 0 && st.decoded >= 0 && decoded == st.decoded
        st.score = when {
            width > 0 && timeMoving && noNewFrames -> st.score + 2     // audio plays, picture doesn't: ~6 s
            ready < 2 && !timeMoving -> st.score + 1                    // "playing" but nothing arrives: ~10 s
            else -> 0
        }
        if (st.score > 0) Diag.event("#$idx stuck score ${st.score} (t=${"%.1f".format(t)} ready=$ready decoded=$decoded)")
        st.t = t; st.decoded = decoded; st.width = width
        if (st.score >= 5) {
            st.score = 0
            val url = web.url ?: return
            val now = SystemClock.uptimeMillis()
            if (url == autoReloadedUrl && now - autoReloadedAt < 120_000) return
            autoReloadedUrl = url; autoReloadedAt = now
            fixPicture()
        }
    }

    private var resumeAt = 0.0
    private var resumeUntil = 0L

    /**
     * Fix picture: reloads the page (the only thing that reliably rebuilds the TV's video layer) and
     * then jumps the video back to where it was, as soon as it starts playing again.
     */
    private fun fixPicture() {
        val t = frames.values.filter { it.width > 0 }.maxOfOrNull { it.t } ?: frames.values.maxOfOrNull { it.t } ?: 0.0
        resumeAt = if (t > 5) t else 0.0
        resumeUntil = SystemClock.uptimeMillis() + 180_000 // time to press Play again on the site
        Diag.event("fix/reload requested · resume at ${"%.0f".format(resumeAt)} s")
        toast(if (resumeAt > 0) "Reloading — the video will continue where it was" else "Reloading the page")
        web.reload()
    }

    /** After a Fix-picture reload: seek the first video that starts playing back to the saved spot. */
    private fun maybeResume(source: Any, t: Double) {
        if (resumeAt <= 0 || SystemClock.uptimeMillis() > resumeUntil) return
        if (t >= resumeAt - 3) { resumeAt = 0.0; return }
        val target = resumeAt
        resumeAt = 0.0
        Diag.event("resume: jumping video back to ${"%.0f".format(target)} s")
        if (source is androidx.webkit.JavaScriptReplyProxy) {
            try { source.postMessage("seek:$target") } catch (_: Exception) {}
        } else {
            web.evaluateJavascript("(function(){var vs=[].slice.call(document.querySelectorAll('video')).sort(function(a,b){return b.offsetWidth*b.offsetHeight-a.offsetWidth*a.offsetHeight});if(vs[0])vs[0].currentTime=$target;})()", null)
        }
    }

    /** Writes a scrubbed snapshot + recent events to the log (tag RibbonDiag). No addresses. */
    private fun report() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val wv = if (android.os.Build.VERSION.SDK_INT >= 26) try { WebView.getCurrentWebViewPackage()?.versionName } catch (_: Exception) { null } else "(Android ${android.os.Build.VERSION.RELEASE})"
        val snap = mutableListOf(
            "web view: visible=${web.visibility == View.VISIBLE} size=${web.width}x${web.height} full-screen=${fullscreenView != null}" +
                (fullscreenView?.let { " (${it.javaClass.simpleName} ${it.width}x${it.height})" } ?: ""),
            "window background=${window.decorView.background != null} · root children=${root.childCount}",
            "frame watcher installed=$frameWatchInstalled · videos reporting=${frames.size}",
            "memory available=${mi.availMem / 1048576} MB · low=${mi.lowMemory}",
            "engine=$wv · version=" + (if (desktop) "desktop" else "mobile") + " · ad blocking " + (if (compat()) "paused" else "on") + " · blocked on page=${blockedOnPage.get()}",
        )
        for ((src, st) in frames) snap += "video #${frameIds[src]} (" + (if (src === web || src in mainFrameSources) "page" else "embedded frame") +
            "): t=${"%.1f".format(st.t)} w=${st.width} decoded=${st.decoded} stuckScore=${st.score} · covering: ${st.cover}"
        Diag.report(snap)
        toast("Report saved. Tell Claude: report sent")
    }

    /** Clears cookies and web storage for the current site only (e.g. to redo a privacy choice), then reloads. */
    private fun resetSite() {
        val u = web.url?.let { Uri.parse(it) } ?: return
        val host = u.host ?: return
        val base = host.split('.').takeLast(2).joinToString(".")
        val cm = CookieManager.getInstance()
        for (h in listOf(host, base, "www.$base")) {
            val origin = "https://$h"
            cm.getCookie(origin)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                for (domain in listOf("", "; Domain=.$base", "; Domain=$h")) cm.setCookie(origin, "$name=; Max-Age=0; Path=/$domain")
            }
            android.webkit.WebStorage.getInstance().deleteOrigin(origin)
            android.webkit.WebStorage.getInstance().deleteOrigin("http://$h")
        }
        cm.flush()
        web.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null)
        web.clearCache(false)
        toast("This site was reset")
        web.postDelayed({ web.reload() }, 400)
    }

    // ---- video check -------------------------------------------------------------------------

    /** Shows numbers only (no addresses) about the video on this page, for troubleshooting. */
    private fun videoCheck() {
        val js = "(function(){var vs=[].slice.call(document.querySelectorAll('video'));var fr=document.querySelectorAll('iframe').length;" +
            "var out={videos:vs.length,frames:fr};var v=vs.sort(function(a,b){return b.offsetWidth*b.offsetHeight-a.offsetWidth*a.offsetHeight})[0];" +
            "if(v){var q=v.getVideoPlaybackQuality?v.getVideoPlaybackQuality():null;out.ready=v.readyState;out.size=v.videoWidth+'x'+v.videoHeight;" +
            "out.time=Math.round(v.currentTime);out.paused=v.paused;out.decoded=q?q.totalVideoFrames:-1;out.dropped=q?q.droppedVideoFrames:-1;" +
            "out.drm=!!v.mediaKeys;out.err=v.error?v.error.code:0;}return JSON.stringify(out);})()"
        web.evaluateJavascript(js) { raw ->
            val o = try { org.json.JSONObject(org.json.JSONArray("[$raw]").getString(0)) } catch (_: Exception) { org.json.JSONObject() }
            val lines = mutableListOf<String>()
            lines += "Ad blocking here: " + if (compat()) "paused (compatibility mode)" else "on · ${blockedOnPage.get()} requests blocked on this page"
            lines += "Version of sites: " + if (desktop) "desktop" else "mobile"
            val vids = o.optInt("videos", 0)
            lines += "Videos on page: $vids · embedded frames: ${o.optInt("frames", 0)}"
            if (vids > 0) {
                lines += "Size: ${o.optString("size")} · ready: ${o.optInt("ready")}/4 · time: ${o.optInt("time")} s · paused: ${o.optBoolean("paused")}"
                lines += "Frames decoded: ${o.optInt("decoded")} · dropped: ${o.optInt("dropped")}"
                lines += "Protected (DRM): " + if (o.optBoolean("drm")) "yes" else "no"
                if (o.optInt("err") != 0) lines += "Error code: ${o.optInt("err")}"
            } else if (o.optInt("frames", 0) > 0) {
                lines += "The player is probably inside an embedded frame, which can't be inspected from here."
            }
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Video check")
                .setMessage(lines.joinToString("\n"))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ---- top control bar -------------------------------------------------------------------

    private val toolbar by lazy { buildToolbar() }
    private val toolbarActions = mutableListOf<Pair<View, () -> Unit>>()

    private fun buildToolbar(): android.widget.LinearLayout {
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 22 * dp; setColor(0xE6101530.toInt()) }
            visibility = View.GONE
        }
        fun btn(label: String, action: () -> Unit) {
            val b = android.widget.TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 16f
                setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.TRANSPARENT) }
            }
            bar.addView(b)
            toolbarActions += b to action
        }
        btn("‹  Back") { sounds.play(Sfx.BACK); if (web.canGoBack()) web.goBack() else finish() }
        btn("▣  Fix picture") { sounds.play(Sfx.SELECT); fixPicture() }
        btn("⟳  Reload") { sounds.play(Sfx.SELECT); web.reload() }
        btn("⌂  Ribbon") { finish() }
        btn("☰  More") { showMenu() }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply { topMargin = (14 * dp).toInt() })
        pointer.bringToFront()
        return bar
    }

    private var barFocus = 0
    private val hideToolbar = Runnable { hideBar() }

    private fun barShown() = toolbar.visibility == View.VISIBLE

    /** The bar only appears when asked for (☰), so it never covers the site's own menus. */
    private fun showBar() {
        barFocus = 0
        toolbar.animate().cancel()
        toolbar.alpha = 1f
        toolbar.visibility = View.VISIBLE
        toolbar.bringToFront(); pointer.bringToFront()
        redrawOverlay()
        sounds.play(Sfx.SELECT)
        paintBar()
    }

    private fun hideBar() {
        root.removeCallbacks(hideToolbar)
        toolbar.visibility = View.GONE
        redrawOverlay()
    }

    /** Forces Ribbon's overlay (bar + pointer) to be redrawn now; on this TV a visibility change alone sometimes isn't. */
    private fun redrawOverlay() {
        toolbar.requestLayout(); toolbar.invalidate()
        root.invalidate(); pointer.invalidate()
        root.postOnAnimation { root.invalidate(); pointer.invalidate() }
    }

    /** Highlights the selected button and restarts the 6-second auto-hide. */
    private fun paintBar() {
        toolbarActions.forEachIndexed { i, (v, _) ->
            (v.background as android.graphics.drawable.GradientDrawable).setColor(if (i == barFocus) 0x6600E5FF else Color.TRANSPARENT)
        }
        redrawOverlay()
        root.removeCallbacks(hideToolbar)
        root.postDelayed(hideToolbar, 6000)
    }

    private fun hit(v: View): Boolean {
        if (toolbar.visibility != View.VISIBLE) return false
        val r = android.graphics.Rect(); v.getHitRect(r); r.offset(toolbar.left, toolbar.top)
        return r.contains(pointer.x0.toInt(), pointer.y0.toInt())
    }

    // ---- hide-element picker -------------------------------------------------------------

    private fun startPick() {
        picking = true
        web.evaluateJavascript(HideRules.PICKER_JS) { hover() }
        toast("Point at what to hide, then press OK · Back cancels")
    }

    private fun hover() {
        val fx = pointer.x0 / root.width
        val fy = pointer.y0 / root.height
        web.evaluateJavascript("window.__rbHover&&__rbHover($fx,$fy)", null)
    }

    private fun pick() {
        picking = false
        web.evaluateJavascript(HideRules.PICK_JS) { result ->
            val selector = try { org.json.JSONArray("[$result]").getString(0) } catch (_: Exception) { "" }
            val h = host()
            if (selector.isNotBlank() && h != null) {
                HideRules.add(this, h, selector)
                applyHideRules()
                sounds.play(Sfx.SELECT)
                toast("Hidden · Ribbon will keep it hidden on this site")
            } else toast("Nothing to hide there")
        }
    }

    private fun cancelPick() {
        picking = false
        web.evaluateJavascript(HideRules.CANCEL_JS, null)
        sounds.play(Sfx.BACK)
    }

    // ---- pointer -------------------------------------------------------------------------

    private class PointerView(ctx: Context) : View(ctx) {
        private val dp = ctx.resources.displayMetrics.density
        var x0 = -1f
        var y0 = -1f
        private var pulseUntil = 0L
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B1440.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeJoin = Paint.Join.ROUND }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC00E5FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * dp }
        private val arrow = Path().apply {
            moveTo(0f, 0f); lineTo(0f, 22f); lineTo(6f, 17f); lineTo(10f, 26f); lineTo(14f, 24f); lineTo(10f, 15.5f); lineTo(17f, 15.5f); close()
        }
        /** Fades the pointer out after a short pause so it never sits on top of a video. */
        private val fade = Runnable { animate().alpha(0f).setDuration(350) }

        init { isFocusable = false; isClickable = false }

        fun poke() {
            animate().cancel(); alpha = 1f; invalidate()
            removeCallbacks(fade); postDelayed(fade, 2500)
        }
        fun pulse() { pulseUntil = SystemClock.uptimeMillis() + 180; poke() }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (x0 < 0) { x0 = w / 2f; y0 = h / 2f; poke() }
        }

        override fun onDraw(c: Canvas) {
            val now = SystemClock.uptimeMillis()
            if (now < pulseUntil) { c.drawCircle(x0, y0, 16f * dp, ring); postInvalidateDelayed(60) }
            c.save(); c.translate(x0, y0); c.scale(dp * 1.15f, dp * 1.15f)
            c.drawPath(arrow, fill); c.drawPath(arrow, outline)
            c.restore()
        }
    }
}
