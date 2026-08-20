import java.util.Base64

plugins {
    id("com.android.application")
}

val encodedKeystore = file("jelliforge-debug.keystore.b64")
val stableKeystore = layout.buildDirectory.file("jelliforge-debug.keystore").get().asFile
if (!stableKeystore.exists()) {
    stableKeystore.parentFile.mkdirs()
    stableKeystore.writeBytes(Base64.getDecoder().decode(encodedKeystore.readText().trim()))
}

android {
    namespace = "com.vhanma.jelliforge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vhanma.jelliforge"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        create("stable") {
            storeFile = stableKeystore
            storePassword = "jelliforge"
            keyAlias = "jelliforge"
            keyPassword = "jelliforge"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
