package app.budget

/**
 * Which limit thresholds the given spending has reached.
 * Returns them in ascending order; the caller decides what to send.
 */
fun thresholdsCrossed(spentMinor: Long, limitMinor: Long, warningPct: Int): List<Int> {
    if (limitMinor <= 0 || spentMinor <= 0) return emptyList()
    val pct = percentOf(spentMinor, limitMinor)
    val crossed = mutableListOf<Int>()
    if (warningPct in 1..99 && pct >= warningPct) crossed += warningPct
    if (pct >= 100) crossed += 100
    return crossed
}
