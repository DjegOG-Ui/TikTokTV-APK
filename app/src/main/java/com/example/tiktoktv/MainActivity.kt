package com.example.tiktoktv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {

    // Verhindert, dass die unsichtbare Webseite selbst Videodaten lädt.
    private val blockWebVideo = true

    // Zeitfenster für den Doppelklick auf die Mitte-Taste
    private val doubleClickMs = 300L

    data class Item(
        val id: String,
        val author: String,
        val desc: String,
        val avatar: String,
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
    private lateinit var sidebar: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var rightCol: LinearLayout
    private lateinit var avatar: ImageView
    private lateinit var heartIcon: IconView
    private lateinit var pauseIcon: IconView
    private lateinit var heartPop: IconView
    private lateinit var meta: LinearLayout
    private lateinit var tvLikeCount: TextView
    private lateinit var tvComCount: TextView
    private lateinit var tvShrCount: TextView
    private lateinit var tvAuthor: TextView
    private lateinit var tvDesc: TextView
    private lateinit var progressFill: View
    private lateinit var player: ExoPlayer
    private lateinit var fontRegular: Typeface
    private lateinit var fontBold: Typeface

    private val topTexts = ArrayList<TextView>()
    private val topLines = ArrayList<View>()
    private val topCells = ArrayList<LinearLayout>()
    private val sideRows = ArrayList<LinearLayout>()
    private val sideLabels = ArrayList<TextView>()

    private val handler = Handler(Looper.getMainLooper())
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(400).start() }
    private val menuTimeout = Runnable { closeMenu() }

    private val items = ArrayList<Item>()
    private val seen = HashSet<String>()
    private val avatarCache = ConcurrentHashMap<String, Bitmap>()
    private var currentAvatarUrl = ""
    private var centerPending: Runnable? = null

    @Volatile
    private var nativeActive = false
    private var onFollowing = false
    private var onSearch = false
    private var menuOpen = false
    private var menuZone = 0
    private var topIdx = 0
    private var sideIdx = 0
    private var roomForSidebar = true
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

    private val bgColor = Color.rgb(18, 18, 18)

    // Liest die Videolisten mit, die TikTok selbst lädt
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
                        var av = '';
                        if (au) {
                            if (typeof au === 'string') { name = au; }
                            else {
                                name = au.uniqueId || au.nickname || '';
                                av = au.avatarMedium || au.avatarThumb || au.avatarLarger || '';
                            }
                        }
                        arr.push({
                            i: String(it.id),
                            a: name,
                            v: av,
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

            function wanted(u) {
                return u.indexOf('item_list') > -1 || u.indexOf('/search/') > -1;
            }

            var of = window.fetch;
            if (of) {
                window.fetch = function() {
                    var a = arguments;
                    var p = of.apply(this, a);
                    try {
                        var u = (typeof a[0] === 'string') ? a[0] : ((a[0] && a[0].url) || '');
                        if (wanted(u)) {
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
                        if (wanted(x.__u || '')) { send(JSON.parse(x.responseText)); }
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

            // Gefällt mir: erst über die Video-ID, sonst das Herz, das gerade mittig im Bild steht
            window.__tvLike = function(id) {
                var SEL = '[data-e2e="like-icon"], [data-e2e="browse-like-icon"], button[aria-label*="like" i], button[aria-label*="gefällt" i]';
                function clickIcon(ic) { (ic.closest('button') || ic).click(); }
                var icons = document.querySelectorAll(SEL);
                if (!icons.length) { return 'noicons:' + document.querySelectorAll('[data-e2e]').length; }

                var as = document.querySelectorAll('a[href*="/video/' + id + '"]');
                for (var i = 0; i < as.length; i++) {
                    var el = as[i];
                    for (var n = 0; n < 16 && el; n++) {
                        if (el.querySelectorAll) {
                            var found = el.querySelectorAll(SEL);
                            if (found.length === 1) { clickIcon(found[0]); return 'ok-id'; }
                            if (found.length > 1) { break; }
                        }
                        el = el.parentElement;
                    }
                }

                var best = null;
                var bd = 1e9;
                var mid = window.innerHeight / 2;
                for (var j = 0; j < icons.length; j++) {
                    var r = icons[j].getBoundingClientRect();
                    var d = Math.abs((r.top + r.bottom) / 2 - mid);
                    if (d < bd) { bd = d; best = icons[j]; }
                }
                if (best) { clickIcon(best); return 'ok-pos'; }
                return 'none';
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

    private fun loadFont(name: String): Typeface? = try {
        Typeface.createFromAsset(assets, name)
    } catch (e: Exception) {
        null
    }

    private fun label(size: Float, bold: Boolean = false): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = size
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
        typeface = if (bold) fontBold else fontRegular
    }

    private fun pill(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(80, 255, 255, 255))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TikTok Sans (wird beim Bauen heruntergeladen), sonst Systemschrift
        val reg = loadFont("tiktoksans_regular.ttf")
        fontRegular = reg ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontBold = loadFont("tiktoksans_bold.ttf")
            ?: (if (reg != null) Typeface.create(reg, Typeface.BOLD) else Typeface.create("sans-serif", Typeface.BOLD))

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
            setBackgroundColor(bgColor)
            visibility = View.GONE
        }
        surface = SurfaceView(this)
        playerLayer.addView(surface, FrameLayout.LayoutParams(screenW, screenH, Gravity.CENTER))

        // Obere Leiste: Für dich | Folge ich | Suche
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val names = listOf("Für dich", "Folge ich", "Suche")
        for (i in names.indices) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(22), dp(8), dp(22), dp(8))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (i == 2) {
                row.addView(
                    IconView(this, IconView.SEARCH),
                    LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
                )
            }
            val t = label(19f, true)
            t.text = names[i]
            row.addView(t)
            val line = View(this)
            line.setBackgroundColor(Color.WHITE)
            cell.addView(row)
            cell.addView(line, LinearLayout.LayoutParams(dp(28), dp(3)).apply { topMargin = dp(4) })
            topBar.addView(cell, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })
            topTexts.add(t)
            topLines.add(line)
            topCells.add(cell)
        }
        playerLayer.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(14) })

        // Linke Leiste: Logo und Einstellungen
        sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), 0, dp(24))
        }
        sidebar.addView(
            IconView(this, IconView.LOGO),
            LinearLayout.LayoutParams(dp(42), dp(42)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        sidebar.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val sideDefs = listOf(
            Pair(IconView.SPEAKER, "Ton an/aus"),
            Pair(IconView.REFRESH, "Neu laden"),
            Pair(IconView.PERSON, "Abmelden"),
            Pair(IconView.POWER, "Beenden")
        )
        for (d in sideDefs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(12), dp(10), dp(12))
            }
            row.addView(IconView(this, d.first), LinearLayout.LayoutParams(dp(30), dp(30)))
            val tv = label(18f, true)
            tv.text = d.second
            tv.visibility = View.GONE
            row.addView(tv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(16) })
            sidebar.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
                marginStart = dp(8)
                marginEnd = dp(8)
            })
            sideRows.add(row)
            sideLabels.add(tv)
        }
        sidebar.addView(View(this), LinearLayout.LayoutParams(1, 0, 1.4f))
        playerLayer.addView(sidebar, FrameLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))

        // Unten links: Name und Beschreibung
        meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvAuthor = label(20f, true)
        tvDesc = label(16f).apply { maxLines = 3 }
        meta.addView(tvAuthor)
        meta.addView(tvDesc, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        playerLayer.addView(meta, FrameLayout.LayoutParams(
            dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.BOTTOM
        ).apply { marginStart = dp(100); bottomMargin = dp(36) })

        // Rechts neben dem Video: Profilbild, Herz, Kommentare, Teilen
        rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val avatarBox = FrameLayout(this)
        val ring = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(60, 60, 60))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        ring.addView(avatar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        avatarBox.addView(ring, FrameLayout.LayoutParams(dp(60), dp(60)))
        avatarBox.addView(
            IconView(this, IconView.PLUS_BADGE),
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )
        rightCol.addView(avatarBox, LinearLayout.LayoutParams(dp(60), dp(72)).apply { bottomMargin = dp(16) })

        heartIcon = IconView(this, IconView.HEART)
        rightCol.addView(heartIcon, LinearLayout.LayoutParams(dp(44), dp(44)))
        tvLikeCount = label(15f, true).apply { gravity = Gravity.CENTER }
        rightCol.addView(tvLikeCount, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) })

        rightCol.addView(IconView(this, IconView.COMMENT), LinearLayout.LayoutParams(dp(44), dp(44)))
        tvComCount = label(15f, true).apply { gravity = Gravity.CENTER }
        rightCol.addView(tvComCount, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) })

        rightCol.addView(IconView(this, IconView.SHARE), LinearLayout.LayoutParams(dp(44), dp(44)))
        tvShrCount = label(15f, true).apply { gravity = Gravity.CENTER }
        rightCol.addView(tvShrCount, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        playerLayer.addView(rightCol, FrameLayout.LayoutParams(
            dp(90), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.BOTTOM
        ).apply { marginStart = dp(700); bottomMargin = dp(30) })

        // Fortschrittsbalken ganz unten
        val progressBg = FrameLayout(this).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) }
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

        // Pause-Symbol und Herz-Animation in der Mitte
        pauseIcon = IconView(this, IconView.PLAY).apply {
            alpha = 0.85f
            visibility = View.GONE
        }
        playerLayer.addView(pauseIcon, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))

        heartPop = IconView(this, IconView.HEART).apply {
            tint = Color.rgb(254, 44, 85)
            alpha = 0f
        }
        playerLayer.addView(heartPop, FrameLayout.LayoutParams(dp(130), dp(130), Gravity.CENTER))

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
            text = "↑ ↓ Video     OK Pause     OK 2× Gefällt mir     ← Ton     → Gefällt mir     Menü  Navigation"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = fontRegular
            gravity = Gravity.CENTER
            setPadding(48, 24, 48, 24)
            background = GradientDrawable().apply {
                cornerRadius = 60f
                setColor(Color.argb(200, 0, 0, 0))
            }
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(60) }
        }
        root.addView(hint)

        setContentView(root)

        renderMenu()
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

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                pauseIcon.visibility =
                    if (!playWhenReady && nativeActive && items.isNotEmpty()) View.VISIBLE else View.GONE
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
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
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
                        id, o.optString("a"), o.optString("d"), o.optString("v"),
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
        avatar.setImageDrawable(null)
        currentAvatarUrl = ""
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
        heartIcon.tint = if (it.liked) Color.rgb(254, 44, 85) else Color.WHITE
        progressFill.scaleX = 0f
        loadAvatar(it.avatar)
    }

    private fun loadAvatar(url: String) {
        currentAvatarUrl = url
        if (url.isEmpty()) {
            avatar.setImageDrawable(null)
            return
        }
        val cached = avatarCache[url]
        if (cached != null) {
            avatar.setImageBitmap(cached)
            return
        }
        avatar.setImageDrawable(null)
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", desktopUserAgent)
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val bmp = BitmapFactory.decodeStream(c.inputStream)
                if (bmp != null) {
                    avatarCache[url] = bmp
                    runOnUiThread {
                        if (currentAvatarUrl == url) avatar.setImageBitmap(bmp)
                    }
                }
            } catch (e: Exception) {
                // Profilbild bleibt einfach leer
            }
        }.start()
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
        val roomy = side >= dp(260)
        roomForSidebar = side >= dp(90)
        updateSidebarVisibility()

        // Bedienelemente rechts neben dem Video (bei Querformat im Bild)
        rightCol.layoutParams = FrameLayout.LayoutParams(
            dp(90), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.BOTTOM
        ).apply {
            marginStart = if (roomy) side + vw + dp(16) else maxOf(0, side + vw - dp(100))
            bottomMargin = dp(30)
        }

        // Name und Beschreibung links neben dem Video (bei Querformat im Bild)
        meta.layoutParams = FrameLayout.LayoutParams(
            if (roomy) side - dp(100) - dp(24) else maxOf(dp(200), vw - dp(140)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.BOTTOM
        ).apply {
            marginStart = if (roomy) dp(100) else side + dp(24)
            bottomMargin = dp(36)
        }
    }

    private fun updateSidebarVisibility() {
        sidebar.visibility = if (menuOpen || roomForSidebar) View.VISIBLE else View.GONE
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

    private fun showHeartPop() {
        heartPop.animate().cancel()
        heartPop.scaleX = 0.6f
        heartPop.scaleY = 0.6f
        heartPop.alpha = 1f
        heartPop.animate().scaleX(1.15f).scaleY(1.15f).alpha(0f).setDuration(700).start()
    }

    // ---------- Menü (Menü-Taste) ----------

    private fun activeTab(): Int = if (onSearch) 2 else if (onFollowing) 1 else 0

    private fun renderMenu() {
        val active = activeTab()
        for (i in topTexts.indices) {
            topTexts[i].setTextColor(if (i == active) Color.WHITE else Color.argb(170, 255, 255, 255))
            topLines[i].visibility = if (i == active) View.VISIBLE else View.INVISIBLE
            topCells[i].background = if (menuOpen && menuZone == 0 && i == topIdx) pill() else null
        }
        for (i in sideRows.indices) {
            sideRows[i].background = if (menuOpen && menuZone == 1 && i == sideIdx) pill() else null
            sideLabels[i].visibility = if (menuOpen) View.VISIBLE else View.GONE
            sideRows[i].gravity = if (menuOpen) (Gravity.START or Gravity.CENTER_VERTICAL) else Gravity.CENTER
        }
        topBar.background = if (menuOpen) GradientDrawable().apply {
            cornerRadius = dp(30).toFloat()
            setColor(Color.argb(190, 0, 0, 0))
        } else null
        sidebar.setBackgroundColor(if (menuOpen) Color.argb(215, 0, 0, 0) else Color.TRANSPARENT)
        val lp = sidebar.layoutParams as FrameLayout.LayoutParams
        lp.width = if (menuOpen) dp(250) else dp(72)
        sidebar.layoutParams = lp
        updateSidebarVisibility()
    }

    private fun openMenu() {
        menuOpen = true
        menuZone = 0
        topIdx = activeTab()
        sideIdx = 0
        scheduleMenuClose()
        renderMenu()
    }

    private fun closeMenu() {
        menuOpen = false
        handler.removeCallbacks(menuTimeout)
        renderMenu()
    }

    private fun scheduleMenuClose() {
        handler.removeCallbacks(menuTimeout)
        handler.postDelayed(menuTimeout, 10000)
    }

    private fun handleMenuKey(code: Int) {
        scheduleMenuClose()
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                closeMenu()
                return
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (menuZone == 0) {
                    if (topIdx > 0) topIdx-- else menuZone = 1
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (menuZone == 0) {
                    if (topIdx < topCells.size - 1) topIdx++
                } else {
                    menuZone = 0
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (menuZone == 0) {
                    menuZone = 1
                    sideIdx = 0
                } else if (sideIdx < sideRows.size - 1) {
                    sideIdx++
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (menuZone == 1) {
                    if (sideIdx > 0) sideIdx-- else menuZone = 0
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                activateMenu()
                return
            }
        }
        renderMenu()
    }

    private fun activateMenu() {
        val zone = menuZone
        val top = topIdx
        val side = sideIdx
        closeMenu()
        if (zone == 0) {
            when (top) {
                0 -> openFeed(feedUrl, "Für dich")
                1 -> openFeed(followingUrl, "Folge ich")
                2 -> showSearchDialog()
            }
        } else {
            when (side) {
                0 -> toggleMute()
                1 -> reloadFeed()
                2 -> logout()
                3 -> finish()
            }
        }
    }

    private fun openFeed(url: String, name: String) {
        resetFeed()
        onSearch = false
        webView.loadUrl(url)
        toast(name)
    }

    private fun reloadFeed() {
        resetFeed()
        webView.reload()
        toast("Lädt neu …")
    }

    private fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        resetFeed()
        setNative(false)
        webView.clearCache(true)
        webView.loadUrl(startUrl)
        toast("Abgemeldet")
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "Suchbegriff"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            typeface = fontRegular
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Suche")
            .setView(input)
            .setPositiveButton("Suchen") { _, _ -> doSearch(input.text.toString()) }
            .setNegativeButton("Abbrechen", null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dlg.dismiss()
                doSearch(input.text.toString())
                true
            } else {
                false
            }
        }
        dlg.show()
    }

    private fun doSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        resetFeed()
        onSearch = true
        webView.loadUrl("https://www.tiktok.com/search/video?q=" + URLEncoder.encode(q, "UTF-8"))
        toast("Suche: " + q)
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
        onFollowing = url.contains("tiktok.com/following")
        onSearch = url.contains("tiktok.com/search")
        renderMenu()
        val isFeed = url.contains("tiktok.com/foryou") || onFollowing || onSearch
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
            menuOpen = false
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
        handler.postDelayed(hideHint, 7000)
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

    // onlyLike = true (Doppelklick): liked nur, nimmt ein bestehendes Like nicht zurück
    private fun likeCurrent(onlyLike: Boolean) {
        val idx = player.currentMediaItemIndex
        val item = items.getOrNull(idx) ?: return
        if (onlyLike && item.liked) {
            showHeartPop()
            return
        }
        webView.evaluateJavascript(
            "window.__tvLike ? window.__tvLike('" + item.id + "') : 'nofn';"
        ) { r ->
            if (r != null && r.contains("ok")) {
                item.liked = !item.liked
                item.likes += if (item.liked) 1 else -1
                if (item.liked) showHeartPop()
                if (items.getOrNull(player.currentMediaItemIndex) === item) refreshUi()
            } else {
                toast("Gefällt mir nicht möglich " + (r ?: ""))
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

    private fun onCenterPressed() {
        val pending = centerPending
        if (pending != null) {
            // Zweiter Druck innerhalb des Zeitfensters: Gefällt mir statt Pause
            handler.removeCallbacks(pending)
            centerPending = null
            likeCurrent(true)
        } else {
            val r = Runnable {
                centerPending = null
                player.playWhenReady = !player.playWhenReady
            }
            centerPending = r
            handler.postDelayed(r, doubleClickMs)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        if (nativeActive) {
            if (menuOpen) {
                if (down) handleMenuKey(code)
                return true
            }
            when (code) {
                KeyEvent.KEYCODE_MENU -> {
                    if (down) openMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) nextVideo(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (down) previousVideo(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (down && event.repeatCount == 0) onCenterPressed()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (down) player.playWhenReady = !player.playWhenReady
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) likeCurrent(false); return true }
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

// Selbst gezeichnete Symbole im Stil deiner Vorlage
class IconView(context: Context, private val kind: Int) : View(context) {

    companion object {
        const val HEART = 0
        const val COMMENT = 1
        const val SHARE = 2
        const val PLUS_BADGE = 3
        const val PERSON = 4
        const val SEARCH = 5
        const val SPEAKER = 6
        const val REFRESH = 7
        const val POWER = 8
        const val LOGO = 9
        const val PLAY = 10
    }

    var tint: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private fun fill(c: Int) {
        paint.style = Paint.Style.FILL
        paint.color = c
    }

    private fun stroke(c: Int, sw: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = c
        paint.strokeWidth = sw
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    private fun note(canvas: Canvas, w: Float, h: Float, dx: Float, color: Int) {
        stroke(color, 0.12f * w)
        canvas.drawLine(0.58f * w + dx, 0.14f * h, 0.58f * w + dx, 0.68f * h, paint)
        path.reset()
        path.moveTo(0.58f * w + dx, 0.14f * h)
        path.quadTo(0.66f * w + dx, 0.34f * h, 0.88f * w + dx, 0.34f * h)
        canvas.drawPath(path, paint)
        fill(color)
        canvas.drawCircle(0.4f * w + dx, 0.72f * h, 0.17f * w, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        when (kind) {
            HEART -> {
                fill(tint)
                canvas.drawCircle(0.3f * w, 0.34f * h, 0.22f * w, paint)
                canvas.drawCircle(0.7f * w, 0.34f * h, 0.22f * w, paint)
                path.reset()
                path.moveTo(0.5f * w, 0.9f * h)
                path.lineTo(0.09f * w, 0.46f * h)
                path.lineTo(0.5f * w, 0.3f * h)
                path.lineTo(0.91f * w, 0.46f * h)
                path.close()
                canvas.drawPath(path, paint)
            }
            COMMENT -> {
                fill(tint)
                canvas.drawCircle(0.5f * w, 0.46f * h, 0.4f * w, paint)
                path.reset()
                path.moveTo(0.55f * w, 0.8f * h)
                path.lineTo(0.84f * w, 0.96f * h)
                path.lineTo(0.8f * w, 0.66f * h)
                path.close()
                canvas.drawPath(path, paint)
                fill(Color.rgb(35, 35, 35))
                canvas.drawCircle(0.3f * w, 0.46f * h, 0.055f * w, paint)
                canvas.drawCircle(0.5f * w, 0.46f * h, 0.055f * w, paint)
                canvas.drawCircle(0.7f * w, 0.46f * h, 0.055f * w, paint)
            }
            SHARE -> {
                fill(tint)
                path.reset()
                path.moveTo(0.6f * w, 0.1f * h)
                path.lineTo(0.96f * w, 0.46f * h)
                path.lineTo(0.6f * w, 0.82f * h)
                path.lineTo(0.6f * w, 0.62f * h)
                path.cubicTo(0.3f * w, 0.6f * h, 0.15f * w, 0.74f * h, 0.07f * w, 0.92f * h)
                path.cubicTo(0.07f * w, 0.52f * h, 0.28f * w, 0.32f * h, 0.6f * w, 0.3f * h)
                path.close()
                canvas.drawPath(path, paint)
            }
            PLUS_BADGE -> {
                fill(Color.rgb(254, 44, 85))
                canvas.drawCircle(0.5f * w, 0.5f * h, 0.5f * w, paint)
                stroke(Color.WHITE, 0.11f * w)
                canvas.drawLine(0.28f * w, 0.5f * h, 0.72f * w, 0.5f * h, paint)
                canvas.drawLine(0.5f * w, 0.28f * h, 0.5f * w, 0.72f * h, paint)
            }
            PERSON -> {
                stroke(tint, 0.09f * w)
                canvas.drawCircle(0.5f * w, 0.3f * h, 0.17f * w, paint)
                rect.set(0.18f * w, 0.56f * h, 0.82f * w, 1.2f * h)
                canvas.drawArc(rect, 180f, 180f, false, paint)
            }
            SEARCH -> {
                stroke(tint, 0.1f * w)
                canvas.drawCircle(0.42f * w, 0.42f * h, 0.25f * w, paint)
                canvas.drawLine(0.6f * w, 0.6f * h, 0.85f * w, 0.85f * h, paint)
            }
            SPEAKER -> {
                fill(tint)
                canvas.drawRect(0.1f * w, 0.4f * h, 0.3f * w, 0.6f * h, paint)
                path.reset()
                path.moveTo(0.28f * w, 0.4f * h)
                path.lineTo(0.52f * w, 0.2f * h)
                path.lineTo(0.52f * w, 0.8f * h)
                path.lineTo(0.28f * w, 0.6f * h)
                path.close()
                canvas.drawPath(path, paint)
                stroke(tint, 0.08f * w)
                rect.set(0.35f * w, 0.3f * h, 0.75f * w, 0.7f * h)
                canvas.drawArc(rect, -50f, 100f, false, paint)
                rect.set(0.3f * w, 0.16f * h, 0.92f * w, 0.84f * h)
                canvas.drawArc(rect, -50f, 100f, false, paint)
            }
            REFRESH -> {
                stroke(tint, 0.1f * w)
                rect.set(0.2f * w, 0.2f * h, 0.8f * w, 0.8f * h)
                canvas.drawArc(rect, -60f, 280f, false, paint)
                // Pfeilspitze am Ende des Bogens
                val px = 0.27f * w
                val py = 0.307f * h
                val dx = 0.643f
                val dy = -0.766f
                val tipX = px + dx * 0.16f * w
                val tipY = py + dy * 0.16f * h
                val nx = -dy
                val ny = dx
                fill(tint)
                path.reset()
                path.moveTo(tipX, tipY)
                path.lineTo(px + nx * 0.13f * w, py + ny * 0.13f * h)
                path.lineTo(px - nx * 0.13f * w, py - ny * 0.13f * h)
                path.close()
                canvas.drawPath(path, paint)
            }
            POWER -> {
                stroke(tint, 0.1f * w)
                rect.set(0.2f * w, 0.22f * h, 0.8f * w, 0.82f * h)
                canvas.drawArc(rect, -60f, 300f, false, paint)
                canvas.drawLine(0.5f * w, 0.1f * h, 0.5f * w, 0.5f * h, paint)
            }
            LOGO -> {
                note(canvas, w, h, -0.03f * w, Color.rgb(37, 244, 238))
                note(canvas, w, h, 0.03f * w, Color.rgb(254, 44, 85))
                note(canvas, w, h, 0f, Color.WHITE)
            }
            PLAY -> {
                fill(tint)
                path.reset()
                path.moveTo(0.25f * w, 0.15f * h)
                path.lineTo(0.9f * w, 0.5f * h)
                path.lineTo(0.25f * w, 0.85f * h)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }
}
