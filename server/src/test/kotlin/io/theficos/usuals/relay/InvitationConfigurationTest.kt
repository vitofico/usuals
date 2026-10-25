package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class InvitationConfigurationTest {
    @Test fun mailIsOptInAndPlaintextTransportIsConfinedToExplicitLocalConfiguration() {
        assertThat(MailConfiguration.fromEnvironment("https://usuals.invalid", emptyMap())).isNull()
        assertThrows(IllegalArgumentException::class.java) {
            MailConfiguration.fromEnvironment("https://usuals.invalid", mapOf("USUALS_EMAIL_INVITATIONS_ENABLED" to "yes"))
        }
        assertThrows(IllegalArgumentException::class.java) { MailConfiguration("mail.example.org", 587, "usuals@example.org", startTls = false) }
        assertThrows(IllegalArgumentException::class.java) { MailConfiguration("mail.example.org", 587, "usuals@example.org", username = "user") }
        assertThrows(IllegalArgumentException::class.java) { MailConfiguration("mail.example.org\r\nHost:evil", 587, "usuals@example.org") }
    }
    @Test fun mailboxValidationRejectsHeaderInjectionAndKeepsAliasesDistinct() {
        assertThat(emailAddress(" Alice+Shopping@Example.org ")).isEqualTo("alice+shopping@example.org")
        assertThat(emailAddress("alice.shopping@example.org")).isNotEqualTo(emailAddress("aliceshopping@example.org"))
        for (email in listOf("a@example.org\r\nBcc:other@example.org", "Name <a@example.org>", "a@localhost", "a@example..org", ".a@example.org", "a..b@example.org", "a@example.org,b@example.org")) {
            assertThrows(RelayError::class.java) { emailAddress(email) }
        }
    }
}
