plugins {
    alias(libs.plugins.android.application)
}

apply(from = rootProject.file("gradle/signing.gradle.kts"))

android {
    namespace = "io.github.ahmetdurmus1218.jarvis.gate"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.ahmetdurmus1218.jarvis.gate"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = project.extra["jarvisVersionCode"] as Int
        versionName = project.version.toString()
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

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi", "MissingTranslation")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlin.test.junit)
}
