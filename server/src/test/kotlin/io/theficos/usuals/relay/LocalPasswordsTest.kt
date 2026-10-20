package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalPasswordsTest {
    @Test fun passwordPolicyCountsUnicodeAndPreservesSpacesWithoutTruncation() {
        LocalPasswords.validate("  a long synthetic password  ")
        LocalPasswords.validate("\uD83C\uDF4B".repeat(128))
        for (value in listOf("a".repeat(14), "a".repeat(129), "a".repeat(15) + "\n", "a".repeat(15) + "\uD800", "a".repeat(15) + "\uDC00")) {
            assertThrows(RelayError::class.java) { LocalPasswords.validate(value) }
        }
        val salt = LocalPasswords.salt()
        val password = "  synthetic test password  "
        val hash = LocalPasswords.hash(password, salt)
        assertThat(hash).hasLength(32)
        assertThat(LocalPasswords.matches(password, salt, hash)).isTrue()
        assertThat(LocalPasswords.matches(password.trim(), salt, hash)).isFalse()
        assertThat(LocalPasswords.matches(password, null, null)).isFalse()
        assertThat(LocalPasswords.matches(password + "\uD800", salt, hash)).isFalse()
        assertThat(LocalPasswords.salt()).isNotEqualTo(salt)
        assertThat(LocalPasswords.ITERATIONS).isAtLeast(600000)
    }
    @Test fun localProviderHasIndependentFailClosedConfigurationAndRestrictedLoginNames() {
        val config = WebIdentityConfig.fromEnvironment("https://usuals.example", mapOf("USUALS_LOCAL_AUTH_ENABLED" to "true"))!!
        assertThat(config.localEnabled).isTrue(); assertThat(config.oidc).isNull()
        assertThat(config.accepts(WebIdentityConfig.LOCAL_ISSUER)).isTrue()
        assertThat(WebIdentityConfig.fromEnvironment(config.origin, emptyMap())).isNull()
        assertThrows(IllegalArgumentException::class.java) { WebIdentityConfig.fromEnvironment(config.origin, mapOf("USUALS_LOCAL_AUTH_ENABLED" to "yes")) }
        assertThat(LocalPasswords.username("Test.Member")).isEqualTo("test.member")
        for (name in listOf("x", "user@example", " user ", "üser", "a".repeat(65))) assertThrows(RelayError::class.java) { LocalPasswords.username(name) }
    }
}
