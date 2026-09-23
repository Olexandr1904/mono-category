package app

import kotlinx.serialization.json.Json

/**
 * The single JSON configuration for every outbound API call — Monobank and Telegram alike.
 *
 * It lives here, rather than inline where the HTTP client is built, because the test suite
 * once configured its own and the two drifted. Production set `encodeDefaults = true` while
 * the test did not, so a message with no keyboard serialised `reply_markup` as null in
 * production and omitted it entirely in tests. Telegram rejects an explicit null there
 * ("Bad Request: object expected as reply markup"), so every plain text the bot sent failed
 * while the suite stayed green. Anything that builds an HTTP client must use this value.
 *
 * - `ignoreUnknownKeys`: Monobank and Telegram both add fields over time; an unknown one
 *   must not fail a response we otherwise understand.
 * - `encodeDefaults`: some request fields have defaults we genuinely want on the wire.
 * - `explicitNulls = false`: an absent optional field is omitted rather than sent as null,
 *   which is what both APIs expect.
 */
val API_JSON: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
