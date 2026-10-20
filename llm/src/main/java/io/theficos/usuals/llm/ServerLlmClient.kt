package io.theficos.usuals.llm

import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.sync.*

/** The chosen server handles provider credentials; a changed connection invalidates this client. */
class ServerLlmClient(private val connection: ConnectionClient, private val saved: ServerConnection = requireNotNull(connection.current())) : LlmClient {
    private suspend fun call(action: String, request: AiRequest = AiRequest()): AiReply {
        require(saved.aiEnabled && connection.current() == saved)
        val reply = try { connection.request<AiReply>("POST", "/v1/ai/$action", request.copy(revision = saved.aiRevision), expected = saved) }
        catch (problem: RelayProblem) {
            throw ModelCallException(ModelProblem(when (problem.code) {
                "ai_budget" -> ModelProblem.Kind.BUDGET_EXHAUSTED
                "unauthenticated", "forbidden" -> ModelProblem.Kind.KEY_REJECTED
                "ai_configuration_changed" -> ModelProblem.Kind.INCOMPLETE_CONFIGURATION
                "ai_images_unverified" -> ModelProblem.Kind.IMAGES_UNSUPPORTED
                else -> ModelProblem.Kind.UNREACHABLE
            }))
        }
        require(connection.current() == saved)
        reply.problem?.let { code -> throw ModelCallException(ModelProblem(runCatching { ModelProblem.Kind.valueOf(code) }.getOrDefault(ModelProblem.Kind.HTTP_ERROR))) }
        return reply
    }
    private suspend fun capabilities(action: String): ModelCapabilities = try {
        val reply = call(action)
        ModelCapabilities(reply.reachable, reply.structured, reply.model, supportsImages = reply.images)
    } catch (problem: ModelCallException) { ModelCapabilities(false, false, null, problem.problem) }
    override suspend fun probe() = capabilities("probe")
    override suspend fun probeImages() = capabilities("probe-image")
    override suspend fun parseUtterance(utterance: String) = items(call("text", AiRequest(text = utterance)))
    override suspend fun parseImage(image: ModelImage) = items(call("image", AiRequest(image = java.util.Base64.getEncoder().encodeToString(image.bytes()), mimeType = image.mimeType)))
    private fun items(reply: AiReply): List<ParsedItem> {
        require(reply.items.size <= 50)
        return reply.items.map { item ->
            require(item.name.length in 1..120 && (item.note?.length ?: 0) <= 200)
            val quantity = item.amount?.let { amount -> Quantity(amount.toDouble(), MeasureUnit.valueOf(requireNotNull(item.unit))) }
            ParsedItem(item.name, quantity, item.note)
        }
    }
    override fun toString() = "ServerLlmClient([redacted])"
}
