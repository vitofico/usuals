import java.time.Duration

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

/**
 * Robolectric's android-all jar, fetched by Gradle instead of by Robolectric.
 *
 * Left to itself, Robolectric resolves this jar at run time through its own
 * Maven client, into `$HOME/.m2`. Inside the build container that directory is
 * not a mounted volume, so every `docker run --rm` refetched roughly a hundred
 * megabytes, at around forty kilobytes a second, inside a test task that is
 * allowed ten minutes. It could not finish, so the task was killed every time
 * and no test ever ran. The stall showed up as a hung suite rather than as a
 * download, because the fetch happens inside the test JVM with nothing printed:
 * a thread dump parked in `MavenArtifactFetcher.fetchArtifact` is what it
 * actually looks like.
 *
 * Declaring it as a dependency moves the fetch to Gradle, which caches it in
 * `~/.gradle` like every other artifact, and that directory *is* a mounted
 * volume. `robolectric.offline` then forbids the run-time fetcher outright, so
 * a test that asks for an SDK this project has not staged fails immediately and
 * says so, rather than silently going to the network and timing out.
 *
 * Three levels are staged, and the third is the surprising one. A test emulates
 * the level `robolectric.properties` pins, or the one its own `@Config` names,
 * but Robolectric also loads the application package against `targetSdk`, from a
 * second jar, whatever level the test itself runs at. So a suite pinned to 33 in
 * an app targeting 34 was fetching both, which is why the old behaviour cost
 * twice what it looked like it should. A `@Config(sdk = ...)` naming a level not
 * listed here fails immediately, naming the jar it wanted.
 */
// One configuration per level, and not one configuration holding three
// dependencies. Every android-all jar is the same `group:artifact` and differs
// only in version, so a single configuration asks Gradle to resolve a version
// conflict it is entitled to resolve: it picks the newest and silently drops
// the rest, leaving the staging directory holding one jar and the suite failing
// on the two that vanished. Separate configurations are separate resolutions.
val robolectricSdkApi26: Configuration by configurations.creating
val robolectricSdkApi33: Configuration by configurations.creating
val robolectricSdkApi36: Configuration by configurations.creating

dependencies {
    robolectricSdkApi26(libs.robolectric.android.all.api26)
    robolectricSdkApi33(libs.robolectric.android.all.api33)
    robolectricSdkApi36(libs.robolectric.android.all.api36)
}

val stageRobolectricSdk by tasks.registering(Sync::class) {
    description = "Puts the android-all jars where Robolectric can find them offline."
    from(robolectricSdkApi26, robolectricSdkApi33, robolectricSdkApi36)
    into(layout.buildDirectory.dir("robolectric-sdks"))
}

// Wall-clock budget for every unit-test task, in every module.
//
// Borrowed from quire, where a wedged `:app` unit-test task once sat until
// GitHub killed the job at its six-hour ceiling, after the release tag had
// already been pushed. A test task that stops making progress has to fail the
// build rather than idle. This is the inner of two nets; the CI job carries its
// own `timeout-minutes` as the outer one.
subprojects {
    tasks.withType<Test>().configureEach {
        timeout.set(Duration.ofMinutes(10))

        dependsOn(stageRobolectricSdk)
        systemProperty("robolectric.offline", "true")
        systemProperty(
            "robolectric.dependency.dir",
            rootProject.layout.buildDirectory.dir("robolectric-sdks").get().asFile.absolutePath,
        )
    }
}

tasks.register<Exec>("verifyReleaseTools") {
    description = "Checks release identity, publication safety and distribution metadata."
    commandLine("python3", "-m", "unittest", "discover", "-s", "scripts/tests", "-v")
}
