plugins { id("com.android.application") }
android {
    namespace = "dev.local.applemusicstrict"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.local.applemusicstrict"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs.getByName("debug") {
        val preservedKey = rootProject.file(".signing/debug.keystore")
        if (preservedKey.exists()) storeFile = preservedKey
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("com.google.android.material:material:1.12.0")
}
