package app.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CryptoTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `roundtrip returns the original text`() {
        val crypto = Crypto(key)
        val secret = "uXXXtokenFromMonobank123"
        assertEquals(secret, crypto.decrypt(crypto.encrypt(secret)))
    }

    @Test
    fun `same plaintext encrypts to different ciphertext each time`() {
        val crypto = Crypto(key)
        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"))
    }

    @Test
    fun `a different key cannot decrypt`() {
        val encoded = Crypto(key).encrypt("secret")
        val other = Crypto(ByteArray(32) { (it + 1).toByte() })
        assertFailsWith<Exception> { other.decrypt(encoded) }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val crypto = Crypto(key)
        val encoded = crypto.encrypt("secret")
        val tampered = encoded.dropLast(2) + if (encoded.endsWith("AA")) "BB" else "AA"
        assertFailsWith<Exception> { crypto.decrypt(tampered) }
    }
}
