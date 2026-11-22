// ublox-core: u-blox 受信機の UBX プロトコル・集計・記録/再生・受信機設定・RTCM3・NTRIP（Android に依存しない Kotlin/JVM）。
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `java-test-fixtures`
    `maven-publish`
}

group = "jp.kjtr.ublox"
version = "0.4.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    testFixturesImplementation(libs.junit)
}

// テスト用のサンプル（testFixtures）は公開しない
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "ublox-core"
            from(components["java"])
            pom {
                name.set("ublox-core")
                description.set("u-blox 受信機の UBX・集計・記録/再生・受信機設定・RTCM3・NTRIP（Kotlin/JVM）")
            }
        }
    }
}
