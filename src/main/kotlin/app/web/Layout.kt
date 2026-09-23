package app.web

import app.i18n.Copy
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import kotlinx.html.*

private data class NavItem(val href: String, val key: String, val title: Copy.() -> String)

private val NAV = listOf(
    NavItem("/", "dashboard") { navDashboard },
    NavItem("/categories", "categories") { navCategories },
    NavItem("/transactions", "transactions") { navTransactions },
    NavItem("/settings", "settings") { navSettings },
)

fun HTML.page(
    copy: Copy,
    title: String,
    active: String,
    // Every page renders the shared logout form in the header, so every caller of page()
    // needs a token in hand regardless of whether its own content has forms.
    csrfToken: String,
    flash: String? = null,
    error: String? = null,
    // The mono caption on the right of the header (e.g. "Монобанк · 5 рахунків" — see
    // design-system.md's Header component). Optional and unused today: no current page
    // has an account count in hand at the point it calls page(), and the header must
    // render regardless. A page task that gathers that data can pass it here without
    // Layout.kt changing again.
    headerMeta: String? = null,
    // Named `content`, not `body`: a parameter called `body` would shadow the
    // kotlinx.html `body { }` tag builder used below.
    content: FlowContent.() -> Unit,
) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title { +copy.pageTitle(title) }
        link(rel = "stylesheet", href = "/static/app.css")
        // Replaces the inline onclick/onchange handlers; see app.js and the CSP in
        // SecurityHeaders.kt, which no longer permits inline script.
        script(src = "/static/app.js") { defer = true }
    }
    body {
        header {
            div("header-inner") {
                nav("tabs") {
                    NAV.forEach { item ->
                        a(href = item.href, classes = if (item.key == active) "tab active" else "tab") {
                            +item.title(copy)
                        }
                    }
                }
                div("header-right") {
                    headerMeta?.let { span("header-meta mono") { +it } }
                    form(action = "/logout", method = FormMethod.post, classes = "logout") {
                        csrfField(csrfToken)
                        button(type = ButtonType.submit, classes = "btn btn-outline") { +copy.logOut }
                    }
                }
            }
        }
        main("container") {
            flash?.takeIf { it.isNotBlank() }?.let { div("flash ok") { +it } }
            error?.takeIf { it.isNotBlank() }?.let { div("flash error") { +it } }
            content()
        }
    }
}

/**
 * The micro-label-over-34px-title header every page opens with (design-system.md's "Page
 * header"). [trailing] renders on the right — a [monthStepper] on the dashboard and
 * transactions pages, action buttons on categories, nothing on settings.
 */
fun FlowContent.pageHeader(microLabel: String, title: String, trailing: (DIV.() -> Unit)? = null) {
    div("page-header") {
        div("page-header-text") {
            div("micro-label") { +microLabel }
            h1 { +title }
        }
        trailing?.let { div("page-header-trailing", it) }
    }
}

/**
 * `‹ label ›` month pill. Deliberately built from `<a>`s, not buttons wired to a JS
 * handler: Monobank month data is only ever reached by GET, so these must keep navigating
 * with JavaScript disabled, exactly like the arrows they replace.
 *
 * [prevHref]/[nextHref] are null past the ends of the available month range
 * (design-handoff.md: "Arrows clamp at the ends of the available month range") — a null
 * side renders as an inert `<span>` instead of a link to nowhere.
 */
fun FlowContent.monthStepper(prevHref: String?, label: String, nextHref: String?) {
    div("month-stepper") {
        monthStepperArrow(prevHref, "‹")
        span("month-stepper-label") { +label }
        monthStepperArrow(nextHref, "›")
    }
}

private fun DIV.monthStepperArrow(href: String?, glyph: String) {
    if (href != null) {
        a(href = href, classes = "month-stepper-arrow") { +glyph }
    } else {
        span("month-stepper-arrow disabled") { +glyph }
    }
}

/**
 * Post/Redirect/Get with a one-shot message carried in the query string. [extraParams] rides
 * alongside it for cases where the following GET needs more than just the message to render
 * correctly — e.g. the transactions page's single-transaction override, which needs to know
 * which row and category the flash's offer refers to.
 */
suspend fun ApplicationCall.redirectWith(
    path: String,
    message: String,
    isError: Boolean = false,
    extraParams: Map<String, String> = emptyMap(),
    // A URL fragment must follow the query string, not precede it — appended last for
    // exactly that reason. `path` itself must never arrive carrying its own `#...` (it
    // would land ahead of `?flash=...` and everything after the `#` stops being a query
    // parameter to both the browser and the next GET). Callers get the anchor scroll
    // (item 2 of the 2026-09-03 UX review) by passing it here, not by baking it into `path`.
    fragment: String? = null,
) {
    val key = if (isError) "error" else "flash"
    val target = buildString {
        append(path)
        append(if ("?" in path) "&" else "?")
        append(key).append("=").append(message.encodeQuery())
        extraParams.forEach { (k, v) -> append("&").append(k).append("=").append(v.encodeQuery()) }
        // Finding 9 of the 2026-09 branch review: every other appended value goes through
        // encodeQuery; the fragment was the one exception, concatenated raw. Both call
        // sites build it from call.parameters["id"] (a Monobank transaction id), so this
        // is defensive rather than an active bug, but a raw `#` or `%` in a future id would
        // silently corrupt the fragment. encodeFragment reuses encodeQuery's escaping but
        // trades its "+"-for-space convention (correct for a query string, not for a
        // fragment) for the "%20" a fragment actually expects.
        fragment?.let { append("#").append(it.encodeFragment()) }
    }
    respondRedirect(target)
}

private fun String.encodeQuery(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8)

private fun String.encodeFragment(): String = encodeQuery().replace("+", "%20")
