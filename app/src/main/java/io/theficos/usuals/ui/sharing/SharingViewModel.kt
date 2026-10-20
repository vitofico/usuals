package io.theficos.usuals.ui.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.usuals.auth.VaultException
import io.theficos.usuals.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SharingViewModel(private val client: HouseholdClient,
    private val connection: ConnectionClient? = null,
    private val choices: suspend () -> List<SharingList> = { emptyList() },
    private val shareList: suspend (String, String, Boolean) -> Unit = { _, _, _ -> },
    private val retryList: suspend (String, String) -> Unit = { _, _ -> },
    private val synchronize: () -> Unit = {},
    private val resetKeys: suspend () -> Unit = {},
    private val pendingWork: suspend () -> ByteArray = { byteArrayOf() },
    private val retainList: suspend (String, String) -> Unit = { _, _ -> },
    private val removeList: suspend (String) -> Unit = {},
    private val readFrozen: suspend (String, ByteArray?, String?) -> FrozenCopy = { _, _, _ -> error("unavailable") },
    private val saveFrozen: suspend (FrozenCopy, String) -> Unit = { _, _ -> },
    statusChanges: kotlinx.coroutines.flow.Flow<*> = kotlinx.coroutines.flow.emptyFlow<Unit>()) : ViewModel() {
    private val mutable = MutableStateFlow(SharingState())
    val state = mutable.asStateFlow()
    init { viewModelScope.launch(Dispatchers.IO) {
        statusChanges.collect { val lists = choices(); mutable.update { it.copy(lists = lists) } }
    } }
    private var verifiedSetup: VerifiedSetup? = null
    private var verifiedReplacement: VerifiedReplacement? = null
    fun selectFrozen(uri: String) {
        if (state.value.busy) return
        mutable.update { it.copy(frozenUri = uri, frozenCopy = null) }
        work(refresh = false) {
            try { val preview = readFrozen(uri, null, null); mutable.update { it.copy(frozenCopy = preview) } }
            catch (_: java.io.IOException) { fileFailure() }
        }
    }
    fun frozenKit(bytes: ByteArray) = work(refresh = false) {
        val uri = requireNotNull(state.value.frozenUri); val secret = state.value.recoveryKey.trim()
        try { val preview = readFrozen(uri, bytes, secret); mutable.update { it.copy(frozenCopy = preview) } }
        catch (_: java.io.IOException) { fileFailure() }
        finally { mutable.update { it.copy(recoveryKey = "") } }
    }
    fun saveFrozenCopy(name: String) {
        val preview = state.value.frozenCopy ?: return
        work(refresh = false) { saveFrozen(preview, name); val lists = choices(); mutable.update { it.copy(lists = lists, frozenCopy = null, frozenUri = null, message = SharingMessage.FROZEN_SAVED) } }
    }
    fun closeFrozen() { mutable.update { it.copy(frozenCopy = null, frozenUri = null, recoveryKey = "") } }
    fun prepareBackupRequest(bytes: ByteArray) = work(refresh = false) {
        try {
            val request = RecoveryKits.frozenRequest(bytes, state.value.recoveryKey.trim())
            val encoded = Wire.json.encodeToString(FrozenRequest.serializer(), request).toByteArray()
            mutable.update { it.copy(backupRequest = encoded, backupFingerprint = request.fingerprint) }
        } finally { mutable.update { it.copy(recoveryKey = "") } }
    }
    fun clearBackupRequest() { mutable.update { it.copy(backupRequest = null) } }
    fun preparePendingExport() = work { val bytes = pendingWork(); mutable.update { it.copy(pendingExport = bytes) } }
    fun clearPendingExport() { mutable.update { it.copy(pendingExport = null) } }
    fun retain(id: String, name: String) = work { retainList(id, name); mutable.update { it.copy(lists = choices(), message = SharingMessage.COPY_KEPT) } }
    fun removeCopy(id: String) = work { removeList(id); mutable.update { it.copy(lists = choices(), message = SharingMessage.COPY_REMOVED) } }
    fun share(household: String, list: String, history: Boolean) = work {
        shareList(household, list, history)
        client.rememberSharingSource(null)
        mutable.update { it.copy(lists = choices(), sharingSource = null, message = SharingMessage.SHARED) }
    }
    fun retryShare(household: String, list: String) = work { retryList(household, list); synchronize(); mutable.update { it.copy(sharingSource = client.sharingSource()) } }
    fun rememberShareSource(source: String?) = work(refresh = false) {
        val local = source?.takeIf { id -> choices().any { it.id == id && !it.shared } }
        client.rememberSharingSource(local)
        mutable.update { it.copy(sharingSource = local) }
    }
    fun rotate(household: String) = work { client.rotate(household); synchronize(); mutable.update { it.copy(message = SharingMessage.ROTATED) } }
    fun remove(household: String, device: String) = work { client.removeDevice(household, device); synchronize(); mutable.update { it.copy(message = SharingMessage.REMOVED) } }
    fun transfer(household: String, device: String) = work {
        val bytes = client.prepareTransfer(household, device)
        mutable.update { it.copy(transferFile = bytes, transferReview = null) }
    }
    fun reviewTransfer(bytes: ByteArray) = work {
        val review = client.reviewTransfer(bytes)
        mutable.update { it.copy(transferReview = review, transferFile = null) }
    }
    fun acceptTransfer() = work { val bytes = client.acceptTransfer(requireNotNull(state.value.transferReview))
        mutable.update { it.copy(transferFile = bytes, transferReview = null) }
    }
    fun completeTransfer(bytes: ByteArray) = work {
        val household = client.completeTransfer(bytes)
        val renewed = client.pendingTransfer(household)
        synchronize()
        mutable.update { it.copy(transferFile = renewed, message = if (renewed == null) SharingMessage.TRANSFERRED else SharingMessage.REAPPROVE) }
    }
    fun closeTransfer() { mutable.update { it.copy(transferReview = null, transferFile = null) } }
    fun resetUnavailable() = work {
        verifiedSetup = null; verifiedReplacement = null
        mutable.value = SharingState(busy = true)
        resetKeys()
        mutable.update { it.copy(message = SharingMessage.KEYS_RESET) }
    }
    fun connectServer() = work {
        requireNotNull(connection).begin(state.value.origin.trim())
        reloadConnection()
    }
    fun checkConnection() = work {
        requireNotNull(connection).finish()
        reloadConnection()
    }
    fun cancelConnection() { connection?.cancel(); reloadConnection() }
    fun disconnectServer() = work { requireNotNull(connection).disconnect(); reloadConnection() }
    fun enableServerAi(enabled: Boolean) { connection?.enableAi(enabled, state.value.ai?.revision); reloadConnection() }
    private fun reloadConnection() {
        val saved = connection?.current()
        mutable.update { it.copy(connection = saved, connecting = connection?.pending(), origin = saved?.origin ?: it.origin) }
    }
    fun loadAi() = work(refresh = false) { mutable.update { it.copy(ai = requireNotNull(connection).request<AiSettings>("GET", "/v1/ai/settings")) } }
    fun joinCode(pending: PendingJoinState) = client.joinCode(pending)
    fun load() = work { reload(); mutable.update { it.copy(lists = choices(), sharingSource = client.sharingSource()) } }
    private fun reload() {
        reloadConnection()
        val households = client.households(); val joining = client.joining(); val bootstrap = client.bootstrapPending()
        mutable.update { it.copy(households = households, joining = joining, bootstrapPending = bootstrap, shareAttempts = client.pendingShares(), members = households.associate { hh -> hh.id to client.members(hh.id) }, devices = households.associate { hh -> hh.id to client.deviceId(hh.id) }, fingerprints = households.associate { hh -> hh.id to client.deviceFingerprints(hh.id) }) }
    }
    fun resumeBootstrap() = work { client.resumeBootstrap(); reload(); mutable.update { it.copy(message = SharingMessage.CREATED) } }
    fun retryJoin() = work { client.resumeJoin(); reload() }
    /** The single scan or link entry point. Previewing a link must not open the private vault or contact a server. */
    fun scanned(value: String, serverOnly: Boolean = false): Boolean {
        if (state.value.busy) return false
        val link = try { PairingLink.parse(value.trim()) } catch (_: Exception) {
            mutable.update { it.copy(problem = SharingProblem.NOT_USUALS) }; return true
        }
        try {
            when (link) {
                is PairingLink.Server -> {
                    require(state.value.connection?.origin.let { it == null || it == link.origin })
                    mutable.update { it.copy(origin = link.origin, problem = null) }
                }
                is PairingLink.Invite -> {
                    require(!serverOnly)
                    InviteCode.parse(link.text)
                    mutable.update { it.copy(invitation = link.text, problem = null) }
                }
            }
        } catch (_: Exception) { mutable.update { it.copy(invitation = "", problem = SharingProblem.VERIFICATION) } }
        return true
    }
    fun field(field: SharingField, value: String) = mutable.update { when (field) {
        SharingField.ORIGIN -> it.copy(origin = value.take(512))
        SharingField.SETUP -> it.copy(setupCode = value.take(100))
        SharingField.INVITATION -> it.copy(invitation = value.take(4096))
        SharingField.RECOVERY -> it.copy(recoveryKey = value.take(100))
        SharingField.FINGERPRINT -> it.copy(fingerprint = value.take(100))
    } }
    fun prepare() = work {
        val kit = client.prepare(state.value.origin.trim())
        verifiedSetup = null
        mutable.update { it.copy(kit = kit, saved = false, verified = false, recoveryKey = "", message = null) }
    }
    fun prepareReplacement(household: String) = work {
        val kit = client.prepareReplacement(household)
        verifiedSetup = null; verifiedReplacement = null
        mutable.update { it.copy(kit = kit, saved = false, verified = false, recoveryKey = "", message = null) }
    }
    fun markSaved() { mutable.update { it.copy(saved = true) } }
    fun verifyFile(bytes: ByteArray) = work {
        when (val kit = requireNotNull(state.value.kit)) {
            is NewHousehold -> verifiedSetup = kit.verifySaved(bytes, state.value.recoveryKey.trim())
            is ReplacementKit -> verifiedReplacement = kit.verifySaved(bytes, state.value.recoveryKey.trim())
            else -> throw IllegalArgumentException("invalid")
        }
        mutable.update { it.copy(verified = true, recoveryKey = "", message = SharingMessage.KIT_VERIFIED) }
    }
    fun create() = work {
        val replacing = verifiedReplacement != null
        if (replacing) client.replaceRecovery(requireNotNull(verifiedReplacement)) else {
            val savedConnection = connection?.current()
            if (savedConnection != null) client.bootstrapAccount(requireNotNull(verifiedSetup), savedConnection)
            else client.bootstrap(requireNotNull(verifiedSetup), state.value.setupCode.trim())
        }
        verifiedSetup = null; verifiedReplacement = null
        mutable.update { it.copy(kit = null, setupCode = "", recoveryKey = "", households = client.households(), message = if (replacing) SharingMessage.REPLACED else SharingMessage.CREATED) }
    }
    fun cancelSetup() { verifiedSetup = null; verifiedReplacement = null; mutable.update { it.copy(kit = null, setupCode = "", recoveryKey = "", verified = false, saved = false) } }
    fun invite(household: String) = work { val invite = client.invitation(household); mutable.update { it.copy(outgoing = invite) } }
    fun closeInvitation() { mutable.update { it.copy(outgoing = null) } }
    fun join() = work {
        val pending = client.requestJoin(state.value.invitation.trim())
        mutable.update { it.copy(joining = pending, invitation = "", message = SharingMessage.WAITING) }
    }
    fun checkJoin() = work {
        val joined = client.completeJoin()
        mutable.update { it.copy(joining = client.joining(), households = client.households(), message = if (joined == null) SharingMessage.WAITING else SharingMessage.JOINED) }
    }
    fun pending(household: String) = work {
        val requests = client.pending(household)
        mutable.update { it.copy(reviewing = household, requests = requests, fingerprint = "") }
    }
    fun closeReview() { mutable.update { it.copy(reviewing = null, requests = emptyList(), fingerprint = "") } }
    fun approve(candidate: JoinCandidate) = work {
        val household = requireNotNull(state.value.reviewing)
        client.approve(household, requireNotNull(candidate.verified))
        val households = client.households()
        mutable.update { it.copy(reviewing = null, requests = emptyList(), fingerprint = "", households = households, message = SharingMessage.APPROVED) }
    }
    fun deny(candidate: JoinCandidate) = work {
        val household = requireNotNull(state.value.reviewing); client.deny(household, candidate.requestId)
        mutable.update { it.copy(reviewing = null, requests = emptyList(), fingerprint = "") }
    }
    fun recover(bytes: ByteArray) = work {
        client.recover(bytes, state.value.recoveryKey.trim())
        mutable.update { it.copy(recoveryKey = "", households = client.households(), message = SharingMessage.RECOVERED) }
    }
    fun discardInvalid(household: String) = work {
        val discarded = client.discardInvalidPending(household)
        mutable.update { it.copy(households = client.households(), message = if (discarded) SharingMessage.DISCARDED else null) }
    }
    fun resume(household: String) = work { client.resume(household); mutable.update { it.copy(households = client.households(), transferFile = client.pendingTransfer(household)) } }
    fun fileFailure() { mutable.update { it.copy(problem = SharingProblem.FILE) } }
    private fun work(refresh: Boolean = true, block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, problem = null, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (problem: VaultException) { mutable.update { it.copy(problem = if (problem.problem == io.theficos.usuals.auth.VaultProblem.USAGE_EXHAUSTED) SharingProblem.CAPACITY else SharingProblem.LOCKED_KEYS) } }
            catch (_: java.io.IOException) { mutable.update { it.copy(problem = SharingProblem.OFFLINE) } }
            catch (_: java.security.GeneralSecurityException) { mutable.update { it.copy(problem = SharingProblem.VERIFICATION) } }
            catch (_: ProtocolViolation) { mutable.update { it.copy(problem = SharingProblem.VERIFICATION) } }
            catch (_: UnsupportedWireVersion) { mutable.update { it.copy(problem = SharingProblem.UPGRADE) } }
            catch (problem: RelayProblem) { mutable.update { it.copy(problem = when (problem.code) {
                "offline", "unavailable" -> SharingProblem.OFFLINE
                "already_joined" -> SharingProblem.ALREADY_JOINED
                "history_unverifiable" -> SharingProblem.HISTORY
                "frozen_key_required" -> SharingProblem.FROZEN_KEY
                "forbidden", "unauthenticated" -> SharingProblem.ACCESS
                "recovery_required" -> SharingProblem.RECOVERY
                "quota_exceeded", "too_large", "rate_limited" -> SharingProblem.CAPACITY
                "membership_conflict", "epoch_stale" -> SharingProblem.CHANGED
                else -> SharingProblem.VERIFICATION
            }) } }
            catch (_: IllegalArgumentException) { mutable.update { it.copy(problem = SharingProblem.VERIFICATION) } }
            catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error; mutable.update { it.copy(problem = SharingProblem.VERIFICATION) } }
            finally { if (refresh) runCatching { reload(); mutable.update { it.copy(lists = choices()) }; synchronize() }; mutable.update { it.copy(busy = false) } }
        }
    }
}
data class SharingState(val origin: String = "", val setupCode: String = "", val invitation: String = "", val recoveryKey: String = "",
    val sharingSource: String? = null,
    val shareAttempts: List<PendingShare> = emptyList(),
    val connection: ServerConnection? = null, val connecting: PendingConnection? = null, val ai: AiSettings? = null,
    val fingerprint: String = "", val bootstrapPending: Boolean = false, val busy: Boolean = false, val problem: SharingProblem? = null, val message: SharingMessage? = null,
    val households: List<HouseholdSummary> = emptyList(), val kit: RecoveryDraft? = null, val saved: Boolean = false, val verified: Boolean = false,
    val backupRequest: ByteArray? = null, val backupFingerprint: String? = null, val pendingExport: ByteArray? = null, val frozenUri: String? = null, val frozenCopy: FrozenCopy? = null,
    val lists: List<SharingList> = emptyList(), val members: Map<String, List<RosterMember>> = emptyMap(), val devices: Map<String, String> = emptyMap(), val fingerprints: Map<String, Map<String, String>> = emptyMap(),
    val transferFile: ByteArray? = null, val transferReview: TransferReview? = null,
    val outgoing: InviteCode? = null, val joining: PendingJoinState? = null, val reviewing: String? = null, val requests: List<JoinCandidate> = emptyList()) {
    override fun toString() = "SharingState([redacted])"
}
enum class SharingField { ORIGIN, SETUP, INVITATION, RECOVERY, FINGERPRINT }
enum class SharingProblem { NOT_USUALS, OFFLINE, VERIFICATION, ACCESS, LOCKED_KEYS, RECOVERY, CAPACITY, CHANGED, UPGRADE, HISTORY, ALREADY_JOINED, FILE, FROZEN_KEY }
enum class SharingMessage { KIT_VERIFIED, CREATED, WAITING, JOINED, APPROVED, RECOVERED, REPLACED, DISCARDED, SHARED, ROTATED, REMOVED, TRANSFERRED, KEYS_RESET, REAPPROVE, COPY_KEPT, COPY_REMOVED, FROZEN_SAVED }

data class SharingList(val id: String, val name: String, val household: String, val shared: Boolean, val accessRemoved: Boolean = false)
