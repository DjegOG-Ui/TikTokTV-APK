package com.example.tiktoktv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.ScrollView
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
        var collects: Long,
        val shares: Long,
        var liked: Boolean,
        var saved: Boolean
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
    private lateinit var saveIcon: IconView
    private lateinit var pauseIcon: IconView
    private lateinit var heartPop: IconView
    private lateinit var meta: LinearLayout
    private lateinit var tvLikeCount: TextView
    private lateinit var tvComCount: TextView
    private lateinit var tvSaveCount: TextView
    private lateinit var tvShrCount: TextView
    private lateinit var tvAuthor: TextView
    private lateinit var tvDesc: TextView
    private lateinit var progressFill: View
    private lateinit var commentPanel: LinearLayout
    private lateinit var commentScroll: ScrollView
    private lateinit var commentList: LinearLayout
    private lateinit var commentTitle: TextView
    private lateinit var player: ExoPlayer
    private lateinit var fontRegular: Typeface
    private lateinit var fontBold: Typeface

    private val topTexts = ArrayList<TextView>()
    private val topLines = ArrayList<View>()
    private val topCells = ArrayList<LinearLayout>()
    private val sideRows = ArrayList<LinearLayout>()
    private val sideLabels = ArrayList<TextView>()
    private val actionCells = ArrayList<LinearLayout>()

    private val handler = Handler(Looper.getMainLooper())
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(400).start() }
    private val menuTimeout = Runnable { closeMenu() }
    private val actionTimeout = Runnable { exitActionMode() }
    private val commentTimeout = Runnable { checkComments() }

    private val items = ArrayList<Item>()
    private val seen = HashSet<String>()
    private val pendingApi = HashMap<String, Boolean>()
    private val avatarCache = ConcurrentHashMap<String, Bitmap>()
    private var currentAvatarUrl = ""
    private var centerPending: Runnable? = null
    private var lastDiag = 0L

    @Volatile
    private var nativeActive = false
    private var onFollowing = false
    private var onSearch = false
    private var menuOpen = false
    private var menuZone = 0
    private var topIdx = 0
    private var sideIdx = 0
    private var actionMode = false
    private var actionIdx = 0
    private var commentsOpen = false
    private var commentGot = false
    private var commentRetried = false
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
    private val savedColor = Color.rgb(250, 206, 21)
    private val likedColor = Color.rgb(254, 44, 85)

    // Liest Videolisten und Kommentare mit und ruft TikToks Funktionen aus der Seite heraus auf
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
                            k: Number(st.collectCount || 0),
                            s: Number(st.shareCount || 0),
                            g: !!it.digged,
                            b: !!it.collected,
                            u: urlOf(it.video)
                        });
                    }
                    if (arr.length > 0 && window.AndroidBridge) {
                        window.AndroidBridge.onItems(JSON.stringify(arr));
                    }
                } catch (e) {}
            }

            function sendComments(json) {
                try {
                    var cs = json.comments || [];
                    var arr = [];
                    for (var i = 0; i < cs.length; i++) {
                        var c = cs[i];
                        var u = c.user || {};
                        arr.push({
                            t: c.text || '',
                            n: u.nickname || u.unique_id || u.uniqueId || '',
                            l: Number(c.digg_count || c.diggCount || 0),
                            r: Number(c.reply_comment_total || c.replyCommentTotal || 0)
                        });
                    }
                    if (window.AndroidBridge) { window.AndroidBridge.onComments(JSON.stringify(arr)); }
                } catch (e) {}
            }

            function wanted(u) {
                return u.indexOf('item_list') > -1 || u.indexOf('/search/') > -1 || u.indexOf('/comment/list') > -1;
            }

            function handle(u, json) {
                if (u.indexOf('/comment/list/reply') > -1) { return; }
                if (u.indexOf('/comment/list') > -1) { sendComments(json); }
                else { send(json); }
            }

            // Gemeinsame Anfrage-Parameter von TikToks eigenen Aufrufen merken
            var SKIP = {'x-bogus': 1, 'x-gnarly': 1, 'mstoken': 1, '_signature': 1, 'count': 1, 'cursor': 1,
                'itemid': 1, 'item_id': 1, 'secuid': 1, 'id': 1, 'coverformat': 1, 'keyword': 1, 'offset': 1,
                'aweme_id': 1, 'type': 1, 'action': 1};
            var DEFAULT_BASE = {
                aid: '1988', app_language: 'de', app_name: 'tiktok_web', browser_language: 'de-DE',
                browser_name: 'Mozilla', browser_online: 'true', browser_platform: 'Linux%20x86_64',
                channel: 'tiktok_web', cookie_enabled: 'true', device_platform: 'web_pc', focus_state: 'true',
                from_page: 'fyp', is_fullscreen: 'false', is_page_visible: 'true', os: 'linux',
                priority_region: '', referer: '', region: 'DE', screen_height: '1080', screen_width: '1920',
                tz_name: 'Europe%2FBerlin', webcast_language: 'de-DE'
            };
            function learn(u) {
                try {
                    if (!u || u.indexOf('/api/') < 0 || u.indexOf('aid=') < 0) { return; }
                    var isList = u.indexOf('item_list') > -1;
                    if (window.__tvBase && !isList) { return; }
                    var q = u.split('?')[1];
                    if (!q) { return; }
                    var parts = q.split('&');
                    var o = {};
                    for (var i = 0; i < parts.length; i++) {
                        var eq = parts[i].indexOf('=');
                        if (eq < 1) { continue; }
                        var k = parts[i].substring(0, eq);
                        if (SKIP[k.toLowerCase()]) { continue; }
                        o[k] = parts[i].substring(eq + 1);
                    }
                    window.__tvBase = o;
                } catch (e) {}
            }

            var of = window.fetch;
            if (of) {
                window.fetch = function() {
                    var a = arguments;
                    var p = of.apply(this, a);
                    try {
                        var u = (typeof a[0] === 'string') ? a[0] : ((a[0] && a[0].url) || '');
                        learn(u);
                        if (wanted(u)) {
                            p.then(function(r) {
                                r.clone().json().then(function(j) { handle(u, j); }).catch(function() {});
                            }).catch(function() {});
                        }
                    } catch (e) {}
                    return p;
                };
            }

            var oo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u) {
                this.__u = u;
                try { learn(String(u)); } catch (e) {}
                return oo.apply(this, arguments);
            };
            var os = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function() {
                var x = this;
                x.addEventListener('load', function() {
                    try {
                        var u = x.__u || '';
                        if (wanted(u)) { handle(u, JSON.parse(x.responseText)); }
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

            function build(path, params) {
                var o = {};
                var b = window.__tvBase || DEFAULT_BASE;
                for (var k in b) { o[k] = b[k]; }
                for (var p in params) { o[p] = encodeURIComponent(params[p]); }
                var parts = [];
                for (var n in o) { parts.push(n + '=' + o[n]); }
                return location.origin + path + '?' + parts.join('&');
            }

            function report(kind, id, ok, info) {
                try {
                    if (window.AndroidBridge && window.AndroidBridge.onApi) {
                        window.AndroidBridge.onApi(kind, id, !!ok, String(info));
                    }
                } catch (e) {}
            }

            // Like / Speichern / Kommentare über TikToks eigene Web-Schnittstelle (mit deiner Anmeldung)
            window.__tvApi = function(kind, id, on) {
                var url;
                var method = 'POST';
                if (kind === 'like') {
                    url = build('/api/commit/item/digg/', {aweme_id: id, type: on ? 1 : 0});
                } else if (kind === 'save') {
                    url = build('/api/item/collect/', {aweme_id: id, action: on ? 1 : 0});
                } else if (kind === 'comment') {
                    url = build('/api/comment/list/', {aweme_id: id, count: 20, cursor: 0});
                    method = 'GET';
                } else {
                    return 'nokind';
                }
                fetch(url, {
                    method: method,
                    credentials: 'include',
                    headers: {'Accept': 'application/json, text/plain, */*'}
                }).then(function(r) {
                    return r.text().then(function(t) { return {s: r.status, t: t}; });
                }).then(function(x) {
                    var j = null;
                    try { j = JSON.parse(x.t); } catch (e) {}
                    if (!j) { report(kind, id, false, 'http' + x.s + ':' + x.t.substring(0, 40)); return; }
                    var sc = (j.status_code !== undefined) ? j.status_code : j.statusCode;
                    if (kind === 'comment') {
                        if (j.comments || sc === 0) {
                            sendComments(j.comments ? j : {comments: []});
                            report(kind, id, true, 'n');
                        } else {
                            report(kind, id, false, 'sc' + sc + ':' + (j.status_msg || 'nocomments'));
                        }
                        return;
                    }
                    report(kind, id, sc === 0, 'sc' + sc + ':' + (j.status_msg || ''));
                }).catch(function(e) {
                    report(kind, id, false, 'err:' + e);
                });
                return 'sent';
            };

            // Reserve: Symbol des aktuellen Videos auf der Seite anklicken
            var SELS = {
                like: '[data-e2e="like-icon"], [data-e2e="browse-like-icon"], button[aria-label*="like" i], button[aria-label*="gefällt" i]',
                comment: '[data-e2e="comment-icon"], [data-e2e="browse-comment-icon"], button[aria-label*="comment" i], button[aria-label*="kommentar" i]',
                save: '[data-e2e="bookmark-icon"], [data-e2e="collect-icon"], [data-e2e="undefined-icon"], [data-e2e="browse-bookmark-icon"], button[aria-label*="favorit" i], button[aria-label*="bookmark" i]'
            };
            window.__tvClick = function(kind, id) {
                var SEL = SELS[kind];
                if (!SEL) { return 'nokind'; }
                function clickIcon(ic) { (ic.closest('button') || ic).click(); }
                var icons = document.querySelectorAll(SEL);
                if (!icons.length) { return 'noicons-' + kind + ':' + document.querySelectorAll('[data-e2e]').length; }
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

            // Fehlersuche: zeigt, was auf der unsichtbaren Seite vorhanden ist
            window.__tvDiag = function() {
                var seenN = {};
                var names = [];
                var els = document.querySelectorAll('[data-e2e]');
                for (var i = 0; i < els.length; i++) {
                    var n = els[i].getAttribute('data-e2e');
                    if (!seenN[n]) { seenN[n] = 1; names.push(n); }
                }
                var bk = window.__tvBase ? Object.keys(window.__tvBase).length : -1;
                return 'seite=' + location.pathname + ' basis=' + bk + ' elemente=' + els.length +
                    ' namen=' + names.slice(0, 40).join(',');
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

        @JavascriptInterface
        fun onComments(json: String) {
            runOnUiThread { showComments(json) }
        }

        @JavascriptInterface
        fun onApi(kind: String, id: String, ok: Boolean, info: String) {
            runOnUiThread { handleApi(kind, id, ok, info) }
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

    // Ein Symbol mit Zahl in der rechten Spalte
    private fun makeAction(icon: IconView, count: TextView): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        cell.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
        cell.addView(count, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        rightCol.addView(cell, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
        return cell
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
                setPadding(dp(16), dp(8), dp(16), dp(8))
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
            t.setSingleLine(true)
            t.setHorizontallyScrolling(true)
            t.gravity = Gravity.CENTER
            // Feste Breite, damit der Text nie abgeschnitten wird
            val tw = (t.paint.measureText(names[i]) * 1.25f).toInt() + dp(8)
            row.addView(t, LinearLayout.LayoutParams(tw, ViewGroup.LayoutParams.WRAP_CONTENT))
            val line = View(this)
            line.setBackgroundColor(Color.WHITE)
            cell.addView(row)
            cell.addView(line, LinearLayout.LayoutParams(dp(28), dp(3)).apply { topMargin = dp(4) })
            topBar.addView(cell, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
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

        // Rechts neben dem Video: Profilbild, Herz, Kommentare, Speichern, Teilen
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
        avatarBox.addView(ring, FrameLayout.LayoutParams(dp(56), dp(56)))
        avatarBox.addView(
            IconView(this, IconView.PLUS_BADGE),
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )
        rightCol.addView(avatarBox, LinearLayout.LayoutParams(dp(56), dp(68)).apply { bottomMargin = dp(10) })

        heartIcon = IconView(this, IconView.HEART)
        tvLikeCount = label(15f, true).apply { gravity = Gravity.CENTER }
        actionCells.add(makeAction(heartIcon, tvLikeCount))

        tvComCount = label(15f, true).apply { gravity = Gravity.CENTER }
        actionCells.add(makeAction(IconView(this, IconView.COMMENT), tvComCount))

        saveIcon = IconView(this, IconView.BOOKMARK)
        tvSaveCount = label(15f, true).apply { gravity = Gravity.CENTER }
        actionCells.add(makeAction(saveIcon, tvSaveCount))

        tvShrCount = label(15f, true).apply { gravity = Gravity.CENTER }
        actionCells.add(makeAction(IconView(this, IconView.SHARE), tvShrCount))

        playerLayer.addView(rightCol, FrameLayout.LayoutParams(
            dp(90), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.BOTTOM
        ).apply { marginStart = dp(700); bottomMargin = dp(24) })

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
            tint = likedColor
            alpha = 0f
        }
        playerLayer.addView(heartPop, FrameLayout.LayoutParams(dp(130), dp(130), Gravity.CENTER))

        // Kommentar-Fenster (rechts)
        commentPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(238, 18, 18, 18))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            visibility = View.GONE
        }
        commentTitle = label(18f, true)
        commentTitle.text = "Kommentare"
        commentPanel.addView(commentTitle)
        commentScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        commentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        commentScroll.addView(commentList, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        commentPanel.addView(commentScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(12) })
        playerLayer.addView(commentPanel, FrameLayout.LayoutParams(
            dp(440), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
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
            text = "↑ ↓ Video     OK Pause     OK 2× Gefällt mir     → Herz / Kommentare / Speichern / Teilen     ← Ton     Menü  Navigation"
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
        renderActions()
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
