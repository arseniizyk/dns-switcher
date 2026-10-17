import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.quickdns.switcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.quickdns.switcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePropsFile = rootProject.file("keystore.properties")
            val keystoreProps = Properties()
            if (keystorePropsFile.exists()) {
                keystoreProps.load(FileInputStream(keystorePropsFile))
            }

            val storeFilePath = keystoreProps.getProperty("storeFile")
                ?: System.getenv("KEYSTORE_PATH")
                ?: "quickdns-release.jks"
            val keyStoreFile = file(storeFilePath)

            val sPassword = keystoreProps.getProperty("storePassword")
                ?: System.getenv("KEYSTORE_PASSWORD")
            val kAlias = keystoreProps.getProperty("keyAlias")
                ?: System.getenv("KEY_ALIAS")
            val kPassword = keystoreProps.getProperty("keyPassword")
                ?: System.getenv("KEY_PASSWORD")

            if (keyStoreFile.exists() && !sPassword.isNullOrEmpty() && !kAlias.isNullOrEmpty() && !kPassword.isNullOrEmpty()) {
                storeFile = keyStoreFile
                storePassword = sPassword
                keyAlias = kAlias
                keyPassword = kPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose Material 3
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
