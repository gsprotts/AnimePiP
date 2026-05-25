(function () {
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