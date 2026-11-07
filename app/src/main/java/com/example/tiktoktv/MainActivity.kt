package com.example.tiktoktv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var webView: WebView

    // false = normale Navigation (Login-Seite, Menüs)
    // true  = Feed-Modus (Fernbedienung steuert Videos)
    private var feedMode = false

    private val startUrl = "https://www.tiktok.com/login/qrcode"
    private val feedUrl = "https://www.tiktok.com/foryou"

    // Desktop-Browser, damit TikTok die volle Web-Version samt QR-Login zeigt
    private val desktopUserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = desktopUserAgent
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                if (url != null && url.contains("tiktok.com/foryou") && !feedMode) {
                    setFeedMode(true)
                }
            }
        }

        webView.loadUrl(startUrl)
        webView.requestFocus()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun setFeedMode(on: Boolean) {
        feedMode = on
        Toast.makeText(
            this,
            if (on) "Feed-Modus (Menü-Taste = Navigation)" else "Navigations-Modus (Menü-Taste = Feed)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun sendKey(keyCode: Int) {
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun togglePlayPause() {
        val js = """
            (function() {
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                    var r = vids[i].getBoundingClientRect();
                    if (r.top < window.innerHeight && r.bottom > 0 && r.height > 200) {
                        if (vids[i].paused) { vids[i].play(); } else { vids[i].pause(); }
                        break;
                    }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        // Menü-Taste (☰ auf der Fire-TV-Fernbedienung): Modus wechseln
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!feedMode && !(webView.url ?: "").contains("tiktok.com/foryou")) {
                    webView.loadUrl(feedUrl)
                }
                setFeedMode(!feedMode)
            }
            return true
        }

        // Play/Pause-Taste funktioniert immer
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY ||
            code == KeyEvent.KEYCODE_MEDIA_PAUSE
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) togglePlayPause()
            return true
        }

        if (feedMode) {
            when (code) {
                // Mitte = Pause/Play
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) togglePlayPause()
                    return true
                }
                // Rechts = Like (TikTok-Web-Shortcut "L")
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) sendKey(KeyEvent.KEYCODE_L)
                    return true
                }
                // Links = Ton an/aus (TikTok-Web-Shortcut "M")
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) sendKey(KeyEvent.KEYCODE_M)
                    return true
                }
                // Hoch/Runter werden normal an die Seite weitergegeben
                // (Pfeiltasten = vorheriges/nächstes Video)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
