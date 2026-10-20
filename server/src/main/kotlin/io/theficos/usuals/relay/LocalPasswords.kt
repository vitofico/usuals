package io.theficos.usuals.relay

import com.google.crypto.tink.subtle.Random
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Version 1 follows the OWASP PBKDF2-HMAC-SHA256 work factor without custom primitives. */
internal object LocalPasswords {
    const val ITERATIONS = 600000
    private val dummySalt = Random.randBytes(32)
    private val dummyHash = Random.randBytes(32)
    fun username(value: String): String {
        val normalized = value.lowercase(Locale.ROOT)
        expect(normalized.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}")))
        return normalized
    }
    fun validate(password: String) {
        expect(password.length <= 256)
        var index = 0
        while (index < password.length) {
            val char = password[index]
            expect(!char.isISOControl() && !char.isLowSurrogate())
            if (char.isHighSurrogate()) { expect(index + 1 < password.length && password[index + 1].isLowSurrogate()); index++ }
            index++
        }
        expect(password.codePointCount(0, password.length) in 15..128)
    }
    fun salt(): ByteArray = Random.randBytes(32)
    fun hash(password: String, salt: ByteArray): ByteArray {
        val characters = password.toCharArray()
        val spec = PBEKeySpec(characters, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); characters.fill('\u0000') }
    }
    fun matches(password: String, salt: ByteArray?, expected: ByteArray?): Boolean {
        expect(password.length <= 256, "unauthenticated", 401)
        val valid = try { validate(password); true } catch (_: RelayError) { false }
        val actual = hash(if (valid) password else "invalid synthetic credential", if (valid) salt ?: dummySalt else dummySalt)
        val equal = MessageDigest.isEqual(actual, if (valid) expected ?: dummyHash else dummyHash)
        return equal && valid && salt != null && expected != null
    }
}
