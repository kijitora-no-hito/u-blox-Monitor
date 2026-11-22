// ublox-android: USB 機器の列挙・許可・ドライバ判定、受信機の接続（UbloxReceiver）、内蔵 GNSS、擬似ロケーション、RTK 補正。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "jp.kjtr.ublox"
version = "0.4.0"

android {
    namespace = "jp.kjtr.ublox.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":ublox-core"))
    // UsbCandidate が UsbSerialDriver を持つので api
    api(libs.usb.serial)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "ublox-android"
                from(components["release"])
                pom {
                    name.set("ublox-android")
                    description.set("u-blox 受信機の USB 接続・UbloxReceiver・内蔵 GNSS・擬似ロケーション・RTK 補正（Android）")
                }
            }
        }
    }
}
