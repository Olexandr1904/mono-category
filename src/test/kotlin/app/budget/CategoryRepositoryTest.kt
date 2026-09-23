package app.budget

import app.withTestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryRepositoryTest {

    @Test
    fun `create and read back`() = withTestDb { db ->
        val repo = CategoryRepository(db, ConduitMccRepository(db))
        val id = repo.create("Restaurants", "🍔", 1_500_000, 80)
        val category = repo.byId(id)!!
        assertEquals("Restaurants", category.name)
        assertEquals("🍔", category.emoji)
        assertEquals(1_500_000, category.monthlyLimitMinor)
        assertEquals(80, category.thresholdPct)
        assertTrue(category.enabled)
    }

    @Test
    fun `list can exclude disabled categories`() = withTestDb { db ->
        val repo = CategoryRepository(db, ConduitMccRepository(db))
        val a = repo.create("Restaurants", "🍔", 1_500_000, 80)
        val b = repo.create("Auto", "🚗", 1_000_000, 80)
        repo.update(repo.byId(b)!!.copy(enabled = false))
        assertEquals(2, repo.list(includeDisabled = true).size)
        assertEquals(listOf(a), repo.list(includeDisabled = false).map { it.id })
    }

    @Test
    fun `setMcc replaces the whole set and mccMapping reflects it`() = withTestDb { db ->
        val repo = CategoryRepository(db, ConduitMccRepository(db))
        val id = repo.create("Restaurants", "🍔", 1_500_000, 80)
        repo.setMcc(id, setOf(5812, 5814))
        assertEquals(setOf(5812, 5814), repo.mccOf(id))
        repo.setMcc(id, setOf(5812))
        assertEquals(setOf(5812), repo.mccOf(id))
        assertEquals(mapOf(5812 to id), repo.mccMapping())
    }

    @Test
    fun `an mcc owned by another category is rejected`() = withTestDb { db ->
        val repo = CategoryRepository(db, ConduitMccRepository(db))
        val restaurants = repo.create("Restaurants", "🍔", 1_500_000, 80)
        val groceries = repo.create("Groceries", "🛒", 2_500_000, 80)
        repo.setMcc(restaurants, setOf(5812))

        val error = assertFailsWith<MccConflictException> { repo.setMcc(groceries, setOf(5812)) }
        assertEquals(5812, error.mcc)
        assertEquals(restaurants, error.ownerCategoryId)
        assertEquals("Restaurants", error.ownerName)

        assertEquals(mapOf(5812 to restaurants), repo.mccMapping())
    }

    @Test
    fun `addMcc appends without touching existing mccs`() = withTestDb { db ->
        val repo = CategoryRepository(db, ConduitMccRepository(db))
        val id = repo.create("Home", "🏠", 1_000_000, 80)
        repo.setMcc(id, setOf(5200))
        repo.addMcc(id, 5712)
        assertEquals(setOf(5200, 5712), repo.mccOf(id))
    }

    @Test
    fun `removeMcc drops a single code without touching the rest`() = withTestDb { db ->
        val repo = CategoryRepository(db, ConduitMccRepository(db))
        val id = repo.create("Home", "🏠", 1_000_000, 80)
        repo.setMcc(id, setOf(5200, 5712))
        repo.removeMcc(5712)
        assertEquals(setOf(5200), repo.mccOf(id))
    }

    @Test
    fun `removeMcc for a code nobody owns is a no-op`() = withTestDb { db ->
        val repo = CategoryRepository(db, ConduitMccRepository(db))
        val home = repo.create("Home", "🏠", 1_000_000, 80)
        repo.setMcc(home, setOf(5712))
        repo.removeMcc(9999)
        assertEquals(setOf(5712), repo.mccOf(home))
    }

    @Test
    fun `delete removes the category and its mcc rows`() = withTestDb { db ->
        val repo = CategoryRepository(db, ConduitMccRepository(db))
        val id = repo.create("Home", "🏠", 1_000_000, 80)
        repo.setMcc(id, setOf(5200))
        repo.delete(id)
        assertNull(repo.byId(id))
        assertTrue(repo.mccMapping().isEmpty())
    }
}
