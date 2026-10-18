package io.theficos.usuals.llm

/** Diagnostics are data. The app owns all display copy. */
data class ModelProblem(
    val kind: Kind,
    val httpCode: Int? = null,
    val excerpt: String? = null,
) {
    enum class Kind {
        UNREACHABLE, KEY_REJECTED, NOT_FOUND, RATE_LIMITED, HTTP_ERROR,
        EMPTY_REPLY, NOT_JSON, UNSUPPORTED_PROVIDER, MISSING_ENDPOINT,
        MISSING_MODEL, INCOMPLETE_CONFIGURATION, BUDGET_EXHAUSTED,
    }
}
