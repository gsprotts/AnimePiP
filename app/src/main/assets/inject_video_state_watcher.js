(function () {
    if (window.__cvVideoWatcherInstalled) return;
    window.__cvVideoWatcherInstalled = true;

    window.__getBestVideo = function () {
        var videos = Array.from(document.querySelectorAll('video'));
        if (!videos.length) return null;

        return videos.sort(function (a, b) {
            function score(v) {
                return (!v.paused ? 100 : 0)
                    + ((v.readyState || 0) * 10)
                    + ((v.videoWidth || 0) * (v.videoHeight || 0) > 0 ? 20 : 0)
                    + ((v.offsetWidth || 0) * (v.offsetHeight || 0) > 0 ? 10 : 0);
            }
            return score(b) - score(a);
        })[0];
    };

    window.__cvReportVideoState = function () {
        var v = window.__getBestVideo();

        AndroidVideoState.update(JSON.stringify(v ? {
            playing: !v.paused && !v.ended && v.readyState > 1,
            width: v.videoWidth || 16,
            height: v.videoHeight || 9
        } : {
            playing: false,
            width: 16,
            height: 9
        }));
    };

    ['play', 'pause', 'ended', 'loadedmetadata', 'durationchange', 'resize'].forEach(function (e) {
        document.addEventListener(e, window.__cvReportVideoState, true);
    });

    if (window.__cvPollInterval) clearInterval(window.__cvPollInterval);
    window.__cvPollInterval = setInterval(window.__cvReportVideoState, 1000);
    window.__cvReportVideoState();
})();