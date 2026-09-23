package app

import app.db.connectDb
import app.db.runMigrations
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files

fun withTestDb(block: (Database) -> Unit) {
    val dir = Files.createTempDirectory("budget-test")
    val file = dir.resolve("test.db")
    try {
        val db = connectDb(file.toString())
        runMigrations(db)
        block(db)
    } finally {
        dir.toFile().deleteRecursively()
    }
}
