package io.theficos.usuals.auth

/**
 * SecretStore fake for JVM tests and previews. It follows the production redaction
 * contract so printing a test or preview object cannot expose its model key.
 */
class InMemorySecretStore(initialModelKey: String? = null) : SecretStore {

    private val lock = Any()

    private var key: String? = initialModelKey?.trim()?.takeIf { it.isNotEmpty() }

    override fun putModelKey(key: String) {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "model key must not be blank" }
        synchronized(lock) { this.key = trimmed }
    }

    override fun modelKey(): String? = synchronized(lock) { key }

    override fun clear() {
        synchronized(lock) { key = null }
    }

    override fun toString(): String = "InMemorySecretStore(model key: ${SecretStore.REDACTED})"
}
