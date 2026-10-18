package io.theficos.comanda.relay

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.sync.Wire
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentityTest {
    @Test fun identityRequiresExplicitCreationAndRemainsStableAcrossProcessReopen() {
        val directory = Files.createTempDirectory("comanda-identity-test")
        val file = directory.resolve("identity")
        try {
            assertThrows(java.nio.file.NoSuchFileException::class.java) { Identity.load(file) }
            Identity.create(file)
            val original = Identity.load(file)
            val reopened = Identity.load(file)
            assertThat(original.serverId).isEqualTo(reopened.serverId)
            val bytes = "synthetic-auth-proof".toByteArray()
            verify(Wire.encode(reopened.publicKey), original.sign(bytes), bytes)
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------")
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { Identity.create(file) }
            Files.write(file, ByteArray(64))
            assertThrows(RelayError::class.java) { Identity.load(file) }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
    }
    @Test fun restoreFenceRotationIsDurableExplicitAndKeepsTheIndependentReceiptIdentity() {
        val directory = Files.createTempDirectory("comanda-fence-test")
        val identity = directory.resolve("identity"); val fence = directory.resolve("fence")
        try {
            Identity.create(identity); val original = Identity.load(identity).serverId
            Files.writeString(fence, java.util.UUID.randomUUID().toString())
            val before = Files.readString(fence)
            val after = RestoreFence.replace(fence)
            assertThat(after).isNotEqualTo(before)
            assertThat(Files.readString(fence)).isEqualTo(after)
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(fence))).isEqualTo("rw-------")
            assertThat(Identity.load(identity).serverId).isEqualTo(original)
            assertThat(RestoreFence.replace(fence)).isNotEqualTo(after)
            val missing = directory.resolve("missing")
            val fresh = RestoreFence.replace(missing)
            assertThat(Files.readString(missing)).isEqualTo(fresh)
            assertThat(Identity.load(identity).serverId).isEqualTo(original)
            Files.delete(missing)
        } finally { Files.deleteIfExists(identity); Files.deleteIfExists(fence); Files.deleteIfExists(directory) }
    }
    @Test fun configuredOriginsRejectAlternateAuthorityAndPermitOnlyExplicitLocalDevelopment() {
        assertThat(canonicalOrigin("https://comanda.example")).isEqualTo("https://comanda.example")
        for (origin in listOf("https://example/", "https://user@example", "https://Example", "https://example:443", "https://example:65536", "https://example?key=x", "https://example#x", "http://example", "http://localhost")) {
            assertThrows(IllegalArgumentException::class.java) { canonicalOrigin(origin) }
        }
        assertThat(canonicalOrigin("http://10.0.2.2:8080", true)).isEqualTo("http://10.0.2.2:8080")
        assertThrows(IllegalArgumentException::class.java) { canonicalOrigin("http://example", true) }
    }
}
