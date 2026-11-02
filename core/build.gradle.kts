import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

// models.lock.json (repo root) is compiled into core as a string constant, so the apps carry the
// exact hashes they were built with and cannot be pointed at other files at runtime.
val generateLockedModels by tasks.registering {
    val input = rootProject.layout.projectDirectory.file("models.lock.json")
    val outDir = layout.buildDirectory.dir("generated/lock/kotlin")
    inputs.file(input).optional()
    outputs.dir(outDir)
    doLast {
        val src = input.asFile
        val json = if (src.exists()) src.readText() else """{"schemaVersion":1,"items":[]}"""
        val escaped = buildString {
            for (c in json) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> {}
                else -> append(c)
            }
        }
        val file = outDir.get().file("io/github/ahmetdurmus1218/jarvis/core/models/LockedModelsJson.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package io.github.ahmetdurmus1218.jarvis.core.models\n\n" +
                "/** Generated from models.lock.json at build time. Do not edit. */\n" +
                "internal const val LOCKED_MODELS_JSON: String = \"$escaped\"\n"
        )
    }
}

kotlin {
    jvmToolchain(21)

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.github.ahmetdurmus1218.jarvis.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateLockedModels)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.datetime)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
