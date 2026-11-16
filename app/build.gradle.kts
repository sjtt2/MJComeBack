plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.sjtt2.mjcomeback"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "io.github.sjtt2.mjcomeback"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 编译期依赖现代 Xposed API（libxposed API 102），运行期由 LSPosed 框架提供
    compileOnly(files("libs/libxposed-api-102.jar"))
    // 模块 App 需要与框架通信（写 Remote Preferences），这个库必须打进 APK
    implementation(files("libs/libxposed-service-102.jar"))
}
