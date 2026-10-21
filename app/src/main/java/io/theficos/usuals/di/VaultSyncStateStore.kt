package io.theficos.usuals.di

import android.content.Context
import io.theficos.usuals.auth.SyncVault
import io.theficos.usuals.auth.VaultException
import io.theficos.usuals.sync.*
import java.io.File

/** Keeps the account connection in the Keystore-wrapped vault. */
class VaultSyncStateStore(private val context: Context, private val vault: SyncVault) : SyncStateStore {
    /** A vault the Keystore will not open reads as signed out: nothing is removed, and the lists stay in Room. */
    override fun read() = try { vault.get("client")?.let(ClientState::decode) } catch (_: VaultException) { null } ?: ClientState()
    override fun update(change: (ClientState) -> ClientState): ClientState = vault.transaction { entries ->
        val old = entries["client"]?.let(ClientState::decode) ?: ClientState()
        change(old).also { entries["client"] = Wire.json.encodeToString(ClientState.serializer(), it) }
    }

    /**
     * Forgets what households left behind, keeping only the account connection. A vault that can no
     * longer be read starts empty: the lists stay in Room and signing in again reconnects them. A
     * Keystore that refuses to answer leaves the phone signed out instead of stopping the app.
     */
    fun prune() {
        try {
            vault.resetUnavailable()
            update { it }
        } catch (_: VaultException) { }
        File(context.applicationContext.noBackupFilesDir, "sync/envelopes").deleteRecursively()
    }
    override fun toString() = "VaultSyncStateStore([redacted])"
}
