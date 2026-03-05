(function () {
    const STORAGE_KEY = 'ska_cookie_consent_v1';

    function createBanner() {
        const existing = document.getElementById('cookie-consent-banner');
        if (existing) return;

        const banner = document.createElement('div');
        banner.id = 'cookie-consent-banner';
        banner.className = 'cookie-consent-banner';
        banner.innerHTML = `
            <div class="cookie-consent-content">
                <p>
                    האתר משתמש בעוגיות לצורך מדידה והצגת מודעות (Google AdSense).
                    בהמשך גלישה באתר אתה מסכים לשימוש זה.
                </p>
                <button id="cookie-consent-accept" class="cookie-consent-btn">הבנתי</button>
            </div>
        `;
        document.body.appendChild(banner);

        const acceptBtn = document.getElementById('cookie-consent-accept');
        if (acceptBtn) {
            acceptBtn.addEventListener('click', function () {
                try {
                    localStorage.setItem(STORAGE_KEY, 'accepted');
                } catch (_) {
                    // Ignore storage failures (private mode, etc.)
                }
                banner.remove();
            });
        }
    }

    function initCookieConsent() {
        try {
            if (localStorage.getItem(STORAGE_KEY) === 'accepted') return;
        } catch (_) {
            // If localStorage fails, still show banner.
        }
        createBanner();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initCookieConsent);
    } else {
        initCookieConsent();
    }
})();
