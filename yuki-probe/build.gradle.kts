
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


val hotReloadPatch = tasks.register("patchHotReload") {
    group = "yuki"
    description = "为 KSP 生成的模块元数据与入口类补上热重载支持"

    doLast {
        val kspRoot = layout.buildDirectory.dir("generated/ksp").get().asFile
        if (!kspRoot.isDirectory) {
            logger.lifecycle("[hotReload] 未找到 KSP 产物目录，跳过：$kspRoot")
            return@doLast
        }

        var propPatched = 0
        kspRoot.walkTopDown().filter { it.isFile && it.name == "module.prop" }.forEach { prop ->
            val text = prop.readText()
            if (text.contains("autoHotReload=false")) {
                prop.writeText(text.replace("autoHotReload=false", "autoHotReload=true"))
                propPatched++
                logger.lifecycle("[hotReload] module.prop: autoHotReload -> true  (${prop.absolutePath})")
            }
        }

        val override = """
            |    // [BetterHeybox POC] 热重载补丁：Yuki 1.5.0-beta.4 未覆写该方法，
            |    // 导致继承 XposedModule 的默认实现（return false）而拒绝热重载。
            |    override fun onHotReloading(
            |        param: io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
            |    ): Boolean = true
        """.trimMargin()

        var classPatched = 0
        kspRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith("_YukiHookXposedInit.kt") }
            .forEach { src ->
                val text = src.readText()
                if (text.contains("onHotReloading")) return@forEach
                val lastBrace = text.lastIndexOf('}')
                if (lastBrace <= 0) return@forEach
                val patched = text.substring(0, lastBrace).trimEnd('\n', '\r') + "\n\n" + override + "\n}\n"
                src.writeText(patched)
                classPatched++
                logger.lifecycle("[hotReload] 入口类: 已补 onHotReloading  (${src.absolutePath})")
            }

        logger.lifecycle("[hotReload] 完成：module.prop=$propPatched 处，入口类=$classPatched 处")
        if (propPatched == 0 || classPatched == 0) {
            logger.warn("[hotReload] 补丁未完全命中，热重载可能不会生效")
        }
    }
}

gradle.projectsEvaluated {
    val isMainVariant = { n: String -> !n.contains("Test") }
    val isKsp = { n: String -> isMainVariant(n) && n.startsWith("ksp") && n.endsWith("Kotlin") }
    val isCompile = { n: String -> isMainVariant(n) && n.startsWith("compile") && n.endsWith("Kotlin") }

    tasks.matching { isKsp(it.name) }.forEach { kspTask ->
        hotReloadPatch.configure { dependsOn(kspTask) }
    }
    tasks.matching { isCompile(it.name) }.forEach { compileTask ->
        compileTask.dependsOn(hotReloadPatch)
    }
}

dependencies {
    implementation(libs.yuki.core)
    implementation(libs.yuki.runtime.libxposed)
    implementation(libs.kavaref.core)
    compileOnly(libs.libxposed.api)
    ksp(libs.yuki.compiler)
}
