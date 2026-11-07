package com.example.tiktoktv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

class MainActivity : Activity() {

    // Die Seite wird in dieser (kleineren) Breite gerendert und dann auf
    // Bildschirmgröße hochskaliert. Das spart viel Rechenleistung.
    // Kleiner (z. B. 960) = flüssiger aber unschärfer, größer (z. B. 1600) = schärfer aber langsamer.
    private val renderWidth = 1280

    private lateinit var webView: WebView
    private lateinit var splash: ImageView
    private lateinit var hint: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(400).start() }

    private var feedMode = false
    private var splashGone = false
    private var hintShownOnce = false

    private val startUrl = "https://www.tiktok.com/login/qrcode"
    private val feedUrl = "https://www.tiktok.com/foryou"
    private val followingUrl = "https://www.tiktok.com/following"

    private val desktopUserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Blendet Menüs, Seitenleiste und Banner aus, schaltet Animationen ab
    // und macht den Hintergrund schwarz (TV-Look, weniger Last).
    private val styleJs = """
        (function() {
            var m = document.querySelector('meta[name=viewport]');
            if (!m) {
                m = document.createElement('meta');
                m.name = 'viewport';
                (document.head || document.documentElement).appendChild(m);
            }
            m.content = 'width=1280';

            var css = `
                html, body { background: #000 !important; overflow-x: hidden !important; }
                ::-webkit-scrollbar { display: none !important; }
                * { scroll-behavior: auto !important; transition: none !important;
                    animation: none !important; }
                #app-header, [class*="DivHeaderContainer"],
                [class*="DivSideNavContainer"], [class*="DivSideNavPlaceholder"],
                [class*="DivBannerContainer"], [class*="DivAppBanner"],
                [class*="DivGetAppContainer"], [class*="DivFooterContainer"] {
                    display: none !important;
                }
            `;
            var s = document.getElementById('__tvstyle');
            if (!s) {
                s = document.createElement('style');
                s.id = '__tvstyle';
                (document.head || document.documentElement).appendChild(s);
            }
            s.textContent = css;
        })();
    """.trimIndent()

    // Klickt das Cookie-Banner weg und lehnt alle optionalen Cookies ab.
    private val cookieJs = """
        (function() {
            if (window.__cookieTimer) { clearInterval(window.__cookieTimer); }
            var declineWords = ['ablehnen', 'decline', 'reject', 'refuse', 'necessary',
                                'notwendig', 'erforderlich', 'essential', 'essenziell'];
            var acceptWords = ['alle zulassen', 'allow all', 'accept all', 'alle akzeptieren',
                               'zustimmen', 'akzeptieren', 'accept', 'agree'];
            function collectButtons(root, out) {
                var els = root.querySelectorAll('*');
                for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    if (el.tagName === 'BUTTON' || el.getAttribute('role') === 'button') { out.push(el); }
                    if (el.shadowRoot) { collectButtons(el.shadowRoot, out); }
                }
            }
            function matches(text, words) {
                for (var i = 0; i < words.length; i++) {
                    if (text.indexOf(words[i]) !== -1) { return true; }
                }
                return false;
            }
            function tryClick() {
                var banner = document.querySelector('tiktok-cookie-banner');
                if (!banner) { return false; }
                var buttons = [];
                collectButtons(banner.shadowRoot ? banner.shadowRoot : banner, buttons);
                if (buttons.length === 0) { return false; }
                var target = null;
                for (var i = 0; i < buttons.length; i++) {
                    var t = (buttons[i].innerText || buttons[i].textContent || '').toLowerCase();
                    if (matches(t, declineWords) && !matches(t, acceptWords)) { target = buttons[i]; break; }
                }
                if (target === null) {
                    var others = [];
                    for (var j = 0; j < buttons.length; j++) {
                        var t2 = (buttons[j].innerText || buttons[j].textContent || '').toLowerCase();
                        if (t2.length > 0 && !matches(t2, acceptWords)) { others.push(buttons[j]); }
                    }
                    if (others.length === 1) { target = others[0]; }
                }
                if (target !== null) { target.click(); return true; }
                return false;
            }
            var tries = 0;
            window.__cookieTimer = setInterval(function() {
                tries++;
                if (tryClick() || tries > 40) {
                    clearInterval(window.__cookieTimer);
                    window.__cookieTimer = null;
                }
            }, 1000);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dm = resources.displayMetrics
        val layoutWidth = minOf(renderWidth, dm.widthPixels)
        val scale = dm.widthPixels.toFloat() / layoutWidth
        val layoutHeight = (dm.heightPixels / scale).toInt()

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(layoutWidth, layoutHeight)
            pivotX = 0f
            pivotY = 0f
            scaleX = scale
            scaleY = scale
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(webView)

        splash = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.banner)
            setPadding(dm.widthPixels / 5, dm.heightPixels / 5, dm.widthPixels / 5, dm.heightPixels / 5)
        }
        root.addView(splash)

        hint = TextView(this).apply {
            text = "↑ ↓  Video      OK  Pause      ←  Ton      →  Gefällt mir      Menü  Für dich / Folge ich"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 24, 48, 24)
            background = GradientDrawable().apply {
                cornerRadius = 60f
                setColor(Color.argb(190, 0, 0, 0))
            }
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = 48 }
        }
        root.addView(hint)

        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = desktopUserAgent
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_DEFAULT
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
                view.evaluateJavascript(styleJs, null)
                view.evaluateJavascript(cookieJs, null)
                updateMode(url)
                handler.postDelayed({ hideSplash() }, 1200)
            }

            // Wird auch bei Seitenwechseln innerhalb von TikTok aufgerufen
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                view.evaluateJavascript(styleJs, null)
                updateMode(url)
            }
        }

        webView.loadUrl(startUrl)
        webView.requestFocus()
        hideSystemBars()
    }

    private fun updateMode(url: String?) {
        if (url == null || !url.contains("tiktok.com")) return
        val isLogin = url.contains("/login")
        // Nach dem Login landet man auf der Startseite -> direkt zum Feed
        val isRoot = url.trimEnd('/') == "https://www.tiktok.com"
        if (!isLogin && isRoot) {
            webView.loadUrl(feedUrl)
            return
        }
        val newFeedMode = !isLogin
        if (newFeedMode && !feedMode && !hintShownOnce) {
            hintShownOnce = true
            showHint()
        }
        feedMode = newFeedMode
    }

    private fun hideSplash() {
        if (splashGone) return
        splashGone = true
        splash.animate().alpha(0f).setDuration(400)
            .withEndAction { splash.visibility = View.GONE }
            .start()
    }

    private fun showHint() {
        handler.removeCallbacks(hideHint)
        hint.animate().cancel()
        hint.alpha = 1f
        handler.postDelayed(hideHint, 6000)
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

    private fun seek(seconds: Int) {
        val js = """
            (function() {
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                    var r = vids[i].getBoundingClientRect();
                    if (r.top < window.innerHeight && r.bottom > 0 && r.height > 200) {
                        vids[i].currentTime = Math.max(0, vids[i].currentTime + ($seconds));
                        break;
                    }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        // Menü-Taste: zwischen "Für dich" und "Folge ich" wechseln
        if (code == KeyEvent.KEYCODE_MENU) {
            if (down && feedMode) {
                val u = webView.url ?: ""
                webView.loadUrl(if (u.contains("/following")) feedUrl else followingUrl)
                showHint()
            }
            return true
        }

        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY ||
            code == KeyEvent.KEYCODE_MEDIA_PAUSE
        ) {
            if (down) togglePlayPause()
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_REWIND) {
            if (down) seek(-10)
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            if (down) seek(10)
            return true
        }

        if (feedMode) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (down) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) sendKey(KeyEvent.KEYCODE_L)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (down) sendKey(KeyEvent.KEYCODE_M)
                    return true
                }
                // Hoch/Runter gehen an die Seite (nächstes/vorheriges Video)
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
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}
