package app.budget

import app.db.ConduitMcc
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * A code the owner marked as carrying no meaning of its own — 4829 (transfer) is the one
 * that motivated this. Such a code takes no part in the MCC layer of categorization, so
 * its transactions arrive uncategorized and the bot asks about each one individually.
 */
class ConduitMccRepository(private val db: Database) {

    fun list(): Set<Int> = transaction(db) {
        ConduitMcc.selectAll().map { it[ConduitMcc.mcc] }.toSet()
    }

    fun contains(mcc: Int): Boolean = transaction(db) {
        ConduitMcc.selectAll().where { ConduitMcc.mcc eq mcc }.empty().not()
    }

    /** Replaces the whole set; the form on /categories submits it as one comma-separated field. */
    fun replaceAll(mccs: Set<Int>) = transaction(db) {
        ConduitMcc.deleteAll()
        mccs.forEach { code -> ConduitMcc.insert { it[mcc] = code; it[note] = "" } }
        Unit
    }
}

/**
 * Thrown when a conduit code is about to be bound to a category. Both facts cannot hold at
 * once: a bound code would keep the MCC layer filing transfers, and the question that is
 * the whole point of marking it conduit would never be asked.
 */
class ConduitMccException(val mcc: Int) :
    RuntimeException("MCC $mcc is marked as a conduit code and cannot belong to a category")
