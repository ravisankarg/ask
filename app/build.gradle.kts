import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val androidNdk = file(
    System.getenv("ANDROID_NDK_HOME")
        ?: "/home/ravi/AG/Android_SDK/android-sdk/ndk/27.2.12479018"
)
val rustProject = rootProject.file("native/askgalaxy-native")
val rustTargetDir = rootProject.file(".native-target")
val generatedJniDir = layout.buildDirectory.dir("generated/rust/jniLibs")
val cargoHome = rootProject.file(".cargo-home")
val lfmNativeProject = rootProject.file("native/lfm")
val lfmNativeBuildDir = layout.buildDirectory.dir("native/lfm")

android {
    namespace = "com.ravi.askgalaxy"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.ravi.askgalaxy"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].jniLibs.srcDir(generatedJniDir)
    // The direct LiteRT Community SigLIP2 artifact is shipped as an
    // uncompressed APK asset when present. ModelInstaller copies it into the
    // app-private model directory on first launch; the catalog also carries a
    // pinned download URL for builds that do not bundle the binary.
    sourceSets["main"].assets.srcDirs(
        rootProject.file("model-artifacts/siglip2"),
        rootProject.file("model-artifacts/face"),
    )

    androidResources {
        noCompress += "tflite"
    }

    packaging {
        // AGP 8.13 packages native libraries at 16 KiB ZIP boundaries; the
        // Rust linker is configured with matching ELF load-segment pages.
        jniLibs.useLegacyPackaging = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Bundled Text Recognition v2 is immediately available offline. Unlike
    // the previous hand-written PP-OCR decoder, it also exposes calibrated
    // line confidence and native layout/rotation handling.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // LiteRT executes the visual/text encoders and face models through native code.
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    // LiteRT-LM provides the native Gemma session runtime; the .litertlm file
    // is installed separately because the E4B artifact is too large to bundle.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.1")
}

val buildRustNative = tasks.register<Exec>("buildRustNative") {
    group = "native"
    description = "Build the arm64 Rust TurboQuant JNI library."
    workingDir(rustProject)
    environment("ANDROID_NDK_HOME", androidNdk.absolutePath)
    environment("ANDROID_NDK_ROOT", androidNdk.absolutePath)
    environment("CARGO_TARGET_DIR", rustTargetDir.absolutePath)
    environment("CARGO_HOME", cargoHome.absolutePath)
    commandLine("cargo", "build", "--release", "--target", "aarch64-linux-android")

    doLast {
        copy {
            from(rustTargetDir.resolve("aarch64-linux-android/release/libaskgalaxy_native.so"))
            into(generatedJniDir.get().dir("arm64-v8a"))
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildRustNative)
}

val buildLfmNative = tasks.register<Exec>("buildLfmNative") {
    group = "native"
    description = "Build the local llama.cpp LFM2.5-VL JNI runtime."
    val cmakeBin = System.getenv("CMAKE_BIN") ?: "cmake"
    val buildDir = lfmNativeBuildDir.get().asFile
    commandLine(
        cmakeBin,
        "-S", lfmNativeProject.absolutePath,
        "-B", buildDir.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DANDROID_ABI=arm64-v8a",
        "-DANDROID_PLATFORM=android-26",
        "-DCMAKE_TOOLCHAIN_FILE=${androidNdk.absolutePath}/build/cmake/android.toolchain.cmake",
    )
    doLast {
        exec {
            commandLine(cmakeBin, "--build", buildDir.absolutePath, "--target", "askgalaxy_lfm", "-j1")
        }
        delete(generatedJniDir.get().file("arm64-v8a/libggml-vulkan.so"))
        copy {
            from(buildDir.resolve("libaskgalaxy_lfm.so"))
            from(buildDir.resolve("bin")) {
                include("*.so")
                exclude("libggml-vulkan.so")
            }
            into(generatedJniDir.get().dir("arm64-v8a"))
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildLfmNative)
}
