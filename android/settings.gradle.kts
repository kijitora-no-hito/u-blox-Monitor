pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android は jitpack でだけ配布されている
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.mik3y") }
        }
    }
}

rootProject.name = "UbloxMonitor"
include(":app")
// 受信機まわりの部品（ほかのアプリからも使えるように分けた。docs/LIBRARY.md）
include(":ublox-core")     // Kotlin/JVM（Android に依存しない）: UBX・集計・記録/再生・受信機設定・RTCM・NTRIP
include(":ublox-android")  // Android ライブラリ: USB・UbloxReceiver・内蔵 GNSS・擬似ロケーション・RTK 補正
