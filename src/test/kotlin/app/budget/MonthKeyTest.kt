package app.budget

import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class MonthKeyTest {
    private fun at(iso: String): Long = Instant.parse(iso).epochSecond

    @Test
    fun `formats as year dash month`() {
        assertEquals("2026-08", monthKeyOf(at("2026-08-18T12:00:00Z")))
    }

    @Test
    fun `last second of august in Kyiv still belongs to august`() {
        // 2026-08-31 23:59:59 Kyiv (UTC+3 in summer) == 2026-08-31 20:59:59 UTC
        assertEquals("2026-08", monthKeyOf(at("2026-08-31T20:59:59Z")))
    }

    @Test
    fun `first minute of september in Kyiv belongs to september`() {
        // 2026-09-01 00:01 Kyiv == 2026-08-31 21:01 UTC — UTC still says august
        assertEquals("2026-09", monthKeyOf(at("2026-08-31T21:01:00Z")))
    }

    @Test
    fun `winter time offset is handled`() {
        // 2026-12-31 23:30 Kyiv (UTC+2 in winter) == 2026-12-31 21:30 UTC
        assertEquals("2026-12", monthKeyOf(at("2026-12-31T21:30:00Z")))
        // 2027-01-01 00:30 Kyiv == 2026-12-31 22:30 UTC
        assertEquals("2027-01", monthKeyOf(at("2026-12-31T22:30:00Z")))
    }

    @Test
    fun `currentMonthKey uses the supplied clock`() {
        val clock = Clock.fixed(Instant.parse("2026-08-31T21:01:00Z"), KYIV)
        assertEquals("2026-09", currentMonthKey(clock))
    }

    @Test
    fun `monthRange spans the whole month in Kyiv time`() {
        val (from, to) = monthRange("2026-08")
        assertEquals(Instant.parse("2026-07-31T21:00:00Z"), from)
        assertEquals(Instant.parse("2026-08-31T21:00:00Z"), to)
    }
}
