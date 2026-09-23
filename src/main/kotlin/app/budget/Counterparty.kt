package app.budget

import app.db.CategoryCounterparty
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

data class CounterpartyRule(val key: String, val categoryId: Long, val displayName: String)

/**
 * The recipient layer of categorization. The key is the table's primary key, so "one
 * recipient, one category" is a property of the schema, the same discipline that already
 * governs MCC bindings.
 */
class CounterpartyRepository(private val db: Database) {

    fun mapping(): Map<String, Long> = transaction(db) {
        CategoryCounterparty.selectAll()
            .associate { it[CategoryCounterparty.counterpartyKey] to it[CategoryCounterparty.categoryId] }
    }

    fun list(): List<CounterpartyRule> = transaction(db) {
        CategoryCounterparty.selectAll()
            .orderBy(CategoryCounterparty.displayName)
            .map {
                CounterpartyRule(
                    it[CategoryCounterparty.counterpartyKey],
                    it[CategoryCounterparty.categoryId],
                    it[CategoryCounterparty.displayName],
                )
            }
    }

    fun upsert(key: String, categoryId: Long, displayName: String) = transaction(db) {
        val updated = CategoryCounterparty.update({ CategoryCounterparty.counterpartyKey eq key }) {
            it[CategoryCounterparty.categoryId] = categoryId
            it[CategoryCounterparty.displayName] = displayName
        }
        if (updated == 0) {
            CategoryCounterparty.insert {
                it[counterpartyKey] = key
                it[CategoryCounterparty.categoryId] = categoryId
                it[CategoryCounterparty.displayName] = displayName
                it[createdAt] = Instant.now().epochSecond
            }
        }
        Unit
    }

    fun delete(key: String) = transaction(db) {
        CategoryCounterparty.deleteWhere { it.run { counterpartyKey eq key } }
        Unit
    }
}
