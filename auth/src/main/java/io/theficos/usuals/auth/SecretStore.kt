package io.theficos.usuals.auth

/**
 * The one place a secret lives on this device.
 *
 * Usuals holds exactly one secret today: the API key for whichever model
 * provider the user configured. Invariant 5 governs it, and it has two halves.
 * "Never leaves the device" is settled by architecture, since the app talks to
 * the provider directly and there is no proxy to leak through. "Never enters a
 * log" is the half that gets broken by accident, months later, by a debug line
 * someone adds while chasing something else. So this interface is shaped to
 * make that accident awkward:
 *
 * - The key is behind a function, not a property. It never sits in a data class
 *   whose generated `toString()` a future `Log.d("state=$settings")` would
 *   expand.
 * - Every implementation's `toString()` is a constant that names the class and
 *   says nothing else. `SecretStoreRedactionTest` fails when one is not.
 * - [hasModelKey] exists so that "is a provider configured?" never has to pull
 *   the plaintext out to answer. Fewer call sites holding the key is fewer call
 *   sites that can print it.
 *
 * There is no flow or observer here on purpose. The settings screen wants to
 * know whether a key exists, which [hasModelKey] answers on demand, and the
 * agent wants the key itself at the moment it makes a call. Neither needs the
 * value cached in memory between those moments.
 */
interface SecretStore {

    /**
     * Store [key], replacing whatever was there.
     *
     * The value is trimmed, because a pasted key carries a stray newline far
     * more often than it carries a meaningful leading space. Blank input is
     * rejected rather than stored: a blank key and no key are the same state,
     * and two ways to spell one state puts a branch in every caller.
     */
    fun putModelKey(key: String)

    /** The stored key, or null when no provider key is configured. */
    fun modelKey(): String?

    /** Forget the key. Backs "remove provider" and a factory reset. */
    fun clear()

    /** Whether a key is configured; the rest of the list needs no provider key. */
    fun hasModelKey(): Boolean = modelKey() != null

    companion object {
        /**
         * What a secret looks like anywhere one would otherwise be printed.
         *
         * Kept in one place so a `toString()`, a log formatter and an error
         * mapper all spell redaction the same way, which turns "did we redact
         * this?" into a grep rather than a reading exercise.
         */
        const val REDACTED: String = "[redacted]"
    }
}
