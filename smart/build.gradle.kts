plugins { alias(libs.plugins.kotlin.jvm) }
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
dependencies {
    api(project(":core:model"))
    implementation(project(":core:parse"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.register<JavaExec>("replay") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.theficos.comanda.smart.ReplayCliKt")
    args(providers.gradleProperty("history").orElse("/tmp/comanda-history.json").get())
}
