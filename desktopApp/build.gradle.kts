import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
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
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
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
            description = "Jarvis — çevrimdışı Türkçe kişisel asistan"
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
