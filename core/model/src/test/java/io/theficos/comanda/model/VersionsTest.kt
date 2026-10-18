package io.theficos.comanda.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionsTest {

    @Test
    fun `versions start at one`() {
        assertThat(NORMALIZER_VERSION).isAtLeast(1)
        assertThat(PROMPT_VERSION).isAtLeast(1)
    }
}
