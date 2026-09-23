package app.web

import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * The policy is only this tight because the UI carries no inline JavaScript: the two
 * handlers that used to be `onclick`/`onchange` attributes now live in
 * `/static/app.js` and attach by `data-` attribute. Reintroducing an inline handler
 * would silently stop working under `script-src 'self'` — which is the intended
 * pressure, since the `onclick` was where a category name could break out of a JS
 * string literal.
 *
 * `default-src 'none'` means anything not named here is denied outright, including
 * `connect-src`, so an injected script has no straightforward exfiltration channel.
 */
private const val CSP = "default-src 'none'; " +
    "script-src 'self'; " +
    "style-src 'self'; " +
    "img-src 'self' data:; " +
    // The self-hosted IBM Plex .woff2 files under /static/fonts — without this,
    // default-src 'none' blocks every @font-face src and the browser falls back
    // silently, no console error, no visible sign anything is wrong.
    "font-src 'self'; " +
    "form-action 'self'; " +
    "base-uri 'none'; " +
    "frame-ancestors 'none'"

/**
 * [hsts] is off in local development: a Strict-Transport-Security header served over
 * plain HTTP is ignored, but one served from `localhost` during a single HTTPS
 * experiment would pin the developer's browser to HTTPS for localhost for a year.
 */
fun Application.installSecurityHeaders(hsts: Boolean = true) {
    intercept(ApplicationCallPipeline.Setup) {
        val headers = call.response.headers
        headers.append("X-Content-Type-Options", "nosniff")
        headers.append("X-Frame-Options", "DENY")
        // same-origin, deliberately not no-referrer. The concern that motivated no-referrer
        // still holds — this application puts flash and error text in the query string (see
        // redirectWith), and that must never travel off-origin — and same-origin already
        // guarantees it: no referrer at all is sent cross-origin.
        //
        // What no-referrer additionally did was break every form in the app. A browser
        // derives the Origin header from the referrer policy, so under no-referrer Chrome
        // posts Origin: null even on a plain same-origin submit, and the cross-origin check
        // rejected it. Confirmed in Chrome 151 against the deployed app; curl never showed
        // it, because curl sends no Origin at all.
        headers.append("Referrer-Policy", "same-origin")
        headers.append("Content-Security-Policy", CSP)
        if (hsts) headers.append("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    }
}
