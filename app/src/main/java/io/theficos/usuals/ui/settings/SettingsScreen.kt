@file:OptIn(ExperimentalMaterial3Api::class)

package io.theficos.usuals.ui.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.theficos.usuals.ui.components.SectionCard
import io.theficos.usuals.R
import io.theficos.usuals.llm.ModelCapabilities
import io.theficos.usuals.auth.ProviderKind
import io.theficos.usuals.ui.theme.AppPalette
import io.theficos.usuals.ui.theme.BackgroundStyle
import io.theficos.usuals.ui.theme.BrandBackdrop

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAccount: () -> Unit = {},
    palette: AppPalette = AppPalette.PLUM,
    onPaletteChange: (AppPalette) -> Unit = {},
    onOpenTutorial: () -> Unit = {},
    onOpenAdvanced: () -> Unit = {},
    advanced: Boolean = false,
    rowFacts: io.theficos.usuals.ui.product.RowFactsStore? = null,
    backgroundStyle: BackgroundStyle = BackgroundStyle.ARTWORK,
    onBackgroundStyleChange: (BackgroundStyle) -> Unit = {},
    notifications: io.theficos.usuals.ui.notifications.SharedNotifications? = null,
    reminders: io.theficos.usuals.ui.shopping.ReminderController? = null,
) {
    val state by vm.state.collectAsState()
    var panel by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(vm) { vm.loadIfNeeded() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // An edge-to-edge window no longer resizes for the keyboard, so the content pads for it.
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (advanced) R.string.settings_advanced else R.string.settings_title), style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding)) {
        BrandBackdrop(Modifier.matchParentSize(), compact = true)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            if (advanced) {
                Text(stringResource(R.string.settings_direct_help), style = MaterialTheme.typography.bodyMedium)
                ModelSection(state, vm)
                BudgetSection(state, vm)
            } else {
                SectionCard {
                    SettingsLink(stringResource(R.string.account_title), Icons.Outlined.AccountCircle,
                        state.account?.let { stringResource(R.string.settings_account_on, it.host) } ?: stringResource(R.string.settings_offline), onOpenAccount)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsLink(stringResource(R.string.appearance_title), Icons.Outlined.Palette) { panel = "appearance" }
                    if (notifications != null) SettingsLink(stringResource(R.string.notifications_title), Icons.Outlined.Notifications) { panel = "notifications" }
                    SettingsLink(stringResource(R.string.settings_privacy_title), Icons.Outlined.Shield) { panel = "privacy" }
                    // Flags come only from what lookup saved, so the list hides with it.
                    if (state.settings.scanLookupEnabled && rowFacts != null) {
                        SettingsLink(stringResource(R.string.row_facts_allergens), Icons.Outlined.WarningAmber) { panel = "allergens" }
                    }
                    SettingsLink(stringResource(R.string.settings_help), Icons.AutoMirrored.Outlined.HelpOutline) { panel = "help" }
                }
                CaptureMatchingSection(state, vm::setAutomaticMatching)
            }
            Spacer(Modifier.height(32.dp))
        }
      }
    }
    panel?.let { selected ->
        AlertDialog(onDismissRequest = { panel = null },
            title = { Text(stringResource(when (selected) { "appearance" -> R.string.appearance_title; "privacy" -> R.string.settings_privacy_title
                "allergens" -> R.string.row_facts_allergens; "notifications" -> R.string.notifications_title; else -> R.string.settings_help })) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (selected) {
                    "notifications" -> notifications?.let { io.theficos.usuals.ui.notifications.NotificationsPanel(it, reminders) }
                    "appearance" -> AppearanceSection(palette, onPaletteChange, backgroundStyle, onBackgroundStyleChange)
                    "allergens" -> rowFacts?.let { AllergensSection(it) }
                    "privacy" -> PrivacySection(state, vm::setScanLookup, vm::setBasketOverview, rowFacts)
                    else -> {
                        TextButton(onClick = { panel = null; onOpenTutorial() }) { Text(stringResource(R.string.tutorial_replay)) }
                        AboutSection(state)
                        TextButton(onClick = { panel = null; onOpenAdvanced() }) { Text(stringResource(R.string.settings_advanced)) }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { panel = null }) { Text(stringResource(R.string.recipe_close)) } })
    }
}

