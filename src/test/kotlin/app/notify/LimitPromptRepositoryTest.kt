package app.notify

import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.withTestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LimitPromptRepositoryTest {

    @Test
    fun `an open dialog is found by the message it asked for a reply to`() = withTestDb { db ->
        val categoryId = CategoryRepository(db, ConduitMccRepository(db)).create("Їжа", "🍏", 0L, 80)
        val repo = LimitPromptRepository(db)
        val id = repo.create(categoryId, keyboardMessageId = 10L)
        repo.attachReplyMessage(id, 11L)

        val found = repo.openByReplyMessage(11L)
        assertEquals(id, found?.id)
        assertEquals(categoryId, found?.categoryId)
        assertEquals(10L, found?.keyboardMessageId)
    }

    @Test
    fun `a resolved dialog is no longer found`() = withTestDb { db ->
        val categoryId = CategoryRepository(db, ConduitMccRepository(db)).create("Їжа", "🍏", 0L, 80)
        val repo = LimitPromptRepository(db)
        val id = repo.create(categoryId, keyboardMessageId = 10L)
        repo.attachReplyMessage(id, 11L)

        assertTrue(repo.resolve(id))
        assertNull(repo.openByReplyMessage(11L))
        assertTrue(repo.listOpen().isEmpty())
    }

    /**
     * A rejected amount re-asks, and the new question takes over the dialog. Two live reply
     * targets for one dialog would let the same question be answered twice.
     */
    @Test
    fun `re-asking moves the reply target, so the superseded question answers nothing`() = withTestDb { db ->
        val categoryId = CategoryRepository(db, ConduitMccRepository(db)).create("Їжа", "🍏", 0L, 80)
        val repo = LimitPromptRepository(db)
        val id = repo.create(categoryId, keyboardMessageId = 10L)
        repo.attachReplyMessage(id, 11L)
        repo.attachReplyMessage(id, 12L)

        assertNull(repo.openByReplyMessage(11L), "the old question must stop being answerable")
        assertEquals(id, repo.openByReplyMessage(12L)?.id)
    }

    @Test
    fun `listOpen returns every unresolved dialog in creation order`() = withTestDb { db ->
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        val a = categories.create("Їжа", "🍏", 0L, 80)
        val b = categories.create("Авто", "🚗", 0L, 80)
        val repo = LimitPromptRepository(db)
        val first = repo.create(a, keyboardMessageId = 1L)
        val second = repo.create(b, keyboardMessageId = 2L)

        assertEquals(listOf(first, second), repo.listOpen().map { it.id })
    }
}
