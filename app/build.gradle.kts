import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.yucj.videosplitter"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.yucj.videosplitter"
        minSdk = 26
        targetSdk = 36
        // CI 注入正式版本（-PappVersionCode/-PappVersionName）；本機 build 用 -dev 後綴，
        // 更新檢查把 -dev 視為比任何正式版舊。
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("appVersionName").orNull ?: "0.1.0-dev"
    }

    signingConfigs {
        // CI 從 repo secrets 還原 keystore 後以環境變數提供；
        // 本機沒設這些變數時退回 debug key，方便直接 assembleRelease。
        // 更新安裝要求簽章一致，所以正式發佈一律走 CI 的固定 key。
        val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
        val keystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
        if (keystorePath != null && keystorePassword != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "release"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.media3.transformer)
    implementation(libs.media3.common)
    implementation(libs.media3.effect)
    implementation(libs.kotlinx.coroutines.android)
}
