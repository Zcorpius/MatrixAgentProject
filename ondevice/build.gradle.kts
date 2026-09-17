plugins {
    alias(libs.plugins.android.library)
}

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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 不依赖其它 Matrix 模块（重整版 §2.1.1 依赖图）
    testImplementation(libs.junit)
}
