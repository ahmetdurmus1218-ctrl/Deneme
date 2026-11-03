import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// sherpa-onnx (Whisper speech-to-text) for the JVM is published only as GitHub release assets.
// Each jar is fetched once into the build folder and must match its pinned SHA-256.
val sherpaVersion = libs.versions.sherpa.onnx.get()
val sherpaJars = mapOf(
    // Java API
    "sherpa-onnx-jvm-$sherpaVersion.jar" to "8320e2457b6e38dd624fb619d3552e11ef78ffeb394622056126b16f2fd99d12",
    // Windows x64 native libraries (shipped in the MSI)
    "sherpa-onnx-native-lib-win-x64-$sherpaVersion.jar" to "536f1c09025996a780b22f972b2e56e0e8b2064e1ceb534456ea7eba0a6ae4cd",
    // Linux x64 native libraries (tests on the CI runner only)
    "sherpa-onnx-native-lib-linux-x64-$sherpaVersion.jar" to "744330a68a7024d9412f029c23b06f847b8444787c94e5118e0fdaf1b4b2cb3f",
)
val fetchSherpaJars = tasks.register("fetchSherpaJars") {
    val dir = layout.buildDirectory.dir("third-party")
    val base = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion"
    val pins = sherpaJars
    inputs.property("pins", pins)
    outputs.files(pins.keys.map { dir.get().file(it) })
    doLast { pins.forEach { (name, sha) -> fetchVerified("$base/$name", sha, dir.get().file(name).asFile) } }
}
fun sherpaJar(name: String) = files(layout.buildDirectory.file("third-party/$name")).builtBy(fetchSherpaJars)

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

kotlin {
    jvmToolchain(21)
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":ui"))
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(sherpaJar("sherpa-onnx-jvm-$sherpaVersion.jar"))
            implementation(sherpaJar("sherpa-onnx-native-lib-win-x64-$sherpaVersion.jar"))
            implementation(libs.pdfbox.desktop)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(sherpaJar("sherpa-onnx-native-lib-linux-x64-$sherpaVersion.jar"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.ahmetdurmus1218.jarvis.desktop.MainKt"
        jvmArgs += listOf("-Djarvis.version=${project.version}", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Jarvis"
            // MSI needs MAJOR.MINOR.BUILD; strip any "-suffix".
            packageVersion = project.version.toString().substringBefore('-')
            // ASCII only: WiX builds the MSI with code page 1252, which has no ı/ş/ğ.
            description = "Jarvis - offline Turkish personal assistant"
            vendor = "Jarvis"
            copyright = "Jarvis"
            // Bundle a complete Java runtime so the user never installs Java.
            includeAllModules = true
            windows {
                menu = true
                menuGroup = "Jarvis"
                shortcut = true
                dirChooser = false
                perUserInstall = true
                // Fixed so new versions upgrade the old install instead of installing side by side.
                upgradeUuid = "6a0f0c4e-1d43-4a5e-9f0b-3c1d6f2b7a91"
            }
        }
    }
}
