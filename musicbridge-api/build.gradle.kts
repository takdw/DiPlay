plugins { id("com.android.library") }

android {
    namespace = "com.shihab.diplay.musicbridge.api"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
