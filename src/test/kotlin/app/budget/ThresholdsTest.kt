package app.budget

import kotlin.test.Test
import kotlin.test.assertEquals

class ThresholdsTest {
    @Test
    fun `below the warning threshold nothing is crossed`() {
        assertEquals(emptyList(), thresholdsCrossed(1_100_000, 1_500_000, 80)) // 73%
    }

    @Test
    fun `at the warning threshold only the warning is crossed`() {
        assertEquals(listOf(80), thresholdsCrossed(1_200_000, 1_500_000, 80)) // exactly 80%
    }

    @Test
    fun `at the limit both thresholds are crossed`() {
        assertEquals(listOf(80, 100), thresholdsCrossed(1_500_000, 1_500_000, 80))
    }

    @Test
    fun `over the limit both thresholds are crossed`() {
        assertEquals(listOf(80, 100), thresholdsCrossed(1_024_000, 1_000_000, 80))
    }

    @Test
    fun `no limit means nothing is crossed`() {
        assertEquals(emptyList(), thresholdsCrossed(900_000, 0, 80))
    }

    @Test
    fun `negative spending crosses nothing`() {
        assertEquals(emptyList(), thresholdsCrossed(-200_000, 1_000_000, 80))
    }

    @Test
    fun `a warning threshold of 100 is not reported twice`() {
        assertEquals(listOf(100), thresholdsCrossed(1_000_000, 1_000_000, 100))
    }
}
