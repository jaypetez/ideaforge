// Pure Kotlin/JVM. This module is the Kotlin twin of src/core + src/runtime, and it inherits
// their rule: no platform, no clock, no randomness, no network. Being a plain JVM module is
// the first fence (nothing Android can even be imported); a purity test is the second.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // The golden-vector loader is shared with :providers, which replays the providers family
    // itself (:core cannot depend on :providers). Test fixtures are the one shared test source:
    // :core's tests see them automatically, :providers asks for testFixtures(project(":core")).
    `java-test-fixtures`
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
    // Sessions are JSON documents shared with the web app; the port works on them directly.
    implementation(libs.kotlinx.serialization.json)
    // The runtime awaits an injected provider; suspend functions keep that cancellable.
    implementation(libs.kotlinx.coroutines.core)

    testFixturesApi(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.kotlin.test)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Tests here read JS sources off disk (the schema number now; the golden vectors later).
    // Without declaring them as inputs, Gradle's up-to-date check and build cache would skip
    // the very run that should catch a JS change the Kotlin port has not followed.
    inputs.file(rootProject.layout.projectDirectory.file("../src/core/session.js"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The golden vectors (npm run vectors) are the contract this module is tested against.
    // Passed through an argument provider rather than systemProperty: the provider declares
    // the directory as a relative-path input, so its absolute location stays out of the cache
    // key and a worktree or CI runner can reuse the result.
    jvmArgumentProviders.add(GoldenVectors(rootProject.layout.projectDirectory.dir("../test/vectors").asFile))
}

class GoldenVectors(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val dir: File,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dideaforge.vectors=${dir.absolutePath}")
}
