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

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.matrix.agent.test"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.matrix.agent.test"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = matrixVersionCode
        versionName = matrixVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The trusted client validates a signature-protected Host API. Instrumentation must
        // therefore target trustedRelease: trustedDebug is signed with the debug build-type
        // key and cannot receive ACCESS_AGENT even though the flavor requests it.
        testBuildType = "release"
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

    // trusted 在 allowlist 中验证正常 API；untrusted 验证越权调用被拒（重整版 §6）
    flavorDimensions += "trust"
    productFlavors {
        create("trusted") {
            dimension = "trust"
            // 可信 release 客户端需与 Host 共享 platform 证书；真机门禁使用
            // connectedTrustedReleaseAndroidTest，而不是默认的 debug 变体。
            signingConfig = signingConfigs.getByName("platform")
        }
        create("untrusted") {
            dimension = "trust"
            applicationIdSuffix = ".untrusted"
            versionNameSuffix = "-untrusted"
            // 保持非 platform 签名，确保越权调用的拒绝路径真实可测。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 对 Service 的唯一编译期认识是 service-lib（重整版 §6）
    implementation(project(":matrix-agent-service-lib"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
}

// Cross-APK tests intentionally do not make the Host an implementation dependency.  The normal
// connectedTrustedReleaseAndroidTest task therefore verifies the *preinstalled system image* and
// correctly fails when vendor/matrix still contains an old APK.  This explicit integration task
// is for a local release candidate: it installs the current platform-signed Host first, then
// exercises both trust boundaries without AGP later removing that Host as a test target.
tasks.register("connectedLocalHostIntegrationAndroidTest") {
    group = "verification"
    description = "Installs current release Host, then verifies trusted and untrusted cross-APK clients."
    dependsOn(
        ":matrix-agent-service:assembleRelease",
        "installTrustedRelease",
        "installTrustedReleaseAndroidTest",
        "installUntrustedRelease",
        "installUntrustedReleaseAndroidTest",
    )
    doLast {
        val sdkProperties = Properties()
        val localProperties = rootProject.file("local.properties")
        if (localProperties.isFile) localProperties.inputStream().use { sdkProperties.load(it) }
        val sdkDir = sdkProperties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
            ?: throw GradleException("无法定位 Android SDK（需 local.properties sdk.dir 或 ANDROID_HOME）")
        val adb = File(sdkDir, "platform-tools/adb").absolutePath
        val hostApk = rootProject.file(
            "matrix-agent-service/build/outputs/apk/release/matrix-agent-service-release.apk",
        )
        if (!hostApk.isFile) throw GradleException("缺少 release Host APK: $hostApk")

        fun run(label: String, command: List<String>, expected: String) {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            logger.lifecycle("== $label ==\n$output")
            if (process.exitValue() != 0 || !output.contains(expected)
                    || output.contains("FAILURES") || output.contains("INSTRUMENTATION_FAILED")) {
                throw GradleException("$label failed:\n$output")
            }
        }

        run("install current release Host", listOf(adb, "install", "-r", hostApk.absolutePath), "Success")
        run("trusted cross-APK", listOf(adb, "shell", "am", "instrument", "-w",
                "com.matrix.agent.test.test/androidx.test.runner.AndroidJUnitRunner"), "OK (2 tests)")
        run("untrusted cross-APK", listOf(adb, "shell", "am", "instrument", "-w",
                "com.matrix.agent.test.untrusted.test/androidx.test.runner.AndroidJUnitRunner"), "OK (1 test)")
    }
}
