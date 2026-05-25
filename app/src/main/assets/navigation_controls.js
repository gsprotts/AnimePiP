(async function () {
    function sleep(ms) {
        return new Promise(function (resolve) {
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
        } catch (e) { }

        try {
            var rect = el.getBoundingClientRect();
            var x = rect.left + rect.width / 2;
            var y = rect.top + rect.height / 2;

            ["pointerdown", "mousedown", "pointerup", "mouseup", "click"].forEach(function (type) {
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
        } catch (e) { }
    }

    function findButton(root, wanted, unwanted) {
        var candidates = Array.from(root.querySelectorAll(
            'button, [role="button"], [aria-label], [title], [data-testid], [data-test-id]'
        )).filter(visible);

        var best = null;
        var bestScore = 0;

        candidates.forEach(function (el) {
            var t = textOf(el);

            if (!t) return;

            for (var i = 0; i < unwanted.length; i++) {
                if (t.indexOf(unwanted[i]) !== -1) {
                    return;
                }
            }

            var score = 0;

            wanted.forEach(function (word) {
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

            ["keydown", "keyup"].forEach(function (type) {
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