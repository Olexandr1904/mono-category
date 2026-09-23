package app.web

import io.ktor.http.Cookie
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.util.AttributeKey
import kotlinx.html.FORM
import kotlinx.html.hiddenInput
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

/**
 * Double-submit CSRF defence. [isSameOriginRequest] (now the narrower Origin-only check
 * in Auth.kt) depended entirely on a header the browser chooses to send, and Safari sends
 * neither Origin nor Referer on a same-origin form POST once Referrer-Policy is
 * `no-referrer` — which is how the owner got locked out. A token the server issues and
 * the form echoes back does not depend on browser header policy at all.
 *
 * The token is a random opaque value, not a signed payload: it carries no fields, so
 * there is nothing for a signature to protect against tampering — only unpredictability
 * matters, and double-submit's security already rests on an attacker being unable to
 * *read* the cookie (httpOnly denies script access, and a cross-site request cannot see
 * another origin's cookies at all), not on the server being able to verify authenticity
 * of a value it never trusted blindly in the first place. Comparing the cookie to the
 * submitted field verbatim (constant-time) is therefore enough.
 */
const val CSRF_COOKIE = "csrf"

/** Hidden field name every form must carry; see [csrfField]. */
const val CSRF_FIELD = "csrf_token"

private const val CSRF_TOKEN_BYTES = 32
private val secureRandom = SecureRandom()

private fun generateCsrfToken(): String {
    val bytes = ByteArray(CSRF_TOKEN_BYTES)
    secureRandom.nextBytes(bytes)
    return HexFormat.of().formatHex(bytes)
}

/**
 * Returns the CSRF token for this request, issuing a fresh cookie if none is present yet.
 * Call this from every GET handler that renders a page containing a form, before writing
 * the response, so the value embedded in the HTML (via [csrfField]) matches the cookie
 * the browser is about to receive. `secureCookies` mirrors the flag [installAuth] already
 * applies to the session cookie, so the two travel under the same rule in production and
 * in the plain-HTTP test/dev setup alike.
 */
fun ApplicationCall.csrfToken(secureCookies: Boolean): String {
    request.cookies[CSRF_COOKIE]?.takeIf { it.isNotBlank() }?.let { return it }
    val token = generateCsrfToken()
    response.cookies.append(
        Cookie(
            name = CSRF_COOKIE,
            value = token,
            path = "/",
            httpOnly = true,
            secure = secureCookies,
            extensions = linkedMapOf("SameSite" to "Lax"),
        ),
    )
    return token
}

/** Embeds the token from [csrfToken] as a hidden field. Every form needs one — miss it and that action 403s. */
fun FORM.csrfField(token: String) {
    hiddenInput(name = CSRF_FIELD) { value = token }
}

/**
 * The parsed body of an unsafe-method request, cached from the parse [installAuth]'s
 * gate already performed. A request body can only be read once without the DoubleReceive
 * plugin, which this project does not pull in for a single field on a handful of routes;
 * route handlers call [formParameters] instead of `call.receiveParameters()` directly so
 * the gate's read is reused rather than double-consuming the body.
 *
 * Falls back to parsing fresh when nothing is cached — the gate only runs when
 * [installAuth] is installed, so a route under test in isolation (no auth pipeline) still
 * works exactly as before.
 */
private val CsrfParametersKey = AttributeKey<Parameters>("csrf-parsed-parameters")

suspend fun ApplicationCall.formParameters(): Parameters =
    attributes.getOrNull(CsrfParametersKey) ?: receiveParameters()

/**
 * Parses the body once, stashes it for [formParameters], and reports whether the
 * `csrf_token` field it contains matches the [CSRF_COOKIE] cookie. Called from
 * [installAuth]'s gate for every unsafe-method, non-exempt request.
 */
internal suspend fun ApplicationCall.hasValidCsrfToken(): Boolean {
    val params = runCatching { receiveParameters() }.getOrElse { Parameters.Empty }
    attributes.put(CsrfParametersKey, params)
    val cookie = request.cookies[CSRF_COOKIE]?.takeIf { it.isNotBlank() } ?: return false
    val field = params[CSRF_FIELD]?.takeIf { it.isNotBlank() } ?: return false
    return MessageDigest.isEqual(cookie.toByteArray(Charsets.UTF_8), field.toByteArray(Charsets.UTF_8))
}
