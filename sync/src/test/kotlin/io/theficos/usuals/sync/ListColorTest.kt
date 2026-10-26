package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.ListColor
import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Test

class ListColorTest {
    private val reducer = Reducer()
    private fun command(id: String, color: String?, expected: String? = null) = Intent(id, "home", "list_color", buildJsonObject {
        put("color", color?.let(::JsonPrimitive) ?: JsonNull); put("expected", expected?.let(::JsonPrimitive) ?: JsonNull)
    })
    @Test fun colorsAreDurableIndependentOfNamesAndGuardConcurrentChoices() {
        val initial = Projection(name = "Groceries")
        val first = reducer.apply(initial, command("one", "sage"))
        assertThat(first.outcome).isEqualTo(Outcome.APPLIED)
        val renamed = reducer.apply(first.projection, Intent("rename", "home", "list_name", buildJsonObject { put("name", "Weekend"); put("expected", JsonNull) }))
        val stale = reducer.apply(renamed.projection, command("two", "rose"))
        assertThat(stale.outcome).isEqualTo(Outcome.CONFLICT)
        assertThat(ListAppearance.color(stale.projection)).isEqualTo(ListColor.SAGE)
        assertThat(stale.projection.name).isEqualTo("Weekend")
        val restored = Wire.json.decodeFromString<Projection>(SnapshotCodec.encode(stale.projection).decodeToString())
        assertThat(ListAppearance.color(restored)).isEqualTo(ListColor.SAGE)
        assertThat(reducer.apply(restored, command("one", "sage")).outcome).isEqualTo(Outcome.NO_OP)
        val reset = reducer.apply(restored, command("reset", null, "one"))
        assertThat(reset.outcome).isEqualTo(Outcome.APPLIED)
        assertThat(ListAppearance.color(reset.projection)).isNull()
        assertThat(ListAppearance.revision(reset.projection)).isEqualTo("reset")
    }
    @Test fun invalidColorsAndShoppingPreferenceWritesCannotReplaceListMetadata() {
        assertThat(reducer.apply(Projection(), command("bad", "#ff0000")).outcome).isEqualTo(Outcome.REJECTED)
        val bad = Projection(preferences = mapOf(ListAppearance.KEY to buildJsonObject { put("color", "unknown") }))
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(bad) }
        val disguised = Intent("bad-pref", "home", "preference", buildJsonObject {
            put("key", ListAppearance.KEY); put("expected", JsonNull); put("changes", buildJsonObject { put("repeatEveryDays", 3) })
        })
        assertThat(reducer.apply(Projection(), disguised).outcome).isEqualTo(Outcome.REJECTED)
    }
    @Test fun metadataSurvivesTheResendHorizonWithoutAddingProjectionFields() {
        var projection = reducer.apply(Projection(), command("color", "teal")).projection
        val keys = Wire.json.encodeToJsonElement(Projection(nameRevision = "existing")).jsonObject.keys
        repeat(2005) { index -> projection = reducer.apply(projection, Intent("name-$index", "home", "list_name", buildJsonObject {
            put("name", "List $index"); put("expected", projection.nameRevision?.let(::JsonPrimitive) ?: JsonNull)
        })).projection }
        assertThat(ListAppearance.color(projection)).isEqualTo(ListColor.TEAL)
        assertThat(ListAppearance.revision(projection)).isEqualTo("color")
        assertThat(Wire.json.encodeToJsonElement(projection).jsonObject.keys).isEqualTo(keys)
    }
}
