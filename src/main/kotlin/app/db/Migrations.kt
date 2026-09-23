package app.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction

private val MIGRATIONS = listOf(
    1 to "V1__init.sql",
    2 to "V2__strip_limits_from_emoji.sql",
    3 to "V3__transfers_and_counterparties.sql",
    4 to "V4__spending_truth.sql",
    5 to "V5__limit_prompts.sql",
)

fun runMigrations(db: Database, upTo: Int = Int.MAX_VALUE) {
    transaction(db) {
        var current = 0
        exec("PRAGMA user_version") { rs -> if (rs.next()) current = rs.getInt(1) }
        for ((version, file) in MIGRATIONS) {
            if (version <= current || version > upTo) continue
            val sql = requireNotNull(object {}.javaClass.getResource("/db/migrations/$file")) {
                "migration resource /db/migrations/$file not found"
            }.readText()
            sql.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { exec(it) }
            exec("PRAGMA user_version=$version", explicitStatementType = StatementType.OTHER)
        }
    }
}
