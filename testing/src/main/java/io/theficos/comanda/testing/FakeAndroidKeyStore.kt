package io.theficos.comanda.testing

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import java.security.spec.AlgorithmParameterSpec

/**
 * An in-memory stand-in for the `AndroidKeyStore` security provider, so that
 * [androidx.security.crypto.MasterKey] and
 * [androidx.security.crypto.EncryptedSharedPreferences] work under Robolectric.
 *
 * Robolectric gives us the Android framework classes but not the secure
 * element behind them, so `KeyStore.getInstance("AndroidKeyStore")` finds no
 * provider and every encrypted-preferences test dies in the constructor. This
 * registers two services and nothing more: a keystore that is a map, and an AES
 * key generator that ignores the Android-specific `KeyGenParameterSpec` it is
 * handed and returns a plain 256-bit key.
 *
 * What that means for the tests using it: the encryption is real (Tink does the
 * actual AES-GCM work), the key protection is not. That is the right trade for
 * a unit test, because the property under test is "the plaintext is not in the
 * file", which holds regardless of where the wrapping key lives.
 *
 * Lifted in shape from quire's test scaffolding of the same name.
 *
 * It lives in its own module, and in a plain JVM one, for two reasons. Two
 * modules need it: `:auth` tests the store directly, and any `:app` test that
 * reaches the settings screen builds one through the object graph. And it
 * touches no Android API at all, only JCA, so an Android module would buy
 * nothing. Test fixtures would have been the obvious home, but the Kotlin
 * Android plugin registers no Kotlin compilation for that source set, so the
 * file silently never compiles.
 */
object FakeAndroidKeyStore {

    @Volatile
    private var installed = false

    /** Idempotent. Call from `@Before` in any suite that touches the store. */
    fun setup() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            Security.removeProvider(PROVIDER_NAME)
            Security.insertProviderAt(FakeProvider(), 1)
            installed = true
        }
    }

    private const val PROVIDER_NAME = "AndroidKeyStore"

    private class FakeProvider : Provider(
        PROVIDER_NAME,
        1.0,
        "In-memory AndroidKeyStore for Robolectric tests",
    ) {
        init {
            putService(
                Service(
                    this, "KeyStore", PROVIDER_NAME,
                    FakeKeyStoreSpi::class.java.name, emptyList(), emptyMap(),
                ),
            )
            putService(
                Service(
                    this, "KeyGenerator", "AES",
                    FakeAesKeyGeneratorSpi::class.java.name, emptyList(), emptyMap(),
                ),
            )
        }
    }

    class FakeKeyStoreSpi : KeyStoreSpi() {

        private class Entry(val key: Key, val certs: Array<Certificate>?)

        private val store = ConcurrentHashMap<String, Entry>()

        override fun engineGetKey(alias: String, password: CharArray?): Key? = store[alias]?.key

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? =
            store[alias]?.certs

        override fun engineGetCertificate(alias: String): Certificate? =
            store[alias]?.certs?.firstOrNull()

        override fun engineGetCreationDate(alias: String): Date = Date(0L)

        override fun engineSetKeyEntry(
            alias: String,
            key: Key,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ) {
            @Suppress("UNCHECKED_CAST")
            store[alias] = Entry(key, chain as Array<Certificate>?)
        }

        override fun engineSetKeyEntry(
            alias: String,
            key: ByteArray,
            chain: Array<out Certificate>?,
        ): Unit = throw UnsupportedOperationException("raw key bytes are not supported")

        override fun engineSetCertificateEntry(alias: String, cert: Certificate) {
            store[alias] = Entry(cert.publicKey, arrayOf(cert))
        }

        override fun engineDeleteEntry(alias: String) {
            store.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(store.keys)

        override fun engineContainsAlias(alias: String): Boolean = store.containsKey(alias)

        override fun engineSize(): Int = store.size

        override fun engineIsKeyEntry(alias: String): Boolean = store[alias]?.key != null

        override fun engineIsCertificateEntry(alias: String): Boolean = false

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

        override fun engineLoad(param: KeyStore.LoadStoreParameter?) = Unit
    }

    class FakeAesKeyGeneratorSpi : KeyGeneratorSpi() {

        private var keySizeBits = 256
        private var random: SecureRandom = SecureRandom()

        override fun engineInit(random: SecureRandom) {
            this.random = random
        }

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            // Android passes a KeyGenParameterSpec here. It describes hardware
            // protection we cannot provide in a JVM test, so it is ignored on
            // purpose rather than rejected.
            if (random != null) this.random = random
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) {
            keySizeBits = keysize
            if (random != null) this.random = random
        }

        override fun engineGenerateKey(): SecretKey {
            val bytes = ByteArray(keySizeBits / 8)
            random.nextBytes(bytes)
            return SecretKeySpec(bytes, "AES")
        }
    }
}
