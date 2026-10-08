plugins { id("com.android.application") }
android {
    namespace = "dev.local.applemusicstrict"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.local.applemusicstrict"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-experimental"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies { compileOnly("io.github.libxposed:api:102.0.0") }
