package app.web

import app.db.SettingsRepository
import app.i18n.Copy
import app.i18n.UkCopy
import app.i18n.copy
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.respondHtml
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat

/**
 * The cookie payload used to be `{"loggedIn":true}`, which serialised to the constant
 * string `{}` — so every login produced a byte-identical cookie that stayed valid
 * forever. Nothing distinguished one login from the next, logout could not revoke it,
 * and rotating ADMIN_PASSWORD did not either; the only way to invalidate the credential
 * was to rotate ENCRYPTION_KEY, which would also destroy the encrypted Mono and Telegram
 * tokens in the database. The three fields below fix that:
 *
 * - [issuedAt] gives the server a real expiry to check. `cookie.maxAgeInSeconds` is a
 *   hint to the browser and nothing more — a replayed cookie ignores it.
 * - [epoch] is compared against a counter in the database that logout increments, which
 *   is what makes "log out" mean "revoke", here and on every other device.
 * - [fingerprint] binds the cookie to the current ADMIN_PASSWORD, so changing the
 *   password invalidates sessions issued under the old one.
 */
@Serializable
data class UserSession(
    val issuedAt: Long,
    val epoch: Long,
    val fingerprint: String,
)

const val SESSION_COOKIE = "budget_session"

/** Server-enforced session lifetime. The cookie's own Max-Age is only a browser hint. */
const val SESSION_MAX_AGE_SECONDS = 30L * 24 * 3600

/**
 * Monobank and Telegram cannot log in, so their callbacks must stay reachable.
 *
 * `/favicon.ico` is here for a different reason: the browser requests it unprompted on every
 * navigation, and without an exemption the gate redirects it to `/login`, which answers with
 * HTML the browser asked to be an icon — one console error per page load, on every screen.
 * It is safe to exempt because its handler returns 204 and reveals nothing.
 */
val PUBLIC_PREFIXES = listOf("/login", "/health", "/favicon.ico", "/webhook/", "/tg/", "/static/")

/**
 * Machine callers. They authenticate with their own shared secret (path segment for
 * Monobank, header for Telegram) and cannot send an Origin header, so they are the only
 * routes exempt from the cross-origin check in [installAuth].
 */
private val CSRF_EXEMPT_PREFIXES = listOf("/webhook/", "/tg/")

private val UNSAFE_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)

/**
 * A counter that invalidates every previously issued session cookie when bumped.
 * Backed by the settings table in production; [inMemory] keeps tests from needing one.
 */
interface SessionEpoch {
    fun current(): Long
    fun bump()

    companion object {
        fun inMemory(): SessionEpoch = object : SessionEpoch {
            @Volatile private var value = 0L
            override fun current() = value
            override fun bump() { value++ }
        }
    }
}

private fun matchesPrefix(path: String, prefixes: List<String>): Boolean =
    prefixes.any { prefix ->
        // Match on a path boundary, not a bare prefix: a plain startsWith would let a
        // future /login-history or /health-metrics slip past authentication entirely.
        val p = prefix.trimEnd('/')
        path == p || path.startsWith("$p/")
    }

/**
 * Ktor's client-side [Sessions] cookie is plaintext and unauthenticated unless a
 * transformer is attached, so without signing anyone who guesses the cookie name can
 * hand-craft it and walk straight past this interceptor. Signing (not encrypting) is
 * enough: the payload holds no secret, and a forged or tampered cookie fails
 * [SessionTransportTransformerMessageAuthentication]'s check and is treated as absent.
 */
