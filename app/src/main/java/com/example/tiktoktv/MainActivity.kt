package com.example.tiktoktv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.Locale

class MainActivity : Activity() {

    // Verhindert, dass die unsichtbare Webseite selbst Videodaten lädt (spart Leistung).
    // Falls gar keine Videos erscheinen: auf false setzen.
    private val blockWebVideo = true

    data class Item(
        val id: String,
        val author: String,
        val desc: String,
        var likes: Long,
        val comments: Long,
        val shares: Long,
        var liked: Boolean
    )

    private lateinit var webView: WebView
    private lateinit var splash: ImageView
    private lateinit var hint: TextView
    private lateinit var playerLayer: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var loadingView: TextView
    private lateinit var rightCol: LinearLayout
    private lateinit var meta: LinearLayout
    private lateinit var tvLikeIcon: TextView
    private lateinit var tvLikeCount: TextView
    private lateinit var tvComCount: TextView
    private lateinit var tvShrCount: TextView
    private lateinit var tvAuthor: TextView
    private lateinit var tvDesc: TextView
    private lateinit var progressFill: View
    private lateinit var player: ExoPlayer

    private val handler = Handler(Looper.getMainLooper())
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(400).start() }

    private val items = ArrayList<Item>()
    private val seen = HashSet<String>()

    @Volatile
    private var nativeActive = false
    private var splashGone = false
    private var hintShownOnce = false
    private var reloadedOnce = false
    private var screenW = 1920
    private var screenH = 1080

    private val startUrl = "https://www.tiktok.com/login/qrcode"
    private val feedUrl = "https://www.tiktok.com/foryou"
    private val followingUrl = "https://www.tiktok.com/following"

    private val desktopUserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Wird vor allen Seitenskripten eingesetzt: liest die Videolisten mit,
    // die TikTok selbst lädt, und verhindert, dass die Seite Videos abspielt.
    private val hookJs = """
        (function() {
            if (window.__tvHook) { return; }
            window.__tvHook = true;
            try { HTMLMediaElement.prototype.play = function() { return Promise.resolve(); }; } catch (e) {}

            function collect(o, out, d) {
                if (!o || d > 9) { return; }
                if (Array.isArray(o)) {
                    for (var i = 0; i < o.length; i++) { collect(o[i], out, d + 1); }
                    return;
                }
                if (typeof o !== 'object') { return; }
                if (o.id && o.video && (o.video.playAddr || o.video.downloadAddr || o.video.bitrateInfo)) {
                    out.push(o);
                    return;
                }
                for (var k in o) { collect(o[k], out, d + 1); }
            }

            function urlOf(v) {
                if (v.playAddr) { return v.playAddr; }
                if (v.downloadAddr) { return v.downloadAddr; }
                try { return v.bitrateInfo[0].PlayAddr.UrlList[0]; } catch (e) {}
                return '';
            }

            function send(json) {
                try {
                    var out = [];
                    collect(json, out, 0);
                    var arr = [];
                    for (var i = 0; i < out.length; i++) {
                        var it = out[i];
                        var st = it.stats || {};
                        var au = it.author;
                        var name = '';
                        if (au) { name = (typeof au === 'string') ? au : (au.uniqueId || au.nickname || ''); }
                        arr.push({
                            i: String(it.id),
                            a: name,
                            d: it.desc || '',
                            l: Number(st.diggCount || 0),
                            c: Number(st.commentCount || 0),
                            s: Number(st.shareCount || 0),
                            g: !!it.digged,
                            u: urlOf(it.video)
                        });
                    }
                    if (arr.length > 0 && window.AndroidBridge) {
                        window.AndroidBridge.onItems(JSON.stringify(arr));
                    }
                } catch (e) {}
            }

            var of = window.fetch;
            if (of) {
                window.fetch = function() {
                    var a = arguments;
                    var p = of.apply(this, a);
                    try {
                        var u = (typeof a[0] === 'string') ? a[0] : ((a[0] && a[0].url) || '');
                        if (u.indexOf('item_list') > -1) {
                            p.then(function(r) {
                                r.clone().json().then(send).catch(function() {});
                            }).catch(function() {});
                        }
                    } catch (e) {}
                    return p;
                };
            }

            var oo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u) {
                this.__u = u;
                return oo.apply(this, arguments);
            };
            var os = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function() {
                var x = this;
                x.addEventListener('load', function() {
                    try {
                        if ((x.__u || '').indexOf('item_list') > -1) { send(JSON.parse(x.responseText)); }
                    } catch (e) {}
                });
                return os.apply(this, arguments);
            };

            function ssr() {
                try {
                    var s = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
                    if (s && s.textContent) { send(JSON.parse(s.textContent)); }
                } catch (e) {}
            }
            document.addEventListener('DOMContentLoaded', ssr);
            setTimeout(ssr, 2000);

            // Gefällt mir: klickt das Herz genau des Videos mit dieser ID
            window.__tvLike = function(id) {
                var as = document.querySelectorAll('a[href*="/video/' + id + '"]');
                for (var i = 0; i < as.length; i++) {
                    var el = as[i];
                    for (var n = 0; n < 16 && el; n++) {
                        var ic = el.querySelector ? el.querySelector('[data-e2e="like-icon"]') : null;
                        if (ic) { (ic.closest('button') || ic).click(); return true; }
                        el = el.parentElement;
                    }
                }
                return false;
            };
        })();
    """.trimIndent()

    // Login-Seite: feste Breite und Cookie-Banner automatisch ablehnen
    private val loginJs = """
        (function() {
            var m = document.querySelector('meta[name=viewport]');
            if (!m) {
                m = document.createElement('meta');
                m.name = 'viewport';
                (document.head || document.documentElement).appendChild(m);
            }
            m.content = 'width=1280';

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

    inner class Bridge {
        @JavascriptInterface
        fun onItems(json: String) {
            runOnUiThread { addItems(json) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(size: Float, bold: Boolean = false): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = size
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // --- WebView (Login und unsichtbare Datenquelle) ---
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

        // --- Nativer Player mit eigener Oberfläche ---
        playerLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        surface = SurfaceView(this)
        playerLayer.addView(surface, FrameLayout.LayoutParams(screenW, screenH, Gravity.CENTER))

        // Rechte Spalte: Herz, Kommentare, Teilen
        rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun addAction(symbol: String): Pair<TextView, TextView> {
            val icon = label(34f).apply { text = symbol; gravity = Gravity.CENTER }
            val count = label(18f, true).apply { gravity = Gravity.CENTER }
            rightCol.addView(icon)
            rightCol.addView(count, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) })
            return Pair(icon, count)
        }
        val like = addAction("♥")
        tvLikeIcon = like.first
        tvLikeCount = like.second
        tvComCount = addAction("💬").second
        tvShrCount = addAction("➦").second
        playerLayer.addView(rightCol, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.BOTTOM
        ).apply { marginEnd = dp(24); bottomMargin = dp(40) })

        // Unten links: Name und Beschreibung
        meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvAuthor = label(22f, true)
        tvDesc = label(17f).apply { maxLines = 2 }
        meta.addView(tvAuthor)
        meta.addView(tvDesc, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        playerLayer.addView(meta, FrameLayout.LayoutParams(
            dp(520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.BOTTOM
        ).apply { marginStart = dp(24); bottomMargin = dp(24) })

        // Fortschrittsbalken ganz unten
        val progressBg = FrameLayout(this).apply { setBackgroundColor(Color.argb(70, 255, 255, 255)) }
        progressFill = View(this).apply {
            setBackgroundColor(Color.WHITE)
            pivotX = 0f
            scaleX = 0f
        }
        progressBg.addView(progressFill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        playerLayer.addView(progressBg, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM
        ))

        loadingView = label(22f).apply {
            text = "Lädt …"
            gravity = Gravity.CENTER
        }
        playerLayer.addView(loadingView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        root.addView(playerLayer)

        // --- Ladebildschirm und Tastenhilfe ---
        splash = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.banner)
            setPadding(screenW / 5, screenH / 5, screenW / 5, screenH / 5)
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
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = 48 }
        }
        root.addView(hint)

        setContentView(root)

        setupPlayer()
        setupWebView()

        webView.loadUrl(startUrl)
        webView.requestFocus()
        hideSystemBars()
        handler.post(progressTicker)
    }

    private fun setupPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(desktopUserAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.tiktok.com/"))
        val resolving = ResolvingDataSource.Factory(
            http,
            ResolvingDataSource.Resolver { dataSpec ->
                val cookie = CookieManager.getInstance().getCookie(dataSpec.uri.toString())
                if (cookie.isNullOrEmpty()) dataSpec
                else dataSpec.withAdditionalHeaders(mapOf("Cookie" to cookie))
            }
        )
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .build()
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setVideoSurfaceView(surface)
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                fitVideo(videoSize)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshUi()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                loadingView.visibility =
                    if (playbackState == Player.STATE_READY) View.GONE else View.VISIBLE
                if (playbackState == Player.STATE_READY) hideSplash()
            }

            override fun onPlayerError(error: PlaybackException) {
                toast("Video nicht abspielbar – nächstes")
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
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

        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, hookJs, setOf("https://www.tiktok.com"))
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // Reserve, falls der Browser das frühe Einsetzen nicht unterstützt
                view.evaluateJavascript(hookJs, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                view.evaluateJavascript(hookJs, null)
                view.evaluateJavascript(loginJs, null)
                updateMode(url)
                handler.postDelayed({ hideSplashIfWeb() }, 1500)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                updateMode(url)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (blockWebVideo && nativeActive) {
                    val u = request.url.toString()
                    if (u.contains("/video/tos/")) {
                        return WebResourceResponse(
                            "text/plain", "utf-8", 403, "Blocked",
                            emptyMap<String, String>(), ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    // ---------- Videoliste ----------

    private fun addItems(json: String) {
        try {
            val arr = JSONArray(json)
            var firstAdded = items.isEmpty()
            for (n in 0 until arr.length()) {
                val o = arr.getJSONObject(n)
                val id = o.optString("i")
                val url = o.optString("u")
                if (id.isEmpty() || !url.startsWith("https://") || seen.contains(id)) continue
                seen.add(id)
                items.add(
                    Item(
                        id, o.optString("a"), o.optString("d"),
                        o.optLong("l"), o.optLong("c"), o.optLong("s"), o.optBoolean("g")
                    )
                )
                player.addMediaItem(MediaItem.fromUri(url))
            }
            if (firstAdded && items.isNotEmpty()) {
                firstAdded = false
                player.prepare()
                player.playWhenReady = nativeActive
                refreshUi()
            }
        } catch (e: Exception) {
            toast("Datenfehler: " + e.message)
        }
    }

    private fun resetFeed() {
        items.clear()
        seen.clear()
        player.stop()
        player.clearMediaItems()
        loadingView.visibility = View.VISIBLE
        reloadedOnce = false
        tvAuthor.text = ""
        tvDesc.text = ""
        tvLikeCount.text = ""
        tvComCount.text = ""
        tvShrCount.text = ""
    }

    // ---------- Oberfläche ----------

    private fun fmt(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    private fun refreshUi() {
        val it = items.getOrNull(player.currentMediaItemIndex) ?: return
        tvAuthor.text = if (it.author.isEmpty()) "" else "@" + it.author
        tvDesc.text = it.desc
        tvLikeCount.text = fmt(it.likes)
        tvComCount.text = fmt(it.comments)
        tvShrCount.text = fmt(it.shares)
        tvLikeIcon.setTextColor(if (it.liked) Color.rgb(254, 44, 85) else Color.WHITE)
        progressFill.scaleX = 0f
    }

    private fun fitVideo(vs: VideoSize) {
        if (vs.width == 0 || vs.height == 0) return
        val ratio = vs.width * vs.pixelWidthHeightRatio / vs.height
        val vw: Int
        val vh: Int
        if (ratio >= screenW.toFloat() / screenH) {
            vw = screenW
            vh = (screenW / ratio).toInt()
        } else {
            vh = screenH
            vw = (screenH * ratio).toInt()
        }
        surface.layoutParams = FrameLayout.LayoutParams(vw, vh, Gravity.CENTER)

        val side = (screenW - vw) / 2
        (rightCol.layoutParams as FrameLayout.LayoutParams).apply {
            marginEnd = side + dp(24)
        }
        (meta.layoutParams as FrameLayout.LayoutParams).apply {
            marginStart = side + dp(24)
            width = minOf(dp(520), (vw * 0.7f).toInt())
        }
        rightCol.requestLayout()
        meta.requestLayout()
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            if (nativeActive) {
                val d = player.duration
                if (d > 0) progressFill.scaleX = (player.currentPosition.toFloat() / d).coerceIn(0f, 1f)
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- Modus ----------

    private fun updateMode(url: String?) {
        if (url == null || !url.contains("tiktok.com")) return
        val isLogin = url.contains("/login")
        val isRoot = url.trimEnd('/') == "https://www.tiktok.com"
        if (!isLogin && isRoot) {
            webView.loadUrl(feedUrl)
            return
        }
        val isFeed = url.contains("tiktok.com/foryou") || url.contains("tiktok.com/following")
        setNative(isFeed)
    }

    private fun setNative(on: Boolean) {
        if (on == nativeActive) return
        nativeActive = on
        if (on) {
            webView.alpha = 0f
            playerLayer.visibility = View.VISIBLE
            webView.requestFocus()
            player.playWhenReady = true
            if (!hintShownOnce) {
                hintShownOnce = true
                showHint()
            }
            // Falls nach 15 Sekunden nichts angekommen ist: einmal neu laden
            handler.postDelayed({
                if (nativeActive && items.isEmpty()) {
                    if (!reloadedOnce) {
                        reloadedOnce = true
                        toast("Keine Videos empfangen – lade neu")
                        webView.reload()
                    } else {
                        toast("Keine Videos empfangen")
                    }
                }
            }, 15000)
        } else {
            webView.alpha = 1f
            playerLayer.visibility = View.GONE
            player.playWhenReady = false
        }
    }

    private fun hideSplashIfWeb() {
        if (!nativeActive) hideSplash()
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

    // ---------- Fernbedienung ----------

    // Hält die unsichtbare Seite synchron, damit TikTok weitere Videos nachlädt
    private fun sendToPage(keyCode: Int) {
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun nextVideo() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.playWhenReady = true
        } else {
            toast("Lädt weitere Videos …")
        }
        sendToPage(KeyEvent.KEYCODE_DPAD_DOWN)
    }

    private fun previousVideo() {
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
            player.playWhenReady = true
            sendToPage(KeyEvent.KEYCODE_DPAD_UP)
        }
    }

    private fun likeCurrent() {
        val idx = player.currentMediaItemIndex
        val item = items.getOrNull(idx) ?: return
        webView.evaluateJavascript("window.__tvLike && window.__tvLike('" + item.id + "');") { r ->
            if (r == "true") {
                item.liked = !item.liked
                item.likes += if (item.liked) 1 else -1
                if (items.getOrNull(player.currentMediaItemIndex) === item) refreshUi()
            } else {
                toast("Gefällt mir gerade nicht möglich")
            }
        }
    }

    private fun toggleMute() {
        if (player.volume > 0f) {
            player.volume = 0f
            toast("Ton aus")
        } else {
            player.volume = 1f
            toast("Ton an")
        }
    }

    private fun seekBy(ms: Long) {
        player.seekTo(maxOf(0L, player.currentPosition + ms))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        if (nativeActive) {
            when (code) {
                KeyEvent.KEYCODE_MENU -> {
                    if (down) {
                        val u = webView.url ?: ""
                        val toFollowing = !u.contains("/following")
                        resetFeed()
                        webView.loadUrl(if (toFollowing) followingUrl else feedUrl)
                        toast(if (toFollowing) "Folge ich" else "Für dich")
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) nextVideo(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (down) previousVideo(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (down) player.playWhenReady = !player.playWhenReady
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) likeCurrent(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) toggleMute(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) seekBy(-10000); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) seekBy(10000); return true }
                KeyEvent.KEYCODE_BACK -> {
                    if (down) finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!nativeActive && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        player.playWhenReady = false
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (nativeActive) player.playWhenReady = true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        webView.destroy()
        super.onDestroy()
    }
}
