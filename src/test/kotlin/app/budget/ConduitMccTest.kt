package app.budget

import app.withTestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConduitMccTest {

    @Test
    fun `replaceAll stores the set and contains answers from it`() = withTestDb { db ->
        val conduit = ConduitMccRepository(db)
        conduit.replaceAll(setOf(4829, 6012))
        assertEquals(setOf(4829, 6012), conduit.list())
        assertTrue(conduit.contains(4829))

        conduit.replaceAll(setOf(4829))
        assertEquals(setOf(4829), conduit.list())
    }

    @Test
    fun `a conduit code cannot be bound to a category`() = withTestDb { db ->
        val conduit = ConduitMccRepository(db)
        val categories = CategoryRepository(db, conduit)
        val misc = categories.create("Різне", "📦", 0, 80)
        conduit.replaceAll(setOf(4829))

        assertFailsWith<ConduitMccException> { categories.addMcc(misc, 4829) }
        assertFailsWith<ConduitMccException> { categories.setMcc(misc, setOf(4829)) }
        assertTrue(categories.mccOf(misc).isEmpty())
    }
}
