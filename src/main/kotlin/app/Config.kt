package app

import java.util.Base64

data class Config(
    val adminPassword: String,
    val encryptionKey: ByteArray,
    val dbPath: String,
    val port: Int,
    val secureCookies: Boolean = true,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val password = env["ADMIN_PASSWORD"]
                ?: error("ADMIN_PASSWORD is required. Set it with: fly secrets set ADMIN_PASSWORD=...")
            val rawKey = env["ENCRYPTION_KEY"]
                ?: error("ENCRYPTION_KEY is required. Generate one with: openssl rand -base64 32")
            val key = Base64.getDecoder().decode(rawKey)
            require(key.size == 32) { "ENCRYPTION_KEY must decode to 32 bytes, got ${key.size}" }
            return Config(
                adminPassword = password,
                encryptionKey = key,
                dbPath = env["DB_PATH"] ?: "/data/budget.db",
                port = env["PORT"]?.toInt() ?: 8080,
                // Secure by default; local dev over plain HTTP opts out explicitly.
                secureCookies = env["DEV_INSECURE_COOKIES"] != "1",
            )
        }
    }
}
