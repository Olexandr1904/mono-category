package app.db

import app.withTestDb
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRepositoryTest {
    private val crypto = Crypto(ByteArray(32) { it.toByte() })

    @Test
    fun `missing key returns null`() = withTestDb { db ->
        assertNull(SettingsRepository(db, crypto).get("nope"))
    }

    @Test
    fun `plain value roundtrips and is stored as-is`() = withTestDb { db ->
        val repo = SettingsRepository(db, crypto)
        repo.set(SettingKeys.DEFAULT_THRESHOLD_PCT, "80")
        assertEquals("80", repo.get(SettingKeys.DEFAULT_THRESHOLD_PCT))
        transaction(db) {
            var stored = ""
            exec("SELECT value FROM settings WHERE key='${SettingKeys.DEFAULT_THRESHOLD_PCT}'") { rs ->
                if (rs.next()) stored = rs.getString(1)
            }
            assertEquals("80", stored)
        }
    }

    @Test
    fun `encrypted value roundtrips but is not stored in the clear`() = withTestDb { db ->
        val repo = SettingsRepository(db, crypto)
        repo.set(SettingKeys.MONO_TOKEN, "uToken123", encrypted = true)
        assertEquals("uToken123", repo.get(SettingKeys.MONO_TOKEN))
        transaction(db) {
            var stored = ""
            exec("SELECT value FROM settings WHERE key='${SettingKeys.MONO_TOKEN}'") { rs ->
                if (rs.next()) stored = rs.getString(1)
            }
            assertNotEquals("uToken123", stored)
        }
    }

    @Test
    fun `set overwrites an existing key`() = withTestDb { db ->
        val repo = SettingsRepository(db, crypto)
        repo.set(SettingKeys.MONO_TOKEN, "first", encrypted = true)
        repo.set(SettingKeys.MONO_TOKEN, "second", encrypted = true)
        assertEquals("second", repo.get(SettingKeys.MONO_TOKEN))
    }

    @Test
    fun `delete removes the key and isSet reflects it`() = withTestDb { db ->
        val repo = SettingsRepository(db, crypto)
        repo.set(SettingKeys.PAIRING_CODE, "A7F3K2")
        assertTrue(repo.isSet(SettingKeys.PAIRING_CODE))
        repo.delete(SettingKeys.PAIRING_CODE)
        assertFalse(repo.isSet(SettingKeys.PAIRING_CODE))
        assertNull(repo.get(SettingKeys.PAIRING_CODE))
    }
}
