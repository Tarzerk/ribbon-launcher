plugins {
    id("com.android.application")
}

android {
    namespace = "dev.ribbon.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ribbon.launcher"
        minSdk = 25
        targetSdk = 34
        versionCode = 10
        versionName = "1.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
