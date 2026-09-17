cmake_minimum_required(VERSION 3.22.1)

project("ondevice_mnn_jni")

# MNN 源码（git submodule，钉死 commit 18759c83）
set(MNN_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/MNN)

# 让 set() 覆盖 option()（关键：使下面的 FORCE 能压过 MNN 上游默认）
if(POLICY CMP0077)
    cmake_policy(SET CMP0077 NEW)
endif()

# 避免 MNN POST_BUILD 命令问题
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "Build from command" FORCE)

# LLM 支持 + 低内存/量化优化（FORCE ON）
set(MNN_BUILD_LLM ON CACHE BOOL "Build LLM support" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "Use low memory mode" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "Support transformer fuse" FORCE)
set(MNN_CPU_WEIGHT_DEQUANT_GEMM ON CACHE BOOL "CPU weight dequant gemm" FORCE)

# CPU only 首发：显式 OFF FORCE 所有 GPU 后端（覆盖 MNN 上游默认 + gradle -D，避免 Operit 那种 FORCE 误开）
set(MNN_VULKAN OFF CACHE BOOL "Enable Vulkan backend" FORCE)
set(MNN_OPENCL OFF CACHE BOOL "Enable OpenCL backend" FORCE)
set(MNN_OPENGL OFF CACHE BOOL "Enable OpenGL backend" FORCE)
set(MNN_SUPPORT_RENDER OFF CACHE BOOL "Enable render backend" FORCE)

# 不分离编译——所有后端编入 libMNN.so（CPU only 下即纯 CPU 后端）
set(MNN_SEP_BUILD OFF CACHE BOOL "Build backends separately" FORCE)

# 禁用非必要功能以加速编译
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "Build benchmark" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "Build test" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "Build tools" FORCE)
set(MNN_BUILD_QUANTOOLS OFF CACHE BOOL "Build quantools" FORCE)
set(MNN_EVALUATION OFF CACHE BOOL "Build evaluation" FORCE)
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "Build converter" FORCE)
set(MNN_BUILD_TRAIN OFF CACHE BOOL "Build train" FORCE)

# 从 submodule 源码编译 MNN
add_subdirectory(${MNN_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/MNN)

# 只编 LLM JNI wrapper（去掉 Operit 的 net/module，本模块不需要通用推理网络）
add_library(MNNWrapper SHARED mnnllmnative.cpp)

target_include_directories(MNNWrapper PRIVATE
    ${MNN_SOURCE_DIR}/include
    ${MNN_SOURCE_DIR}/source
    ${MNN_SOURCE_DIR}/express
    ${MNN_SOURCE_DIR}/transformers/llm/engine/include
    ${MNN_SOURCE_DIR}/transformers/llm/engine/src
    ${MNN_SOURCE_DIR}/3rd_party
)

# 解决 TLS 问题
target_compile_options(MNNWrapper PRIVATE -fno-emulated-tls)
target_compile_options(MNN PRIVATE -fno-emulated-tls)
if(TARGET llm)
    target_compile_options(llm PRIVATE -fno-emulated-tls)
    # minja chat template 引擎——structured tool call 依赖此（渲染 llm_config.json 的 jinja.chat_template）
    target_compile_options(MNNWrapper PRIVATE -DLLM_USE_MINJA)
endif()

target_link_libraries(MNNWrapper MNN android log jnigraphics)
if(TARGET llm)
    target_link_libraries(MNNWrapper llm)
endif()

# 16KB page 对齐（Android 15+ 要求，三个 target 都要）
target_link_options(MNNWrapper PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(MNN PRIVATE "-Wl,-z,max-page-size=16384")
if(TARGET llm)
    target_link_options(llm PRIVATE "-Wl,-z,max-page-size=16384")
endif()
