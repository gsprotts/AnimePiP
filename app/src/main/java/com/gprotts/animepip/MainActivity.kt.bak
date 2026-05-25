package com.gprotts.animepip

import android.view.KeyEvent
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import androidx.core.graphics.toColorInt

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AnimePiP"

        private const val WEBPAGE_HOME = "https://www.crunchyroll.com/"

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val ACTION_MEDIA_CONTROL = "com.gprotts.animepip.MEDIA_CONTROL"
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_PLAY_PAUSE = 1
        private const val CONTROL_BACK = 2
        private const val CONTROL_FORWARD = 3

        private const val CR_DARK = "#141519"
        private const val CR_ORANGE = "#F47521"
        private const val CR_ORANGE_DIM = "#BF5A10"
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var pipButton: ImageButton
    private lateinit var insetsController: WindowInsetsControllerCompat

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // ── Layout state ──────────────────────────────────────────────────────────

    private var safeRightInset = 0
    private var safeBottomInset = 0

    // ── Video/PiP state ───────────────────────────────────────────────────────

    private var videoIsPlaying = false
    private var videoAspectRatio = Rational(16, 9)

    /**
     * True only when the native Android PiP button has asked Webpage's own
     * fullscreen control to open, and we are waiting for WebChromeClient.onShowCustomView().
     */
    private var pendingPipAfterPlayerFullscreen = false

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_MEDIA_CONTROL) return

            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_PLAY_PAUSE -> togglePlayPauseFromPip()
                CONTROL_BACK -> seekFromPip(-10.0)
                CONTROL_FORWARD -> seekFromPip(10.0)
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                mediaReceiver,
                IntentFilter(ACTION_MEDIA_CONTROL),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(mediaReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
        }

        buildLayout()
        setupWebView()
        setupBackHandling()

        webView.loadUrl(WEBPAGE_HOME)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(mediaReceiver)
        } catch (_: Exception) {
            // Ignore if already unregistered.
        }

        try {
            webView.destroy()
        } catch (_: Exception) {
            // Ignore shutdown noise.
        }

        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        // Correct behaviour:
        // - If Webpage is already in its real player fullscreen, Home enters PiP.
        // - If not fullscreen, do not PiP the full page layout.
        //
        // Browser/player fullscreen generally needs a real user/page gesture, so the reliable
        // path is: on-screen PiP button -> Webpage fullscreen button -> Android PiP.
        if (customView != null && videoIsPlaying) {
            enterPipIfPossible()
        } else {
            Log.d(TAG, "Home pressed while not player-fullscreen; not entering page-layout PiP")
        }
    }

    override fun onPictureInPictureModeChanged(isInPiP: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPiP, newConfig)

        if (isInPiP) {
            pipButton.visibility = View.GONE
            clearAllPaddingForVideoSurface()
            hideSystemBars()
            injectPipContainCss()

            Log.d(TAG, "Entered PiP")
        } else {
            pendingPipAfterPlayerFullscreen = false
            removePipContainCss()

            if (customView != null) {
                // Return to the player's real fullscreen view after PiP closes.
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                clearAllPaddingForVideoSurface()
                hideSystemBars()
                pipButton.visibility = View.GONE
            } else {
                showSystemBars()
                ViewCompat.requestApplyInsets(webContainer)
                updatePipButtonVisibility()
            }

            webView.postDelayed({
                js("if (window.__cvReportVideoState) window.__cvReportVideoState();")
                updatePipButtonVisibility()
                updatePipParams()
            }, 250)

            updatePipParams()
            Log.d(TAG, "Exited PiP")
            Log.d(TAG, "Exited PiP")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (isInPictureInPictureMode) {
            clearAllPaddingForVideoSurface()
            injectPipContainCss()

            customView?.requestLayout()
            fullscreenContainer.requestLayout()
            webView.requestLayout()

            customView?.invalidate()
            fullscreenContainer.invalidate()
            webView.invalidate()

            webView.postDelayed({
                updatePipParams()
            }, 100)
        } else {
            ViewCompat.requestApplyInsets(webContainer)
            updatePipButtonPosition()
        }
    }

    // ── Back handling ─────────────────────────────────────────────────────────

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        customView != null && !isInPictureInPictureMode -> {
                            // Back exits Webpage/player fullscreen first.
                            customViewCallback?.onCustomViewHidden()
                        }

                        webView.canGoBack() -> {
                            // Browser-style back, staying inside the app.
                            webView.goBack()
                        }

                        webView.url?.equals(WEBPAGE_HOME, ignoreCase = true) != true -> {
                            // No history, but not home. Go home instead of closing the app.
                            webView.loadUrl(WEBPAGE_HOME)
                        }

                        else -> {
                            // Stay in the app. Do not call the default system back exit.
                            Log.d(TAG, "Back pressed at root; staying in app")
                        }
                    }
                }
            }
        )
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    @SuppressLint("UseKtx")
    private fun buildLayout() {
        val dp = resources.displayMetrics.density

        root = FrameLayout(this).apply {
            setBackgroundColor(CR_DARK.toColorInt())
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        setContentView(root)

        webContainer = FrameLayout(this).apply {
            setBackgroundColor(CR_DARK.toColorInt())
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        root.addView(
            webContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        fullscreenContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        root.addView(
            fullscreenContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        webView = WebView(this)
        webContainer.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setupNativePipButton(dp)

        // Normal mode: keep content below system bars.
        // Fullscreen/PiP: remove padding so PiP does not capture black bars.
        ViewCompat.setOnApplyWindowInsetsListener(webContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            safeRightInset = bars.right
            safeBottomInset = bars.bottom

            if (!isInPictureInPictureMode && customView == null) {
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                view.setPadding(0, 0, 0, 0)
            }

            updatePipButtonPosition()

            insets
        }

        ViewCompat.requestApplyInsets(webContainer)

        insetsController = WindowInsetsControllerCompat(window, webView)

        WebView.setWebContentsDebuggingEnabled(false)
    }

    /**
     * Native Android overlay button, not an in-page DOM button.
     *
     * This is intentionally screen-relative rather than webpage-relative, so desktop page zoom,
     * scroll, and visualViewport quirks cannot push it off-screen.
     */
    private fun setupNativePipButton(dp: Float) {
        val btnSizePx = (52 * dp).toInt()

        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(CR_ORANGE_DIM.toColorInt())
        }

        val fill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(CR_ORANGE.toColorInt())
        }

        val insetPx = (3 * dp).toInt()

        pipButton = ImageButton(this).apply {
            background = LayerDrawable(arrayOf(ring, fill)).apply {
                setLayerInset(1, insetPx, insetPx, insetPx, insetPx)
            }

            setImageDrawable(pipIconDrawable(dp))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                (12 * dp).toInt(),
                (12 * dp).toInt(),
                (12 * dp).toInt(),
                (12 * dp).toInt()
            )

            contentDescription = "Picture in Picture"
            visibility = View.GONE

            setOnClickListener {
                requestPlayerFullscreenThenPip()
            }
        }

        root.addView(
            pipButton,
            FrameLayout.LayoutParams(btnSizePx, btnSizePx).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, (20 * dp).toInt(), (20 * dp).toInt())
            }
        )
    }

    /**
     * Draws a small PiP glyph so no extra vector asset is needed.
     */
    private fun pipIconDrawable(dp: Float): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = android.graphics.Paint.Style.FILL
            }

            private val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2.5f * dp
                strokeJoin = android.graphics.Paint.Join.ROUND
            }

            override fun draw(canvas: android.graphics.Canvas) {
                val b = bounds
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                val r = 2.5f * dp

                val outerRect = android.graphics.RectF(
                    b.left + strokePaint.strokeWidth / 2,
                    b.top + strokePaint.strokeWidth / 2,
                    b.right - strokePaint.strokeWidth / 2,
                    b.bottom - strokePaint.strokeWidth / 2
                )
                canvas.drawRoundRect(outerRect, r, r, strokePaint)

                val iL = b.left + w * 0.48f
                val iT = b.top + h * 0.44f
                val iR = b.right - w * 0.05f
                val iB = b.bottom - h * 0.05f
                canvas.drawRoundRect(
                    android.graphics.RectF(iL, iT, iR, iB),
                    r * 0.7f,
                    r * 0.7f,
                    paint
                )
            }

            override fun setAlpha(a: Int) {
                paint.alpha = a
                strokePaint.alpha = a
            }

            override fun setColorFilter(cf: android.graphics.ColorFilter?) {
                paint.colorFilter = cf
                strokePaint.colorFilter = cf
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun updatePipButtonPosition() {
        if (!::pipButton.isInitialized) return

        val dp = resources.displayMetrics.density
        val params = pipButton.layoutParams as? FrameLayout.LayoutParams ?: return

        params.gravity = Gravity.BOTTOM or Gravity.END
        params.setMargins(
            0,
            0,
            safeRightInset + (20 * dp).toInt(),
            safeBottomInset + (20 * dp).toInt()
        )

        pipButton.layoutParams = params
    }

    private fun updatePipButtonVisibility() {
        if (!::pipButton.isInitialized) return

        val isWatch = (webView.url ?: "").contains("/watch/", ignoreCase = true)

        pipButton.visibility =
            if (
                isWatch &&
                !isInPictureInPictureMode &&
                customView == null
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun clearAllPaddingForVideoSurface() {
        root.setPadding(0, 0, 0, 0)
        webContainer.setPadding(0, 0, 0, 0)
        fullscreenContainer.setPadding(0, 0, 0, 0)
    }

    private fun hideSystemBars() {
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemBars() {
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            userAgentString = DESKTOP_UA
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        CookieManager.getInstance().setAcceptCookie(true)

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(VideoStateBridge(), "AndroidVideoState")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Block external navigation attempts. Keep Webpage in the wrapper.
                return !url.contains("crunchyroll.com", ignoreCase = true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectVideoStateWatcher()
                injectBannerHider()
                updatePipButtonVisibility()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                Log.w(TAG, "WebView error: ${error?.description} at ${request?.url}")
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                Log.w(TAG, "HTTP error: ${errorResponse?.statusCode} at ${request?.url}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                Log.d(TAG, "JS console: ${msg?.message()}")
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                customView = view
                customViewCallback = callback

                fullscreenContainer.removeAllViews()

                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )

                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                pipButton.visibility = View.GONE

                clearAllPaddingForVideoSurface()
                hideSystemBars()

                updatePipParams()

                if (pendingPipAfterPlayerFullscreen) {
                    // The player's own fullscreen control successfully produced a WebView
                    // fullscreen custom view. Now Android PiP can safely capture the video.
                    webView.postDelayed({
                        pendingPipAfterPlayerFullscreen = false
                        enterPipIfPossible()
                    }, 220)
                }
            }

            override fun onHideCustomView() {
                if (customView == null) return

                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE

                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                webView.visibility = View.VISIBLE
                removePipContainCss()

                if (!isInPictureInPictureMode) {
                    showSystemBars()
                    ViewCompat.requestApplyInsets(webContainer)
                    updatePipButtonVisibility()
                }

                updatePipParams()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                } else {
                    super.onPermissionRequest(request)
                }
            }
        }
    }

    // ── JS injections ─────────────────────────────────────────────────────────

    private fun injectVideoStateWatcher() {
        js(
            """
            (function() {
                if (window.__cvVideoWatcherInstalled) return;
                window.__cvVideoWatcherInstalled = true;

                window.__getBestVideo = function() {
                    var videos = Array.from(document.querySelectorAll('video'));
                    if (!videos.length) return null;

                    return videos.sort(function(a, b) {
                        function score(v) {
                            return (!v.paused ? 100 : 0)
                                + ((v.readyState || 0) * 10)
                                + ((v.videoWidth  || 0) * (v.videoHeight || 0) > 0 ? 20 : 0)
                                + ((v.offsetWidth || 0) * (v.offsetHeight || 0) > 0 ? 10 : 0);
                        }
                        return score(b) - score(a);
                    })[0];
                };

                window.__cvReportVideoState = function() {
                    var v = window.__getBestVideo();

                    AndroidVideoState.update(JSON.stringify(v ? {
                        playing: !v.paused && !v.ended && v.readyState > 1,
                        width:   v.videoWidth  || 16,
                        height:  v.videoHeight || 9
                    } : {
                        playing: false,
                        width: 16,
                        height: 9
                    }));
                };

                ['play','pause','ended','loadedmetadata','durationchange','resize'].forEach(function(e) {
                    document.addEventListener(e, window.__cvReportVideoState, true);
                });

                setInterval(window.__cvReportVideoState, 1000);
                window.__cvReportVideoState();
            })();
            """.trimIndent()
        )
    }

    /**
     * Called by the native Android overlay button.
     *
     * This still clicks Webpage's own fullscreen control, rather than calling
     * requestFullscreen() on the video/container directly.
     */
    private fun requestPlayerFullscreenThenPip() {
        js("if (window.__cvReportVideoState) window.__cvReportVideoState();")

        if (customView != null) {
            enterPipIfPossible()
            return
        }

        pendingPipAfterPlayerFullscreen = true
        pipButton.visibility = View.GONE
        clearAllPaddingForVideoSurface()

        // Focus the player/document, then send Webpage's own fullscreen hotkey.
        js(
            """
        (function(){
            var v = window.__getBestVideo && window.__getBestVideo();

            if (v) {
                var root =
                    v.closest('[data-testid="player-controls-root"]') ||
                    v.closest('[data-testid*="player"]') ||
                    v.closest('[class*="VideoPlayer"]') ||
                    v.closest('[class*="video-player"]') ||
                    v.closest('[class*="vilos"]') ||
                    v.closest('[class*="Vilos"]') ||
                    v.closest('[class*="player"]') ||
                    v.closest('[class*="Player"]') ||
                    document.body;

                if (root && root.focus) {
                    root.setAttribute('tabindex', '-1');
                    root.focus();
                }

                if (v.focus) {
                    v.setAttribute('tabindex', '-1');
                    v.focus();
                }
            }

            return "focused-for-f-key";
        })();
        """.trimIndent()
        )

        webView.postDelayed({
            sendFKeyToWebView()
            waitForPlayerFullscreenAfterHotkey()
        }, 80)
    }

    private fun injectBannerHider() {
        js(
            """
            (function() {
                if (window.__bannerHiderInstalled) return;
                window.__bannerHiderInstalled = true;

                var SELECTORS = [
                    '.app-banner', '.open-app-btn',
                    '[class*="AppBanner"]', '[class*="AppRedirect"]',
                    '[class*="MobileAppBanner"]', '.erc-mobile-app-banner',
                    '.mobile-app-prompt', '.vilos-mobile-app-banner'
                ];

                function hide() {
                    SELECTORS.forEach(function(sel) {
                        document.querySelectorAll(sel).forEach(function(el) {
                            if (el && el.style) {
                                el.style.setProperty('display','none','important');
                                el.style.setProperty('visibility','hidden','important');
                            }
                        });
                    });
                }

                hide();
                new MutationObserver(hide).observe(document.documentElement, {
                    childList: true,
                    subtree: true
                });
            })();
            """.trimIndent()
        )
    }

    private fun injectPipContainCss() {
        js(
            """
        (function(){
            var v = window.__getBestVideo && window.__getBestVideo();
            if (!v) return "no-video";

            document.querySelectorAll('video[data-cv-pip-contain="1"]').forEach(function(old) {
                old.removeAttribute('data-cv-pip-contain');
            });

            v.setAttribute('data-cv-pip-contain', '1');

            var style = document.getElementById('__cv_pip_contain_style');
            if (!style) {
                style = document.createElement('style');
                style.id = '__cv_pip_contain_style';
                document.head.appendChild(style);
            }

            style.textContent = `
                html, body {
                    margin: 0 !important;
                    padding: 0 !important;
                    width: 100vw !important;
                    height: 100vh !important;
                    min-width: 0 !important;
                    min-height: 0 !important;
                    overflow: hidden !important;
                    background: #000 !important;
                }

                body * {
                    min-width: 0 !important;
                    min-height: 0 !important;
                    box-sizing: border-box !important;
                }

                video[data-cv-pip-contain="1"] {
                    position: fixed !important;
                    left: 0 !important;
                    top: 0 !important;
                    right: 0 !important;
                    bottom: 0 !important;
                    width: 100vw !important;
                    height: 100vh !important;
                    max-width: 100vw !important;
                    max-height: 100vh !important;
                    min-width: 0 !important;
                    min-height: 0 !important;
                    object-fit: contain !important;
                    object-position: center center !important;
                    transform: none !important;
                    background: #000 !important;
                    z-index: 2147483647 !important;
                }
            `;

            return "pip-contain-css-applied-strong";
        })();
        """.trimIndent()
        )
    }

    private fun removePipContainCss() {
        js(
            """
            (function(){
                var style = document.getElementById('__cv_pip_contain_style');
                if (style) style.remove();

                document.querySelectorAll('video[data-cv-pip-contain="1"]').forEach(function(v) {
                    v.removeAttribute('data-cv-pip-contain');
                });

                return "pip-contain-css-removed";
            })();
            """.trimIndent()
        )
    }

    // ── JS bridge ─────────────────────────────────────────────────────────────

    inner class VideoStateBridge {
        @JavascriptInterface
        fun update(json: String) {
            runOnUiThread {
                try {
                    val obj = JSONObject(json)

                    val playing = obj.optBoolean("playing", false)
                    val w = obj.optInt("width", 16).coerceAtLeast(1)
                    val h = obj.optInt("height", 9).coerceAtLeast(1)

                    videoIsPlaying = playing
                    videoAspectRatio = safeVideoRational(w, h)

                    updatePipButtonVisibility()
                    updatePipParams()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed parsing video state: $json", e)
                    videoIsPlaying = false
                    videoAspectRatio = Rational(16, 9)
                    updatePipButtonVisibility()
                    updatePipParams()
                }
            }
        }

        @JavascriptInterface
        fun onPlayerFullscreenClicked() {
            runOnUiThread {
                // Wait for WebChromeClient.onShowCustomView(). If it does not come,
                // do not enter PiP: entering without default player fullscreen is the broken path.
                webView.postDelayed({
                    if (pendingPipAfterPlayerFullscreen && customView == null) {
                        pendingPipAfterPlayerFullscreen = false
                        showSystemBars()
                        ViewCompat.requestApplyInsets(webContainer)
                        updatePipButtonVisibility()
                        Log.w(TAG, "Player fullscreen click did not produce WebView fullscreen; not entering PiP")
                    }
                }, 2500)
            }
        }

        @JavascriptInterface
        fun onPlayerFullscreenFailed(reason: String) {
            runOnUiThread {
                pendingPipAfterPlayerFullscreen = false
                showSystemBars()
                ViewCompat.requestApplyInsets(webContainer)
                updatePipButtonVisibility()
                Log.w(TAG, "Player fullscreen failed; not entering PiP: $reason")
            }
        }
    }

    // ── PiP ──────────────────────────────────────────────────────────────────

    private fun safeVideoRational(width: Int, height: Int): Rational {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val ratio = safeWidth.toFloat() / safeHeight.toFloat()

        // Most Webpage's content is effectively 16:9.
        // Use a stable exact ratio so Android PiP does not chase tiny reported-size changes.
        if (ratio in 1.70f..1.85f) {
            return Rational(16, 9)
        }

        // 4:3 older content.
        if (ratio in 1.28f..1.38f) {
            return Rational(4, 3)
        }

        // Very wide cinema-style content.
        if (ratio in 2.20f..2.39f) {
            return Rational(21, 9)
        }

        return Rational(16, 9)
    }

    private fun pipActions(): List<RemoteAction> {

        val ppIcon = if (videoIsPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val ppTitle = if (videoIsPlaying) "Pause" else "Play"

        return listOf(
            makeAction(android.R.drawable.ic_media_rew, "Back 10s", CONTROL_BACK, 1),
            makeAction(ppIcon, ppTitle, CONTROL_PLAY_PAUSE, 2),
            makeAction(android.R.drawable.ic_media_ff, "Fwd 10s", CONTROL_FORWARD, 3)
        )
    }

    private fun updatePipParams() {

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(videoAspectRatio)
            .setActions(pipActions())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Only auto-enter from real fullscreen. Otherwise, Android captures the full page.
            builder.setAutoEnterEnabled(videoIsPlaying && customView != null)

            // Important for WebView/video resize crop issues:
            // seamless resize can crop WebView video surfaces on small PiP sizes.
            builder.setSeamlessResizeEnabled(false)
        }

        try {
            setPictureInPictureParams(builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update PiP params", e)
        }
    }

    private fun enterPipIfPossible() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        // Do not hard-block on cached videoIsPlaying.
        // It can be stale after PiP exit.
        js("if (window.__cvReportVideoState) window.__cvReportVideoState();")

        if (customView == null) {
            Log.w(TAG, "Refusing PiP because player is not in WebView fullscreen")
            return
        }

        clearAllPaddingForVideoSurface()
        hideSystemBars()
        injectPipContainCss()

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(videoAspectRatio)
            .setActions(pipActions())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
            builder.setSeamlessResizeEnabled(false)
        }

        try {
            enterPictureInPictureMode(builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enter PiP", e)
            pendingPipAfterPlayerFullscreen = false
            removePipContainCss()
            ViewCompat.requestApplyInsets(webContainer)
        }
    }

    // ── PiP commands ──────────────────────────────────────────────────────────

    private fun togglePlayPauseFromPip() {
        js(
            """
        (function(){
            var v = window.__getBestVideo && window.__getBestVideo();
            if (!v) return "no-video";

            if (v.paused) {
                v.play();
            } else {
                v.pause();
            }

            setTimeout(function(){
                if (window.__cvReportVideoState) {
                    window.__cvReportVideoState();
                }
            }, 100);

            return v.paused ? "pause" : "play";
        })()
        """.trimIndent()
        )

        // Optimistically flip the icon immediately.
        videoIsPlaying = !videoIsPlaying
        updatePipParams()

        // Then correct it from the actual video state.
        webView.postDelayed({
            js("if (window.__cvReportVideoState) window.__cvReportVideoState();")
            updatePipParams()
        }, 300)

        webView.postDelayed({
            js("if (window.__cvReportVideoState) window.__cvReportVideoState();")
            updatePipParams()
        }, 900)
    }

    private fun seekFromPip(deltaSeconds: Double) {
        if (deltaSeconds < 0) {
            runWebpageNativeControl("back")
        } else {
            runWebpageNativeControl("forward")
        }

        webView.postDelayed({
            js("if (window.__cvReportVideoState) window.__cvReportVideoState();")
            updatePipParams()
        }, 500)
    }

    private fun runWebpageNativeControl(action: String) {
        js(
            """
            (async function(){
                function sleep(ms) {
                    return new Promise(function(resolve) {
                        setTimeout(resolve, ms);
                    });
                }

                function textOf(el) {
                    if (!el) return "";

                    return [
                        el.getAttribute("aria-label"),
                        el.getAttribute("title"),
                        el.getAttribute("data-testid"),
                        el.getAttribute("data-test-id"),
                        el.className,
                        el.textContent
                    ].filter(Boolean).join(" ").toLowerCase();
                }

                function visible(el) {
                    if (!el) return false;

                    var rect = el.getBoundingClientRect();
                    var style = window.getComputedStyle(el);

                    return rect.width > 0 &&
                        rect.height > 0 &&
                        style.visibility !== "hidden" &&
                        style.display !== "none" &&
                        style.opacity !== "0";
                }

                function clickElement(el) {
                    if (!el) return false;

                    try {
                        el.scrollIntoView({
                            block: "center",
                            inline: "center",
                            behavior: "instant"
                        });
                    } catch (e) {}

                    try {
                        var rect = el.getBoundingClientRect();
                        var x = rect.left + rect.width / 2;
                        var y = rect.top + rect.height / 2;

                        ["pointerdown", "mousedown", "pointerup", "mouseup", "click"].forEach(function(type) {
                            var event;

                            if (type.indexOf("pointer") === 0 && window.PointerEvent) {
                                event = new PointerEvent(type, {
                                    bubbles: true,
                                    cancelable: true,
                                    composed: true,
                                    clientX: x,
                                    clientY: y,
                                    pointerId: 1,
                                    pointerType: "touch",
                                    isPrimary: true
                                });
                            } else {
                                event = new MouseEvent(type, {
                                    bubbles: true,
                                    cancelable: true,
                                    composed: true,
                                    clientX: x,
                                    clientY: y
                                });
                            }

                            el.dispatchEvent(event);
                        });

                        if (typeof el.click === "function") {
                            el.click();
                        }

                        return true;
                    } catch (e) {
                        try {
                            el.click();
                            return true;
                        } catch (e2) {
                            return false;
                        }
                    }
                }

                function getVideo() {
                    return window.__getBestVideo && window.__getBestVideo();
                }

                function getPlayerRoot(v) {
                    return (
                        v.closest('[data-testid*="player"]') ||
                        v.closest('[class*="VideoPlayer"]') ||
                        v.closest('[class*="video-player"]') ||
                        v.closest('[class*="vilos"]') ||
                        v.closest('[class*="Vilos"]') ||
                        v.closest('[class*="player"]') ||
                        v.closest('[class*="Player"]') ||
                        document
                    );
                }

                function showControls(root, v) {
                    try {
                        clickElement(v);
                        clickElement(root);
                    } catch (e) {}
                }

                function findButton(root, wanted, unwanted) {
                    var candidates = Array.from(root.querySelectorAll(
                        'button, [role="button"], [aria-label], [title], [data-testid], [data-test-id]'
                    )).filter(visible);

                    var best = null;
                    var bestScore = 0;

                    candidates.forEach(function(el) {
                        var t = textOf(el);

                        if (!t) return;

                        for (var i = 0; i < unwanted.length; i++) {
                            if (t.indexOf(unwanted[i]) !== -1) {
                                return;
                            }
                        }

                        var score = 0;

                        wanted.forEach(function(word) {
                            if (t.indexOf(word) !== -1) {
                                score += 10;
                            }
                        });

                        if (el.tagName && el.tagName.toLowerCase() === "button") {
                            score += 3;
                        }

                        var rect = el.getBoundingClientRect();
                        if (rect.top > window.innerHeight * 0.45) {
                            score += 2;
                        }

                        if (score > bestScore) {
                            bestScore = score;
                            best = el;
                        }
                    });

                    return bestScore > 0 ? best : null;
                }

                function dispatchHotkey(root, key, keyCode) {
                    try {
                        if (root && root.focus) root.focus();

                        ["keydown", "keyup"].forEach(function(type) {
                            var event = new KeyboardEvent(type, {
                                key: key,
                                code: key,
                                keyCode: keyCode,
                                which: keyCode,
                                bubbles: true,
                                cancelable: true
                            });

                            root.dispatchEvent(event);
                            document.dispatchEvent(event);
                            window.dispatchEvent(event);
                        });

                        return true;
                    } catch (e) {
                        return false;
                    }
                }

                var action = "$action";
                var v = getVideo();

                if (!v) {
                    return "no-video";
                }

                var root = getPlayerRoot(v);

                showControls(root, v);
                await sleep(150);

                if (action === "playPause") {
                    var playPauseButton = findButton(
                        root,
                        ["play", "pause"],
                        ["replay", "skip", "back", "forward", "next", "previous", "fullscreen", "settings"]
                    );

                    if (playPauseButton && clickElement(playPauseButton)) {
                        await sleep(150);
                        if (window.__cvReportVideoState) window.__cvReportVideoState();
                        return "native-play-pause-click";
                    }

                    try {
                        if (v.paused) {
                            await v.play();
                        } else {
                            v.pause();
                        }

                        if (window.__cvReportVideoState) window.__cvReportVideoState();
                        return "video-play-pause-fallback";
                    } catch (e) {
                        return "play-pause-failed:" + String(e);
                    }
                }

                if (action === "back") {
                    var backButton = findButton(
                        root,
                        ["back", "rewind", "replay", "10", "10s", "10 seconds", "skip back"],
                        ["forward", "next", "fullscreen", "settings", "volume", "mute"]
                    );

                    if (backButton && clickElement(backButton)) {
                        await sleep(250);
                        if (window.__cvReportVideoState) window.__cvReportVideoState();
                        return "native-back-click";
                    }

                    dispatchHotkey(root, "ArrowLeft", 37);
                    await sleep(250);

                    if (window.__cvReportVideoState) window.__cvReportVideoState();
                    return "back-hotkey-fallback";
                }

                if (action === "forward") {
                    var forwardButton = findButton(
                        root,
                        ["forward", "10", "10s", "10 seconds", "skip forward"],
                        ["back", "rewind", "replay", "previous", "fullscreen", "settings", "volume", "mute"]
                    );

                    if (forwardButton && clickElement(forwardButton)) {
                        await sleep(250);
                        if (window.__cvReportVideoState) window.__cvReportVideoState();
                        return "native-forward-click";
                    }

                    dispatchHotkey(root, "ArrowRight", 39);
                    await sleep(250);

                    if (window.__cvReportVideoState) window.__cvReportVideoState();
                    return "forward-hotkey-fallback";
                }

                return "unknown-action";
            })()
            """.trimIndent()
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendFKeyToWebView() {
        webView.requestFocus()

        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F)

        webView.dispatchKeyEvent(down)
        webView.dispatchKeyEvent(up)

        Log.d(TAG, "Sent F key to WebView")
    }

    private fun waitForPlayerFullscreenAfterHotkey() {
        webView.postDelayed({
            if (pendingPipAfterPlayerFullscreen && customView == null) {
                pendingPipAfterPlayerFullscreen = false
                showSystemBars()
                ViewCompat.requestApplyInsets(webContainer)
                updatePipButtonVisibility()
                Log.w(TAG, "F hotkey did not produce WebView fullscreen")
            }
        }, 2500)
    }

    private fun makeAction(
        @DrawableRes icon: Int,
        title: String,
        type: Int,
        rc: Int
    ): RemoteAction {
        val pi = PendingIntent.getBroadcast(
            this,
            rc,
            Intent(ACTION_MEDIA_CONTROL).apply {
                setPackage(packageName)
                putExtra(EXTRA_CONTROL_TYPE, type)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return RemoteAction(
            Icon.createWithResource(this, icon),
            title,
            title,
            pi
        )
    }

    private fun js(script: String) {
        webView.evaluateJavascript(script, null)
    }
}
