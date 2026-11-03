import java.io.File
import java.net.URI
import java.security.MessageDigest

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
val fetchSherpaAar = tasks.register("fetchSherpaAar") {
    val out = sherpaAar
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    val expected = sherpaSha256
    inputs.property("sha256", expected)
    outputs.file(out)
    doLast { fetchVerified(url, expected, out.get().asFile) }
}

/** Downloads [url] to [target] unless a file with the expected SHA-256 is already there. */
fun fetchVerified(url: String, expected: String, target: File) {
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }
    if (target.isFile && sha256(target) == expected) return
    target.parentFile.mkdirs()
    val tmp = File(target.path + ".part")
    URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    val actual = sha256(tmp)
    if (actual != expected) {
        tmp.delete()
        throw GradleException("SHA-256 mismatch for $url: expected $expected, got $actual")
    }
    if (!tmp.renameTo(target)) throw GradleException("could not move ${tmp.name} into place")
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
        // On-device probes run by the CI emulator test (no test framework needed), see src/androidTest.
        testInstrumentationRunner = "io.github.ahmetdurmus1218.jarvis.probe.Probes"
    }
    // The probe APK targets the release app that the emulator test installs.
    testBuildType = "release"

    // One APK per CPU type: jarvis.apk is the arm64 build (every Android 12+ phone is 64-bit ARM);
    // the x86_64 build exists only for the CI emulator. Shipping both in one APK doubled its size.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

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
        // Only the ZIM reader of java-libkiwix is used (AndroidZim loads c++_shared, zim, zim_wrapper);
        // the Kiwix server libraries would add ~30 MB per CPU type.
        jniLibs.excludes += setOf("**/libkiwix.so", "**/libkiwix_wrapper.so")
        // Compressed native libraries: a much smaller download; Android unpacks them once at install.
        jniLibs.useLegacyPackaging = true
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
    implementation(libs.kiwix.libkiwix)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.pdfbox.android) {
        // Only needed for password-protected PDFs, which are refused; saves ~7 MB.
        exclude(group = "org.bouncycastle")
    }
    implementation(files(sherpaAar).builtBy(fetchSherpaAar))
}
