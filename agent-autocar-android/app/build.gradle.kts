plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "fr.agentautocar"
    compileSdk = 34
    defaultConfig { applicationId = "fr.agentautocar"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "0.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.webkit:webkit:1.11.0") }
