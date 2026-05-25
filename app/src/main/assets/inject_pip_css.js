(function () {
    var v = window.__getBestVideo && window.__getBestVideo();
    if (!v) return "no-video";

    document.querySelectorAll('video[data-cv-pip-contain="1"]').forEach(function (old) {
        old.removeAttribute('data-cv-pip-contain');
    });

    v.setAttribute('data-cv-pip-contain', '1');

    var style = document.getElementById('__cv_pip_contain_style');
    if (!style) {
        style = document.createElement('style');
        style.id = '__cv_pip_contain_style';
        document.head.appendChild(style);
    }

    window.__pipCss || "";
    return "pip-contain-css-applied-strong";
})();