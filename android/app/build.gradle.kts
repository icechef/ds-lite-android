plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.k7.deepseekchat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.k7.deepseekchat"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "1.4.5"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 前端资源已经是压缩好的构建产物，不再二次压缩可以加快打包与首帧
    androidResources {
        noCompress += listOf("js", "css", "woff2", "woff", "ttf")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // 全部使用 Android 框架 API（Activity + WebView），不引入任何第三方依赖，
    // 这样 assembleDebug/release --offline 都能在本机 gradle 缓存下完成。
}
