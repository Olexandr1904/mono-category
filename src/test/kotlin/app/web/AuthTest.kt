package app.web

import app.i18n.UkCopy
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.cookie
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.http.HttpHeaders
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthTest {

    private val encryptionKey = ByteArray(32) { it.toByte() }

    /**
     * What a browser sends when it submits a form this application served. Every
     * state-changing request needs it now: [installAuth] refuses an unsafe method whose
     * Origin (or Referer) does not match the host the request was addressed to. The test
     * engine's host is "localhost".
     */
    private fun HttpRequestBuilder.sameOrigin() = header(HttpHeaders.Origin, "http://localhost")

    /**
     * Every unsafe-method request now needs a CSRF token too, matching the cookie the
     * server issues on a GET that renders a form. Requires `HttpCookies` installed on the
     * client so the cookie set by this GET is both stored (for [submitForm] to send back
     * automatically) and readable here (to put the same value in the hidden field).
     */
    private suspend fun HttpClient.fetchCsrfToken(): String {
        val response = get("/login")
        // Read the Set-Cookie header directly rather than the client's cookie jar: the
        // jar's own lookup is keyed by URL and was flaky against the in-process test
        // engine's bare "localhost" host, whereas the header itself is unambiguous.
        val raw = response.headers.getAll("Set-Cookie").orEmpty().first { it.startsWith("$CSRF_COOKIE=") }
        return raw.substringAfter("$CSRF_COOKIE=").substringBefore(';')
    }

    private fun ApplicationTestBuilder.setup() {
        application {
            // secureCookies = false: the test client speaks plain HTTP over the
            // in-process engine, so a secure cookie would never be returned and
            // every login-dependent test below would fail for an unrelated reason.
            installAuth("hunter2", encryptionKey, secureCookies = false)
            routing {
                authRoutes("hunter2", encryptionKey, secureCookies = false)
                get("/") { call.respondText("dashboard") }
                get("/health") { call.respondText("ok") }
                get("/webhook/abc123") { call.respondText("mono") }
                get("/health-metrics") { call.respondText("metrics") }
                get("/logins") { call.respondText("logins") }
            }
        }
    }

    @Test
    fun `a protected page redirects to login`() = testApplication {
        setup()
        val client = createClient { followRedirects = false }
        val response = client.get("/")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/login", response.headers["Location"])
    }

    @Test
    fun `health and webhook paths stay public`() = testApplication {
        setup()
        val client = createClient { followRedirects = false }
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/webhook/abc123").status)
    }

    @Test
    fun `the wrong password does not log you in`() = testApplication {
        setup()
        val client = createClient { followRedirects = false; install(HttpCookies) }
        val token = client.fetchCsrfToken()
        val response = client.submitForm(
            "/login",
            parameters { append("password", "wrong"); append(CSRF_FIELD, token) },
        ) { sameOrigin() }
        // 401, not 200: a rejected credential is not a successful request, and the status
        // is what any log-scraping ban tool keys on. (The original returned OK from both
        // branches of an `if (error == null) OK else OK`.)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains(UkCopy.wrongPassword))
        assertEquals(HttpStatusCode.Found, client.get("/").status)
    }

    @Test
    fun `the right password grants access`() = testApplication {
        setup()
        val client = createClient { followRedirects = false; install(HttpCookies) }
        val token = client.fetchCsrfToken()
        val login = client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, token) },
        ) { sameOrigin() }
        assertEquals(HttpStatusCode.Found, login.status)
        assertEquals("/", login.headers["Location"])

        val dashboard = client.get("/")
        assertEquals(HttpStatusCode.OK, dashboard.status)
        assertEquals("dashboard", dashboard.bodyAsText())
    }

    @Test
    fun `logout revokes access`() = testApplication {
        setup()
        val client = createClient { followRedirects = false; install(HttpCookies) }
        val token = client.fetchCsrfToken()
        client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, token) },
        ) { sameOrigin() }
        assertEquals(HttpStatusCode.OK, client.get("/").status)

        client.submitForm("/logout", parameters { append(CSRF_FIELD, token) }) { sameOrigin() }
        assertEquals(HttpStatusCode.Found, client.get("/").status)
    }

    @Test
    fun `a path that merely starts with a public prefix is still protected`() = testApplication {
        setup()
        val client = createClient { followRedirects = false }
        assertEquals(HttpStatusCode.Found, client.get("/health-metrics").status)
        assertEquals(HttpStatusCode.Found, client.get("/logins").status)
    }

    @Test
    fun `the login page renders a form`() = testApplication {
        setup()
        val body = client.get("/login").bodyAsText()
        assertTrue(body.contains("<form"))
        assertTrue(body.contains("type=\"password\""))
        // Every form needs the hidden CSRF field or its POST 403s; the login form is no
        // exception even though it is rendered outside the shared page() helper.
        assertTrue(body.contains("name=\"$CSRF_FIELD\""), body)
    }

    @Test
    fun `logout requires a session, not a bare cross-origin POST`() = testApplication {
        setup()
        val client = createClient { followRedirects = false; install(HttpCookies) }
        val token = client.fetchCsrfToken()
        // A valid token and a same-origin request pass the CSRF gate; there is simply no
        // session behind them, so the gate passing must not be mistaken for being logged in.
        val response = client.submitForm("/logout", parameters { append(CSRF_FIELD, token) }) { sameOrigin() }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/login", response.headers["Location"])
    }

    // --- cross-origin (CSRF) ------------------------------------------------------

    @Test
    fun `a state-changing POST from another origin is refused`() = testApplication {
        setup()
        val client = createClient { followRedirects = false; install(HttpCookies) }
        val token = client.fetchCsrfToken()
        client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, token) },
        ) { sameOrigin() }
        assertEquals(HttpStatusCode.OK, client.get("/").status, "precondition: logged in")

        // Same session, same cookie jar, and a *valid* CSRF token — only the Origin
        // differs. This is the shape of a form auto-submitted from an attacker's page: a
        // correct token must not be enough on its own when Origin says otherwise.
        val forged = client.submitForm("/logout", parameters { append(CSRF_FIELD, token) }) {
            header(HttpHeaders.Origin, "https://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, forged.status)
        assertEquals(HttpStatusCode.OK, client.get("/").status, "the forged POST must not have logged us out")
    }

    @Test
    fun `a state-changing POST with no Origin, no Referer and no token is refused`() = testApplication {
        setup()
        val client = createClient { followRedirects = false }
        // Neither header present, and no cookie ever issued to this client: the request
        // proves nothing, browser-side or token-side.
        assertEquals(HttpStatusCode.Forbidden, client.post("/login").status)
    }

    /**
     * The exact case that was broken in production: Safari sends neither Origin nor
     * Referer on a same-origin form POST once Referrer-Policy is no-referrer
     * (SecurityHeaders.kt), so the old Origin/Referer check rejected every form in that
     * browser. A valid CSRF token must be sufficient on its own. Revert the
     * `hasValidCsrfToken` call in installAuth's gate to see this fail — before the fix it
     * fell through to the old Origin-or-Referer check, which had nothing to go on here.
     */
    @Test
    fun `a POST with no Origin and no Referer succeeds when the CSRF token is valid`() = testApplication {
        setup()
        val client = createClient { followRedirects = false; install(HttpCookies) }
        val token = client.fetchCsrfToken()
        val login = client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, token) },
        )
        assertEquals(HttpStatusCode.Found, login.status)
    }

    /**
     * The second half of the same production bug, and the half curl could not reach.
     * Chrome does send an Origin on a same-origin form POST — but its value is derived
     * from the page's Referrer-Policy, so under `no-referrer` it sends the literal string
     * `null`. The check treated that as a foreign origin and rejected every form, while
     * curl passed the same flow because it sends no Origin at all. Observed in Chrome 151
     * against the deployed app. A literal `null` must therefore be treated as absent and
     * left to the CSRF token, which a sandboxed iframe cannot read.
     */
    @Test
    fun `a POST with Origin null succeeds when the CSRF token is valid`() = testApplication {
        setup()
        val client = createClient { followRedirects = false; install(HttpCookies) }
        val token = client.fetchCsrfToken()
        val login = client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, token) },
        ) { header(HttpHeaders.Origin, "null") }
        assertEquals(HttpStatusCode.Found, login.status)
    }

    @Test
    fun `a POST with a valid token but no CSRF cookie is refused`() = testApplication {
        setup()
        val client = createClient { followRedirects = false }
        // The field alone, with nothing to compare it against, proves nothing — an
        // attacker's page can put any value it likes in a hidden field.
        val response = client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, "attacker-supplied") },
        ) { sameOrigin() }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a POST with the CSRF cookie but no token field is refused`() = testApplication {
        setup()
        val client = createClient { followRedirects = false; install(HttpCookies) }
        client.fetchCsrfToken() // cookie now sits in the jar, but the form below omits the field
        val response = client.submitForm(
            "/login",
            parameters { append("password", "hunter2") },
        ) { sameOrigin() }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a POST whose token does not match the CSRF cookie is refused`() = testApplication {
        setup()
        val client = createClient { followRedirects = false; install(HttpCookies) }
        client.fetchCsrfToken()
        val response = client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, "not-the-cookie-value") },
        ) { sameOrigin() }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `an opaque null Origin is refused when it has no CSRF cookie`() = testApplication {
        setup()
        // A sandboxed iframe or an attacker's page is what genuinely sends Origin: null
        // cross-site — and it arrives with no csrf cookie, because SameSite=Lax withholds
        // the cookie on a cross-site POST. That absence is the barrier, not the Origin
        // value: the double-submit comparison has nothing to compare against.
        //
        // Rejecting on Origin: null itself was the earlier behaviour and it was wrong.
        // Chrome derives Origin from the page's Referrer-Policy, so under no-referrer it
        // sent Origin: null on ordinary same-origin submits too, and every form in the
        // application returned 403 in a real browser. This test now pins the barrier that
        // actually holds; `a POST with Origin null succeeds when the CSRF token is valid`
        // pins the legitimate case it used to break.
        val client = createClient { followRedirects = false }
        val response = client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, "attacker-guessed") },
        ) {
            header(HttpHeaders.Origin, "null")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `the machine webhooks stay exempt from the cross-origin check`() = testApplication {
        application {
            installAuth("hunter2", encryptionKey, secureCookies = false)
            routing {
                post("/webhook/abc123") { call.respondText("mono") }
                post("/tg/updates") { call.respondText("tg") }
            }
        }
        val client = createClient { followRedirects = false }
        // Monobank and Telegram send no Origin; refusing them would disable the webhook.
        assertEquals(HttpStatusCode.OK, client.post("/webhook/abc123").status)
        assertEquals(HttpStatusCode.OK, client.post("/tg/updates").status)
    }

    // --- session lifecycle --------------------------------------------------------

    @Test
    fun `every login issues a distinct cookie`() = testApplication {
        // The regression this guards: the payload used to serialise to the constant "{}",
        // so the cookie was byte-identical forever and across installs.
        val ticking = object : Clock() {
            private var instant = Instant.parse("2026-08-21T00:00:00Z")
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant { instant = instant.plusSeconds(1); return instant }
        }
        application {
            installAuth("hunter2", encryptionKey, secureCookies = false, clock = ticking)
            routing { authRoutes("hunter2", encryptionKey, clock = ticking, secureCookies = false) }
        }
        val client = createClient { followRedirects = false; install(HttpCookies) }
        val token = client.fetchCsrfToken()
        val first = client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, token) },
        ) { sameOrigin() }
        val second = client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, token) },
        ) { sameOrigin() }
        assertNotEquals(first.headers["Set-Cookie"], second.headers["Set-Cookie"])
    }

    @Test
    fun `a captured cookie stops working after logout`() = testApplication {
        val epoch = SessionEpoch.inMemory()
        application {
            installAuth("hunter2", encryptionKey, secureCookies = false, epoch = epoch)
            routing {
                authRoutes("hunter2", encryptionKey, epoch = epoch, secureCookies = false)
                get("/") { call.respondText("dashboard") }
            }
        }
        val client = createClient { followRedirects = false; install(HttpCookies) }
        val token = client.fetchCsrfToken()
        client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, token) },
        ) { sameOrigin() }
        val captured = client.get("/login").call.request.headers[HttpHeaders.Cookie]

        client.submitForm("/logout", parameters { append(CSRF_FIELD, token) }) { sameOrigin() }

        // Replay the exact cookie an attacker would have copied off the machine.
        val replay = createClient { followRedirects = false }
        val response = replay.get("/") { header(HttpHeaders.Cookie, captured!!) }
        assertEquals(HttpStatusCode.Found, response.status, "logout must revoke the credential, not just forget it")
        assertEquals("/login", response.headers["Location"])
    }

    @Test
    fun `a cookie issued under the old password stops working after a password change`() {
        // Two separate applications, one cookie: minted under "hunter2", then presented to
        // a deployment whose ADMIN_PASSWORD has since been rotated. testApplication cannot
        // nest, so the credential is carried across in a local.
        var minted: String? = null
        testApplication {
            application {
                installAuth("hunter2", encryptionKey, secureCookies = false)
                routing { authRoutes("hunter2", encryptionKey, secureCookies = false) }
            }
            val client = createClient { followRedirects = false; install(HttpCookies) }
            val token = client.fetchCsrfToken()
            val login = client.submitForm(
                "/login",
                parameters { append("password", "hunter2"); append(CSRF_FIELD, token) },
            ) { sameOrigin() }
            minted = login.headers["Set-Cookie"]!!.substringBefore(';')
        }
        val cookie = minted!!
        assertTrue(cookie.startsWith("$SESSION_COOKIE="), "precondition: a real session cookie was captured")

        // Control: against an unchanged deployment the very same cookie still works, so a
        // failure below is the password rotation and nothing else.
        testApplication {
            application {
                installAuth("hunter2", encryptionKey, secureCookies = false)
                routing {
                    authRoutes("hunter2", encryptionKey, secureCookies = false)
                    get("/") { call.respondText("dashboard") }
                }
            }
            val client = createClient { followRedirects = false }
            assertEquals(HttpStatusCode.OK, client.get("/") { header(HttpHeaders.Cookie, cookie) }.status)
        }

        testApplication {
            application {
                installAuth("a-totally-new-password", encryptionKey, secureCookies = false)
                routing {
                    authRoutes("a-totally-new-password", encryptionKey, secureCookies = false)
                    get("/") { call.respondText("dashboard") }
                }
            }
            val client = createClient { followRedirects = false }
            val response = client.get("/") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.Found, response.status, "rotating the password must revoke old sessions")
            assertEquals("/login", response.headers["Location"])
        }
    }

    @Test
    fun `a cookie older than the session lifetime is refused`() = testApplication {
        val issuedAt = Instant.parse("2026-01-01T00:00:00Z")
        val muchLater = issuedAt.plus(Duration.ofDays(31))
        val issuingClock = Clock.fixed(issuedAt, ZoneOffset.UTC)
        // The application checks the age itself; the browser's Max-Age is only a hint and
        // a replayed cookie ignores it entirely.
        application {
            installAuth("hunter2", encryptionKey, secureCookies = false, clock = Clock.fixed(muchLater, ZoneOffset.UTC))
            routing {
                authRoutes("hunter2", encryptionKey, clock = issuingClock, secureCookies = false)
                get("/") { call.respondText("dashboard") }
            }
        }
        val client = createClient { followRedirects = false; install(HttpCookies) }
        val token = client.fetchCsrfToken()
        client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, token) },
        ) { sameOrigin() }
        assertEquals(HttpStatusCode.Found, client.get("/").status)
    }

    // --- login throttling ---------------------------------------------------------

    @Test
    fun `repeated wrong passwords are throttled`() = testApplication {
        application {
            installAuth("hunter2", encryptionKey, secureCookies = false)
            routing { authRoutes("hunter2", encryptionKey, throttle = LoginThrottle(maxPerClient = 3), secureCookies = false) }
        }
        val client = createClient { followRedirects = false; install(HttpCookies) }
        val token = client.fetchCsrfToken()
        repeat(3) {
            client.submitForm(
                "/login",
                parameters { append("password", "wrong"); append(CSRF_FIELD, token) },
            ) { sameOrigin() }
        }
        val throttlePrefix = UkCopy.tooManyAttempts(0).substringBefore("0")
        val blocked = client.submitForm(
            "/login",
            parameters { append("password", "wrong"); append(CSRF_FIELD, token) },
        ) { sameOrigin() }
        assertTrue(blocked.bodyAsText().contains(throttlePrefix), blocked.bodyAsText())

        // And the throttle is not a password oracle: the correct password is refused too
        // while the window is open, so an attacker cannot use it to test candidates.
        val correct = client.submitForm(
            "/login",
            parameters { append("password", "hunter2"); append(CSRF_FIELD, token) },
        ) { sameOrigin() }
        assertTrue(correct.bodyAsText().contains(throttlePrefix), correct.bodyAsText())
    }

    // The critical fix: without a signing transformer, Ktor's client-side session cookie
    // is plaintext JSON with no authentication, so this hand-crafted cookie would have
    // logged the requester in. Comment out `transform(...)` in installAuth to see this
    // test fail — it is the one test in the suite that a forged cookie alone can defeat.
    @Test
    fun `a hand-crafted session cookie is rejected, not treated as a valid login`() = testApplication {
        setup()
        val client = createClient { followRedirects = false }
        val response = client.get("/") {
            cookie(SESSION_COOKIE, """{"loggedIn":true}""")
        }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/login", response.headers["Location"])
    }
}
