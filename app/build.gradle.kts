import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名配置：keystore.properties + app-sign.keystore（都不入库）
val keystorePropertiesFile = rootProject.file("keystore.properties")
val signingProps = if (keystorePropertiesFile.exists()) {
    Properties().apply { load(keystorePropertiesFile.inputStream()) }
} else {
    null
}

android {
    namespace = "moe.lovefirefly.bzk.sougouext"
    compileSdk = 37

    defaultConfig {
        applicationId = "moe.lovefirefly.bzk.sougouext"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signingProps != null) {
            create("appSign") {
                keyAlias = signingProps["keyAlias"] as String
                keyPassword = signingProps["keyPassword"] as String
                storeFile = rootProject.file(signingProps["storeFile"] as String)
                storePassword = signingProps["storePassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            if (signingProps != null) signingConfig = signingConfigs.getByName("appSign")
        }
        release {
            if (signingProps != null) signingConfig = signingConfigs.getByName("appSign")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // libxposed API 101+ —— 只编译期需要，框架在运行期提供
    compileOnly("io.github.libxposed:api:101.0.0")
    // 界面侧需要：通过 XposedService 写 remote preferences（模块内读）
    implementation("io.github.libxposed:service:101.0.0")
    // 与 BZK 同款的拖动界面（RecyclerView + ItemTouchHelper），版本取本地缓存里有的
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    // 与 BZK 一致的 Material 3 风格（本地缓存版本）
    implementation("com.google.android.material:material:1.10.0")
}
