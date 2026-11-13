plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Nomor build dari GitHub Actions (naik otomatis tiap build). Di luar GitHub dipakai 1.
val runNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "id.my.ruangaplikasi.suara"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.my.ruangaplikasi.suara"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // Tanda tangan tetap: hanya dipakai bila kunci tersedia (dari GitHub Secrets).
    // Bila tidak ada, build tetap berjalan dengan tanda tangan debug seperti biasa.
    val ksPath: String? = System.getenv("KATE_KEYSTORE")
    signingConfigs {
        if (ksPath != null && File(ksPath).exists()) {
            create("kate") {
                storeFile = File(ksPath)
                storePassword = System.getenv("KATE_STORE_PASS")
                keyAlias = System.getenv("KATE_KEY_ALIAS")
                keyPassword = System.getenv("KATE_KEY_PASS")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("kate")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("kate")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // FileProvider untuk memasang file pembaruan APK
    implementation("androidx.core:core:1.12.0")
}