fun Application.installAuth(
    adminPassword: String,
    encryptionKey: ByteArray,
    secureCookies: Boolean = true,
    epoch: SessionEpoch = SessionEpoch.inMemory(),
    clock: Clock = Clock.systemUTC(),
) {
    require(adminPassword.isNotBlank()) { "ADMIN_PASSWORD must not be blank" }
    // Derived, not reused verbatim: the session signing key must not equal the token
    // encryption key even though both trace back to the same secret.
    val signingKey = MessageDigest.getInstance("SHA-256").digest(encryptionKey + "session".toByteArray())
    val fingerprint = passwordFingerprint(adminPassword, encryptionKey)

    install(Sessions) {
        cookie<UserSession>(SESSION_COOKIE) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = SESSION_MAX_AGE_SECONDS
            cookie.extensions["SameSite"] = "Lax"
            cookie.secure = secureCookies
            transform(SessionTransportTransformerMessageAuthentication(signingKey))
        }
    }

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()

        // The cross-origin check runs before the session check, and covers /login too.
        //
        // Two layers, in order:
        //  1. Origin, when the browser sends one, must match the host the request
        //     actually addressed. This used to fall back to Referer when Origin was
        //     absent, but Safari sends neither on a same-origin form POST once
        //     Referrer-Policy is no-referrer — which is how the owner got locked out of
        //     every form in the app. Origin absent is therefore no longer a rejection by
        //     itself; it just means this layer has nothing to check.
        //  2. The CSRF token is always required on top, regardless of what Origin says.
        //     A double-submit token does not depend on what a particular browser chooses
        //     to send, so a browser that sends Origin gets both controls and one that
        //     does not (Safari, or any future client) still gets a real one.
        if (call.request.httpMethod in UNSAFE_METHODS && !matchesPrefix(path, CSRF_EXEMPT_PREFIXES)) {
            if (!call.originMatchesOrAbsent()) {
                call.respond(HttpStatusCode.Forbidden, "Cross-origin request rejected")
                return@intercept finish()
            }
            if (!call.hasValidCsrfToken()) {
                call.respond(HttpStatusCode.Forbidden, "Missing or invalid CSRF token")
                return@intercept finish()
            }
        }

        if (matchesPrefix(path, PUBLIC_PREFIXES)) return@intercept

        val session = call.sessions.get<UserSession>()
        if (session == null || !session.isValid(fingerprint, epoch.current(), clock)) {
            // Clear explicitly: an expired or revoked cookie is still a well-signed one,
            // so the browser would keep resending it on every request otherwise.
            if (session != null) call.sessions.clear<UserSession>()
            call.respondRedirect("/login")
            finish()
        }
    }
}

private fun UserSession.isValid(expectedFingerprint: String, currentEpoch: Long, clock: Clock): Boolean {
    if (!MessageDigest.isEqual(fingerprint.toByteArray(), expectedFingerprint.toByteArray())) return false
    if (epoch != currentEpoch) return false
    val age = clock.instant().epochSecond - issuedAt
    // A negative age means the cookie claims to have been issued in the future, which a
    // genuine one never does; allow a minute of clock skew and reject the rest.
    return age in -60..SESSION_MAX_AGE_SECONDS
}

/**
 * Ties a cookie to the password that created it without storing the password anywhere.
 * Keyed with the encryption key so the fingerprint is not a bare, offline-guessable
 * hash of the password.
 */
internal fun passwordFingerprint(adminPassword: String, encryptionKey: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(encryptionKey + "fingerprint".toByteArray() + adminPassword.toByteArray(Charsets.UTF_8))
    return HexFormat.of().formatHex(digest.copyOf(16))
}

/**
 * True when either there is no Origin header to check, or the one present points at the
 * host this request actually addressed.
 *
 * This is deliberately not a full same-origin verdict on its own any more — see the call
 * site in [installAuth]. Origin absent used to fall back to Referer, but that fallback is
 * gone: Referrer-Policy: no-referrer strips it deliberately (SecurityHeaders.kt), and the
 * CSRF token now covers the case this fallback used to paper over.
 *
 * The comparison target is the host the browser itself addressed, which an attacker
 * cannot influence for a cross-site request: the victim's browser sends our host in
 * `Host`, and its own `Origin`. Hostnames are compared without the port because cookies
 * are not port-scoped, so a port distinction would add strictness the cookie itself does
 * not honour.
 */
internal fun ApplicationCall.originMatchesOrAbsent(): Boolean {
    val expected = (request.headers["X-Forwarded-Host"] ?: request.host())
        .substringBefore(':').lowercase().ifBlank { return false }
    val origin = request.headers[HttpHeaders.Origin]?.takeIf { it.isNotBlank() } ?: return true
    // A literal "null" is treated as absent, not as foreign. It does come from a sandboxed
    // iframe or an opaque origin — but it is also what Chrome sends on an ordinary
    // same-origin form POST when the page carries a restrictive Referrer-Policy, because
    // the Origin value is derived from that policy. This application served
    // Referrer-Policy: no-referrer, so every real login arrived as Origin: null and was
    // rejected. Observed in Chrome 151 against the deployed app; curl never reproduced it,
    // since it sends no Origin at all. The policy is now same-origin (SecurityHeaders.kt),
    // but this stays defensive: nothing is lost by deferring to the CSRF token here, which
    // a sandboxed iframe cannot read — it is httpOnly and cross-origin-unreadable.
    if (origin == "null") return true
    val actual = runCatching { java.net.URI(origin).host }.getOrNull()?.lowercase()
    return actual == expected
}

