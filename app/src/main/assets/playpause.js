(function () {
    var v = window.__getBestVideo && window.__getBestVideo();
    if (!v) return "no-video";

    if (v.paused) {
        v.play();
    } else {
        v.pause();
    }

    setTimeout(function () {
        if (window.__cvReportVideoState) {
            window.__cvReportVideoState();
        }
    }, 100);

    return v.paused ? "pause" : "play";
})()