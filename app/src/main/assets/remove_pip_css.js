(function () {
    var style = document.getElementById('__cv_pip_contain_style');
    if (style) style.remove();

    document.querySelectorAll('video[data-cv-pip-contain="1"]').forEach(function (v) {
        v.removeAttribute('data-cv-pip-contain');
    });

    window.__pipCss = undefined;

    return "pip-contain-css-removed";
})();