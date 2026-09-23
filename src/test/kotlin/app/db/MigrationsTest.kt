package app.db

import app.budget.CategoryRepository
import app.budget.ConduitMccRepository
import app.withTestDb
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationsTest {
    @Test
    fun `migrations create every table and bump user_version`() = withTestDb { db ->
        transaction(db) {
            val tables = mutableListOf<String>()
            exec("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name") { rs ->
                while (rs.next()) tables += rs.getString(1)
            }
            listOf(
                "accounts", "categories", "category_mcc", "transactions",
                "webhook_events", "notification_events", "mcc_prompts", "settings", "limit_prompts",
            ).forEach { assertTrue(it in tables, "missing table $it, got $tables") }

            var version = -1
            exec("PRAGMA user_version") { rs -> if (rs.next()) version = rs.getInt(1) }
            assertEquals(5, version)
        }
    }

    @Test
    fun `running migrations twice is a no-op`() = withTestDb { db ->
        runMigrations(db)
        runMigrations(db)
        transaction(db) {
            var version = -1
            exec("PRAGMA user_version") { rs -> if (rs.next()) version = rs.getInt(1) }
            assertEquals(5, version)
        }
    }

    @Test
    fun `V2 takes a limit back out of the emoji column`() = withTestDb { db ->
        // Rows written before the form labelled its boxes: the monthly limit was typed
        // into the emoji field, so every Telegram button read "🍽25000 Ресторани та бари".
        // Rewinding user_version replays V2 over a row shaped like the ones in production.
        // Bounded to V2: withTestDb already carried this database to head, and V3's
        // CREATE TABLEs are not safe to run a second time.
        transaction(db) {
            exec(
                """
                INSERT INTO categories (name, emoji, monthly_limit_minor, threshold_pct, created_at)
                VALUES ('Ресторани та бари', '🍽25000', 2500000, 80, 0),
                       ('Авто', '🚗', 0, 80, 0)
                """.trimIndent(),
            )
            exec("PRAGMA user_version=1", explicitStatementType = StatementType.OTHER)
        }
        runMigrations(db, upTo = 2)
        transaction(db) {
            val emoji = mutableMapOf<String, String>()
            exec("SELECT name, emoji FROM categories") { rs ->
                while (rs.next()) emoji[rs.getString(1)] = rs.getString(2)
            }
            assertEquals("🍽", emoji["Ресторани та бари"])
            assertEquals("🚗", emoji["Авто"], "a clean emoji must survive untouched")
        }
    }

    @Test
    fun `wal mode and foreign keys are enabled`() = withTestDb { db ->
        transaction(db) {
            var journal = ""
            exec("PRAGMA journal_mode") { rs -> if (rs.next()) journal = rs.getString(1) }
            assertEquals("wal", journal.lowercase())

            var fk = 0
            exec("PRAGMA foreign_keys") { rs -> if (rs.next()) fk = rs.getInt(1) }
            assertEquals(1, fk)
        }
    }

    @Test
    fun `V3 adds the conduit and counterparty tables and the prompt kind column`() = withTestDb { db ->
        transaction(db) {
            val tables = mutableSetOf<String>()
            exec("SELECT name FROM sqlite_master WHERE type='table'") { rs ->
                while (rs.next()) tables += rs.getString(1)
            }
            assertTrue("conduit_mcc" in tables, tables.toString())
            assertTrue("category_counterparty" in tables, tables.toString())

            val txnColumns = mutableSetOf<String>()
            exec("PRAGMA table_info(transactions)") { rs ->
                while (rs.next()) txnColumns += rs.getString("name")
            }
            assertTrue("counterparty_key" in txnColumns, txnColumns.toString())
            assertTrue("counterparty_source" in txnColumns, txnColumns.toString())

            val promptColumns = mutableSetOf<String>()
            exec("PRAGMA table_info(mcc_prompts)") { rs ->
                while (rs.next()) promptColumns += rs.getString("name")
            }
            assertTrue("kind" in promptColumns, promptColumns.toString())
            assertTrue("reply_message_id" in promptColumns, promptColumns.toString())
        }
    }

    @Test
    fun `a database already at V2 is carried up to head without losing its rows`() {
        // Not withTestDb: that migrates to head, and V3's ALTER TABLEs cannot run twice.
        // This builds a database genuinely stopped at V2 — the state every existing
        // deployment is in — and then runs the upgrade against populated tables. Inserted
        // with raw SQL, not CategoryRepository.create: that repository's Exposed table
        // object matches HEAD's schema (counts_as_spending, added by V4), which does not
        // exist on disk until the migration below runs.
        val file = File.createTempFile("v2-upgrade", ".db").also { it.deleteOnExit() }
        val db = connectDb(file.absolutePath)
        runMigrations(db, upTo = 2)
        transaction(db) {
            exec(
                """INSERT INTO categories
                   (name, emoji, monthly_limit_minor, threshold_pct,
                    notify_warning, notify_exceeded, enabled, position, created_at)
                   VALUES ('Оренда', '🏠', 0, 80, 1, 1, 1, 0, 0)""",
            )
        }

        runMigrations(db)

        transaction(db) {
            var version = 0
            exec("PRAGMA user_version") { rs -> if (rs.next()) version = rs.getInt(1) }
            assertEquals(5, version)
        }
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        assertEquals(1, categories.list().size)
    }

    @Test
    fun `V4 adds counts_as_spending, defaulting every existing category to true`() {
        // Not withTestDb, same reasoning as the V2-to-V3 test: build a database genuinely
        // stopped at V3 — where every production deployment sits before this change ships
        // — with a real row already in it, then apply V4 over that row. Inserted with raw
        // SQL, not CategoryRepository.create: that repository's Exposed table object
        // matches HEAD's schema (it already declares counts_as_spending), which does not
        // exist on disk until the migration below runs.
        val file = File.createTempFile("v3-upgrade", ".db").also { it.deleteOnExit() }
        val db = connectDb(file.absolutePath)
        runMigrations(db, upTo = 3)
        transaction(db) {
            exec(
                """INSERT INTO categories
                   (name, emoji, monthly_limit_minor, threshold_pct,
                    notify_warning, notify_exceeded, enabled, position, created_at)
                   VALUES ('Оренда', '🏠', 0, 80, 1, 1, 1, 0, 0)""",
            )
        }

        runMigrations(db)

        transaction(db) {
            var version = 0
            exec("PRAGMA user_version") { rs -> if (rs.next()) version = rs.getInt(1) }
            assertEquals(5, version)

            val columns = mutableSetOf<String>()
            exec("PRAGMA table_info(categories)") { rs -> while (rs.next()) columns += rs.getString("name") }
            assertTrue("counts_as_spending" in columns, columns.toString())
        }
        // The category existed before V4 ran; it must come back counting as spending —
        // the whole point of defaulting the column to 1 is that this migration is
        // invisible until the owner deliberately opts a category out.
        val categories = CategoryRepository(db, ConduitMccRepository(db))
        assertTrue(categories.list().single().countsAsSpending)
    }
}
