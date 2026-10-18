plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // One Tink implementation per runtime. Android also upgrades SecurityCrypto's legacy reader.
    implementation(if (rootProject.name == "comanda-relay") libs.tink else libs.tink.android)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
sourceSets.test { resources.srcDir("${projectDir}/../testing/src/test/resources") }
