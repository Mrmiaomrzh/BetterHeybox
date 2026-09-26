
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.better.heybox.yukiprobe"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.better.heybox.yukiprobe"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "v0.0.1-probe"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}


dependencies {
    implementation(libs.yuki.core)
    implementation(libs.yuki.runtime.libxposed)
    implementation(libs.kavaref.core)
    compileOnly(libs.libxposed.api)
    ksp(libs.yuki.compiler)
}
