// Pure Kotlin/JVM: the Kotlin twin of src/providers. :providers may use :core, never the
// reverse. OkHttp is the only network library; nothing Android is on this classpath.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    // ProviderException lives in :core and is part of this module's API.
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // The vector loader (Vectors.kt) is shared from :core's test fixtures.
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The providers vector family is replayed here. Declared as an input through an argument
    // provider, as in :core, so a vector edit re-runs these tests instead of hitting the cache.
    jvmArgumentProviders.add(GoldenVectors(rootProject.layout.projectDirectory.dir("../test/vectors").asFile))
}

class GoldenVectors(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val dir: File,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dideaforge.vectors=${dir.absolutePath}")
}