fun Route.authRoutes(
    adminPassword: String,
    encryptionKey: ByteArray,
    // Nullable, not required: the language setting lives behind the same encrypted
    // settings table as everything else, but a caller that has no use for it (or is not
    // yet wired up to pass it) still gets a working, Ukrainian-by-default login page.
    settings: SettingsRepository? = null,
    epoch: SessionEpoch = SessionEpoch.inMemory(),
    throttle: LoginThrottle = LoginThrottle(),
    clock: Clock = Clock.systemUTC(),
    // Mirrors installAuth's own flag: the CSRF cookie travels under the same Secure rule
    // as the session cookie, in production and in the plain-HTTP test/dev setup alike.
    secureCookies: Boolean = true,
) {
    val fingerprint = passwordFingerprint(adminPassword, encryptionKey)
    fun currentCopy(): Copy = settings?.copy() ?: UkCopy

    get("/login") { call.respondLoginPage(currentCopy(), token = call.csrfToken(secureCookies)) }

    post("/login") {
        val copy = currentCopy()
        // A single shared password guards a full bank transaction history, and an
        // unmetered POST endpoint lets an attacker work through a dictionary as fast as
        // the network allows. The throttle turns that into a rate no dictionary survives.
        val client = call.clientKey()
        val wait = throttle.retryAfter(client)
        if (wait > 0) {
            loginLog.warn("throttled login attempt from {} ({}s remaining)", client, wait)
            return@post call.respondLoginPage(copy, token = call.csrfToken(secureCookies), error = copy.tooManyAttempts(wait))
        }

        val submitted = call.formParameters()["password"].orEmpty()
        if (constantTimeEquals(submitted, adminPassword)) {
            throttle.recordSuccess(client)
            call.sessions.set(
                UserSession(
                    issuedAt = clock.instant().epochSecond,
                    epoch = epoch.current(),
                    fingerprint = fingerprint,
                ),
            )
            call.respondRedirect("/")
        } else {
            throttle.recordFailure(client)
            loginLog.warn("failed login attempt from {}", client)
            call.respondLoginPage(copy, token = call.csrfToken(secureCookies), error = copy.wrongPassword)
        }
    }

    post("/logout") {
        // Bumping the epoch is what makes this a real logout: clearing the cookie only
        // asks one browser to forget a credential that would otherwise still work.
        epoch.bump()
        call.sessions.clear<UserSession>()
        call.respondRedirect("/login")
    }
}

/**
 * Identifies the caller for throttling. `Fly-Client-IP` is set by Fly's proxy and cannot
 * be spoofed from outside; the *last* `X-Forwarded-For` entry is the one the nearest
 * proxy appended, whereas the first is whatever the client claimed. Falling back to the
 * socket address covers local development.
 */
internal fun ApplicationCall.clientKey(): String =
    request.headers["Fly-Client-IP"]?.takeIf { it.isNotBlank() }
        ?: request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: request.local.remoteHost

private val loginLog = org.slf4j.LoggerFactory.getLogger("app.web.Auth")

private suspend fun ApplicationCall.respondLoginPage(copy: Copy, token: String, error: String? = null) {
    respondHtml(if (error == null) HttpStatusCode.OK else HttpStatusCode.Unauthorized) {
        head {
            meta(charset = "utf-8")
            title { +copy.pageTitle(copy.logIn) }
            link(rel = "stylesheet", href = "/static/app.css")
        }
        body {
            main("login") {
                h1 { +copy.appName }
                error?.let { div("flash error") { +it } }
                form(action = "/login", method = FormMethod.post) {
                    csrfField(token)
                    input(type = InputType.password, name = "password") {
                        placeholder = copy.passwordPlaceholder
                        autoFocus = true
                    }
                    button(type = ButtonType.submit) { +copy.logIn }
                }
            }
        }
    }
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
