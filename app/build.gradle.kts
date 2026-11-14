import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: a gitignored keystore.properties at the project root (storeFile, storePassword,
// keyAlias, keyPassword), or the env vars LYNE_KEYSTORE_FILE / LYNE_KEYSTORE_PASSWORD /
// LYNE_KEY_ALIAS / LYNE_KEY_PASSWORD (CI). Without either, the release APK is built unsigned so the
// build never breaks.
val releaseSigning: Map<String, String>? = run {
    val props = Properties()
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { props.load(it) }
    fun pick(key: String, env: String): String? =
        props.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }
    val store = pick("storeFile", "LYNE_KEYSTORE_FILE")
    val storePw = pick("storePassword", "LYNE_KEYSTORE_PASSWORD")
    val alias = pick("keyAlias", "LYNE_KEY_ALIAS")
    val keyPw = pick("keyPassword", "LYNE_KEY_PASSWORD") ?: storePw
    if (store != null && storePw != null && alias != null && keyPw != null && rootProject.file(store).isFile) {
        mapOf("storeFile" to store, "storePassword" to storePw, "keyAlias" to alias, "keyPassword" to keyPw)
    } else null
}

android {
    namespace = "com.lyne.adas.l1"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lyne.adas.l1"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.1"

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // TFLite/LiteRT ship native .so per ABI; never compress .tflite model assets.
        jniLibs { useLegacyPackaging = false }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    androidResources {
        noCompress += "tflite"
    }
    lint {
        // MainActivity is a ComponentActivity (no FragmentActivity), so the old fragment 1.1.0 that
        // play-services pulls in transitively never handles our ActivityResult calls.
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)

    implementation(libs.play.services.location)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.support)
    implementation(libs.litert.metadata)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
