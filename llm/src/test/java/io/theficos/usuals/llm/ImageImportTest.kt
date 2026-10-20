package io.theficos.usuals.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.util.Base64
import javax.imageio.ImageIO

class ImageImportTest {
    @Test fun imageSupportIsProbedWithSyntheticPixelsAndTheActualPhotoNeedsASeparateCall() = runTest {
        MockWebServer().use { server ->
            server.start()
            val challenge = VisionChallenge.create()
            val config = ProviderConfig.ollama("vision", server.url("/v1").toString())
            val client = OpenAiCompatibleClient(config, imageChallenge = { challenge })
            fun reply(content: JsonObject) = MockResponse().setBody(buildJsonObject { put("choices", buildJsonArray {
                add(buildJsonObject { putJsonObject("message") { put("content", content.toString()) } })
            }) }.toString())
            server.enqueue(reply(buildJsonObject { put("colours", JsonArray(challenge.colours.map(::JsonPrimitive))) }))
            val capabilities = client.probeImages()
            assertThat(capabilities.reachable && capabilities.supportsImages && capabilities.supportsStructuredOutput).isTrue()
            val probe = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val content = probe.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonArray
            val encoded = content[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
            val bitmap = ImageIO.read(Base64.getDecoder().decode(encoded.substringAfter(',' )).inputStream())
            assertThat(bitmap.width).isEqualTo(384)
            assertThat(bitmap.height).isEqualTo(256)
            server.enqueue(reply(buildJsonObject { put("items", buildJsonArray { add(buildJsonObject {
                put("name", "tomatoes"); put("amount", 2); put("unit", "KILOGRAM")
            }) }) }))
            assertThat(client.parseImage(challenge.image).single().name).isEqualTo("tomatoes")
            val actual = server.takeRequest().body.readUtf8()
            assertThat(actual).contains("image_url")
            assertThat(actual).contains("untrusted")
            assertThat(challenge.image.toString()).isEqualTo("ModelImage([redacted])")
        }
    }
    @Test fun textOnlyProviderCannotPassByReturningItsUsualTextProbeReply() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"ok\":1}"}}]}"""))
            val result = OpenAiCompatibleClient(ProviderConfig.ollama("text", server.url("/v1").toString())).probeImages()
            assertThat(result.reachable).isTrue()
            assertThat(result.supportsImages).isFalse()
            assertThat(result.problem?.kind).isEqualTo(ModelProblem.Kind.IMAGES_UNSUPPORTED)
        }
    }
    @Test fun redirectsNeverForwardPhotoBodiesToAnotherEndpoint() = runTest {
        MockWebServer().use { source -> MockWebServer().use { other ->
            source.start(); other.start()
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/stolen")))
            val result = OpenAiCompatibleClient(ProviderConfig.ollama("vision", source.url("/v1").toString())).parseImage(VisionChallenge.create().image)
            assertThat(result).isEmpty()
            assertThat(other.requestCount).isEqualTo(0)
        } }
    }
    @Test fun oversizedRepliesAreRejectedAndUnitOnlyPhotosDoNotInventAmounts() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = OpenAiCompatibleClient(ProviderConfig.ollama("vision", server.url("/v1").toString()))
            server.enqueue(MockResponse().setBody("x".repeat(512001)))
            assertThat(client.probeImages().problem?.kind).isEqualTo(ModelProblem.Kind.NOT_JSON)
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"items\":[{\"name\":\"salt\",\"unit\":\"GRAM\"}]}"}}]}"""))
            assertThat(client.parseImage(VisionChallenge.create().image).single().quantity).isNull()
        }
    }
    @Test fun cancellationCancelsTheUnderlyingImageRequest() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val cancelled = java.util.concurrent.CountDownLatch(1)
            val http = defaultHttpClient().newBuilder().eventListener(object : okhttp3.EventListener() {
                override fun canceled(call: okhttp3.Call) { cancelled.countDown() }
            }).build()
            val client = OpenAiCompatibleClient(ProviderConfig.ollama("vision", server.url("/v1").toString()), http)
            val job = launch { client.probeImages() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { check(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) != null) }
            job.cancel()
            job.join()
            assertThat(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        }
    }
}
