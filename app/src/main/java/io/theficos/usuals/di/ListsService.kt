package io.theficos.usuals.di

import io.theficos.usuals.sync.*
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URLEncoder

/** The account's list routes (docs/sync/lists-v2.md). A failure is a [RelayProblem] with the server's code and status. */
interface ListsService {
    suspend fun changes(cursor: String?, wait: Int): ListChanges
    suspend fun create(list: String, projection: Projection): SharedList
    suspend fun intents(list: String, intents: List<Intent>): IntentBatchResult
    suspend fun delete(list: String)
    suspend fun people(query: String): List<Person>
    suspend fun addMember(list: String, user: String): SharedList
    suspend fun removeMember(list: String, user: String)
    suspend fun archive(list: String, archived: Boolean): SharedList
}

/** Sends each request with the phone's credential; a missing one reads as a 401, like an expired one. */
class HttpListsService(private val transport: RelayTransport, private val connection: () -> ServerConnection?) : ListsService {
    override suspend fun changes(cursor: String?, wait: Int): ListChanges =
        call("GET", "/api/lists/changes?" + listOfNotNull(cursor?.let { "cursor=" + escape(it) }, "limit=50", "wait=$wait", "activity=1").joinToString("&"))
    override suspend fun create(list: String, projection: Projection): SharedList =
        call("PUT", "/api/lists/${escape(list)}", Wire.json.encodeToString(CreateList.serializer(), CreateList(projection)))
    override suspend fun intents(list: String, intents: List<Intent>): IntentBatchResult =
        call("POST", "/api/lists/${escape(list)}/intents", Wire.json.encodeToString(IntentBatch.serializer(), IntentBatch(intents)))
    override suspend fun delete(list: String) { send("DELETE", "/api/lists/${escape(list)}") }
    override suspend fun people(query: String): List<Person> = call<People>("GET", "/api/people?q=" + escape(query)).people
    override suspend fun addMember(list: String, user: String): SharedList =
        call("POST", "/api/lists/${escape(list)}/members", Wire.json.encodeToString(AddMember.serializer(), AddMember(user)))
    override suspend fun removeMember(list: String, user: String) { send("DELETE", "/api/lists/${escape(list)}/members/${escape(user)}") }
    override suspend fun archive(list: String, archived: Boolean): SharedList =
        call("PUT", "/api/lists/${escape(list)}/archived", Wire.json.encodeToString(ArchiveList.serializer(), ArchiveList(archived)))

    private suspend inline fun <reified T> call(method: String, path: String, body: String? = null): T =
        Wire.json.decodeFromJsonElement(Wire.strictJson(send(method, path, body), MAX_RESPONSE, payload = true))
    private suspend fun send(method: String, path: String, body: String? = null): ByteArray {
        val saved = connection() ?: throw RelayProblem("unauthenticated", 401)
        val response = transport.request(saved.origin, method, path, body?.toByteArray(), saved.token, MAX_RESPONSE)
        if (response.status !in 200..299) throw RelayProblem(runCatching { ConnectionClient.decode<ServiceError>(response.body).code }.getOrDefault("unavailable"), response.status)
        return response.body
    }
    private fun escape(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    override fun toString() = "HttpListsService([redacted])"

    private companion object { const val MAX_RESPONSE = 10 * 1024 * 1024 }
}
