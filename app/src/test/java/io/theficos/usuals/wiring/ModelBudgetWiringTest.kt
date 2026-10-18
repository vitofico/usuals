package io.theficos.usuals.wiring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.auth.ProviderSettings
import io.theficos.usuals.di.OpenAiModelProbe
import io.theficos.usuals.di.ServiceLocator
import io.theficos.usuals.llm.*
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.ui.settings.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelBudgetWiringTest {
    @Test fun `production budget reads the saved ceiling and keeps usage across graph reconstruction`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        ServiceLocator.reset()
        try {
            ServiceLocator.init(context)
            ServiceLocator.settingsStore.save(AppSettings(dailyCallCeiling = 1))
            assertThat(ServiceLocator.modelBudget.dailyCallCeiling).isEqualTo(1)
            assertThat(ServiceLocator.modelBudget.tryConsume()).isTrue()
            ServiceLocator.reset()
            ServiceLocator.init(context)
            assertThat(ServiceLocator.modelBudget.usedToday()).isEqualTo(1)
            assertThat(ServiceLocator.modelBudget.tryConsume()).isFalse()
        } finally { ServiceLocator.reset() }
    }
    @Test fun `connection probes share the same ceiling and stop before contacting the client`() = runTest {
        var probes = 0
        val client = object : LlmClient {
            override suspend fun probe(): ModelCapabilities { probes++; return ModelCapabilities(true, true, "fake") }
            override suspend fun parseUtterance(utterance: String) = emptyList<ParsedItem>()
        }
        val probe = OpenAiModelProbe(apiKey = { null }, budget = ModelBudget(1), clients = { client })
        val settings = ProviderSettings.ollama("http://localhost:11434/v1", "fake")
        assertThat(probe.probe(settings).reachable).isTrue()
        assertThat(probe.probe(settings).problem!!.kind).isEqualTo(ModelProblem.Kind.BUDGET_EXHAUSTED)
        assertThat(probes).isEqualTo(1)
    }
}
