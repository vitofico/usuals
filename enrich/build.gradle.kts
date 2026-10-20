plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// One project edge, to :core:model, which is what "depends on core:model only"
// in the plan actually means. The rest are libraries, not modules: coroutines
// because every lookup suspends, okhttp because the enricher speaks HTTP, and
// serialization-json for parsing.
//
// The serialization *plugin* is deliberately not applied. Nothing here is
// @Serializable: the OFF response is walked as a JSON tree, the way
// OpenAiCompatibleClient already walks model responses. That is not only house
// style, it is the safer parser for this source. OFF records are sparse and a
// declared-but-absent field throws MissingFieldException on a DTO regardless of
// ignoreUnknownKeys, so a DTO would turn a normal sparse record into a failure.
dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // `api`, not `implementation`, because OpenFoodFactsEnricher takes an
    // OkHttpClient as an injectable constructor parameter and that puts the type
    // in its public signature. Hiding okhttp while exposing it in a constructor
    // is a contradiction the compiler notices, and the alternatives are worse:
    // a second constructor purely to launder the type, or an HTTP abstraction
    // invented for one caller. A module whose job is to speak HTTP may say so.
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
