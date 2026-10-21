package io.theficos.usuals.di

import android.content.Context
import io.theficos.usuals.auth.SyncVault
import io.theficos.usuals.auth.VaultException
import io.theficos.usuals.sync.*
import java.io.File

/** Keeps the account connection in the Keystore-wrapped vault. Households are gone, so their storage is not offered. */
class VaultSyncStateStore(private val context: Context, private val vault: SyncVault) : SyncStateStore {
    /** A vault the Keystore will not open reads as signed out: nothing is removed, and the lists stay in Room. */
    override fun read() = try { vault.get("client")?.let { Wire.json.decodeFromString<ClientState>(it) } } catch (_: VaultException) { null } ?: ClientState()
    override fun update(change: (ClientState) -> ClientState): ClientState = vault.transaction { entries ->
        val old = entries["client"]?.let { Wire.json.decodeFromString<ClientState>(it) } ?: ClientState()
        change(old).also { entries["client"] = Wire.json.encodeToString(ClientState.serializer(), it) }
    }
    override fun reserve(scope: String) = throw UnsupportedOperationException("households_removed")
    override fun saveBlob(id: String, bytes: ByteArray) = throw UnsupportedOperationException("households_removed")
    override fun readBlob(id: String): ByteArray? = throw UnsupportedOperationException("households_removed")
    override fun removeBlob(id: String) = throw UnsupportedOperationException("households_removed")

    /**
     * Forgets what households left behind, keeping only the account connection. A vault that can no
     * longer be read starts empty: the lists stay in Room and signing in again reconnects them. A
     * Keystore that refuses to answer leaves the phone signed out instead of stopping the app.
     */
    fun prune() {
        try {
            vault.resetUnavailable()
            update { ClientState(connection = it.connection, connecting = it.connecting) }
        } catch (_: VaultException) { }
        File(context.applicationContext.noBackupFilesDir, "sync/envelopes").deleteRecursively()
    }
    override fun toString() = "VaultSyncStateStore([redacted])"
}
