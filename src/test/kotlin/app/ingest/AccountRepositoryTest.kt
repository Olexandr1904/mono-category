package app.ingest

import app.mono.MonoAccount
import app.withTestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountRepositoryTest {

    private fun account(id: String, currencyCode: Int = 980) = MonoAccount(id = id, currencyCode = currencyCode)

    @Test
    fun `a brand-new account defaults to active`() = withTestDb { db ->
        val repo = AccountRepository(db)
        repo.replaceAll(listOf(account("acc-1")))
        assertTrue(repo.list().single().active)
        assertEquals(listOf("acc-1"), repo.activeIds())
    }

    @Test
    fun `replaceAll preserves an active flag the owner already switched off`() = withTestDb { db ->
        val repo = AccountRepository(db)
        repo.replaceAll(listOf(account("acc-1"), account("acc-2")))
        repo.setActive(setOf("acc-2")) // acc-1 switched off

        // A refresh from Monobank must not silently re-enable acc-1.
        repo.replaceAll(listOf(account("acc-1"), account("acc-2")))

        val byId = repo.list().associateBy { it.id }
        assertFalse(byId.getValue("acc-1").active, "a refresh must not resurrect a deactivated account")
        assertTrue(byId.getValue("acc-2").active)
        assertEquals(listOf("acc-2"), repo.activeIds())
    }

    @Test
    fun `replaceAll defaults a genuinely new account to active even when others are known`() = withTestDb { db ->
        val repo = AccountRepository(db)
        repo.replaceAll(listOf(account("acc-1")))
        repo.setActive(emptySet()) // acc-1 off

        repo.replaceAll(listOf(account("acc-1"), account("acc-2")))

        val byId = repo.list().associateBy { it.id }
        assertFalse(byId.getValue("acc-1").active)
        assertTrue(byId.getValue("acc-2").active, "an account never seen before must start active")
    }

    @Test
    fun `an account switched off stays off after Monobank stops returning it`() = withTestDb { db ->
        val repo = AccountRepository(db)
        repo.replaceAll(listOf(account("acc-1"), account("acc-2")))
        repo.setActive(setOf("acc-2")) // acc-1 switched off

        // acc-1 leaves the token's scope entirely — Monobank no longer returns it.
        repo.replaceAll(listOf(account("acc-2")))
        repo.replaceAll(listOf(account("acc-2"))) // and again, any number of times

        val byId = repo.list().associateBy { it.id }
        assertTrue("acc-1" in byId, "a switched-off account must not be deleted just because it stopped being returned")
        assertFalse(byId.getValue("acc-1").active, "an account switched off must stay off even after leaving Monobank's response")
        assertEquals(listOf("acc-2"), repo.activeIds())
    }

    @Test
    fun `setActive turns off every account not in the set`() = withTestDb { db ->
        val repo = AccountRepository(db)
        repo.replaceAll(listOf(account("acc-1"), account("acc-2"), account("acc-3")))
        repo.setActive(setOf("acc-2"))
        val byId = repo.list().associateBy { it.id }
        assertFalse(byId.getValue("acc-1").active)
        assertTrue(byId.getValue("acc-2").active)
        assertFalse(byId.getValue("acc-3").active)
    }
}
