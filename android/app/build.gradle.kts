import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseKeys = Properties()
val releaseKeysFile = file(System.getProperty("user.home") + "/.config/helm-release/keystore.properties")
if (releaseKeysFile.exists()) releaseKeysFile.inputStream().use { releaseKeys.load(it) }

android {
    namespace = "com.orvlabs.helm"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.orvlabs.helm"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "2.1"
    }

    signingConfigs {
        if (releaseKeys.isNotEmpty()) create("release") {
            storeFile = file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    sourceSets["test"].resources.srcDir("src/main/assets")

    androidResources { generateLocaleConfig = true }

    lint {
        error += setOf("MissingTranslation", "HardcodedText")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)
    implementation(libs.datastore)
    implementation(libs.biometric)
    implementation(libs.core.ktx)
    implementation(libs.okhttp)
    testImplementation("junit:junit:4.13.2")
    implementation(libs.okhttp.sse)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.unstyled.sheet)
    implementation(libs.unstyled.switch)
    implementation(libs.phosphor)
    implementation(libs.glance)
    implementation(libs.work)
    implementation(libs.wearable)
    implementation(libs.genai.prompt)
}
