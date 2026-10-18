plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Version de l'app : passée par la CI à partir du tag (v1.2.3 -> -PappVersion=1.2.3).
val appVersion = (findProperty("appVersion") as String?) ?: "0.1.0"
val appVersionCode = appVersion.split(".").map { it.toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 10_000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 } }
    .coerceAtLeast(1)

// Dépôt GitHub où sont publiées les Releases (mises à jour de l'app).
val updateRepo = (findProperty("updateRepo") as String?) ?: "MatteoMagloire/AI-mobile"

android {
    namespace = "com.matteomagloire.aimobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.matteomagloire.aimobile"
        minSdk = 31
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion

        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")

        ndk {
            // Le Samsung S21 (Snapdragon 888) est en arm64 : on ne compile que cette ABI.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        // Clé debug fixe (versionnée) : chaque APK de test peut s'installer par-dessus le précédent.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystore = System.getenv("RELEASE_KEYSTORE_PATH")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("RELEASE_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
