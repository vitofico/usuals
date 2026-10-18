package io.theficos.comanda.smart

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.parse.InputLanguages
import org.junit.Test

class NewCategoryLanguagesTest {
    @Test fun `new input packs categorise fresh multilingual lists without UI locale dependencies`() {
        val classifier = CategoryClassifier(InputLanguages.available)
        for (name in listOf("latte", "lait", "Milch")) assertThat(classifier.classify(name)).isEqualTo(Category.DAIRY)
        for (name in listOf("mele", "pommes", "Äpfel")) assertThat(classifier.classify(name)).isEqualTo(Category.PRODUCE)
        for (name in listOf("pane", "pain", "Brot")) assertThat(classifier.classify(name)).isEqualTo(Category.BAKERY)
        for (name in listOf("carta igienica", "papier toilette", "Toilettenpapier")) assertThat(classifier.classify(name)).isEqualTo(Category.HOUSEHOLD)
    }
}
