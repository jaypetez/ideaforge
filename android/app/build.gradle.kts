plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Type-safe navigation routes are @Serializable classes.
    alias(libs.plugins.kotlin.serialization)
}

// The version already lives in two places (package.json and src/version.js, kept equal by a
// test). Android reads package.json rather than adding a third copy to keep in step.
val appVersion: String = providers.fileContents(layout.projectDirectory.file("../../package.json"))
    .asText
    .map { json ->
        Regex(""""version"\s*:\s*"(\d+\.\d+\.\d+)"""").find(json)?.groupValues?.get(1)
            ?: error("package.json has no MAJOR.MINOR.PATCH version")
    }
    .get()

fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.split('.').map(String::toInt)
    require(minor < 100 && patch < 100) { "versionCode scheme needs minor and patch below 100" }
    return major * 10_000 + minor * 100 + patch
}

android {
    namespace = "io.github.jaypetez.ideaforge"
    // Compile against 37 (current AndroidX requires it); targetSdk stays 36 until the API 37
    // runtime changes, notably local network access, are tested on their own.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.jaypetez.ideaforge"
        // 29: foregroundServiceType="microphone" for hands-free driving, and scoped storage.
        minSdk = 29
        targetSdk = 36
        versionName = appVersion
        versionCode = versionCodeOf(appVersion)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Installs beside a release build instead of replacing it.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The instrumented walkthrough (src/androidTest) on a Gradle managed emulator, which
        // .github/workflows/android-device.yml runs headless:
        //     ./gradlew pixel6Api34DebugAndroidTest
        // An ATD ("automated test device") image: no Play services or launcher apps, so it
        // boots and runs faster, and API 34 is a level ATD images exist for.
        managedDevices {
            allDevices {
                create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel6Api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                    testedAbi = "x86_64"
                }
            }
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The debug build's scripted provider (`--es provider scripted`) replays the walkthrough
// golden vector, so the fixture reaches the app as data rather than a Kotlin transcription of
// tools/fixtures/walkthrough.mjs. Copied into the debug variant's assets only: a release APK
// carries neither the script nor the provider that reads it.
abstract class WalkthroughAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val vector: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        vector.get().asFile.copyTo(File(out, "walkthrough.json"))
    }
}

val walkthroughAsset = tasks.register<WalkthroughAsset>("walkthroughAsset") {
    vector.set(layout.projectDirectory.file("../../test/vectors/walkthrough.json"))
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(walkthroughAsset, WalkthroughAsset::outputDir)
    }
    // AGP creates no release unit-test task, so the debug tests check the release variant's
    // final merged manifest for anything the debug-only recognizer probe (#89) adds. Declared
    // as an input, which also makes the test task build it.
    onVariants(selector().withBuildType("release")) { variant ->
        val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        tasks.withType<Test>().configureEach { jvmArgumentProviders.add(ReleaseManifest(manifest)) }
        // And the release variant's merged assets, which must not carry the walkthrough script
        // the debug-only scripted provider replays (#94).
        val assets = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.ASSETS)
        tasks.withType<Test>().configureEach { jvmArgumentProviders.add(ReleaseAssets(assets)) }
    }
}

class ReleaseAssets(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val dir: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dideaforge.releaseAssets=${dir.get().asFile.absolutePath}")
}

class ReleaseManifest(
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val file: Provider<RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dideaforge.releaseManifest=${file.get().asFile.absolutePath}")
}

// The interview tests replay the walkthrough vector through InterviewViewModel, with the same
// loader and the same input declaration as :core (see GoldenVectors there).
tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(GoldenVectors(rootProject.layout.projectDirectory.dir("../test/vectors").asFile))
}

class GoldenVectors(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val dir: File,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dideaforge.vectors=${dir.absolutePath}")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":providers"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material3.adaptive.navigation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    // The instrumented walkthrough (src/androidTest), run on an emulator.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// The backup interop fixtures (test/fixtures, issue #87): the web's backup that the import
// test reads, and the Android export the web's test/backup.test.mjs reads. Declared as an
// input so a regenerated web fixture reruns the tests, with a relative path sensitivity so
// a worktree or CI runner can reuse the result.
tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(SharedFixtures(rootProject.layout.projectDirectory.dir("../test/fixtures").asFile))
    // IDEAFORGE_UPDATE_FIXTURES=1 lets BackupInteropTest rewrite backup-from-android.json.
    environment("IDEAFORGE_UPDATE_FIXTURES", System.getenv("IDEAFORGE_UPDATE_FIXTURES") ?: "")
}

class SharedFixtures(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val dir: File,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dideaforge.fixtures=${dir.absolutePath}")
}
