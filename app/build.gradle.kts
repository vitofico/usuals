plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Static version. Source of truth: gradle.properties (VERSION_NAME, VERSION_CODE).
// Commit the version before tagging so source builds and published APKs agree.
val appVersionName: String = project.property("VERSION_NAME") as String
val appVersionCode: Int = (project.property("VERSION_CODE") as String).toInt()

android {
    namespace = "io.theficos.usuals"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.theficos.usuals"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "21" }
    buildFeatures { compose = true }
    androidResources { generateLocaleConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED") }
    }
    // Don't embed dependency metadata in the APK. AGP 8.x writes it into the v3
    // signing block and F-Droid's `check apk` rejects it as a privacy leak.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // AGP 8.3+ embeds git origin/branch/SHA by default, which breaks
            // F-Droid reproducible builds. The tag already pins the commit.
            vcsInfo.include = false
            // Production keys never enter Gradle. scripts/sign-release signs offline.
            signingConfig = null
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:parse"))
    implementation(project(":data:local"))
    implementation(project(":llm"))
    implementation(project(":smart"))
    implementation(project(":sync"))
    implementation(libs.okhttp)
    implementation(libs.work.runtime.ktx)
    implementation(libs.zxing.core)
    implementation(project(":recipe"))
    implementation(project(":auth"))
    implementation(project(":enrich"))
    implementation(project(":capture"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)

    // CameraX lives here as well as in :capture, deliberately.
    //
    // :capture owns the decoding (ScanAnalyzer, BarcodeDecoder) and keeps its
    // CameraX dependency to itself, which is the module boundary working. But
    // the preview surface is a piece of UI, and UI is this module's job, so
    // binding a camera to a lifecycle and putting a PreviewView on screen needs
    // the artifacts on this compile classpath too. Without them the scan screen
    // can only ever render the "no camera" notice.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)

    // Compose screen tests run under Robolectric so `scripts/dgradle test`
    // exercises composables without an emulator. Tests that need these live in
    // src/testDebug/ so :app:testReleaseUnitTest doesn't try to compile against
    // debugImplementation-scoped deps.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
