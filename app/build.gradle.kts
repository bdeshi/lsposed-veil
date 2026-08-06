import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

// versionName/versionCode come from git, not a hand-maintained literal, so a release tag and
// the app's own version can never drift apart. Exactly on a tagged commit this is the tag
// itself (e.g. "1.0.0"); otherwise it's "<last-tag>-<commits-since>-g<hash>" (git-describe's
// usual dev-build form). No tags yet, or git unavailable: falls back to "0.0.0".
fun gitDescribe(): String {
    return try {
        val out = ByteArrayOutputStream()
        exec {
            commandLine("git", "describe", "--tags", "--always")
            standardOutput = out
        }
        out.toString().trim()
    } catch (e: Exception) {
        "0.0.0"
    }
}

fun gitCommitCount(): Int {
    return try {
        val out = ByteArrayOutputStream()
        exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            standardOutput = out
        }
        out.toString().trim().toInt()
    } catch (e: Exception) {
        1
    }
}

android {
    namespace = "space.bdeshi.veil"
    compileSdk = 35

    defaultConfig {
        applicationId = "space.bdeshi.veil"
        // 27 (Android 8.1) is LSPosed/Zygisk's own practical floor — nothing here restricts
        // it further. No upper bound: compileSdk is a compile-time API surface, not a ceiling
        // on which OS versions can install and run the app.
        minSdk = 27
        targetSdk = 35
        versionCode = gitCommitCount()
        versionName = gitDescribe()
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falls back to debug signing when no keystore.properties exists (e.g. a fresh
            // clone, or CI without release-signing secrets configured), so assembleRelease
            // always produces something installable. See scripts/generate-keystore.sh.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    compileOnly("de.robv.android.xposed:api:82")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
