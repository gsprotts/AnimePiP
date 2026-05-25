(function () {
    if (window.__bannerHiderInstalled) return;
    window.__bannerHiderInstalled = true;

    var SELECTORS = [
        '.app-banner', '.open-app-btn',
        '[class*="AppBanner"]', '[class*="AppRedirect"]',
        '[class*="MobileAppBanner"]', '.erc-mobile-app-banner',
        '.mobile-app-prompt', '.vilos-mobile-app-banner'
    ];

    function hide() {
        SELECTORS.forEach(function (sel) {
            document.querySelectorAll(sel).forEach(function (el) {
                if (el && el.style) {
                    el.style.setProperty('display', 'none', 'important');
                    el.style.setProperty('visibility', 'hidden', 'important');
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