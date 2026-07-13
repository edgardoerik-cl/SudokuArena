plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sudokuarena"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sudokuarena"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        val socketUrl = providers.gradleProperty("SOCKET_URL")
            .getOrElse("http://10.0.2.2:3000")
        buildConfigField("String", "SOCKET_URL", "\"$socketUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("io.socket:socket.io-client:2.1.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
