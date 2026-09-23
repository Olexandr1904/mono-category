package app.i18n

import kotlin.math.abs

/**
 * Ukrainian's three-way plural, for the few strings where the number has to stand next to
 * (or directly above) its noun and the `"операцій: N"` dodge used everywhere else in
 * [UkCopy] is not available — see the 2026-09-22 production walkthrough, finding 4, where
 * the Категорії summary printed "2 категорій".
 *
 * [one] for 1, 21, 31…; [few] for 2–4, 22–24…; [many] for 0, 5–20, 25–30…  The 11–14 band
 * is what makes a naive `n % 10` wrong: 11 takes [many], not [one].
 *
 * A genitive context ("з N категорій") uses the same function with the genitive singular as
 * [one] and the genitive plural as both [few] and [many].
 */
fun ukPlural(n: Long, one: String, few: String, many: String): String {
    val magnitude = abs(n)
    if (magnitude % 100 in 11..14) return many
    return when (magnitude % 10) {
        1L -> one
        2L, 3L, 4L -> few
        else -> many
    }
}

/** [Copy]'s counts are a mix of `Int` and `Long`; this keeps the call sites free of casts. */
fun ukPlural(n: Int, one: String, few: String, many: String): String =
    ukPlural(n.toLong(), one, few, many)
