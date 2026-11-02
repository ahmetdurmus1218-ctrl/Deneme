plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

apply(from = rootProject.file("gradle/signing.gradle.kts"))

// sherpa-onnx (Whisper speech-to-text) is published only as a GitHub release asset, not on Maven.
// The AAR is fetched once into the build folder and must match the pinned SHA-256, else the build fails.
val sherpaVersion = libs.versions.sherpa.onnx.get()
val sherpaSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaAar = layout.buildDirectory.file("third-party/sherpa-onnx-$sherpaVersion.aar")
val fetchSherpaAar by tasks.registering {
    val out = sherpaAar
    outputs.file(out)
    doLast {
        val target = out.get().asFile
        fun sha256(f: java.io.File): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        if (target.isFile && sha256(target) == sherpaSha256) return@doLast
        target.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
        val tmp = java.io.File(target.path + ".part")
        java.net.URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val actual = sha256(tmp)
        if (actual != sherpaSha256) {
            tmp.delete()
            throw GradleException("sherpa-onnx AAR SHA-256 mismatch: expected $sherpaSha256, got $actual")
        }
        tmp.renameTo(target)
    }
}

android {
    namespace = "io.github.ahmetdurmus1218.jarvis"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.ahmetdurmus1218.jarvis"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = project.extra["jarvisVersionCode"] as Int
        versionName = project.version.toString()
        // 64-bit phones (all Android 12+ devices ship 64-bit) and the x86_64 emulator used by CI.
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        // On-device probes run by the CI emulator test (no test framework needed), see src/androidTest.
        testInstrumentationRunner = "io.github.ahmetdurmus1218.jarvis.probe.WhisperProbe"
    }
    // The probe APK targets the release app that the emulator test installs.
    testBuildType = "release"

    signingConfigs {
        create("jarvis") {
            storeFile = file(project.extra["jarvisStoreFile"] as String)
            storePassword = project.extra["jarvisStorePassword"] as String
            keyAlias = project.extra["jarvisKeyAlias"] as String
            keyPassword = project.extra["jarvisKeyPassword"] as String
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("jarvis")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarvis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        jniLibs.pickFirsts += setOf("**/libc++_shared.so")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi", "MissingTranslation")
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)
    implementation(files(sherpaAar).builtBy(fetchSherpaAar))
}
