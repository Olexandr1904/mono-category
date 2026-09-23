package app.budget

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Domain rule, not an environment setting. Never read from TZ. */
val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")

private val MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM")

fun monthKeyOf(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(KYIV).format(MONTH_FORMAT)

fun currentMonthKey(clock: Clock = Clock.system(KYIV)): String =
    Instant.now(clock).atZone(KYIV).format(MONTH_FORMAT)

/**
 * A month key this codebase is willing to parse. [YearMonth.parse] accepts far more than
 * `yyyy-MM` (negative years, six-digit years) and throws on everything else, so a raw
 * query parameter reaching [previousMonthKey] used to turn into an uncaught
 * `DateTimeParseException` and a 500. The year range keeps the arithmetic in
 * [previousMonthKey]/[nextMonthKey] away from the edges where formatting stops
 * round-tripping.
 */
private val MONTH_KEY_FORMAT = Regex("""^(19[7-9]\d|2\d{3})-(0[1-9]|1[0-2])$""")

fun isMonthKey(value: String): Boolean = MONTH_KEY_FORMAT.matches(value)

/** [raw] when it is a well-formed month key, otherwise the current month. */
fun safeMonthKey(raw: String?, clock: Clock = Clock.system(KYIV)): String =
    raw?.takeIf(::isMonthKey) ?: currentMonthKey(clock)

/** Start inclusive, end exclusive. */
fun monthRange(monthKey: String): Pair<Instant, Instant> {
    val ym = YearMonth.parse(monthKey, MONTH_FORMAT)
    val start = ym.atDay(1).atStartOfDay(KYIV).toInstant()
    val end = ym.plusMonths(1).atDay(1).atStartOfDay(KYIV).toInstant()
    return start to end
}

fun previousMonthKey(monthKey: String): String =
    YearMonth.parse(monthKey, MONTH_FORMAT).minusMonths(1).format(MONTH_FORMAT)

fun nextMonthKey(monthKey: String): String =
    YearMonth.parse(monthKey, MONTH_FORMAT).plusMonths(1).format(MONTH_FORMAT)

fun formatDay(epochSeconds: Long): String =
    LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), KYIV)
        .format(DateTimeFormatter.ofPattern("dd.MM"))

private val FULL_DAY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** "03.09.2026" — the Операції row meta line (design-handoff.md §3.4), which unlike the
 *  bare `dd.MM` of [formatDay] carries the year: a row can be visible next to rows from a
 *  different month once a rule strip keeps one alive past the active month filter. */
fun formatFullDay(epochSeconds: Long): String =
    LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), KYIV).format(FULL_DAY_FORMAT)

/** The Kyiv calendar date a transaction falls on — for day-grouping headers
 *  (design-handoff.md §3.4's "3 вересня, четвер") and nothing else numeric, hence
 *  [LocalDate] rather than a formatted string: the month/weekday names it labels are
 *  locale text and belong in [app.i18n.Copy], not here. */
fun kyivDate(epochSeconds: Long): LocalDate =
    LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), KYIV)

private val DATETIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/** "22.08 14:31" in Kyiv time — used to say when a sync ran, not just what it did. */
fun formatDateTime(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(KYIV).format(DATETIME_FORMAT)
