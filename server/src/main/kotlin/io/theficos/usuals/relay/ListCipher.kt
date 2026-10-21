package io.theficos.usuals.relay

import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals stored lists so a database copy or backup alone does not reveal them. The server decrypts only in memory
 * while serving a member. Each ciphertext is bound to its list id, so it cannot be moved onto another list.
 */
class ListCipher(private val key: ByteArray) {
    init { require(key.size == 32) }
    private val random = SecureRandom()
    private fun cipher(mode: Int, list: String, nonce: ByteArray, offset: Int) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce, offset, 12))
        updateAAD("USUALS/LIST/1\u0000$list".toByteArray())
    }
    /** Returns nonce || ciphertext with a fresh random 12-byte nonce. */
    fun seal(list: String, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val sealed = nonce.copyOf(12 + plain.size + 16)
        cipher(Cipher.ENCRYPT_MODE, list, nonce, 0).doFinal(plain, 0, plain.size, sealed, 12)
        return sealed
    }
    /** A wrong key, a moved or a damaged ciphertext fails closed: the request answers 503, never an empty list. */
    fun open(list: String, sealed: ByteArray): ByteArray = try {
        require(sealed.size >= 28)
        cipher(Cipher.DECRYPT_MODE, list, sealed, 0).doFinal(sealed, 12, sealed.size - 12)
    } catch (_: GeneralSecurityException) { reject("unavailable", 503) } catch (_: IllegalArgumentException) { reject("unavailable", 503) }
    /** Refuses to serve with a key that does not open the stored lists. */
    fun check(db: Database) = db.readSnapshot { c ->
        c.query("SELECT id,projection FROM list WHERE projection IS NOT NULL LIMIT 1") { it.getObject(1).toString() to it.getBytes(2) }
            .forEach { (list, sealed) -> check(runCatching { open(list, sealed) }.isSuccess) { "list_key_mismatch" } }
    }
    /** Re-encrypts every stored list under [next] in one transaction; run it with traffic stopped. */
    fun rewrap(db: Database, next: ListCipher) = db.transaction { c ->
        c.query("SELECT id FROM list WHERE projection IS NOT NULL ORDER BY id FOR UPDATE") { it.getObject(1).toString() }.forEach { list ->
            val sealed = c.query("SELECT projection FROM list WHERE id=?", id(list)) { it.getBytes(1) }.single()
            c.execute("UPDATE list SET projection=? WHERE id=?", next.seal(list, open(list, sealed)), id(list))
        }
    }
    override fun toString() = "ListCipher([redacted])"
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()) =
            ListCipher(AiConfiguration.readKey(Path.of(requireNotNull(env["USUALS_LIST_KEY_FILE"]) { "missing_config:USUALS_LIST_KEY_FILE" })))
    }
}