@Composable
private fun SettingsLink(label: String, icon: ImageVector, supporting: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Modelo --------------------------------------------------------------------

@Composable
private fun ModelSection(state: SettingsUiState, vm: SettingsViewModel) {
    val provider = state.settings.provider
    Section(stringResource(R.string.settings_model_title)) {
        Quiet(stringResource(R.string.settings_model_intro))

        Text(
            stringResource(R.string.settings_model_provider),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
        ProviderKind.entries.filter { it != ProviderKind.ANTHROPIC }.forEach { kind ->
            ChoiceRow(
                label = stringResource(
                    when (kind) {
                        ProviderKind.ANTHROPIC -> R.string.settings_provider_anthropic
                        ProviderKind.OPENAI_COMPATIBLE -> R.string.settings_provider_openai
                        ProviderKind.OLLAMA -> R.string.settings_provider_ollama
                    },
                ),
                selected = kind == provider.kind,
                onSelect = { vm.setProviderKind(kind) },
            )
        }

        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = vm::setBaseUrl,
            label = { Text(stringResource(R.string.settings_model_base_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (provider.kind == ProviderKind.OLLAMA) {
            Quiet(stringResource(R.string.settings_model_ollama_hint))
            Quiet(stringResource(R.string.settings_model_local_example))
        }

        OutlinedTextField(
            value = provider.model,
            onValueChange = vm::setModelName,
            label = { Text(stringResource(R.string.settings_model_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_model_is_local), style = MaterialTheme.typography.bodyMedium)
                Quiet(stringResource(R.string.settings_model_is_local_hint))
            }
            Switch(checked = provider.isLocal, onCheckedChange = vm::setIsLocal)
        }

        // Write-only. There is no path that reads a stored key back into this
        // field, and there is not meant to be one.
        OutlinedTextField(
            value = state.apiKeyDraft,
            onValueChange = vm::setApiKeyDraft,
            label = { Text(stringResource(R.string.settings_model_api_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Quiet(stringResource(R.string.settings_model_api_key_hint))
        Quiet(
            stringResource(
                when (state.apiKey) {
                    KeyState.PRESENT -> R.string.settings_model_api_key_saved
                    KeyState.ABSENT -> R.string.settings_model_api_key_missing
                    KeyState.UNAVAILABLE -> R.string.settings_model_api_key_unavailable
                },
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::saveApiKey, enabled = state.apiKeyDraft.isNotBlank()) {
                Text(stringResource(R.string.settings_model_api_key_save))
            }
            // Offered while the keystore is unreadable as well as while a key is
            // known to be there, because "there may be a key I cannot see" is
            // exactly the state in which someone wants to try removing it.
            if (state.apiKey != KeyState.ABSENT) {
                TextButton(onClick = vm::clearApiKey) {
                    Text(
                        stringResource(R.string.settings_model_api_key_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        OutlinedButton(onClick = vm::probeModel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_test_connection))
        }
        ModelProbeReport(state.modelProbe, provider.model, provider.isLocal)
    }
}

/**
 * Reports the probe honestly, including the part the person would rather not
 * read.
 *
 * Capability-gating rather than config-gating: two endpoints with the same wire
 * format differ in what they support, and an endpoint that cannot return
 * structured output cannot read a recipe. Better to be told here than halfway
 * through one.
 */
@Composable
private fun ModelProbeReport(probe: ProbeState<ModelCapabilities>, modelName: String, isLocal: Boolean) {
    when (probe) {
        ProbeState.Idle -> Unit

        ProbeState.Running -> Quiet(stringResource(R.string.settings_probe_running))

        ProbeState.Broken -> Bad(stringResource(R.string.settings_probe_unexpected))

        is ProbeState.Done -> {
            val result = probe.result
            val problem = result.problem
            if (!result.reachable) {
                Bad(
                    problem?.let { modelProblemText(it) }
                        ?: stringResource(R.string.settings_probe_unreachable),
                )
            } else if (problem != null) {
                Bad(modelProblemText(problem))
            } else {
                Text(stringResource(R.string.settings_probe_reachable), style = MaterialTheme.typography.bodyMedium)

                val known = result.model
                if (known != null && known.isNotBlank()) {
                    Text(
                        stringResource(R.string.settings_probe_model_ok, known),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Bad(stringResource(R.string.settings_probe_model_missing, modelName))
                }

                if (result.supportsStructuredOutput) {
                    Text(
                        stringResource(R.string.settings_probe_structured_ok),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Bad(stringResource(R.string.settings_probe_no_structured_output))
                }

                Quiet(
                    stringResource(
                        if (isLocal) R.string.settings_probe_local else R.string.settings_probe_remote,
                    ),
                )
            }
        }
    }
}

// Privacidad ----------------------------------------------------------------

/**
 * Invariant 8, discharged.
 *
 * "On device", "private" and "free" all have edges, and this is where the edges
 * are stated: on the same screen that makes the claim, in the app, not in a
 * document nobody opens. The cloud warning is emphasised exactly when it
 * applies, because a warning shown to somebody running a local model is a
 * warning that gets skimmed the day it matters.
 */
@Composable
private fun PrivacySection(
    state: SettingsUiState,
    onScanLookup: (Boolean) -> Unit,
    onBasketOverview: (Boolean) -> Unit,
    rowFacts: io.theficos.usuals.ui.product.RowFactsStore?,
) {
    var details by rememberSaveable { mutableStateOf(false) }
    Section(stringResource(R.string.settings_privacy_title), collapsible = false) {
        Row(Modifier.fillMaxWidth().toggleable(state.settings.scanLookupEnabled, role = Role.Switch, onValueChange = onScanLookup), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scan_lookup_toggle), modifier = Modifier.weight(1f))
            Switch(checked = state.settings.scanLookupEnabled, onCheckedChange = null)
        }
        Quiet(stringResource(R.string.scan_lookup_hint))
        // Both read only what lookups saved, so they are offered only beside a lookup that is on.
        if (state.settings.scanLookupEnabled) {
            rowFacts?.let { RowFactsSection(it) }
            Row(Modifier.fillMaxWidth().toggleable(state.settings.basketOverviewEnabled, role = Role.Switch, onValueChange = onBasketOverview), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.basket_title), modifier = Modifier.weight(1f))
                Switch(checked = state.settings.basketOverviewEnabled, onCheckedChange = null)
            }
            Quiet(stringResource(R.string.basket_setting_hint))
        }
        TextButton(onClick = { details = !details }) {
            Text(stringResource(if (details) R.string.settings_privacy_hide else R.string.settings_privacy_details))
            Icon(if (details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (details) {
            Text(stringResource(R.string.settings_privacy_kept_title), style = MaterialTheme.typography.titleSmall)
            Bullet(stringResource(R.string.settings_privacy_kept_capture))
            Bullet(stringResource(R.string.settings_privacy_kept_secrets))

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_privacy_edges_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Bullet(stringResource(R.string.account_privacy))
            Bullet(stringResource(R.string.scan_lookup_privacy))
            Bullet(stringResource(R.string.settings_privacy_edge_voice))
            Bullet(stringResource(R.string.settings_privacy_edge_recipe))
            Bullet(stringResource(R.string.settings_privacy_edge_cloud))

            Bullet(stringResource(R.string.connection_privacy))
        }
    }
}

/**
 * Product facts on list rows, under the lookup switch because they only ever
 * show what lookup saved. Hidden while lookup is off, when rows show none.
 */
@Composable
private fun RowFactsSection(store: io.theficos.usuals.ui.product.RowFactsStore) {
    val choices by store.state.collectAsState()
    Row(Modifier.fillMaxWidth().toggleable(choices.showGrades, role = Role.Switch, onValueChange = store::showGrades), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.row_facts_grades), modifier = Modifier.weight(1f))
        Switch(checked = choices.showGrades, onCheckedChange = null)
    }
    Quiet(stringResource(R.string.row_facts_grades_hint))
}

/**
 * The allergens this phone flags on list rows. Health data, so it is stored
 * only on this phone; the copy says so, and says that no flag is no promise.
 */
@Composable
private fun AllergensSection(store: io.theficos.usuals.ui.product.RowFactsStore) {
    val choices by store.state.collectAsState()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Quiet(stringResource(R.string.row_facts_allergens_hint))
    io.theficos.usuals.ui.AVOIDABLE_ALLERGENS.forEach { tag ->
        val avoided = tag in choices.avoid
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(avoided, role = Role.Checkbox) { store.avoid(tag, it) },
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = avoided, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(io.theficos.usuals.ui.allergenName(tag).replaceFirstChar { it.titlecase(locale) }, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(stringResource(R.string.row_facts_allergens_caution), style = MaterialTheme.typography.bodyMedium)
}

// Presupuesto ---------------------------------------------------------------

@Composable
private fun BudgetSection(state: SettingsUiState, vm: SettingsViewModel) {
    Section(stringResource(R.string.settings_budget_title)) {
        WholeNumberField(
            label = stringResource(R.string.settings_budget_daily),
            value = state.settings.dailyCallCeiling,
            onValue = vm::setDailyCallCeiling,
        )
        Quiet(stringResource(R.string.settings_budget_daily_hint))
        Text(
            if (state.settings.dailyCallCeiling <= 0) {
                pluralStringResource(R.plurals.settings_budget_today_disabled, state.callsToday, state.callsToday)
            } else {
                stringResource(R.string.settings_budget_today, state.callsToday, state.settings.dailyCallCeiling)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.settings.dailyCallCeiling in 1..state.callsToday) {
            Bad(stringResource(R.string.settings_budget_exhausted))
        }


    }
}

// Acerca de -----------------------------------------------------------------

@Composable
private fun AboutSection(state: SettingsUiState) {
    Section(stringResource(R.string.settings_about_title)) {
        Text(
            stringResource(R.string.settings_about_version, state.info.versionName, state.info.versionCode),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.settings_about_licence, state.info.licence),
            style = MaterialTheme.typography.bodyMedium,
        )
        // Open Food Facts is published under the ODbL, which asks for credit
        // wherever its data is displayed. A scanned row credits it beside the
        // data itself; this is the copy for somebody who has gone looking for
        // where the data came from, so it names the licence in full.
        Text(
            stringResource(R.string.settings_about_off_attribution),
            style = MaterialTheme.typography.bodyMedium,
        )
        Quiet(stringResource(R.string.settings_about_no_telemetry))
        Quiet(stringResource(R.string.settings_typography_credit))

    }
}

// Shared pieces -------------------------------------------------------------

@Composable
private fun Section(title: String, collapsible: Boolean = true, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val status = stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed)
    SectionCard {
        if (collapsible) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = status }, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
        } else Text(title, style = MaterialTheme.typography.titleMedium)
        if (!collapsible || expanded) content()
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun Bad(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Bullet(text: String) {
    Text("· $text", style = MaterialTheme.typography.bodyMedium)
}

/**
 * A field that only holds digits.
 *
 * It keeps its own text and commits nothing while blank, so clearing it to type
 * a new number does not momentarily set the ceiling to zero, and zero stays
 * something the person has to type on purpose.
 */
@Composable
private fun WholeNumberField(label: String, value: Int, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(9)
            text = digits
            digits.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CaptureMatchingSection(state: SettingsUiState, onChange: (Boolean) -> Unit) {
    SectionCard(title = stringResource(R.string.settings_capture_title)) {
        Row(Modifier.fillMaxWidth().toggleable(state.settings.automaticMatchingEnabled, enabled = state.loaded,
            role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_automatic_matching), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = state.settings.automaticMatchingEnabled, onCheckedChange = null, enabled = state.loaded)
        }
        Text(stringResource(R.string.settings_automatic_matching_help), style = MaterialTheme.typography.bodySmall)
    }
}
