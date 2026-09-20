import java.util.Properties

private val platformKeyDirectory = file("tools/key")
private val platformKeyStore = platformKeyDirectory.resolve("platform.p12")
private val matrixVersionName = providers.gradleProperty("MATRIX_VERSION_NAME").get()
private val matrixVersionCode = providers.gradleProperty("MATRIX_VERSION_CODE").get().toInt()
private val platformSigningProperties = Properties().apply {
    val propertiesFile = platformKeyDirectory.resolve("signing.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

// 内部调试轨迹门控（评估 v1.0 §4.3）
val traceRequested = providers.gradleProperty("matrix.debugTraceUi")
    .map(String::toBoolean)
    .orElse(false)

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.matrix.agent.launcher"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.matrix.agent.launcher"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = matrixVersionCode
        versionName = matrixVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("platform") {
            storeFile = platformKeyStore
            storePassword = platformSigningProperties.getProperty("storePassword", "")
            keyAlias = platformSigningProperties.getProperty("keyAlias", "platform")
            keyPassword = platformSigningProperties.getProperty("keyPassword", "")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            // Keep local debug builds installable over the platform-signed prebuilt Launcher.
            signingConfig = signingConfigs.getByName("platform")
            buildConfigField("boolean", "MATRIX_DEBUG_TRACE_UI",
                    traceRequested.get().toString())
        }
        findByName("internal")?.let { internal ->
            internal.buildConfigField("boolean", "MATRIX_DEBUG_TRACE_UI",
                    traceRequested.get().toString())
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("platform")
            buildConfigField("boolean", "MATRIX_DEBUG_TRACE_UI", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // API 36 is the project-wide approved Android baseline. Moving to a newer target
        // requires a coordinated platform/compatibility review, not a Launcher-only bump.
        disable += "OldTargetApi"
    }
}

dependencies {
    // 只依赖 service-lib，不接触 Service APK 实现（重整版 §5.1）
    implementation(project(":matrix-agent-service-lib"))

    // XML View + Java ViewModel + LiveData
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.drawerlayout)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
