package io.theficos.usuals.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.*
import io.theficos.usuals.ui.theme.GradeShade
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
class ScoreBadgesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun panel(quality: ProductQuality) = compose.setContent { UsualsTheme { ScorePanel(quality) } }

    /**
     * The window drawn in software, cropped to one node. captureToImage waits for a
     * frame commit callback that Robolectric never delivers.
     */
    private fun pixelsOf(description: String): List<List<Int>> {
        val bounds = compose.onNodeWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return (bounds.top.toInt() until bounds.bottom.toInt()).map { y ->
            (bounds.left.toInt() until bounds.right.toInt()).map { x -> bitmap.getPixel(x, y) }
        }
    }

    @Test fun `stated grades are announced in full, including Green-Score A plus`() {
        panel(ProductQuality(nutriScore = NutriScore.B, novaGroup = NovaGroup.ULTRA_PROCESSED, greenScore = GreenScore.A_PLUS))
        compose.onNodeWithContentDescription("Nutri-Score B").assertExists()
        compose.onNodeWithContentDescription("NOVA 4 (ultra-processed)").assertExists()
        compose.onNodeWithContentDescription("Green-Score A+").assertExists()
        compose.onNodeWithText("Experimental outside France", substring = true).assertExists()
        compose.onAllNodesWithText("Not stated").assertCountEquals(0)
    }

    @Test fun `Green-Score F is a grade, not a gap`() {
        panel(ProductQuality(greenScore = GreenScore.F))
        compose.onNodeWithContentDescription("Green-Score F").assertExists()
        compose.onAllNodes(hasContentDescription("Green-Score not stated")).assertCountEquals(0)
    }

    // Native graphics so the chip's pixels can be read back.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `a missing grade shows no letter and no grade colour, only Not stated`() {
        panel(ProductQuality(productName = "Cocoa"))
        compose.onNodeWithContentDescription("Nutri-Score not stated").assertExists()
        for (letter in NutriScore.entries) {
            compose.onAllNodes(hasContentDescription("Nutri-Score ${letter.name}")).assertCountEquals(0)
        }
        compose.onAllNodesWithText("Not stated").assertCountEquals(3)
        val fills = GradeShade.entries.map { it.fill.toArgb() }.toSet()
        val used = pixelsOf("Nutri-Score not stated").flatten().toSet()
        assertThat(used).isNotEmpty()
        assertThat(used.intersect(fills)).isEmpty()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `a stated grade is drawn in its own fill`() {
        panel(ProductQuality(nutriScore = NutriScore.E))
        val rows = pixelsOf("Nutri-Score E")
        assertThat(rows[3][rows[3].size / 2]).isEqualTo(GradeShade.STEP_5.fill.toArgb())
    }

    @Test fun `does not apply is its own state, apart from not stated`() {
        panel(ProductQuality(productName = "Beer", nutriScoreNotApplicable = true))
        compose.onNodeWithContentDescription("Nutri-Score does not apply").assertExists()
        compose.onNodeWithText("Does not apply").assertExists()
        compose.onAllNodes(hasContentDescription("Nutri-Score not stated")).assertCountEquals(0)
    }

    @Test fun `additives are counted and listed as E-numbers without any verdict`() {
        panel(ProductQuality(additives = listOf("en:e250", "en:e330", "en:e322i", "en:e1400")))
        compose.onNodeWithText("4 additives: E250, E330, E322i, E1400").assertExists()
    }

    @Test fun `additives keep their three honest states`() {
        panel(ProductQuality(productName = "Cocoa", additives = emptyList()))
        compose.onNodeWithText("Open Food Facts lists no additives.").assertExists()
        compose.onNodeWithText("Additives: not stated").assertDoesNotExist()
    }

    @Test fun `regulated allergens read in English and unknown tags keep their tag text`() {
        panel(ProductQuality(allergens = listOf("en:milk", "en:nuts", "en:sulphur-dioxide-and-sulphites", "fr:lait-cru")))
        compose.onNodeWithText("Allergens: milk, tree nuts, sulphur dioxide and sulphites, lait-cru").assertExists()
    }

    @Test fun `allergens not stated and none listed stay apart`() {
        panel(ProductQuality(productName = "Cocoa"))
        compose.onNodeWithText("Allergens: not stated").assertExists()
    }

    @Config(qualifiers = "it-w411dp-h891dp")
    @Test fun `allergens are named in the reader's language`() {
        panel(ProductQuality(allergens = listOf("en:milk", "en:nuts", "en:sesame-seeds", "en:mystery")))
        compose.onNodeWithText("Allergeni: latte, frutta a guscio, semi di sesamo, mystery").assertExists()
        compose.onNodeWithContentDescription("Nutri-Score non indicato").assertExists()
    }

    @Test fun `the scan strip shows only stated grades`() {
        compose.setContent { UsualsTheme { ScoreStrip(ProductQuality(nutriScore = NutriScore.C, greenScore = GreenScore.A_PLUS)) } }
        compose.onNodeWithContentDescription("Nutri-Score C").assertExists()
        compose.onNodeWithContentDescription("Green-Score A+").assertExists()
        compose.onAllNodes(hasContentDescription("NOVA", substring = true)).assertCountEquals(0)
    }

    @Test fun `the compact chip describes an unknown grade rather than hiding it`() {
        compose.setContent { UsualsTheme { NutriScoreChip(null) } }
        compose.onNodeWithContentDescription("Nutri-Score not stated").assertExists()
    }
}
