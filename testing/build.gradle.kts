plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.tink)
}

tasks.test {
    systemProperty("usuals.root", rootProject.projectDir.absolutePath)
}
