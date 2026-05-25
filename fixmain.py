#!/usr/bin/env python3
'''
Apply targeted MainActivity.kt hardening changes without relying on fragile
hand-written git patch hunk numbers.

Run from the repo root:

    python3 tools/apply_mainactivity_fixes.py

or on Windows:

    py tools\apply_mainactivity_fixes.py

The script is intentionally conservative: if it cannot find an expected block,
it stops and tells you which edit failed.
'''

from __future__ import annotations

from pathlib import Path
import re
import sys


DEFAULT_PATH = Path("app/src/main/java/com/gprotts/animepip/MainActivity.kt")


def fail(message: str) -> None:
    raise SystemExit(f"\nERROR: {message}\nNo file was written.\n")


def replace_exact(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        fail(f"Could not find exact block for: {label}")
    return text.replace(old, new, 1)


def replace_regex(text: str, pattern: str, replacement: str, label: str, flags: int = re.S) -> str:
    new_text, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        fail(f"Could not find regex block for: {label}")
    return new_text


def main() -> None:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PATH

    if not path.exists():
        fail(f"MainActivity.kt not found at {path}")

    text = path.read_text(encoding="utf-8")
    original = text

    # 1. Constants.
    if "VIDEO_STATE_BRIDGE_NAME" not in text:
        text = replace_exact(
            text,
            'private const val CR_ORANGE_DIM = "#BF5A10"',
            '''private const val CR_ORANGE_DIM = "#BF5A10"
        private const val VIDEO_STATE_BRIDGE_NAME = "AndroidVideoState"
        private const val MAX_VIDEO_STATE_JSON_LENGTH = 4096''',
            "add JS bridge constants",
        )

    # 2. Pending callback tracking.
    if "pendingUiCallbacks" not in text:
        text = replace_exact(
            text,
            "private var videoAspectRatio = Rational(16, 9)",
            '''private var videoAspectRatio = Rational(16, 9)
    private val pendingUiCallbacks = mutableSetOf<Runnable>()''',
            "add pendingUiCallbacks",
        )

    # 3. onDestroy clears callbacks.
    if "override fun onDestroy() {\n        clearPendingUiCallbacks()" not in text:
        text = replace_regex(
            text,
            r"override fun onDestroy\(\)\s*\{\s*try\s*\{",
            "override fun onDestroy() {\n        clearPendingUiCallbacks()\n        try {",
            "clear callbacks in onDestroy",
        )

    # 4. Add helper methods after onDestroy.
    if "private fun postDelayedSafely(delayMillis: Long, block: () -> Unit)" not in text:
        helper_methods = '''
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

'''
        text = replace_regex(
            text,
            r"(super\.onDestroy\(\)\s*\}\s*)override fun onUserLeaveHint",
            r"\1" + helper_methods + r"    override fun onUserLeaveHint",
            "insert delayed-callback helpers after onDestroy",
        )

    # 5. Replace delayed callback after PiP exit.
    text = replace_regex(
        text,
        r"webView\.postDelayed\(\{\s*js\(\"if \(window\.__cvReportVideoState\) window\.__cvReportVideoState\(\);\"\)\s*updatePipButtonVisibility\(\)\s*updatePipParams\(\)\s*\},\s*250\)",
        '''postDelayedSafely(250) {
                js("if (window.__cvReportVideoState) window.__cvReportVideoState();")
                updatePipButtonVisibility()
                updatePipParams()
            }''',
        "replace PiP-exit postDelayed",
    )

    # 6. Remove duplicate Exited PiP log.
    text = replace_regex(
        text,
        r'Log\.d\(TAG,\s*"Exited PiP"\)\s*Log\.d\(TAG,\s*"Exited PiP"\)',
        'Log.d(TAG, "Exited PiP")',
        "remove duplicate Exited PiP log",
    )

    # 7. Replace configuration delayed callback.
    text = replace_regex(
        text,
        r"webView\.postDelayed\(\{\s*updatePipParams\(\)\s*\},\s*100\)",
        "postDelayedSafely(100) { updatePipParams() }",
        "replace configuration postDelayed",
    )

    # 8. Harden mixed content.
    text = replace_exact(
        text,
        "mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE",
        "mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW",
        "set mixed content to NEVER_ALLOW",
    )

    # 9. Do not attach JS bridge before navigation; attach after allowed page finishes.
    text = replace_regex(
        text,
        r'webView\.addJavascriptInterface\(VideoStateBridge\(\),\s*"AndroidVideoState"\)',
        "webView.removeJavascriptInterface(VIDEO_STATE_BRIDGE_NAME)",
        "remove eager addJavascriptInterface",
    )

    # 10. Add onPageStarted inside WebViewClient.
    if "override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?)" not in text:
        page_started = '''webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                clearPendingUiCallbacks()

                if (!CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl(url)) {
                    view.removeJavascriptInterface(VIDEO_STATE_BRIDGE_NAME)
                }
            }

            override fun shouldOverrideUrlLoading'''
        text = replace_regex(
            text,
            r"webView\.webViewClient\s*=\s*object\s*:\s*WebViewClient\(\)\s*\{\s*override fun shouldOverrideUrlLoading",
            page_started,
            "insert onPageStarted",
        )

    # 11. Replace unsafe URL contains allowlist.
    text = replace_regex(
        text,
        r'val url = request\?\.url\?\.toString\(\) \?: return false\s*// Block external navigation attempts\.\s*Keep Webpage in the wrapper\.\s*return !url\.contains\("crunchyroll\.com",\s*ignoreCase\s*=\s*true\)',
        '''val url = request?.url?.toString() ?: return false

                // Block external navigation attempts. Keep Webpage in the wrapper.
                val allowed = CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl(url)
                if (!allowed) {
                    view?.removeJavascriptInterface(VIDEO_STATE_BRIDGE_NAME)
                }
                return !allowed''',
        "replace URL allowlist",
    )

    # 12. Only inject after allowed Crunchyroll page finishes.
    text = replace_regex(
        text,
        r"override fun onPageFinished\(view: WebView,\s*url: String\)\s*\{\s*injectVideoStateWatcher\(\)\s*injectBannerHider\(\)\s*updatePipButtonVisibility\(\)\s*\}",
        '''override fun onPageFinished(view: WebView, url: String) {
                if (CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl(url)) {
                    view.removeJavascriptInterface(VIDEO_STATE_BRIDGE_NAME)
                    view.addJavascriptInterface(VideoStateBridge(), VIDEO_STATE_BRIDGE_NAME)
                    injectVideoStateWatcher()
                    injectBannerHider()
                }
                updatePipButtonVisibility()
            }''',
        "gate onPageFinished injection",
    )

    # 13. Debug-only JS console logs.
    text = replace_regex(
        text,
        r"override fun onConsoleMessage\(msg: ConsoleMessage\?\): Boolean\s*\{\s*Log\.d\(TAG,\s*\"JS console: \$\{msg\?\.message\(\)\}\"\)\s*return true\s*\}",
        '''override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "JS console: ${msg?.message()}")
                }
                return true
            }''',
        "gate JS console logging",
    )

    # 14. Replace onShowCustomView delayed PiP enter.
    text = replace_regex(
        text,
        r"webView\.postDelayed\(\{\s*pendingPipAfterPlayerFullscreen\s*=\s*false\s*enterPipIfPossible\(\)\s*\},\s*220\)",
        '''postDelayedSafely(220) {
                    pendingPipAfterPlayerFullscreen = false
                    enterPipIfPossible()
                }''',
        "replace fullscreen postDelayed",
    )

    # 15. Replace F-key delayed callback.
    text = replace_regex(
        text,
        r"webView\.postDelayed\(\{\s*sendFKeyToWebView\(\)\s*waitForPlayerFullscreenAfterHotkey\(\)\s*\},\s*80\)",
        '''postDelayedSafely(80) {
            sendFKeyToWebView()
            waitForPlayerFullscreenAfterHotkey()
        }''',
        "replace F-key postDelayed",
    )

    # 16. Harden VideoStateBridge.update input.
    if "Rejected oversized video state JSON" not in text:
        text = replace_regex(
            text,
            r"(@JavascriptInterface\s*fun update\(json: String\)\s*\{\s*)runOnUiThread",
            r'''\1if (json.length > MAX_VIDEO_STATE_JSON_LENGTH) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Rejected oversized video state JSON")
            }
            return
        }

        runOnUiThread''',
            "add JSON length guard",
        )

    # 17. Do not log raw JSON in release or on parse failure.
    text = replace_regex(
        text,
        r'Log\.w\(TAG,\s*"Failed parsing video state: \$json",\s*e\)',
        '''if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Failed parsing video state", e)
                    }''',
        "remove raw JSON logging",
    )

    # 18. Replace fullscreen timeout delayed callback.
    text = replace_regex(
        text,
        r"webView\.postDelayed\(\{\s*if \(pendingPipAfterPlayerFullscreen && customView == null\) \{\s*pendingPipAfterPlayerFullscreen = false\s*showSystemBars\(\)\s*ViewCompat\.requestApplyInsets\(webContainer\)\s*updatePipButtonVisibility\(\)\s*Log\.w\(TAG,\s*\"Player fullscreen click did not produce WebView fullscreen; not entering PiP\"\)\s*\}\s*\},\s*2500\)",
        '''postDelayedSafely(2500) {
                    if (pendingPipAfterPlayerFullscreen && customView == null) {
                        pendingPipAfterPlayerFullscreen = false
                        showSystemBars()
                        ViewCompat.requestApplyInsets(webContainer)
                        updatePipButtonVisibility()
                        Log.w(TAG, "Player fullscreen click did not produce WebView fullscreen; not entering PiP")
                    }
                }''',
        "replace fullscreen timeout postDelayed",
    )

    # 19. Truncate player fullscreen failure reason.
    if "val safeReason = reason.take(200)" not in text:
        text = replace_regex(
            text,
            r"(fun onPlayerFullscreenFailed\(reason: String\)\s*\{\s*runOnUiThread\s*\{\s*)pendingPipAfterPlayerFullscreen = false",
            r"\1val safeReason = reason.take(200)\n                pendingPipAfterPlayerFullscreen = false",
            "add safeReason",
        )
        text = replace_regex(
            text,
            r'Log\.w\(TAG,\s*"Player fullscreen failed; not entering PiP: \$reason"\)',
            'Log.w(TAG, "Player fullscreen failed; not entering PiP: $safeReason")',
            "use safeReason",
        )

    if text == original:
        print("No changes needed; MainActivity.kt already appears patched.")
        return

    backup = path.with_suffix(path.suffix + ".bak")
    backup.write_text(original, encoding="utf-8")
    path.write_text(text, encoding="utf-8")

    print(f"Patched {path}")
    print(f"Backup written to {backup}")
    print()
    print("Now run:")
    print("  ./gradlew testDebugUnitTest")
    print("  ./gradlew lintDebug")
    print("  ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
