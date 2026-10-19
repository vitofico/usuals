package io.theficos.usuals.relay

import io.theficos.usuals.sync.*
import io.theficos.usuals.parse.Normalizer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Disposable synthetic keys only. Cross-check the browser implementation against Tink. */
object WebInteropFixture {
    private fun fresh() = UUID.randomUUID().toString()
    // Typed values follow the native text serializer. Raw differential intents
    // are built directly below to avoid canonicalizing their numeric literals.
    private inline fun <reified T> value(input: T) = Wire.json.parseToJsonElement(Wire.json.encodeToString(input))
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val directory = Path.of(args[1])
        when (args[0]) {
            "generate" -> generate(directory)
            "verify" -> verifyBrowser(directory)
            else -> error("invalid_fixture_mode")
        }
    }
    private fun generate(directory: Path) {
        Files.createDirectories(directory)
        val origin = "https://usuals.invalid"
        val identity = Identity.generate()
        val owner = SyncCrypto.device()
        val recipient = SyncCrypto.device()
        val recovery = SyncCrypto.device()
        val household = fresh()
        val list = fresh()
        val member = fresh()
        fun sign(body: Manifest, packages: List<KeyPackage> = emptyList(), refs: List<SnapshotRef> = emptyList()): SignedManifest {
            val draft = SignedManifest(Wire.encode(Wire.json.encodeToString(body).toByteArray()), Wire.encode(ByteArray(64)), packages, refs)
            return draft.copy(signature = SyncCrypto.sign(owner, draft.signingBytes()))
        }
        fun proof(signed: SignedManifest) = ManifestProof(signed.manifestBytes, signed.signature)
        val genesis = sign(Manifest(serverId = identity.serverId, householdId = household, genesisHash = Wire.zero,
            epoch = 1, previousHash = Wire.zero, action = "genesis", signer = owner.publicKeys.id,
            members = listOf(RosterMember(member, "owner", listOf(owner.publicKeys))), recovery = recovery.publicKeys))
        val projection = Projection(name = "Market list")
        val snapshotBytes = SnapshotCodec.encode(projection)
        fun next(prior: SignedManifest, action: String, key: ByteArray, members: List<RosterMember>, joins: Map<String, String> = emptyMap()): Pair<SignedManifest, Snapshot> {
            val epoch = prior.body().epoch + 1
            val snapshotHeader = Header(identity.serverId, household, list, owner.publicKeys.id, fresh(), fresh(), epoch, prior.hash(), 0, Wire.zero)
            val snapshotEnvelope = SyncCrypto.seal(snapshotHeader, snapshotBytes, key, owner) {}
            val ref = SnapshotRef(fresh(), snapshotEnvelope.digest(), list, 0, Wire.zero)
            val packages = (members.flatMap { it.devices } + recovery.publicKeys).map {
                SyncCrypto.wrap(identity.serverId, household, list, epoch, prior.hash(), it, key, owner)
            }
            val body = Manifest(serverId = identity.serverId, householdId = household, genesisHash = genesis.hash(), epoch = epoch,
                previousHash = prior.hash(), action = action, signer = owner.publicKeys.id, members = members,
                recovery = recovery.publicKeys, lists = listOf(ListCutoff(list, 0, Wire.zero, ref.candidateId, ref.hash)),
                packageHashes = packages.map { it.hash(identity.serverId, household) }, approvedJoins = joins,
                createdListId = if (action == "create_list") list else null)
            val signed = sign(body, packages, listOf(ref))
            MembershipProofs.transition(signed, prior.body(), prior.hash(), genesis.hash(), identity.serverId)
            return signed to Snapshot(snapshotEnvelope, 0, Wire.zero)
        }
        val created = next(genesis, "create_list", SyncCrypto.randomKey(), genesis.body().members).first
        val invitation = InvitationLink(origin = origin, serverId = identity.serverId, householdId = household,
            genesisHash = genesis.hash(), ownerDeviceId = owner.publicKeys.id,
            ownerFingerprint = Wire.fingerprint(identity.serverId, household, owner.publicKeys.signingPublicKey, owner.publicKeys.recipientPublicKey),
            inviteId = fresh(), memberId = fresh(), role = "editor", secret = Wire.encode(SyncCrypto.randomKey()), expiresAt = 1_900_000_000_000)
        val assertion = JoinAssertion(origin, identity.serverId, household, genesis.hash(), invitation.inviteId, fresh(), invitation.memberId,
            recipient.publicKeys, Wire.hash(Wire.decode(invitation.secret, 32)), fresh(), Wire.encode(SyncCrypto.randomKey()), invitation.expiresAt)
        val pending = PendingJoin(assertion.requestId, Wire.encode(assertion.bytes()), SyncCrypto.sign(recipient, assertion.bytes()))
        val key = SyncCrypto.randomKey()
        val (approved, snapshot) = next(created, "approve_join", key,
            created.body().members + RosterMember(invitation.memberId, "editor", listOf(recipient.publicKeys)),
            mapOf(assertion.requestId to Wire.hash(assertion.bytes())))
        var verified = VerifiedMembership.genesis(proof(genesis), identity.info(), household, genesis.hash())
        verified = verified.advance(proof(created)).advance(proof(approved))
        check(verified.hash == approved.hash())
        val intent = Intent(fresh(), list, "capture", buildJsonObject {
            put("proposedId", fresh()); put("key", "name:milk"); put("name", "Milk"); put("quantity", "2.0"); put("unit", "LITRE")
        })
        check(Reducer(1).apply(projection, intent).outcome == Outcome.APPLIED)
        val plaintext = Wire.json.encodeToString(intent).toByteArray()
        val envelope = SyncCrypto.seal(Header(identity.serverId, household, list, recipient.publicKeys.id,
            intent.id, fresh(), 3, approved.hash(), 0, Wire.zero), plaintext, key, recipient) {}
        val head = Wire.head(Wire.zero, 1, envelope)
        val receipt = Receipt(1, Wire.zero, head, identity.sign(Wire.receipt(identity.serverId, household, list, 1, head)))
        val keyPackage = approved.packages.single { it.recipientFingerprint == Wire.fingerprint(identity.serverId, household,
            recipient.publicKeys.signingPublicKey, recipient.publicKeys.recipientPublicKey) }
        val input = buildJsonObject {
            put("normalizationCases", normalizationCases()); put("reducerCases", reducerCases())
            put("version", 1); put("synthetic", true); put("origin", origin); put("server", value(identity.info()))
            put("householdId", household); put("listId", list); put("recipient", value(recipient)); put("key", Wire.encode(key))
            put("genesis", value(proof(genesis))); put("created", value(proof(created))); put("approved", value(proof(approved)))
            put("signedManifests", value(listOf(genesis, created, approved))); put("invitation", value(invitation))
            put("joinAssertion", value(assertion)); put("joinPending", value(pending)); put("snapshot", value(snapshot))
            put("keyPackage", value(keyPackage)); put("envelope", value(envelope)); put("plaintext", Wire.encode(plaintext)); put("receipt", value(receipt))
        }
        Files.writeString(directory.resolve("kotlin-input.json"), input.toString())
        println("PASS: synthetic JVM crypto and membership fixture generated")
    }
    private fun normalizationCases() = buildJsonArray {
        val examples = listOf("Milk", "MILKS", "The apples", "LA leche de avena!", "LÁPICES", "AÑO", "ano", "Crème fraîche", "Pâtes", "Latte intero", "Brokkoli", "Straße", "İPEK", "ΣΙΓΜΑΣ", "Ⅳ", "½", "𝕄ilk", "2️⃣ milks", "茶", "of", "la", "boxes", "tomatoes", "yoghurts", "tomates", "azúcar", "piñones", "n\u0303", "n\u0301", "  bread  and   milk ", "water\tand\nbread", "bread-meals", "PEACHES", "leches", "caffè", "café", "Hühnerbrühe", "Äpfel", "œufs", "lait d’avoine")
        for (text in examples) add(buildJsonObject { put("input", text); put("expected", Normalizer.normalize(text)) })
    }
    private fun reducerCases() = buildJsonArray {
        val reducer = Reducer(1)
        val list = "differential-list"
        fun capture(id: String, edits: Map<String, JsonElement> = emptyMap()) = Intent(id, list, "capture", JsonObject(mapOf(
            "proposedId" to JsonPrimitive("row-$id"), "key" to JsonPrimitive("milk"), "name" to JsonPrimitive("Milk")) + edits))
        fun addCase(name: String, intent: Intent, before: Projection = Projection(), extreme: Boolean = false, legacyIds: Boolean = false, unsupportedNumber: Boolean = false) {
            val result = reducer.apply(before, intent)
            add(buildJsonObject {
                put("name", name); put("before", value(before))
                // Build the protocol object directly to exercise valid raw JSON
                // lexemes, including those a serializer would canonicalize.
                put("intent", buildJsonObject {
                    put("id", intent.id); put("listId", intent.listId); put("kind", intent.kind)
                    put("payload", intent.payload); put("schema", intent.schema); put("normalizer", intent.normalizer)
                })
                put("unsupportedQuantity", extreme); put("unsupportedLegacyIds", legacyIds)
                put("unsupportedNumber", unsupportedNumber)
                put("expectedOutcome", result.outcome.name)
                put("expectedRows", JsonObject(result.projection.rows.mapValues { it.value.facts }))
                put("expectedAliases", value(result.projection.aliases)); put("expectedTombstones", value(result.projection.tombstones))
                put("expectedPurchases", value(result.projection.purchases))
            })
        }
        for ((i, quantity) in listOf("0.0", "-0.0", "1.0", "1", "0.001", "1.0E7", "1.0E-4", "NaN", "Infinity", "-1.0", "4.9E-324", "5.0E-324", "1.7976931348623157E308", "1.8E308", "9.007199254740992E15").withIndex()) {
            addCase("quantity-string-$quantity", capture("quantity-$i", mapOf("quantity" to JsonPrimitive(quantity), "unit" to JsonPrimitive("LITRE"))),
                extreme = quantity in setOf("4.9E-324", "5.0E-324", "1.7976931348623157E308", "1.8E308", "9.007199254740992E15"))
        }
        for ((i, gtin) in listOf("4006381333931", "4006381333932", "0000000000000", "12345670", "036000291452", "10012345000017", "abc").withIndex()) {
            addCase("gtin-$gtin", capture("gtin-$i", mapOf("gtin" to JsonPrimitive(gtin))))
        }
        val primitives = listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(false), JsonPrimitive(""), JsonPrimitive("1.0"), Wire.json.parseToJsonElement("1.0"), Wire.json.parseToJsonElement("1e0"), Wire.json.parseToJsonElement("-0.0"), buildJsonObject { put("x", 1) }, buildJsonArray { add(1) })
        for (field in listOf("key", "name", "position", "fromSuggestion", "checkedAt", "quantity")) for ((i, primitive) in primitives.withIndex()) {
            val edits = if (field == "quantity") mapOf(field to primitive, "unit" to JsonPrimitive("PIECE")) else mapOf(field to primitive)
            addCase("primitive-$field-$i", capture("$field-$i", edits))
        }
        val initial = reducer.apply(Projection(), capture("base", mapOf("quantity" to JsonPrimitive("1.1"), "unit" to JsonPrimitive("LITRE")))).projection
        for ((i, quantity) in listOf("2.2", "0.001", "1.0E7").withIndex()) {
            addCase("merge-decimal-$quantity", capture("merge-$i", mapOf("quantity" to JsonPrimitive(quantity), "unit" to JsonPrimitive("LITRE"))), initial)
        }
        for (stamp in listOf("01", "+1", "-0", "1e0", "1.0")) {
            addCase("checked-at-string-$stamp", capture("stamp-$stamp", mapOf("checked" to JsonPrimitive(true), "checkedAt" to JsonPrimitive(stamp))))
        }
        addCase("hexadecimal-position", capture("hex-position", mapOf("position" to JsonPrimitive("0x1.0p0"))), unsupportedNumber = true)
        addCase("hexadecimal-quantity-rejected", capture("hex-quantity", mapOf("quantity" to JsonPrimitive("0x1.0p0"), "unit" to JsonPrimitive("LITRE"))))
        val numericImport = Intent("numeric-import", list, "import", buildJsonObject {
            put("rows", buildJsonArray {
                for (target in listOf("2", "1")) add(buildJsonObject {
                    put("id", target); put("facts", buildJsonObject {
                        put("key", "milk"); put("name", "Milk"); put("checked", false)
                        put("quantity", if (target == "2") "1.0" else "2.0"); put("unit", "LITRE")
                    })
                })
            })
        })
        addCase("numeric-id-import", numericImport, legacyIds = true)
        val legacy = reducer.apply(Projection(), numericImport).projection
        check(legacy.rows.keys.toList() == listOf("2", "1"))
        addCase("numeric-id-first-merge", capture("numeric-merge", mapOf("quantity" to JsonPrimitive("1.0"), "unit" to JsonPrimitive("LITRE"))), legacy, legacyIds = true)
        val tiedPurchases = Projection(
            purchases = listOf(10, 2).associate { purchase -> purchase.toString() to buildJsonObject {
                put("id", purchase); put("tripId", "trip"); put("key", "milk"); put("name", "Buy$purchase"); put("boughtAt", 0)
            } },
            trips = mapOf("trip" to buildJsonObject { put("closedAt", 0) }),
            rules = mapOf("milk" to buildJsonObject { put("revision", "rule"); put("everyDays", 1) }),
        )
        SnapshotCodec.encode(tiedPurchases)
        addCase("numeric-purchase-text-order", Intent("numeric-purchase-repeat", list, "repeat", buildJsonObject {
            put("key", "milk"); put("ruleRevision", "rule"); put("enablingPurchase", "10"); put("asOf", 86400000)
            put("proposedId", "repeat-row"); put("occurrence", Reducer.occurrenceId(list, "milk", "rule", "10"))
        }), tiedPurchases)
    }
    private fun verifyBrowser(directory: Path) {
        val input = Wire.json.parseToJsonElement(Files.readString(directory.resolve("kotlin-input.json"))).jsonObject
        val output = Wire.json.parseToJsonElement(Files.readString(directory.resolve("browser-output.json"))).jsonObject
        check(input.getValue("synthetic").jsonPrimitive.boolean)
        val recipient = Wire.json.decodeFromJsonElement<DeviceSecrets>(input.getValue("recipient"))
        val key = Wire.decode(input.getValue("key").jsonPrimitive.content, 32)
        val server = Wire.json.decodeFromJsonElement<ServerInfo>(input.getValue("server"))
        val household = input.getValue("householdId").jsonPrimitive.content
        val list = input.getValue("listId").jsonPrimitive.content
        val envelope = Wire.json.decodeFromJsonElement<Envelope>(output.getValue("envelope"))
        val header = envelope.parsed()
        check(header.serverId == server.serverId && header.householdId == household && header.listId == list && header.authorDeviceId == recipient.publicKeys.id)
        check(SyncCrypto.open(envelope, key, recipient.publicKeys.signingPublicKey).contentEquals(Wire.decode(input.getValue("plaintext").jsonPrimitive.content)))
        val wrapped = Wire.json.decodeFromJsonElement<KeyPackage>(output.getValue("package"))
        check(wrapped.listId == list && wrapped.epoch == 3L)
        check(SyncCrypto.unwrap(server.serverId, household, wrapped, recipient, recipient.publicKeys.signingPublicKey).contentEquals(key))
        println("PASS: browser-created envelope and HPKE package verified by JVM/Tink")
        val created = Wire.json.parseToJsonElement(Files.readString(directory.resolve("browser-owner-output.json"))).jsonObject
        val kit = RecoveryKits.open(Wire.decode(created.getValue("file").jsonPrimitive.content), created.getValue("secret").jsonPrimitive.content)
        val signed = Wire.json.decodeFromJsonElement<SignedManifest>(created.getValue("signed"))
        val restored = VerifiedMembership.genesis(kit.genesis, kit.server, kit.household, kit.genesis.signed().hash())
            .advance(ManifestProof(signed.manifestBytes, signed.signature))
        check(restored.manifest.action == "create_list" && restored.manifest.epoch == 2L)
        val recoveryFingerprint = Wire.fingerprint(kit.server.serverId, kit.household, kit.recovery.publicKeys.signingPublicKey, kit.recovery.publicKeys.recipientPublicKey)
        val recoveryPackage = signed.packages.single { it.recipientFingerprint == recoveryFingerprint }
        val owner = restored.manifest.members.single().devices.single()
        val listKey = SyncCrypto.unwrap(kit.server.serverId, kit.household, recoveryPackage, kit.recovery, owner.signingPublicKey)
        val snapshot = Wire.json.decodeFromJsonElement<Snapshot>(created.getValue("snapshot"))
        val projection = SnapshotCodec.decode(SyncCrypto.open(snapshot.envelope, listKey, owner.signingPublicKey, 10 * 1024 * 1024))
        check(projection.name == "Our list" && projection.rows.isEmpty())
        println("PASS: browser household, recovery kit, key packages and initial snapshot verified by native codecs")
    }
}
