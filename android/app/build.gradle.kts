import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 署名情報は keystore.properties（gitignore 済み）から読む。無ければ release は未署名のまま。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

// Google Maps の API キーは local.properties の MAPS_API_KEY（git 管理外）から Manifest に入れる。
// 無ければプレースホルダのままビルドでき、地図は地理院タイルだけになる（BuildConfig.HAS_MAPS_KEY = false で Google の種類を出さない）。
val mapsApiKey: String = Properties().run {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
    getProperty("MAPS_API_KEY")?.trim().orEmpty()
}
val hasMapsKey = mapsApiKey.isNotEmpty() && !mapsApiKey.startsWith("PLACEHOLDER")

android {
    namespace = "jp.kjtr.ubloxmonitor"
    compileSdk = 36

    defaultConfig {
        // 公開後は変更できない（変えると別アプリ扱いになる）。
        applicationId = "jp.kjtr.ubloxmonitor"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "0.5.0"
        vectorDrawables { useSupportLibrary = true }
        manifestPlaceholders["MAPS_API_KEY"] = if (hasMapsKey) mapsApiKey else "PLACEHOLDER_GET_YOUR_OWN_KEY"
        buildConfigField("boolean", "HAS_MAPS_KEY", hasMapsKey.toString())
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // 受信機まわり（UBX・集計・設定・RTCM・NTRIP / USB・接続・内蔵 GNSS・擬似ロケーション・RTK 補正）
    implementation(project(":ublox-core"))
    implementation(project(":ublox-android"))
    // 地図（地理院タイル）
    implementation(libs.osmdroid)
    // 地図（Google Maps。MAPS_API_KEY があるときだけ選べる。RTK の cm 単位を見るため z21 前後まで寄れる）
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)
    implementation(libs.maps.compose.widgets)
    // play-services-maps が古い fragment（1.0）を連れてくるので新しい版にそろえる（registerForActivityResult の lint・不具合対策）
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    // 更新の確認の JSON（org.json）を JVM のテストで動かすため（android.jar のスタブは中身が無い）
    testImplementation(libs.org.json)
    testImplementation(testFixtures(project(":ublox-core")))

    debugImplementation(libs.androidx.ui.tooling)
}
