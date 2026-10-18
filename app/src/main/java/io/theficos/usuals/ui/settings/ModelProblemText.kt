package io.theficos.usuals.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.theficos.usuals.R
import io.theficos.usuals.llm.ModelProblem

@Composable
internal fun modelProblemText(problem: ModelProblem): String = stringResource(
    when (problem.kind) {
        ModelProblem.Kind.BUDGET_EXHAUSTED -> R.string.model_problem_budget
        ModelProblem.Kind.UNREACHABLE -> R.string.model_problem_unreachable
        ModelProblem.Kind.KEY_REJECTED -> R.string.model_problem_key_rejected
        ModelProblem.Kind.NOT_FOUND -> R.string.model_problem_not_found
        ModelProblem.Kind.RATE_LIMITED -> R.string.model_problem_rate_limited
        ModelProblem.Kind.HTTP_ERROR -> R.string.model_problem_http
        ModelProblem.Kind.EMPTY_REPLY -> R.string.model_problem_empty
        ModelProblem.Kind.NOT_JSON -> R.string.model_problem_json
        ModelProblem.Kind.UNSUPPORTED_PROVIDER -> R.string.model_problem_unsupported
        ModelProblem.Kind.MISSING_ENDPOINT -> R.string.model_problem_endpoint
        ModelProblem.Kind.MISSING_MODEL -> R.string.model_problem_model
        ModelProblem.Kind.INCOMPLETE_CONFIGURATION -> R.string.model_problem_configuration
    },
    problem.httpCode ?: 0,
)
