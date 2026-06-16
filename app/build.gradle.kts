plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun pnpmCommand(vararg args: String): List<String> {
    val command = listOf("pnpm") + args
    return if (System.getProperty("os.name").lowercase().contains("windows")) {
        listOf("cmd", "/c") + command
    } else {
        command
    }
}

android {
    namespace = "com.dadigua.hyperbrowser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dadigua.hyperbrowser"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.1.6-beta.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Work around a lint/Kotlin analysis API crash in androidx.lifecycle's
        // NullSafeMutableLiveData detector during release lintVital.
        disable += "NullSafeMutableLiveData"
    }
}

val internalPagesDir = rootProject.layout.projectDirectory.dir("internal-pages")

val pnpmInstallInternalPages = tasks.register<Exec>("pnpmInstallInternalPages") {
    workingDir = internalPagesDir.asFile
    inputs.file(internalPagesDir.file("package.json"))
    inputs.file(internalPagesDir.file("pnpm-lock.yaml"))
    outputs.dir(internalPagesDir.dir("node_modules"))
    commandLine(pnpmCommand("install", "--frozen-lockfile"))
}

val buildInternalPages = tasks.register<Exec>("buildInternalPages") {
    dependsOn(pnpmInstallInternalPages)
    workingDir = internalPagesDir.asFile
    inputs.dir(internalPagesDir.dir("src"))
    inputs.dir(internalPagesDir.dir("public"))
    inputs.file(internalPagesDir.file("home.html"))
    inputs.file(internalPagesDir.file("bookmarks.html"))
    inputs.file(internalPagesDir.file("history.html"))
    inputs.file(internalPagesDir.file("vite.config.ts"))
    inputs.file(internalPagesDir.file("tsconfig.json"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets"))
    commandLine(pnpmCommand("build"))
}

tasks.named("preBuild") {
    dependsOn(buildInternalPages)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.media:media:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.mozilla.geckoview:geckoview:151.0.20260525130955")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

configurations.configureEach {
    resolutionStrategy.force(
        "androidx.core:core:1.15.0",
        "androidx.core:core-ktx:1.15.0"
    )
}
