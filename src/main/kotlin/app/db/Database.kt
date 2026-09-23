package app.db

import org.jetbrains.exposed.sql.Database
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File

fun connectDb(path: String): Database {
    File(path).absoluteFile.parentFile?.mkdirs()
    val config = SQLiteConfig().apply {
        setJournalMode(SQLiteConfig.JournalMode.WAL)
        enforceForeignKeys(true)
        busyTimeout = 5000
    }
    val dataSource = SQLiteDataSource(config).apply {
        url = "jdbc:sqlite:$path"
    }
    return Database.connect(dataSource)
}
