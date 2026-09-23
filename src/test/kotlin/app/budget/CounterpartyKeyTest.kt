package app.budget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CounterpartyKeyTest {

    @Test
    fun `an IBAN wins over everything else and is normalised`() {
        val raw = """{"counterIban":"ua21 3223 1300 0002 6007 2335 6600 1","counterName":"Петренко Іван"}"""
        val found = counterpartyOf(raw, "На картку")!!
        assertEquals("iban:UA213223130000026007233566001", found.key)
        assertEquals(CounterpartySource.IBAN, found.source)
        assertTrue(found.exact)
        assertEquals("Петренко Іван", found.displayName)
    }

    @Test
    fun `edrpou comes next`() {
        val found = counterpartyOf("""{"counterEdrpou":"12345678"}""", "ФОП Коваленко")!!
        assertEquals("edrpou:12345678", found.key)
        assertEquals(CounterpartySource.EDRPOU, found.source)
    }

    @Test
    fun `a masked card number in the description is an exact key`() {
        val found = counterpartyOf("{}", "На картку 4441**1234")!!
        assertEquals("card:4441**1234", found.key)
        assertEquals(CounterpartySource.CARD, found.source)
        assertTrue(found.exact)
    }

    @Test
    fun `a name is a probable key, never an exact one`() {
        val found = counterpartyOf("""{"counterName":"  Петренко   Іван "}""", "На картку")!!
        assertEquals("name:петренко іван", found.key)
        assertEquals(CounterpartySource.NAME, found.source)
        assertTrue(!found.exact)
        assertEquals("Петренко Іван", found.displayName)
    }

    @Test
    fun `an unrecognised payload yields no key at all`() {
        assertNull(counterpartyOf("{}", "На чорну картку"))
        assertNull(counterpartyOf("not json", "На картку"))
        assertNull(counterpartyOf("""{"counterName":"   "}""", "На картку"))
    }

    @Test
    fun `an explicit JSON null falls through the ladder instead of becoming a key`() {
        val found = counterpartyOf(
            """{"counterIban":null,"counterName":"Петренко Іван"}""",
            "На картку",
        )!!
        assertEquals(CounterpartySource.NAME, found.source)
        assertEquals("name:петренко іван", found.key)
    }

    @Test
    fun `a non-string value in a counterparty field does not become a key`() {
        assertNull(counterpartyOf("""{"counterIban":12345}""", "На картку"))
    }
}
