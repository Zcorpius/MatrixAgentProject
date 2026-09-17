import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "com.matrix.agent.service"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// AIDL 工具链会为 parcelable 声明生成同名空源文件，与手写 DTO 实现撞名；
// sources jar 只保留手写源码，排除 AIDL 生成目录。
tasks.withType<Jar>().configureEach {
    if (name == "sourceReleaseJar") {
        exclude { it.file.absolutePath.contains("aidl_source_output_dir") }
    }
}

// Maven 坐标占位（com.matrix.agent:matrix-agent-service-lib），版本随 toml 统一管理；
// 仓库地址与凭据经 gradle property 注入（不落明文），缺省仅 publishToMavenLocal。
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.matrix.agent"
            artifactId = "matrix-agent-service-lib"
            version = libs.versions.matrixAgentServiceLib.get()
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

// ── contractHash 生成（审计 A-105）─────────────────────────────────────────
// 对 AIDL 与公开 api Java（Parcelable/常量）做确定性规范化（排序 + 去注释去空白）
// 后取 SHA-256，生成 ContractVersion 常量类；服务端（阶段 B）以同一任务产物对齐。
// 内容变化 ⇒ hash 变化 ⇒ 协商拒绝，阻断 "major 相同、接口产物不一致" 的组合。
val contractGenDir = layout.buildDirectory.dir("generated/contractVersion")
val generateContractHash = tasks.register("generateContractHash") {
    val aidlFiles = fileTree("src/main/aidl")
    val apiFiles = fileTree("src/main/java/com/matrix/agent/api")
    inputs.files(aidlFiles)
    inputs.files(apiFiles)
    outputs.dir(contractGenDir)
    doLast {
        fun normalize(f: File): String =
            f.readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\n]*"), "")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        val allFiles = mutableListOf<File>()
        aidlFiles.forEach { f -> allFiles.add(f) }
        apiFiles.forEach { f -> allFiles.add(f) }
        val entries = allFiles
            .map { f -> f.relativeTo(projectDir).path to normalize(f) }
            .sortedBy { pair -> pair.first }
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { pair ->
            digest.update(pair.first.toByteArray())
            digest.update(pair.second.toByteArray())
        }
        val hash = digest.digest().joinToString("") { b -> "%02x".format(b) }
        val dir = contractGenDir.get().asFile
        dir.mkdirs()
        File(dir, "com/matrix/agent/api/common/ContractVersion.java").apply {
            parentFile.mkdirs()
            writeText(
                """package com.matrix.agent.api.common;

// 由 generateContractHash 任务自动生成：AIDL + 公开 api 的确定性摘要。
// 服务端必须以同一任务产物对齐；协商不匹配时只允许 getServiceInfo。
public final class ContractVersion {
    public static final String CONTRACT_HASH = "$hash";
    private ContractVersion() {
    }
}
""",
            )
        }
    }
}
android {
    sourceSets {
        getByName("main") {
            java.srcDir("$buildDir/generated/contractVersion")
        }
    }
}
tasks.named("preBuild") {
    dependsOn(generateContractHash)
}

// 发布物纯净性门禁（审计 A-007/A-116）：publishToMavenLocal 后校验 POM 与 Gradle module
// metadata 均不含 Kotlin runtime；可独立执行，供 CI 挂载。
tasks.register("verifyPublishedPom") {
    group = "verification"
    description = "校验已发布的 POM/.module 不含 org.jetbrains.kotlin 依赖。"
    dependsOn("publishToMavenLocal")
    doLast {
        val group = "com/matrix/agent"
        val artifact = "matrix-agent-service-lib"
        val version = libs.versions.matrixAgentServiceLib.get()
        val base = System.getProperty("user.home") + "/.m2/repository/$group/$artifact/$version"
        for (fileName in listOf("$artifact-$version.pom", "$artifact-$version.module")) {
            val f = File(base, fileName)
            if (!f.exists()) throw GradleException("发布物缺失: ${f.absolutePath}")
            if (f.readText().contains("org.jetbrains.kotlin")) {
                throw GradleException("发布物含 Kotlin 依赖: ${f.absolutePath}")
            }
        }
        logger.lifecycle("verifyPublishedPom: POM/.module 均无 Kotlin 依赖 ✓")
    }
}

dependencies {
    // AGP built-in Kotlin 会向所有 Android 模块自动注入 kotlin-stdlib；本 SDK 是纯 Java
    // 契约库，发布面不得携带 Kotlin runtime（重整版 §2.4），故在全部配置上排除。
    configurations.configureEach {
        exclude(group = "org.jetbrains.kotlin")
    }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
