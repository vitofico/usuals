package io.theficos.comanda.testing

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.crypto.tink.Aead
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Test

/** Contract probes only: no production sync, reducer or network implementation. */
class SharedListContractTest {
    private fun fixture(name: String): JsonObject = javaClass
        .getResourceAsStream("/shared-list-v1/$name.json")!!.bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun aeadKey() = KeysetHandle.generateNew(AesGcmParameters.builder()
        .setKeySizeBytes(32).setIvSizeBytes(12).setTagSizeBytes(16)
        .setVariant(AesGcmParameters.Variant.NO_PREFIX).build())
    private fun field(out: DataOutputStream, value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }
    private fun signingInput(header: ByteArray, ciphertext: ByteArray) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.write("COMANDA/ENVELOPE/1\u0000".toByteArray(Charsets.US_ASCII))
            field(out, header)
            field(out, ciphertext)
        }
    }.toByteArray()

    @Test fun `binary header and signing bytes match independently authored golden`() {
        val vector = fixture("wire")
        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeShort(1)
                out.write(vector.text("serverId").unhex())
                vector.getValue("uuids").jsonArray.forEach { id ->
                    val uuid = UUID.fromString(id.jsonPrimitive.content)
                    out.writeLong(uuid.mostSignificantBits)
                    out.writeLong(uuid.leastSignificantBits)
                }
                out.writeLong(vector.getValue("epoch").jsonPrimitive.long)
                out.write(ByteArray(32) { vector.getValue("manifestFillByte").jsonPrimitive.int.toByte() })
                out.writeLong(vector.getValue("baseSeq").jsonPrimitive.long)
                out.write(ByteArray(32) { vector.getValue("baseHeadFillByte").jsonPrimitive.int.toByte() })
                out.writeShort(1)
            }
        }.toByteArray()
        assertThat(header.hex()).isEqualTo(vector.text("headerHex"))
        val input = signingInput(header, vector.text("ciphertextHex").unhex())
        assertThat(input.hex()).isEqualTo(vector.text("signingInputHex"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(input).hex())
            .isEqualTo(vector.text("signingInputSha256"))
        val aad = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write("COMANDA/AAD/1\u0000".toByteArray(Charsets.US_ASCII))
                field(out, header)
            }
        }.toByteArray()
        assertThat(aad.hex()).isEqualTo(vector.text("aadHex"))
    }

    @Test fun `raw Ed25519 matches public RFC 8032 test one`() {
        val vector = fixture("ed25519-rfc8032")
        val message = vector.text("messageHex").unhex()
        val signature = Ed25519Sign(vector.text("seedHex").unhex()).sign(message)
        assertThat(signature.hex()).isEqualTo(vector.text("signatureHex"))
        Ed25519Verify(vector.text("publicHex").unhex()).verify(signature, message)
    }

    @Test fun `envelope signature rejects changes to each routing field or ciphertext`() {
        val wire = fixture("wire")
        val key = fixture("ed25519-rfc8032")
        val signer = Ed25519Sign(key.text("seedHex").unhex())
        val verifier = Ed25519Verify(key.text("publicHex").unhex())
        val header = wire.text("headerHex").unhex()
        val ciphertext = wire.text("ciphertextHex").unhex()
        val signature = signer.sign(signingInput(header, ciphertext))
        verifier.verify(signature, signingInput(header, ciphertext))
        // First byte of every fixed-width field, including both versions.
        for (offset in listOf(0, 2, 34, 50, 66, 82, 98, 114, 122, 154, 162, 194)) {
            val changed = header.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertThrows(GeneralSecurityException::class.java) {
                verifier.verify(signature, signingInput(changed, ciphertext))
            }
        }
        val changedBody = ciphertext.copyOf().also { it[0] = 127 }
        assertThrows(GeneralSecurityException::class.java) {
            verifier.verify(signature, signingInput(header, changedBody))
        }
    }

    @Test fun `Tink AEAD rejects ciphertext and associated-data substitution`() {
        AeadConfig.register()
        val aead = aeadKey().getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        val aad = fixture("wire").text("aadHex").unhex()
        val clear = "synthetic milk: 2 l".toByteArray()
        val encrypted = aead.encrypt(clear, aad)
        assertThat(encrypted.size).isEqualTo(12 + clear.size + 16)
        assertThat(aead.decrypt(encrypted, aad)).isEqualTo(clear)
        assertThrows(GeneralSecurityException::class.java) { aead.decrypt(encrypted, aad + 0) }
        val corrupt = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { aead.decrypt(corrupt, aad) }
        val other = aeadKey().getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        assertThrows(GeneralSecurityException::class.java) { other.decrypt(encrypted, aad) }
    }

    @Test fun `RAW AES GCM wire layout agrees with JCA`() {
        // Public synthetic test key; S3 uses Tink key generation, never this value.
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 32).toByte() }
        val aad = fixture("wire").text("aadHex").unhex()
        val text = "synthetic milk".toByteArray()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
        encrypt.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        encrypt.updateAAD(aad)
        val wire = nonce + encrypt.doFinal(text)
        assertThat(wire.size).isEqualTo(12 + text.size + 16)
        val tink = com.google.crypto.tink.subtle.AesGcmJce(key)
        assertThat(tink.decrypt(wire, aad)).isEqualTo(text)
        assertThrows(GeneralSecurityException::class.java) { tink.decrypt(wire, aad + 1) }
    }

    @Test fun `HPKE key package is bound to recipient and context`() {
        HybridConfig.register()
        fun key() = KeysetHandle.generateNew(HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX).build())
        val recipient = key()
        val context = fixture("wire").text("aadHex").unhex()
        val listKey = ByteArray(32) { it.toByte() }
        val wrapped = recipient.publicKeysetHandle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java).encrypt(listKey, context)
        assertThat(wrapped.size).isEqualTo(32 + listKey.size + 16)
        val decrypt = recipient.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
        assertThat(decrypt.decrypt(wrapped, context)).isEqualTo(listKey)
        assertThrows(GeneralSecurityException::class.java) { decrypt.decrypt(wrapped, context + 1) }
        assertThrows(GeneralSecurityException::class.java) {
            key().getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java).decrypt(wrapped, context)
        }
    }

    @Test fun `semantic fixtures identify all operation outcomes without pretending to run a reducer`() {
        val cases = fixture("reducer").getValue("cases").jsonArray.map { it.jsonObject }
        assertThat(cases.map { it.text("id") }.distinct()).hasSize(cases.size)
        val kinds = mutableSetOf<String>()
        for (case in cases) {
            val operations = case.getValue("operations").jsonArray.map { it.jsonObject }
            kinds += operations.map { it.text("kind") }
            val expected = case.getValue("expected").jsonObject
            assertThat(expected.getValue("facts").jsonObject).isNotEmpty()
            assertThat(expected.getValue("outcomes").jsonObject.keys)
                .containsExactlyElementsIn(operations.map { it.text("id") }.toSet())
        }
        assertThat(kinds).containsAtLeast("capture", "edit", "check", "delete", "finish", "undo", "repeat", "alias", "import", "preference", "list_name")
        assertThat(fixture("security").getValue("cases").jsonArray.map { it.jsonObject.text("expected") })
            .containsAtLeast("epoch_stale", "intent_exists", "membership_conflict", "recovery_required", "forbidden")
    }

    @Test fun `migration design remains grounded in finalized schema four`() {
        val root = File(System.getProperty("comanda.root"))
        val schema = Json.parseToJsonElement(File(root,
            "data/local/schemas/io.theficos.comanda.data.local.ComandaDatabase/4.json").readText())
            .jsonObject.getValue("database").jsonObject
        assertThat(schema.getValue("version").jsonPrimitive.int).isEqualTo(4)
        val design = File(root, "docs/sync/migration-v5.md").readText()
        assertThat(design).contains(schema.text("identityHash"))
        assertThat(schema.getValue("entities").jsonArray.map { it.jsonObject.text("tableName") })
            .containsExactly("items", "trip", "purchase", "item_pref", "item_alias", "product_quality")
    }

    private fun api() = Json.parseToJsonElement(File(System.getProperty("comanda.root"),
        "docs/sync/openapi.json").readText()).jsonObject

    @Test fun `HTTP contract requires authentication on every data route`() {
        val publicRoutes = setOf("/healthz", "/readyz", "/v1/server-info",
            "/v1/devices/challenge", "/v1/devices/session", "/v1/join-requests")
        for ((path, methods) in api().getValue("paths").jsonObject) {
            for (method in methods.jsonObject.values) {
                val security = method.jsonObject.getValue("security").jsonArray
                assertWithMessage(path).that(security.isEmpty()).isEqualTo(path in publicRoutes)
                if (path == "/v1/households") {
                    assertThat(security.single().jsonObject.keys).containsExactly("bootstrapToken")
                } else if (path !in publicRoutes) {
                    assertThat(security.flatMap { it.jsonObject.keys }).contains("deviceSession")
                    assertThat(security.flatMap { it.jsonObject.keys }).doesNotContain("bootstrapToken")
                }
            }
        }
    }

    @Test fun `recovery sessions have no ordinary write or invite routes`() {
        val allowed = setOf(
            "get /v1/households/{h}/membership", "post /v1/households/{h}/membership",
            "get /v1/households/{h}/lists", "get /v1/households/{h}/key-packages",
            "get /v1/lists/{l}/operations", "get /v1/lists/{l}/snapshot",
            "put /v1/households/{h}/snapshot-candidates/{c}",
        )
        val actual = mutableSetOf<String>()
        for ((path, methods) in api().getValue("paths").jsonObject) {
            for ((method, operation) in methods.jsonObject) {
                if (operation.jsonObject.getValue("security").jsonArray.any { "recoverySession" in it.jsonObject }) {
                    actual += "$method $path"
                }
            }
        }
        assertThat(actual).containsExactlyElementsIn(allowed)
    }

    @Test fun `API references resolve and snapshot bounds do not inherit operation ceiling`() {
        val doc = api()
        val schemas = doc.getValue("components").jsonObject.getValue("schemas").jsonObject
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    node["\$ref"]?.jsonPrimitive?.content?.let { reference ->
                        assertThat(reference).startsWith("#/components/schemas/")
                        assertThat(schemas.keys).contains(reference.substringAfterLast('/'))
                    }
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(doc)
        fun cipherLimit(name: String) = schemas.getValue(name).jsonObject.getValue("properties")
            .jsonObject.getValue("ciphertext").jsonObject.getValue("maxLength").jsonPrimitive.int
        assertThat(cipherLimit("SnapshotEnvelope")).isGreaterThan(cipherLimit("Envelope"))
        val manifest = schemas.getValue("SignedManifest").jsonObject.getValue("properties").jsonObject
        assertThat(manifest.keys).contains("snapshotRefs")
        assertThat(manifest.keys).doesNotContain("snapshots")
    }
}
