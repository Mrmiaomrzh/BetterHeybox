pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven {
            name = "yukiLocal"
            url = uri("${rootDir}/gradle/m2-yuki")
        }
    }
}

rootProject.name = "BetterHeybox"
include(":app")
include(":yuki-probe")
