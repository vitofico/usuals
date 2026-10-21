import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
application { mainClass = "io.theficos.usuals.relay.MainKt" }
dependencies {
    implementation(project(":sync"))
    implementation(project(":llm"))
    implementation(project(":core:parse"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tink)
    implementation(libs.relay.ktor.core)
    implementation(libs.relay.ktor.netty)
    implementation(libs.relay.ktor.statuspages)
    // Same Ktor release as the catalog's server modules; gzips the two large list responses.
    implementation("io.ktor:ktor-server-compression-jvm:${libs.versions.ktor.get()}")
    implementation(libs.relay.pgjdbc)
    implementation(libs.relay.hikari)
    testImplementation(libs.relay.ktor.test)
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

// Explicit synthetic fixture, isolated from all production startup paths.
tasks.register<JavaExec>("browserPreview") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.theficos.usuals.relay.BrowserPreviewFixture")
    jvmArgs("-Xmx512m")
    doFirst {
        args(requireNotNull(providers.gradleProperty("previewPort").orNull),
            requireNotNull(providers.gradleProperty("previewOutput").orNull))
        providers.gradleProperty("previewModelOrigin").orNull?.let { args(it) }
    }
}


// Ship only the self-contained browser runtime, never test fixtures or sources of secrets.
val stageWebAssets by tasks.registering(Sync::class) {
    from("../web") { include("index.html", "assets/**", "src/**", "locales/**") }
    inputs.dir("../core/parse/src/main/resources/input-language")
    into(layout.buildDirectory.dir("generated/web-resources/web"))
    doLast {
        val codes = rootProject.file("../web/locales").listFiles().orEmpty()
            .filter { it.extension == "json" }.map { it.nameWithoutExtension }.sorted()
        check("en" in codes && codes.all { Regex("[a-z]{2,3}(?:-[A-Z]{2})?").matches(it) }) { "invalid_web_locales" }
        destinationDir.resolve("locales.json").writeText(codes.joinToString(",", "[", "]") { "\"$it\"" } + "\n")
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        val keys = listOf("ingredient-headings", "ignored-recipe-headings", "numbers", "multipliers", "units", "recipe-measures", "connectors")
        val packs = fileTree("../core/parse/src/main/resources/input-language") { include("*.properties") }.files.sorted().map { file ->
            val values = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
            keys.joinToString(",", "{", "}") { quote(it) + ":" + quote(values.getProperty(it, "")) }
        }
        destinationDir.resolve("assets/import-language.json").writeText(packs.joinToString(",", "[", "]") + "\n")
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/web-resources")) }
tasks.processResources { dependsOn(stageWebAssets) }

val webTest by tasks.registering(Exec::class) {
    workingDir(rootProject.file(".."))
    doFirst {
        val tests = fileTree("../web/test") { include("*.test.mjs") }.files.sorted()
        check(tests.isNotEmpty()) { "missing_web_tests" }
        commandLine(listOf("node", "--test") + tests.map { it.absolutePath })
    }
}
tasks.check { dependsOn(webTest) }
