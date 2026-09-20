import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
}

// sherpa-onnx 官方产物路径（下载任务在文件底部；android{} 的 sourceSets 需在此前可见）
val sherpaOnnxDir = layout.buildDirectory.dir("sherpa-onnx")
val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxSha256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
val sherpaOnnxAar = sherpaOnnxDir.map { it.file("sherpa-onnx-$sherpaOnnxVersion.aar") }
val sherpaClassesJar = sherpaOnnxDir.map { it.file("sherpa-onnx-classes.jar") }
val sherpaJniLibs = sherpaOnnxDir.map { it.dir("jniLibs") }

android {
    namespace = "com.matrix.agent.ondevice"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")

        // 端侧推理仅支持 arm64-v8a（MNN 模型体积/算力约束）
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fno-emulated-tls")
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-28",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DMNN_BUILD_SHARED_LIBS=ON",
                    "-DMNN_SEP_BUILD=OFF",
                    "-DMNN_BUILD_TOOLS=OFF",
                    "-DMNN_BUILD_DEMO=OFF",
                    "-DMNN_BUILD_CONVERTER=OFF",
                    "-DMNN_USE_LOGCAT=ON",
                    "-DMNN_BUILD_TEST=OFF",
                    "-DMNN_BUILD_BENCHMARK=OFF",
                    "-DMNN_BUILD_QUANTOOLS=OFF",
                    // CPU only 首发：GPU 后端全关，与顶层 CMakeLists 的 OFF FORCE 一致
                    "-DMNN_OPENCL=OFF",
                    "-DMNN_OPENGL=OFF",
                    "-DMNN_VULKAN=OFF",
                    // ARM82（v8.2 dotprod）真机可加速，但 arm64 模拟器 CPU 通常不支持
                    // dotprod → Arm82Backend SIGILL。验证用 OFF（纯 CPU backend）；
                    // 真机部署若 CPU 支持 v8.2 可改 ON。
                    "-DMNN_ARM82=OFF",
                    "-DMNN_BUILD_LLM=ON",
                    "-DMNN_SUPPORT_TRANSFORMER_FUSE=ON",
                    "-DMNN_LOW_MEMORY=ON",
                    "-DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // sherpa-onnx .so（下载任务拆解自官方 AAR，见文件底部）并入 jniLibs
    sourceSets {
        getByName("main") {
            jniLibs.directories.add(sherpaJniLibs.get().asFile.absolutePath)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ---------------------------------------------------------------- sherpa-onnx 官方 AAR 下载任务
// 上游不发布 Maven Central；按 Operit 模式消费官方预编译产物（不手写 C++ JNI）：
// classes.jar 为官方 Kotlin API，jni/arm64-v8a/libsherpa-onnx-jni.so 静态链接 onnxruntime
// （已验证 PT_LOAD 16KB 对齐，满足 Android 15+）。版本与 SHA-256 钉死，产物下载/拆解到
// build 目录，二进制不入 git——与 MNN 走 submodule 的"可复现钉死"策略同源。
//
// AGP 禁止 library 直接依赖本地 .aar（bundleDebugAar 拒绝），故拆解为两件常规产物：
// classes.jar 走 files() 依赖、.so 走 jniLibs srcDir，语义与整 AAR 等价
// （AAR 的 manifest/R.txt 为空壳；本工程不 minify，proguard.txt 无消费方）。
val downloadSherpaOnnxAar by tasks.registering {
    description = "下载官方 sherpa-onnx AAR 并拆解（v$sherpaOnnxVersion，SHA-256 钉死）"
    val aar = sherpaOnnxAar.get().asFile
    val classesJar = sherpaClassesJar.get().asFile
    val jniSo = File(sherpaJniLibs.get().asFile, "arm64-v8a/libsherpa-onnx-jni.so")
    outputs.files(aar, classesJar, jniSo)
    outputs.upToDateWhen { aar.isFile && classesJar.isFile && jniSo.isFile }
    doLast {
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "v$sherpaOnnxVersion/sherpa-onnx-static-link-onnxruntime-$sherpaOnnxVersion.aar"
        val digest = MessageDigest.getInstance("SHA-256")
        val tmp = File(aar.parentFile, aar.name + ".part").apply { parentFile.mkdirs() }
        @Suppress("DEPRECATION")
        URL(url).openStream().use { input ->
            DigestInputStream(input, digest).use { hashed ->
                tmp.outputStream().use { hashed.copyTo(it) }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sherpaOnnxSha256) {
            tmp.delete()
            throw GradleException("sherpa-onnx AAR SHA-256 不匹配: $actual（预期 $sherpaOnnxSha256）")
        }
        if (!tmp.renameTo(aar)) throw GradleException("sherpa-onnx AAR 落盘失败: $aar")
        // 拆解：classes.jar + arm64 .so（zip 条目名以 AAR 内部布局为准）
        val zip = ZipFile(aar)
        try {
            val classesEntry = zip.getEntry("classes.jar")
                ?: throw GradleException("AAR 缺少 classes.jar")
            val classesStream = zip.getInputStream(classesEntry)
                ?: throw GradleException("classes.jar 流打开失败")
            classesJar.outputStream().use { out -> classesStream.copyTo(out) }
            classesStream.close()

            val soEntry = zip.getEntry("jni/arm64-v8a/libsherpa-onnx-jni.so")
                ?: throw GradleException("AAR 缺少 arm64-v8a JNI 库")
            val soStream = zip.getInputStream(soEntry)
                ?: throw GradleException("JNI 库流打开失败")
            File(jniSo.parentFile?.path ?: ".").mkdirs()
            jniSo.outputStream().use { out -> soStream.copyTo(out) }
            soStream.close()
        } finally {
            zip.close()
        }
        logger.lifecycle("sherpa-onnx v$sherpaOnnxVersion 就绪: $classesJar + $jniSo")
    }
}

tasks.named("preBuild") { dependsOn(downloadSherpaOnnxAar) }

dependencies {
    // 不依赖其它 Matrix 模块（重整版 §2.1.1 依赖图）
    // builtBy：消费方（:matrix-agent-service desugar/merge）解析该 jar 前自动先跑下载拆解任务
    implementation(files(sherpaClassesJar).builtBy(downloadSherpaOnnxAar))
    implementation(libs.kotlin.stdlib) // Kotlin API 类的运行时依赖（AAR 无 POM 传递）
    testImplementation(libs.junit)
}
