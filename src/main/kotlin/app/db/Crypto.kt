package app.db

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM. Encoded form is base64(nonce | ciphertext+tag). */
class Crypto(key: ByteArray) {
    init { require(key.size == 32) { "key must be 32 bytes, got ${key.size}" } }

    private val secretKey = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(nonce + encrypted)
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size > NONCE_BYTES) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, NONCE_BYTES)),
        )
        return String(cipher.doFinal(bytes.copyOfRange(NONCE_BYTES, bytes.size)), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
