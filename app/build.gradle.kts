import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Rust core (rust-core/) -> per-ABI .so, packaged through jniLibs.
// ---------------------------------------------------------------------------
val ndkVersion = "29.0.14206865"
val rustAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val androidSdkDir: String = localProperties.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: ""
val cargoBin: String = "${System.getProperty("user.home")}/.cargo/bin/cargo"
val rustJniLibsDir = layout.buildDirectory.dir("rustJniLibs")

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds the Rust calculation core for the Android ABIs."
    workingDir = rootProject.file("rust-core")

    val abiArgs = rustAbis.flatMap { listOf("-t", it) }
    commandLine(
        listOf(
            cargoBin, "ndk",
            *abiArgs.toTypedArray(),
            "-o", rustJniLibsDir.get().asFile.absolutePath,
            "build", "--release"
        )
    )
    environment("ANDROID_NDK_HOME", "$androidSdkDir/ndk/$ndkVersion")
    environment(
        "PATH",
        "${System.getProperty("user.home")}/.cargo/bin:${System.getenv("PATH") ?: ""}"
    )

    inputs.file(rootProject.file("rust-core/Cargo.toml"))
    inputs.dir(rootProject.file("rust-core/src"))
    outputs.dir(rustJniLibsDir)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(cargoNdkBuild)
}

android {
    namespace = "com.oxi.calc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oxi.calc"
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets.getByName("main") {
        jniLibs.srcDir(rustJniLibsDir.get().asFile)
    }

    buildTypes {
        release {
            // R8 code shrinking/optimization: smaller APK and faster cold start on low-end devices.
            optimization {
                enable = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
