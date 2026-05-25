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
        private const val VIDEO_STATE_BRIDGE_NAME = "AndroidVideoState"
        private const val MAX_VIDEO_STATE_JSON_LENGTH = 4096
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

    private val  videoStateBridge = VideoStateBridge()

    // ── Layout state ──────────────────────────────────────────────────────────

    private var safeRightInset = 0
    private var safeBottomInset = 0

    // ── Video/PiP state ───────────────────────────────────────────────────────

    private var videoIsPlaying = false
    private var videoAspectRatio = Rational(16, 9)
    private val pendingUiCallbacks = java.util.concurrent.CopyOnWriteArraySet<Runnable>()

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
        clearPendingUiCallbacks()
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

    
    private fun postDelayedSafely(delayMillis: Long, block: () -> Unit) {
        if (!::webView.isInitialized) return

        val callback = object : Runnable {
            override fun run() {
                pendingUiCallbacks.remove(this)
                if (!isFinishing && !isDestroyed) {
                    block()
                }
            }
        }

        pendingUiCallbacks.add(callback)
        webView.postDelayed(callback, delayMillis)
    }

    private fun clearPendingUiCallbacks() {
        if (::webView.isInitialized) {
            pendingUiCallbacks.forEach { webView.removeCallbacks(it) }
        }
        pendingUiCallbacks.clear()
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

            postDelayedSafely(250) {
                js("if (window.__cvReportVideoState) window.__cvReportVideoState();")
                updatePipButtonVisibility()
                updatePipParams()
            }

            updatePipParams()
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

            postDelayedSafely(100) { updatePipParams() }
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
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        CookieManager.getInstance().setAcceptCookie(true)

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.removeJavascriptInterface(VIDEO_STATE_BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                clearPendingUiCallbacks()

                if (!CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl(url)) {
                    view.removeJavascriptInterface(VIDEO_STATE_BRIDGE_NAME)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Block external navigation attempts. Keep Webpage in the wrapper.
                val allowed = CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl(url)
                if (!allowed) {
                    view?.removeJavascriptInterface(VIDEO_STATE_BRIDGE_NAME)
                }
                return !allowed
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl(url)) {
                    view.addJavascriptInterface(videoStateBridge, VIDEO_STATE_BRIDGE_NAME)
                    injectVideoStateWatcher()
                    injectBannerHider()
                }
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
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "JS console: ${msg?.message()}")
                }
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
                    postDelayedSafely(220) {
                    pendingPipAfterPlayerFullscreen = false
                    enterPipIfPossible()
                }
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
    private val injectVideoStateWatcher by lazy {
        assets.open("inject_video_state_watcher.js").bufferedReader().readText()
    }

    private fun injectVideoStateWatcher() {
        js(injectVideoStateWatcher)
    }

    private val fullscreenVideoPlayer by lazy {
        assets.open("fullscreen_video_player.js").bufferedReader().readText()
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
        js(fullscreenVideoPlayer)

        postDelayedSafely(80) {
            sendFKeyToWebView()
            waitForPlayerFullscreenAfterHotkey()
        }
    }

    private val hideBanner by lazy {
        assets.open("hidebanner.js").bufferedReader().readText()
    }

    private fun injectBannerHider() {
        js(hideBanner)
    }

    private val pipContainCss by lazy {
        assets.open("pip.css").bufferedReader().readText()
    }
    
    private val injectPipCssScript by lazy {
        assets.open("inject_pip_css.js").bufferedReader().readText()
    }
    
    private fun injectPipContainCss() {
        js("window.__pipCss = `$pipContainCss`;")
        js(injectPipCssScript)
    }

    private val removePipCssScript by lazy {
        assets.open("remove_pip_css.js").bufferedReader().readText()
    }

    private fun removePipContainCss() {
        js(removePipCssScript)
    }

    // ── JS bridge ─────────────────────────────────────────────────────────────

    inner class VideoStateBridge {
        @JavascriptInterface
        fun update(json: String) {
            if (json.length > MAX_VIDEO_STATE_JSON_LENGTH) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Rejected oversized video state JSON")
                }
                return
            }

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
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Failed parsing video state", e)
                    }
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
                postDelayedSafely(2500) {
                    if (pendingPipAfterPlayerFullscreen && customView == null) {
                        pendingPipAfterPlayerFullscreen = false
                        showSystemBars()
                        ViewCompat.requestApplyInsets(webContainer)
                        updatePipButtonVisibility()
                        Log.w(TAG, "Player fullscreen click did not produce WebView fullscreen; not entering PiP")
                    }
                }
            }
        }

        @JavascriptInterface
        fun onPlayerFullscreenFailed(reason: String) {
            runOnUiThread {
                val safeReason = reason.take(200)
                pendingPipAfterPlayerFullscreen = false
                showSystemBars()
                ViewCompat.requestApplyInsets(webContainer)
                updatePipButtonVisibility()
                Log.w(TAG, "Player fullscreen failed; not entering PiP: $safeReason")
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

    private val playPauseScript by lazy {
        assets.open("playpause.js").bufferedReader().readText()
    }

    private fun togglePlayPauseFromPip() {
        js(playPauseScript)

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

    private val nativeControlScript by lazy {
        assets.open("navigation_controls.js").bufferedReader().readText()
    }
    
    private fun runWebpageNativeControl(action: String) {
        js(nativeControlScript.replace("\$action", action))
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
