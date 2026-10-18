plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
application { mainClass = "io.theficos.usuals.relay.MainKt" }
dependencies {
    implementation(project(":sync"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tink)
    implementation(libs.relay.ktor.core)
    implementation(libs.relay.ktor.netty)
    implementation(libs.relay.ktor.websockets)
    implementation(libs.relay.ktor.statuspages)
    implementation(libs.relay.pgjdbc)
    implementation(libs.relay.hikari)
    testImplementation(libs.relay.ktor.test)
    testImplementation(libs.relay.ktor.client.websockets)
    testImplementation(project(":core:parse"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
subprojects {
    // Both compiler versions share source, never outputs or incremental caches.
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("shared/" + path.replace(':', '_')))
}
allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockFile.set(rootProject.file("locks/" + path.replace(':', '_').ifEmpty { "relay" } + ".lockfile"))
    }
    tasks.withType<Test>().configureEach { maxParallelForks = 1 }
}
tasks.test { systemProperty("usuals.root", file("..").absolutePath) }
sourceSets.test { resources.srcDir("../testing/src/test/resources") }

// Explicit disposable-container load client; never run as part of the unit suite.
tasks.register<JavaExec>("profileClient") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.theficos.usuals.relay.ContainerLoadProfile")
    jvmArgs("-Xmx512m")
    doFirst { args(requireNotNull(providers.gradleProperty("profileInput").orNull) { "Pass -PprofileInput with a private local fixture path" }) }
}

// Explicit synthetic fixture, isolated from all production startup paths.
tasks.register<JavaExec>("browserPreview") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.theficos.usuals.relay.BrowserPreviewFixture")
    jvmArgs("-Xmx512m")
    doFirst {
        args(requireNotNull(providers.gradleProperty("previewPort").orNull),
            requireNotNull(providers.gradleProperty("previewOutput").orNull))
    }
}


// Ship only the self-contained browser runtime, never test fixtures or sources of secrets.
val stageWebAssets by tasks.registering(Sync::class) {
    from("../web") { include("index.html", "assets/**", "src/**", "locales/**") }
    into(layout.buildDirectory.dir("generated/web-resources/web"))
    doLast {
        val codes = rootProject.file("../web/locales").listFiles().orEmpty()
            .filter { it.extension == "json" }.map { it.nameWithoutExtension }.sorted()
        check("en" in codes && codes.all { Regex("[a-z]{2,3}(?:-[A-Z]{2})?").matches(it) }) { "invalid_web_locales" }
        destinationDir.resolve("locales.json").writeText(codes.joinToString(",", "[", "]") { "\"$it\"" } + "\n")
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/web-resources")) }
tasks.processResources { dependsOn(stageWebAssets) }

val webInteropDirectory = layout.buildDirectory.dir("web-interop")
val generateWebInteropFixtures by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.theficos.usuals.relay.WebInteropFixture")
    args("generate", webInteropDirectory.get().asFile.absolutePath)
}
val webTest by tasks.registering(Exec::class) {
    dependsOn(generateWebInteropFixtures)
    workingDir(rootProject.file(".."))
    environment("USUALS_WEB_INTEROP_DIR", webInteropDirectory.get().asFile.absolutePath)
    doFirst {
        val tests = fileTree("../web/test") { include("**/*.test.mjs") }.files.sorted()
        check(tests.isNotEmpty()) { "missing_web_tests" }
        commandLine(listOf("node", "--test") + tests.map { it.absolutePath })
    }
}
val verifyWebInterop by tasks.registering(JavaExec::class) {
    dependsOn(webTest)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.theficos.usuals.relay.WebInteropFixture")
    args("verify", webInteropDirectory.get().asFile.absolutePath)
}
tasks.check { dependsOn(verifyWebInterop) }


// Fast browser-only feedback while the JVM interoperability fixture remains a separate gate.
tasks.register<Exec>("webUnitTest") {
    workingDir(rootProject.file(".."))
    doFirst {
        val tests = fileTree("../web/test") { include("**/*.test.mjs"); exclude("interop.test.mjs") }.files.sorted()
        check(tests.isNotEmpty()) { "missing_web_tests" }
        commandLine(listOf("node", "--test") + tests.map { it.absolutePath })
    }
}
