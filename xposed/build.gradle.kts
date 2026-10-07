import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.vincenthzr.locationspoofer.xposed"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        // Opt-in, read-only SDK tracing for investigating downstream step statistics.
        buildConfigField("boolean", "STEP_PIPELINE_DIAGNOSTICS",
            (providers.gradleProperty("stepPipelineDiagnostics").orNull == "true").toString())
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }

    // 与 app/build.gradle.kts 中的 scheme 维度保持一致。
    // META-INF/xposed/scope.list 按 flavor 放在 src/scoped/resources 与 src/global/resources 下。
    flavorDimensions += "scheme"
    productFlavors {
        create("scoped") {
            dimension = "scheme"
            buildConfigField("boolean", "GLOBAL_SCHEME", "false")
        }
        create("global") {
            dimension = "scheme"
            buildConfigField("boolean", "GLOBAL_SCHEME", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-geo"))
    compileOnly(libs.xposed.api)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
