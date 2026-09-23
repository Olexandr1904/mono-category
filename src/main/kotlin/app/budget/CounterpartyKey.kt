package app.budget

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class CounterpartySource(val code: String) {
    IBAN("iban"),
    EDRPOU("edrpou"),
    CARD("card"),
    NAME("name"),
}

/**
 * Who received the money, as far as the payload lets us tell.
 *
 * [exact] separates "this is the same account" from "this is the same string a person
 * typed". Only an exact key may link transactions without asking; a name is shown as a
 * hint and nothing more, because half the transfers in this country are signed "Іван П.".
 */
data class Counterparty(
    val key: String,
    val source: CounterpartySource,
    val displayName: String,
) {
    val exact: Boolean get() = source != CounterpartySource.NAME
}

/**
 * A masked card number as Monobank writes it into a transfer's description: four digits,
 * a run of asterisks, four digits. Deliberately narrow. Widening it to catch more transfers
 * trades a missing key — which costs one extra question — for a wrong one, which silently
 * files someone else's money.
 */
private val MASKED_CARD = Regex("""\d{4}\*+\d{4}""")

private val LOOSE_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The recipient of an outgoing transfer, or null when the payload does not identify one.
 *
 * Null is the ordinary case, not an error: for a personal card Monobank supplies neither
 * counterIban nor counterEdrpou, and only some descriptions carry a masked card number.
 * Callers must handle "no key" as normal — the bot still asks, it just cannot offer to
 * remember the answer.
 */
fun counterpartyOf(rawJson: String, description: String): Counterparty? {
    val fields = runCatching { LOOSE_JSON.parseToJsonElement(rawJson).jsonObject }.getOrNull()
    // isString rejects both JsonNull and bare numbers/booleans in one check — a JSON `null`
    // or a `true` written into one of these fields must fall through the ladder, not be
    // stringified into a fabricated key ("null", "true").
    fun field(name: String): String? = fields?.get(name)
        ?.let { runCatching { it.jsonPrimitive }.getOrNull() }
        ?.takeIf { it.isString }
        ?.content
        ?.trim()?.takeIf { it.isNotEmpty() }

    val name = field("counterName")
    val display = name ?: description.trim()

    field("counterIban")?.let { iban ->
        return Counterparty("iban:${iban.filterNot { it.isWhitespace() }.uppercase()}", CounterpartySource.IBAN, display)
    }
    field("counterEdrpou")?.let { edrpou ->
        val digits = edrpou.filter { it.isDigit() }
        if (digits.isNotEmpty()) return Counterparty("edrpou:$digits", CounterpartySource.EDRPOU, display)
    }
    MASKED_CARD.find(description)?.let { match ->
        return Counterparty("card:${match.value}", CounterpartySource.CARD, display)
    }
    if (name != null) {
        val normalised = name.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
        return Counterparty("name:$normalised", CounterpartySource.NAME, name.split(Regex("""\s+""")).joinToString(" "))
    }
    return null
}
