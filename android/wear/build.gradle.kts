plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.orvlabs.helm.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.orvlabs.helm"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.tiles)
    implementation(libs.protolayout)
    implementation(libs.protolayout.expression)
    implementation(libs.wearable)
    implementation(libs.guava)
}
