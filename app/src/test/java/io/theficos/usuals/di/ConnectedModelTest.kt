package io.theficos.usuals.di

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.llm.LlmClient
import io.theficos.usuals.llm.ModelCapabilities
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.sync.*
import org.junit.Test

class ConnectedModelTest {
    private val model = object : LlmClient {
        override suspend fun probe(): ModelCapabilities = error("not invoked")
        override suspend fun parseUtterance(utterance: String): List<ParsedItem> = error("not invoked")
    }
    private fun saved(enabled: Boolean) = ServerConnection("https://server.example", InstanceInfo(name = "Server",
        server = ServerInfo(Wire.encode(ByteArray(32)), Wire.encode(ByteArray(32))), accounts = true, ai = true),
        "synthetic-user", "synthetic-token", Long.MAX_VALUE, aiEnabled = enabled)

    @Test fun connectedServerWithAiOffDoesNotReadLegacyCredentialsOrBuildAnyClient() {
        assertThat(connectedModelAccess({ saved(false) }, { error("legacy settings must not be read") }, { error("no consent") })).isNull()
    }
    @Test fun connectedServerUsesOnlyItsGateway() {
        assertThat(connectedModelAccess({ saved(true) }, { error("no direct-provider fallback") }, { model })?.client).isSameInstanceAs(model)
    }
    @Test fun disconnectedLegacyConfigurationStillWorksWithoutBuildingAGateway() {
        assertThat(connectedModelAccess({ null }, { model }, { error("no server") })?.client).isSameInstanceAs(model)
    }
    @Test fun selectedRouteIsInvalidatedWhenConnectionChanges() {
        var connection: ServerConnection? = null
        val legacy = connectedModelAccess({ connection }, { model }, { error("unused") })!!
        assertThat(legacy.serverManaged).isFalse()
        connection = saved(false)
        assertThat(legacy.isCurrent()).isFalse()
        connection = saved(true)
        val server = connectedModelAccess({ connection }, { error("no fallback") }, { model })!!
        assertThat(server.serverManaged).isTrue()
        connection = connection!!.copy(aiEnabled = false)
        assertThat(server.isCurrent()).isFalse()
    }
}
