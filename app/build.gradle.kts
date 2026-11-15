import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    // 程式碼的套件（namespace）維持 com.c520.shengdewoxin；對外的套件名稱（applicationId）2026-11-15 隨英文名改成 com.c520.saydone
    namespace = "com.c520.shengdewoxin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.c520.saydone"
        // Android 12 起：裝置端語音、RoleManager、FUSED 定位、App 自己的深淺色設定都有，程式不用寫舊版分支
        minSdk = 31
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.0"
    }

    // 簽章放在專案資料夾旁邊的 voice-assistant-signing（不進版控；用 Dropbox 同步，每台電腦都用同一份）：
    // 正式版的金鑰與密碼寫在 signing.properties；開發版用那裡的 debug.keystore，換一台電腦編的開發版也能直接更新手機上的 App。
    // 沒有這個資料夾的電腦照樣能編 debug 版（用 Android 預設的 debug 金鑰），只是編不出簽好章的正式版。
    val signingDir = rootDir.resolveSibling("voice-assistant-signing")
    val signing = Properties().apply { signingDir.resolve("signing.properties").takeIf { it.isFile }?.reader()?.use { load(it) } }
    signingConfigs {
        signingDir.resolve("debug.keystore").takeIf { it.isFile }?.let { getByName("debug").storeFile = it }
        signing.getProperty("storeFile")?.let { store ->
            create("release") {
                storeFile = signingDir.resolve(store)
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        error += listOf(
            "ExportedReceiver", "ExportedService", "UnsafeImplicitIntentLaunch",
            "MutableImplicitPendingIntent", "UnspecifiedRegisterReceiverFlag",
        )
    }
}

kotlin {
    jvmToolchain(17)
}

// 只有單元測試用（不會進 APK）：JUnit，和 Android 內建的 org.json 在電腦上跑測試時的替身
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
