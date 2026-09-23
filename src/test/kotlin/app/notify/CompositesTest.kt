package app.notify

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompositesTest {

    private class Claiming(
        private val claims: Boolean,
        private val log: MutableList<String>,
        private val name: String,
    ) : CallbackHandler {
        override suspend fun handle(query: TgCallbackQuery): Boolean {
            log += name
            return claims
        }
    }

    @Test
    fun `the first handler to claim a callback stops the rest`() {
        val log = mutableListOf<String>()
        val composite = CompositeCallbackHandler(
            listOf(Claiming(false, log, "a"), Claiming(true, log, "b"), Claiming(true, log, "c")),
        )
        assertTrue(runBlocking { composite.handle(TgCallbackQuery(id = "1", data = "x")) })
        assertEquals(listOf("a", "b"), log)
    }

    /** Nobody claiming is what makes the update handler answer the query itself, so a
     *  button nobody recognises still stops spinning. */
    @Test
    fun `a callback nobody claims is reported unhandled`() {
        val log = mutableListOf<String>()
        val composite = CompositeCallbackHandler(listOf(Claiming(false, log, "a")))
        assertFalse(runBlocking { composite.handle(TgCallbackQuery(id = "1", data = "x")) })
    }

    /**
     * Unlike the callback composite this one must not short-circuit: a failure re-homing
     * one service's prompts must not also swallow another service's cleanup.
     */
    @Test
    fun `every observer runs even when an earlier one throws`() {
        val ran = mutableListOf<String>()
        val composite = CompositeChatMoveObserver(
            listOf(
                object : ChatMoveObserver {
                    override suspend fun onChatMoved(from: String, to: String) {
                        ran += "first"
                        throw IllegalStateException("telegram is down")
                    }
                },
                object : ChatMoveObserver {
                    override suspend fun onChatMoved(from: String, to: String) {
                        ran += "second"
                    }
                },
            ),
        )
        runBlocking { composite.onChatMoved("1", "2") }
        assertEquals(listOf("first", "second"), ran)
    }
}
