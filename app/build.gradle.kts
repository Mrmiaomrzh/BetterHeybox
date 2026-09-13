import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.better.heybox"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // 预览版单独包名：可与正式版同时安装、在 LSPosed 里分别勾选（注意两者不要同时启用，
        // 否则同一进程会装两份 Hook）。
        applicationId = "com.better.heybox.preview"
        minSdk = 26
        targetSdk = 37
        versionCode = (project.findProperty("VERSION_CODE") as String? ?: "1").toInt()
        versionName = project.findProperty("VERSION_NAME") as String? ?: "v0.3.1"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            excludes += "lib/x86/**"
        }
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation("com.github.QWEA0:liquidglass:90f4ea28e3")
}
