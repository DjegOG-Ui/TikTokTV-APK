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

    // Eigene Oberfläche (Vollbild-Video, Herz/Kommentare/Teilen am Rand).
    // Bei Problemen auf false setzen: dann wird nur Kopfzeile/Seitenleiste ausgeblendet.
    private val customUi = true

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

    // Schlanke Oberfläche: wird einmal eingesetzt, danach nur bei neuem Video aktualisiert
    private val feedJs = """
        (function() {
            if (window.__tv) { if (window.__tvRoute) { window.__tvRoute(); } return; }
            window.__tv = true;
            var CUSTOM = __CUSTOM__;

            var css = [
                'html.__tvon, html.__tvon body { background:#000 !important; overflow:hidden !important; }',
                'html.__tvon #app-header, html.__tvon [class*="DivHeaderContainer"], ' +
                    'html.__tvon [class*="DivSideNavContainer"], html.__tvon [class*="DivSideNavPlaceholder"], ' +
                    'html.__tvon [class*="DivBannerContainer"], html.__tvon [class*="DivAppBanner"], ' +
                    'html.__tvon [class*="DivGetAppContainer"] { display:none !important; }',
                'video.__tvactive { position:fixed !important; left:0 !important; top:0 !important; ' +
                    'width:100vw !important; height:100vh !important; max-width:none !important; ' +
                    'max-height:none !important; object-fit:contain !important; transform:none !important; ' +
                    'z-index:2147483000 !important; background:#000 !important; }',
                '#__tvui { position:fixed; left:0; top:0; width:100vw; height:100vh; z-index:2147483500; ' +
                    'pointer-events:none; font-family:sans-serif; color:#fff; display:none; }',
                '#__tvui .col { position:absolute; bottom:12vh; display:flex; flex-direction:column; align-items:center; }',
                '#__tvui .it { margin-top:3vh; text-align:center; }',
                '#__tvui .it svg { width:5.5vh; height:5.5vh; fill:#fff; }',
                '#__tvui .it.liked svg { fill:#fe2c55; }',
                '#__tvui .ct { font-size:2.2vh; font-weight:bold; margin-top:0.4vh; text-shadow:0 0 6px #000; }',
                '#__tvui .meta { position:absolute; bottom:6vh; text-shadow:0 0 6px #000; }',
                '#__tvui .au { font-size:3vh; font-weight:bold; }',
                '#__tvui .ds { font-size:2.3vh; margin-top:1vh; }'
            ].join(' ');
            var st = document.createElement('style');
            st.textContent = css;
            document.documentElement.appendChild(st);

            function item(cls, path) {
                return '<div class="it ' + cls + '"><svg viewBox="0 0 24 24"><path d="' + path +
                    '"/></svg><div class="ct"></div></div>';
            }
            var heart = 'M12 21s-7-4.4-9.3-8.6C1 9 2.8 5.5 6 5.5c1.8 0 3.2 1 4 2.3.8-1.3 2.2-2.3 4-2.3 3.2 0 5 3.5 3.3 6.9C19 16.6 12 21 12 21z';
            var bubble = 'M4 4h16v12H8l-4 4z';
            var share = 'M14 4l7 7-7 7v-4c-6 0-9 2-11 5 1-6 4-10 11-11z';

            var ui = document.createElement('div');
            ui.id = '__tvui';
            ui.innerHTML = '<div class="col">' + item('like', heart) + item('com', bubble) + item('shr', share) +
                '</div><div class="meta"><div class="au"></div><div class="ds"></div></div>';
            document.documentElement.appendChild(ui);

            var col = ui.querySelector('.col');
            var meta = ui.querySelector('.meta');
            var elLike = ui.querySelector('.it.like');
            var ctLike = ui.querySelector('.it.like .ct');
            var ctCom = ui.querySelector('.it.com .ct');
            var ctShr = ui.querySelector('.it.shr .ct');
            var elAu = ui.querySelector('.au');
            var elDs = ui.querySelector('.ds');

            var active = null;
            var mutedFlag = null;

            function onFeed() {
                return /\/(foryou|following)/.test(location.pathname);
            }

            function txt(root, sels) {
                for (var i = 0; i < sels.length; i++) {
                    var e = root.querySelector(sels[i]);
                    if (e && e.textContent) { return e.textContent.trim(); }
                }
                return '';
            }

            function container(v) {
                var el = v;
                for (var i = 0; i < 12 && el; i++) {
                    if (el.querySelector && el.querySelector('[data-e2e="like-count"]')) { return el; }
                    el = el.parentElement;
                }
                return null;
            }

            function refresh() {
                if (!CUSTOM || !active) { return; }
                var ratio = active.videoWidth > 0 ? active.videoWidth / active.videoHeight : 0.5625;
                var dw = Math.min(window.innerWidth, window.innerHeight * ratio);
                var left = (window.innerWidth - dw) / 2;
                col.style.right = (left + dw * 0.03) + 'px';
                meta.style.left = (left + dw * 0.04) + 'px';
                meta.style.width = (dw * 0.72) + 'px';

                var c = container(active);
                if (c) {
                    ctLike.textContent = txt(c, ['[data-e2e="like-count"]']);
                    ctCom.textContent = txt(c, ['[data-e2e="comment-count"]']);
                    ctShr.textContent = txt(c, ['[data-e2e="share-count"]']);
                    elAu.textContent = txt(c, ['[data-e2e="video-author-uniqueid"]', '[data-e2e="browse-username"]']);
                    var d = txt(c, ['[data-e2e="video-desc"]', '[data-e2e="browse-video-desc"]']);
                    elDs.textContent = d.length > 140 ? d.substring(0, 140) + '...' : d;
                    var li = c.querySelector('[data-e2e="like-icon"]');
                    var btn = li ? (li.closest('button') || li) : null;
                    var liked = btn && btn.getAttribute('aria-pressed') === 'true';
                    elLike.classList.toggle('liked', !!liked);
                }
            }

            function setActive(v) {
                if (v === active) { refresh(); return; }
                if (active) { active.classList.remove('__tvactive'); }
                active = v;
                if (CUSTOM) { v.classList.add('__tvactive'); }
                if (mutedFlag !== null) { v.muted = mutedFlag; }
                refresh();
                setTimeout(refresh, 800);
            }

            function scan() {
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                    if (!vids[i].paused && vids[i].readyState >= 2) { setActive(vids[i]); return; }
                }
            }

            document.addEventListener('playing', function(e) {
                var v = e.target;
                if (v && v.tagName === 'VIDEO' && onFeed()) { setActive(v); }
            }, true);

            window.__tvRoute = function() {
                var on = onFeed();
                document.documentElement.classList.toggle('__tvon', on);
                ui.style.display = (on && CUSTOM) ? 'block' : 'none';
                if (!on && active) { active.classList.remove('__tvactive'); active = null; }
                if (on) { scan(); }
            };

            window.__tvLike = function() {
                if (!active) { return; }
                var c = container(active);
                if (!c) { return; }
                var i = c.querySelector('[data-e2e="like-icon"]');
                if (i) { (i.closest('button') || i).click(); }
                setTimeout(refresh, 500);
            };
            window.__tvMute = function() {
                if (!active) { return; }
                mutedFlag = !active.muted;
                active.muted = mutedFlag;
            };
            window.__tvPause = function() {
                if (!active) { scan(); }
                if (!active) { return; }
                if (active.paused) { active.play(); } else { active.pause(); }
            };
            window.__tvSeek = function(sec) {
                if (!active) { return; }
                active.currentTime = Math.max(0, active.currentTime + sec);
            };

            window.__tvRoute();
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
                if (tryClick() || tries > 20) {
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
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
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
                view.evaluateJavascript(feedJs.replace("__CUSTOM__", customUi.toString()), null)
                view.evaluateJavascript(cookieJs, null)
                updateMode(url)
                handler.postDelayed({ hideSplash() }, 1200)
            }

            // Wird auch bei Seitenwechseln innerhalb von TikTok aufgerufen
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                view.evaluateJavascript("window.__tvRoute && window.__tvRoute();", null)
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

    private fun js(code: String) {
        webView.evaluateJavascript(code, null)
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
            if (down) js("window.__tvPause && window.__tvPause();")
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_REWIND) {
            if (down) js("window.__tvSeek && window.__tvSeek(-10);")
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            if (down) js("window.__tvSeek && window.__tvSeek(10);")
            return true
        }

        if (feedMode) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (down) js("window.__tvPause && window.__tvPause();")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) js("window.__tvLike && window.__tvLike();")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (down) js("window.__tvMute && window.__tvMute();")
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
