@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.usuals.ui.recipe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.recipe.IngredientReason
import io.theficos.usuals.recipe.RecipeProblem
import io.theficos.usuals.ui.list.quantityText

@Composable
fun RecipeImportSheet(state: RecipeUiState, onUrl: (String) -> Unit, onRead: () -> Unit, onModel: () -> Unit,
                      onToggle: (Int) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val adding = state.busy && state.result is RecipeLoad.Review
    ModalBottomSheet(onDismissRequest = { if (!adding) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(end = 12.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        .padding(8.dp).size(24.dp))
                Text(stringResource(R.string.recipe_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !adding) { Text(stringResource(R.string.recipe_close)) }
            }
            if (state.result !is RecipeLoad.Review) {
                OutlinedTextField(state.url, onUrl, label = { Text(stringResource(R.string.recipe_url)) }, singleLine = true,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Button(onClick = onRead, enabled = state.url.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recipe_read)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (val result = state.result) {
                null -> Text(stringResource(R.string.recipe_intro, stringResource(R.string.app_name)), style = MaterialTheme.typography.bodyMedium)
                is RecipeLoad.Failed -> Text(recipeProblem(result), color = MaterialTheme.colorScheme.error)
                is RecipeLoad.Unstructured -> {
                    Text(stringResource(R.string.recipe_no_structured))
                    Text(stringResource(R.string.recipe_model_privacy), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onModel, enabled = !state.busy && result.text.isNotBlank()) { Text(stringResource(R.string.recipe_use_model)) }
                }
                is RecipeLoad.Review -> {
                    result.title?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                    result.servings?.let { Text(stringResource(R.string.recipe_servings, it)) }
                    Text(stringResource(if (result.viaModel) R.string.recipe_model_review else R.string.recipe_review_hint), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        itemsIndexed(result.rows, key = { _, row -> row.key }) { index, row ->
                            Surface(shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, if (index in state.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                color = if (index in state.selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                                Checkbox(index in state.selected, { onToggle(index) }, enabled = !state.busy && row.reason != IngredientReason.ON_LIST)
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(row.item.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(row.original, style = MaterialTheme.typography.bodySmall)
                                    row.item.quantity?.let { Text(quantityText(it), style = MaterialTheme.typography.labelMedium) }
                                    if (row.reason != IngredientReason.NONE) Text(stringResource(when (row.reason) {
                                        IngredientReason.ON_LIST -> R.string.recipe_on_list
                                        IngredientReason.PANTRY -> R.string.recipe_pantry
                                        IngredientReason.RECENT -> R.string.recipe_recent
                                        IngredientReason.NONE -> error("unreachable")
                                    }), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            }
                        }
                    }
                    if (result.rows.isEmpty()) Text(stringResource(R.string.recipe_empty))
                    Button(onClick = onAdd, enabled = state.selected.isNotEmpty() && !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(pluralStringResource(R.plurals.recipe_add, state.selected.size, state.selected.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun recipeProblem(result: RecipeLoad.Failed): String = stringResource(when (result.modelProblem) {
    RecipeModelProblem.NOT_CONFIGURED -> R.string.recipe_model_missing
    RecipeModelProblem.BUDGET -> R.string.model_problem_budget
    RecipeModelProblem.EMPTY -> R.string.recipe_model_empty
    null -> when (result.problem) {
        RecipeProblem.INVALID_URL -> R.string.recipe_invalid_url
        RecipeProblem.BLOCKED -> R.string.recipe_blocked
        RecipeProblem.HTTP -> R.string.recipe_http
        RecipeProblem.UNREACHABLE -> R.string.recipe_unreachable
        RecipeProblem.TIMEOUT -> R.string.recipe_timeout
        RecipeProblem.TOO_LARGE -> R.string.recipe_too_large
        RecipeProblem.NOT_HTML -> R.string.recipe_not_html
        null -> R.string.recipe_failed
    }
}, result.httpCode ?: 0)
