package dev.ribbon.launcher

import android.app.Activity
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Shows an HDMI input full-screen. Fire TV's own input switcher is locked to Amazon's system apps,
 * so Ribbon plays the input itself through Android's standard TvView (the same hardware
 * passthrough the TV uses). Back returns to Ribbon.
 */
class InputActivity : Activity() {

    companion object {
        const val EXTRA_INPUT_ID = "input_id"
        const val EXTRA_FALLBACK_ID = "fallback_id"
        const val EXTRA_LABEL = "label"
        private const val TAG = "RibbonInput"
    }

    private lateinit var tv: TvView
    private var triedFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TvView(this)
        root.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        Compat.hideSystemBars(window)

        val label = intent.getStringExtra(EXTRA_LABEL)
        if (label != null) {
            root.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(48, 36, 48, 36)
                animate().alpha(0f).setStartDelay(1500).setDuration(400)
            })
        }

        tv.setCallback(object : TvView.TvInputCallback() {
            override fun onConnectionFailed(inputId: String) = failed(inputId, "connection failed")
            override fun onDisconnected(inputId: String) = failed(inputId, "disconnected")
            // "Unavailable" also fires while the signal is still locking on (tuning/buffering), so just log it.
            override fun onVideoUnavailable(inputId: String, reason: Int) { Log.d(TAG, "video unavailable on $inputId, reason $reason") }
            override fun onVideoAvailable(inputId: String) { Log.d(TAG, "video available on $inputId") }
        })
        tune(intent.getStringExtra(EXTRA_INPUT_ID) ?: return finish())
    }

    private fun tune(id: String) {
        Log.d(TAG, "tuning $id")
        tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id))
    }

    private fun failed(inputId: String, why: String) {
        Log.w(TAG, "$inputId: $why")
        val fallback = intent.getStringExtra(EXTRA_FALLBACK_ID)
        if (!triedFallback && fallback != null && fallback != inputId) {
            triedFallback = true
            tune(fallback)
        } else if (why.startsWith("connection") || why == "disconnected") {
            Toast.makeText(this, "Couldn't open ${intent.getStringExtra(EXTRA_LABEL) ?: "input"}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        tv.reset()
        finish()
    }
}
