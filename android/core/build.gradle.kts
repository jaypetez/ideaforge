// Pure Kotlin/JVM. This module is the Kotlin twin of src/core + src/runtime, and it inherits
// their rule: no platform, no clock, no randomness, no network. Being a plain JVM module is
// the first fence (nothing Android can even be imported); a purity test is the second.
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
}
