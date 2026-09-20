<div align="center">

# MatrixAgent

### 面向 Android 系统镜像的本地智能 Agent 运行时

把身份、调度、模型、记忆、语音、下载与审计收敛到可信系统进程，<br>
让应用只通过稳定 SDK 使用 Agent 能力。

<p>
  <img src="https://img.shields.io/badge/version-v0.6.14-F36F4A?style=flat-square" alt="Version 0.6.14" />
  <img src="https://img.shields.io/badge/Android-15-0F766E?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 15" />
  <img src="https://img.shields.io/badge/LineageOS-22.2-167C80?style=flat-square&amp;logo=lineageos&amp;logoColor=white" alt="LineageOS 22.2" />
  <img src="https://img.shields.io/badge/Java-17-173B46?style=flat-square&amp;logo=openjdk&amp;logoColor=white" alt="Java 17" />
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-173B46?style=flat-square" alt="arm64-v8a" />
</p>

[功能概览](#功能概览) · [对话与语音](#对话与语音) · [系统架构](#系统架构) · [快速开始](#快速开始) · [SDK 接入](#sdk-接入) · [质量验证](#质量验证)

</div>

> [!IMPORTANT]
> MatrixAgent 面向定制 Android ROM 和系统镜像集成。Host 依赖 platform 签名、system UID 与系统服务能力，不是一个下载后即可在任意手机上独立运行的普通 APK。

## 为什么是 MatrixAgent

普通聊天应用把 UI、密钥、模型调用和工具执行放在同一进程里；MatrixAgent 则把 Agent 设计成系统基础设施。Host 是唯一执行权威，Launcher、OEM 应用和其他可信客户端都只能通过版本化 SDK 与它交互。

| 设计目标 | MatrixAgent 的做法 |
|---|---|
| **系统级可信执行** | 调用方身份、用户、音区、车辆状态与策略判断均由 Host 生成，客户端不能伪造 |
| **稳定客户端边界** | AIDL、DTO、错误码与五个 Manager 独立发布，使用契约哈希和版本协商保护兼容性 |
| **统一模型入口** | 云端 API、局域网服务与 MNN 端侧模型共享同一模型域和生命周期 |
| **对话与语音闭环** | 文字、PTT 与唤醒后的语音最终转写进入同一对话执行链；Host 管理录音、模型、焦点与播报 |
| **默认安全失败** | 签名权限、调用方复验、受控网络端点、SQLCipher 与 KeyStore 共同构成安全边界 |

## 界面预览

以下截图来自 Mi 9 SE（`grus`）真机 Launcher。界面为系统状态栏保留安全区域；对话交互页已作为新的工作区入口加入，截图会随 UI 迭代补充。

<table>
  <tr>
    <td align="center"><img src="docs/images/launcher-drawer.png" width="170" alt="MatrixAgent 导航" /></td>
    <td align="center"><img src="docs/images/launcher-tasks.png" width="170" alt="任务工作" /></td>
    <td align="center"><img src="docs/images/launcher-voice.png" width="170" alt="语音功能" /></td>
    <td align="center"><img src="docs/images/launcher-models.png" width="170" alt="模型接入" /></td>
    <td align="center"><img src="docs/images/launcher-downloads.png" width="170" alt="模型市场" /></td>
  </tr>
  <tr>
    <td align="center"><sub>工作区导航</sub></td>
    <td align="center"><sub>任务工作</sub></td>
    <td align="center"><sub>语音功能</sub></td>
    <td align="center"><sub>模型接入</sub></td>
    <td align="center"><sub>模型市场</sub></td>
  </tr>
</table>

## 功能概览

| | |
|---|---|
| **01 · 任务运行时**<br><br>自然语言任务、订阅、取消、追加指令与进程重启恢复；支持主驾优先仲裁、会话隔离、总 deadline、工具策略检查和 readback 验证。 | **02 · 持久化对话**<br><br>文字、PTT、唤醒三种输入统一入链；同一会话串行执行，消息、状态、幂等键和任务关联均可恢复。 |
| **03 · 语音闭环**<br><br>端侧 Vosk / Sherpa ASR、实时转写、唤醒、PTT、VAD、音频焦点和前台麦克风服务；状态明确呈现等待、录音、思考、播报与打断。 | **04 · 模型运行时**<br><br>统一接入 OpenAI Chat、Anthropic Messages、Gemini、Ollama 与 MNN 端侧协议；API Key 仅通过一次性 Binder 管道进入 Host。 |
| **05 · 模型市场与语音资源**<br><br>目录缓存、Range 断点续传、暂停、恢复、取消、删除和原子安装；识别与 Piper 语音模型同样具有安装事实与可见进度。 | **06 · 数据、安全与 SDK**<br><br>Room + SQLCipher、Android KeyStore、敏感字段脱敏、epoch gate、签名权限和逐事务调用方校验；Launcher 只依赖 AAR。 |

### 模型接入矩阵

| 运行位置 | 已支持入口 |
|---|---|
| 云端 | 智谱 GLM、DeepSeek、通义千问、Moonshot Kimi、火山方舟 / 豆包、Anthropic Claude、Google Gemini |
| 局域网 | Ollama、LM Studio、vLLM |
| 自定义 | OpenAI-Compatible Endpoint |
| 端侧 | 已下载并安装的 MNN-LLM 模型 |

### 语音模型管理

- 首次使用前由用户显式下载中英文 Vosk 或 Sherpa 端侧识别资源，并查看实时下载进度；启动录音不会隐式拉取大文件。
- 已安装资源展示语言、版本与占用空间，可独立删除；删除后相应下载入口自动恢复。
- 下载具备校验、版本指针、安装锁与失败回滚，模型“已安装”只在有效版本指针切换后成立。
- `RECORD_AUDIO` 未授权、前台服务被拒或模型未就绪时返回明确状态，不降级为隐形后台录音。

## 对话与语音

### 一个执行入口，三类输入

文字、按住说话（PTT）和“唤醒词后的最终转写”都由 `ConversationCoordinator` 接收。输入先完成校验与持久化，再按 `conversationId` 进入串行执行 lane：同一对话能看到前一轮的已完成上下文，不同对话互不阻塞。语音 partial 仅在内存和订阅中传递，用于实时 UI；只有最终接受的文本才成为用户消息并触发 Agent。

每次接受会原子写入用户消息、顺序号、幂等键、任务关联与只读提示。执行过程使用以下状态，进程终止后不会遗留伪“进行中”结果：

| 状态 | 含义 |
|---|---|
| `ACCEPTED` / `RUNNING` | 已持久化，或已进入该对话的执行 lane |
| `COMPLETED` / `FAILED` / `CANCELLED` / `REJECTED` | 可确定的终态 |
| `EXECUTION_UNKNOWN` | 写操作在进程中断、超时或取消后无法确认；绝不伪装成“已撤销” |

应用重启会对未收敛记录进行对账。对话正文按产品数据处理，存放在 SQLCipher 数据库并纳入清除用户数据路径；日志和审计投影保持脱敏。

### 语音会话与播报

- PTT 的“松开”与“取消”是不同意图：前者冲刷 ASR final，后者终止本轮；endpoint 与手动冲刷共享幂等保护，不能双投递。
- 唤醒、PTT 和文字输入最终使用同一对话任务链。语音桥以 `voiceSessionId` / task 关联和响应 token 防止迟到结果污染新会话。
- `THINKING` 有独立 watchdog；任务订阅丢失、执行超时或进程死亡都会收敛到可解释状态，不会永久停在“思考中”。
- 录音期间由按需的 microphone 前台服务维持合规性，Audio Focus、打断与 TTS 的唯一权威仍是语音会话控制器。

### TTS 路由

播报引擎按“已配置腾讯云 TTS → 已安装 Piper 端侧 TTS → Android 系统 TTS”选择。腾讯云凭证通过一次性 Binder 管道进入 Host，并由 Android KeyStore 加密保存；配置投影和日志均不返回密钥。单次云端失败会在同一 utterance 上自动交给本地引擎，只有两者均失败才向上报告 `VOICE_OUTPUT_UNAVAILABLE`。

腾讯云接入是可选增强：网络、地域、账号权限和设备音频链路仍需要在目标 ROM 真机完成端到端认证，凭证不得写入仓库、截图或 issue。

## 系统架构

```mermaid
flowchart LR
    subgraph Clients[可信客户端]
        Launcher["Launcher<br/>Java + XML + MVVM"]
        OEM[OEM / 系统应用]
        Tests[Trusted / Untrusted Tests]
    end

    SDK["service-lib<br/>AIDL + DTO + Managers"]

    subgraph Host[platform-signed MatrixAgent Host]
        Root["Root Binder<br/>MatrixAgentManagerService"]
        Task[Task Domain]
        Conversation[Conversation Domain]
        Voice[Voice Domain]
        Model[Model Domain]
        Download[Download Domain]
        Data[(SQLCipher + KeyStore)]
        Native[MNN On-device Runtime]

        Root --> Task
        Root --> Conversation
        Root --> Voice
        Root --> Model
        Root --> Download
        Task --> Data
        Conversation --> Task
        Conversation --> Data
        Voice --> Conversation
        Voice --> Data
        Model --> Native
        Download --> Native
    end

    Launcher --> SDK
    OEM --> SDK
    Tests --> SDK
    SDK --> Root
```

客户端先连接 Root Binder，再取得任务、对话、模型、下载和语音五个类型安全的域 Manager。Host 通过 `ServiceManager.addService()` 注册系统服务，同时保留签名权限保护的显式绑定入口；Binder 死亡后，SDK 会重连并恢复活动订阅。

### 边界原则

1. **Host 拥有事实**：身份、调度状态、密钥、模型文件、录音和持久化数据均由 Host 管理。
2. **SDK 拥有契约**：客户端只看到稳定 DTO、错误码和 Manager，不依赖 Host 内部包结构。
3. **Launcher 只负责呈现**：页面状态来自 SDK 投影，UI 不绕过契约读取数据库或文件系统。
4. **失败必须可见**：权限、版本、网络、模型与执行错误均映射为显式状态，不静默降级。

### 模块划分

| 模块 | 交付物 | 主要职责 |
|---|---|---|
| `matrix-agent-service` | `com.matrix.agent` APK | system UID Host、五域 Stub、任务引擎、对话协调、模型、语音、下载、持久化与审计 |
| `matrix-agent-service-lib` | AAR | AIDL、Parcelable DTO、常量、`MatrixAgent` 门面与五个客户端 Manager |
| `matrix-agent-launcher` | `com.matrix.agent.launcher` APK | SDK-only 工作区，展示任务、对话、语音、模型接入与模型市场 |
| `matrix-agent-test` | trusted / untrusted APK | 跨 APK 权限、身份、契约与 Binder 行为验证 |
| `ondevice` | Android Library + JNI | MNN-LLM Java 边界与 native 生命周期管理，不暴露给客户端 |

## 快速开始

### 环境要求

- macOS 或 Linux、JDK 17
- Android SDK 36、Build Tools 与 Platform Tools
- Android NDK、CMake 3.22.1
- Gradle Wrapper 9.3.1、Android Gradle Plugin 9.0.1
- `arm64-v8a` 目标设备
- 与目标 ROM 匹配的 platform 证书和同一 ROM 产出的 framework compile stub

当前认证基线为 Mi 9 SE（`grus`）、Android 15、LineageOS 22.2。Host 使用 `android:sharedUserId="android.uid.system"` 和 hidden `ServiceManager` API，不能使用另一套 ROM 的签名或普通 `android.jar` 替代。

### 1. 获取源码与子模块

```bash
git clone --recurse-submodules https://github.com/Zcorpius/MatrixAgentProject.git
cd MatrixAgentProject
```

已经 clone 的工作区可单独初始化 MNN：

```bash
git submodule update --init --recursive
```

`ondevice/src/main/cpp/MNN` 固定为上游提交 `18759c835f7c36d3fcaec25f6d9a029386e802f8`。

### 2. 配置本机 Android SDK

在根目录创建不提交到仓库的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

### 3. 准备系统构建材料

三个 APK 模块分别从自己的 `tools/key/` 目录读取：

```text
platform.p12
platform.x509.pem
signing.properties
```

Service 还需要目标 ROM 生成的 framework compile stub：

```text
matrix-agent-service/tools/framework/framework-lineage-grus.jar
```

更新方法见 [`matrix-agent-service/tools/framework/README.md`](matrix-agent-service/tools/framework/README.md)。生产 platform 私钥不得提交到公共仓库，也不要在不同 ROM 间复用。

### 4. 构建

```bash
./buildTool.sh all debug
```

也可以按模块和变体构建：

```bash
./buildTool.sh service release
./buildTool.sh launcher debug
./buildTool.sh test release
```

脚本会执行 clean、构建、签名指纹校验与原子归档。产物位于 `outputs/`，版本统一读取根目录的 `gradle.properties`：

```properties
MATRIX_VERSION_NAME=0.6.14
MATRIX_VERSION_CODE=6014
```

### 5. 安装与启动

目标设备必须使用与 APK 相同的 platform 证书：

```bash
adb install -r outputs/matrix-agent-service-debug-v0.6.14-6014-*.apk
adb install -r outputs/matrix-agent-launcher-debug-v0.6.14-6014-*.apk
adb shell am start -n com.matrix.agent.launcher/.LauncherActivity
```

系统镜像集成时，将 release APK 作为 `priv-app` 预埋，通过 `android_app_import` 加入产品包，并同步维护产品 makefile、默认权限与 SELinux 策略。平台证书、framework stub、预埋 APK 和设备 ROM 必须来自同一构建基线。

## SDK 接入

发布本地 Maven 版本：

```bash
./gradlew :matrix-agent-service-lib:publishToMavenLocal
```

可信客户端声明签名权限并依赖 AAR：

```xml
<uses-permission android:name="com.matrix.agent.permission.ACCESS_AGENT" />
```

```kotlin
dependencies {
    implementation("com.matrix.agent:matrix-agent-service-lib:0.6.14")
}
```

Java 客户端使用异步连接，不阻塞主线程：

```java
MatrixAgent agent = MatrixAgent.createAsyncHandle(context, (client, state) -> {
    if (state != ConnectionState.CONNECTED) return;

    MatrixAgentManager tasks = client.getAgentManager();
    AgentRequest request = new AgentRequest(
            UUID.randomUUID().toString(),
            "launcher-session",
            "把主驾温度调到 24 度，然后导航回家",
            AgentRequest.INPUT_TEXT,
            "zh-CN");

    tasks.submit(request, event -> {
        // SDK 已将回调投递到事件 Handler；这里只更新调用方状态。
    });
});

// 页面或进程组件销毁时释放连接。
agent.release();
```

`MatrixAgent` 提供 `getAgentManager()`、`getConversationManager()`、`getModelManager()`、`getDownloadManager()` 和 `getVoiceManager()`。订阅接口返回 `AutoCloseable`，调用方必须在生命周期结束时关闭。

对话接口同样只经 SDK 使用；不要让客户端直接访问 Host 数据库：

```java
ConversationManager conversations = client.getConversationManager();
ConversationInfo conversation = conversations.createConversation(
        new CreateConversationRequest("回家路上", "zh-CN"),
        UUID.randomUUID().toString());

conversations.sendText(new SendTextRequest(
        conversation.conversationId, "把屏幕亮度调低一点", "zh-CN"),
        UUID.randomUUID().toString());
```

PTT 绑定通过 `createVoiceBinding()` 创建一次性关联，再由语音域开始会话；语音 partial 通过 `ConversationManager.subscribeConversation()` 订阅，客户端应在生命周期结束时关闭返回的 `AutoCloseable`。

## 质量验证

不连接设备即可运行完整质量门禁：

```bash
./gradlew verifyArchitecture
```

该任务覆盖五个模块的单元测试、lint、架构边界检查，并验证发布 SDK 的元数据不携带 Kotlin runtime。

连接正确签名的真机后，按验证目标选择：

| 目标 | 命令 |
|---|---|
| Host 与 trusted / untrusted 跨 APK | `./gradlew :matrix-agent-test:connectedLocalHostIntegrationAndroidTest` |
| Vosk / Sherpa、麦克风、TTS、焦点与前台服务 | `./gradlew :matrix-agent-service:connectedVoiceCertificationAndroidTest` |
| MNN 模型与 native tool-call | `./gradlew :matrix-agent-service:connectedOnDeviceCertificationAndroidTest` |

## 当前边界

- 车辆 Capability 默认装配仍是 emulator / demo Provider；接入真实车辆前需实现 OEM/VHAL Provider，并复用现有策略、参数校验与 readback 契约。
- `ondevice` 首发为 arm64 CPU 路径，GPU 后端关闭；模型体积和峰值内存需按目标硬件验证。
- 当模型市场上游目录没有发布者签名或逐文件摘要时，Host 只能提供固定 HTTPS 来源、大小限制、结构校验和最后一次可用缓存，不能将其描述为独立可信根。
- 系统语音、端侧推理和跨 APK 权限属于真机能力；普通 JVM 测试通过不代表真机认证已经通过。
- 腾讯云 TTS 的凭证配置、云端返回、Piper/系统 TTS 回退和实际扬声器输出必须一起做真机闭环验收；仅配置成功不等于已验证播报成功。

## 设计文档

- [`MatrixAgent架构重构实施方案.md`](MatrixAgent架构重构实施方案.md)：当前重构落地结果、包结构和验证标准
- [`MatrixAgent重构架构设计与功能实现-重整版.md`](MatrixAgent重构架构设计与功能实现-重整版.md)：目标架构、契约与阶段设计
- [`matrix-agent-service代码架构评审.md`](matrix-agent-service代码架构评审.md)：源码评审、已修复项与剩余边界
- [`MatrixAgent对话交互与统一语音架构设计.md`](MatrixAgent对话交互与统一语音架构设计.md)：对话持久化、语音 ingress、TTS 回注与恢复语义
- [`MatrixAgent当前架构与功能实现.md`](MatrixAgent当前架构与功能实现.md)：迁移前单 APK 历史基线，仅用于追溯

---

<div align="center">
  <sub>MatrixAgent · System-owned intelligence for Android</sub>
</div>
