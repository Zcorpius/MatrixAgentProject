# MatrixAgent 旧单 APK 基线记录（历史）

> **不代表当前工程。** 本文记录的是迁移前 `/Users/zhangyongbin/Desktop/Learn/AI/MatrixAgent`
> 的单 APK 0.5.5 基线，用于追溯迁移来源；当前实现以本仓库模块和
> `阶段A完整审计与可执行缺陷清单.md` 为准。当前工程已拆为
> `matrix-agent-service`、`matrix-agent-service-lib`、`matrix-agent-launcher`、
> `matrix-agent-test`、`ondevice` 五个模块，release Voice/Vosk 与系统语音入口也已进入
> `matrix-agent-service/src/main`。下文的“当前”“尚未”等措辞均只描述当时的旧工程。

> 基于 `/Users/zhangyongbin/Desktop/Learn/AI/MatrixAgent` 当前源码梳理，聚焦**已经落地的代码**，不把后续的 Service/AIDL 重构设想当作现状。
>
> 代码基线：`com.matrix.agent`，`versionName 0.5.5`，`versionCode 10`，`minSdk 28`，`targetSdk/compileSdk 36`，Java 17。

## 1. 当前结论

MatrixAgent 当前是一个单 APK Android Demo/原型工程：UI、Agent Runtime、模型配置、端侧推理、模型下载、记忆、审计与 debug 语音闭环均在 `:app` 中；`:ondevice` 是 MNN JNI/Java 封装模块。

它**尚不是**多 APK、多 Android Service、AIDL 或 ServiceManager 架构：

- 仅有一个 applicationId：`com.matrix.agent`。
- `settings.gradle` 仅包含 `:app` 与 `:ondevice`。
- 正式 manifest 只有 `MainActivity` 与 `data.download.DownloadService`；下载服务是前台数据同步服务。
- 系统语音相关的 `MatrixVoiceInteractionService`、`MatrixRecognitionService` 等只存在于 `src/debug`，release 不包含 Vosk 依赖与语音入口。
- 未实现 AIDL、`ServiceManager.addService()`、Maven service-lib、独立 Launcher APK、跨进程 Binder 调用或真实 VHAL/ECU Provider。

```text
当前 APK：com.matrix.agent

MainActivity / Fragment / ViewModel
          │
          ▼
MatrixAgentApplication ──lazy──► AppContainer（组合根）
                                      │
     ┌────────────────────────────────┼─────────────────────────────────────┐
     ▼                                ▼                                     ▼
AgentRuntimeRepository         ModelGatewayRepository              ModelDownloadManager
     │                                │                                     │
TaskScheduler → AgentEngine     云端 HTTP / MNN 端侧网关            DownloadService（前台）
     │                                │
Policy → ToolExecutor                SecureModelConfigStore
     │
Memory / Audit / Session
```

## 2. 模块与源码结构

| 模块/目录 | 当前职责 |
|---|---|
| `:app` | Android 应用主模块。包含 UI、Agent 核心、数据层、模型接入、下载、存储与正式产品代码。 |
| `:ondevice` | MNN 端侧大模型 Java/JNI 接口；`MnnOnDeviceLlmFactory` 创建 MNN 会话。 |
| `app/src/main/java/com/matrix/agent/core` | 不依赖 Android UI 的业务核心：Agent 循环、能力、策略、工具、身份、会话、记忆、Prompt、语音抽象。 |
| `app/src/main/java/com/matrix/agent/data` | Repository、Room/SQLCipher、模型下载、审计、记忆持久化、语音运行时编排。 |
| `app/src/main/java/com/matrix/agent/platform` | Android/网络/Keystore/MNN/Vosk/TTS 等平台适配。 |
| `app/src/main/java/com/matrix/agent/presentation` | Fragment、ViewModel 与 UI state。 |
| `app/src/debug` | Vosk、调试语音页面及 Android VoiceInteraction/Recognition 系统入口；仅 debug 构建。 |

构建入口见：`settings.gradle`、`app/build.gradle`、`ondevice/build.gradle`。

## 3. 启动、UI 与依赖装配

### 3.1 Application 与组合根

`MatrixAgentApplication` 不在 `onCreate()` 立即初始化所有对象；首次调用 `getContainer()` 时才通过双检锁创建 `AppContainer`。这样系统拉起进程或 debug 语音入口出现时，不会立刻加载 Room、模型网关和线程池。

`AppContainer` 是当前最重要的组合根，集中创建并持有：

- `AgentRuntimeRepository`：文本/语音请求的统一入口；
- `ModelGatewayRepository`：模型配置、连接测试、云端/端侧网关创建；
- `TaskScheduler`、`AgentEngine`、`ToolExecutor`、`ModelCallExecutor`；
- SQLCipher Room 数据库、MemoryStore、AuditRepository/AuditEventRecorder；
- `ModelDownloadManager` 与 DAO；
- `GatewayLifecycleManager`：切换端侧模型后的 retire/drain/close；
- `SteerMailbox`：运行中任务的 REPROMPT、FORCE_TOOL、DEFER 指令通道。

线程资源被明确分成两组：

| 线程池 | 配置 | 用途 |
|---|---|---|
| scheduler pool | core/max `2/2`，队列 32，`AbortPolicy` | `TaskScheduler` 运行 Agent 任务与仲裁。 |
| I/O pool | core/max `2/8`，队列 32，`AbortPolicy` | 模型调用与工具调用。 |

两池分离避免 Agent 任务占住线程后，同池再提交模型/工具任务并等待而造成嵌套等待死锁。队列满时显式拒绝并产生可观察终态，而不是在 Binder/UI 调用线程上执行重任务。

### 3.2 当前 UI 页面

`MainActivity` 是带 Drawer 的单 Activity 容器，默认进入“Agent 测试”页。

| 页面 | 主要类 | 已实现能力 |
|---|---|---|
| Agent 测试 | `AgentTestFragment` / `AgentTestViewModel` | 输入命令、执行 Agent、展示终态/轨迹/车辆状态等调试信息。 |
| 模型 API 接入 | `ModelApiFragment` / `ModelApiViewModel` | 配置云端 API、测试连接、保存并应用模型网关、切换回 Demo。 |
| 模型下载 | `ModelDownloadFragment` / `ModelDownloadViewModel` | 浏览模型市场、触发下载/暂停/恢复、选用已下载模型。 |
| 语音闭环 | `VoiceDebugFragment` / `VoiceDebugViewModel` | 仅 debug 构建显示；由 `VoiceEntryProvider` 解耦，release 会隐藏入口。 |

## 4. Agent 请求与执行链路

### 4.1 请求进入 Runtime

文本请求通过：

```text
AgentTestViewModel
  → AgentRuntimeRepository.execute(command, actor, cancellationToken)
  → newRequestBuilder(...)
  → TaskScheduler.submit(request, AgentEngine::execute)
```

语音最终结果通过 `VoiceAgentRequest` 进入 `AgentRuntimeRepository.execute(VoiceAgentRequest)`，复用同一请求构造、调度、取消和审计路径，只额外携带输入来源、语言、ASR 置信度与音区信息。

`AgentRuntimeRepository` 在创建 `AgentRequest` 时补齐并控制：

- `sessionId`：当前演示环境区分 `demo-driver` 与 `demo-passenger`；
- `arbitrationKey`：当前是共享的 `demo-vehicle`，用于同车调度；
- `VehicleZone`、当前车辆状态、总 deadline；
- `readOnlyHint`：由意图分类器判定，影响调度抢占与超时语义；
- `memorySaveAllowed`：仅显式“记住/保存”等意图才允许长期记忆写入；
- Memory epoch：用于清除数据后拒绝旧任务/旧异步写回。

### 4.2 调度与取消语义

`TaskScheduler` 以 `arbitrationKey` 串行化同车任务，使用公平锁维持 FIFO。它已有针对乘员角色的策略：主驾的只读请求可抢占副驾正在运行的只读任务；写操作不允许被这种方式强行中断。

Repository 对 timeout/interruption 的收敛遵循“读写语义不同”的原则：

- 只读任务超时可确定为 `TIMED_OUT`；
- 写任务在命令可能已经发出而无法确认时，先协作取消并等待短收敛窗口；无法确认时返回 `EXECUTION_UNKNOWN`，不会误报为“仅超时”或“已取消”；
- 所有终态都进入 Audit 持久化兜底。

### 4.3 AgentEngine 主循环

`AgentEngine` 的单次任务是有预算上限的多轮循环：

```text
构建系统 Prompt + 会话 + Memory
  → 检查 cancel / deadline / loop budget
  → 消费 SteerMailbox
  → ModelCallExecutor 调用 ModelGateway
  → 解析 assistant 文本和 ToolCall
  → CapabilityRegistry 查定义
  → PolicyEngine 前置判定
  → ToolExecutor 执行 Provider，并按需要 readback 验证
  → ToolObservation 回填下一轮模型上下文
  → 生成脱敏 Trajectory、终态、Memory/Audit 记录
```

已实现的控制点包括：最大迭代次数、最大工具调用数、总 deadline、会话上下文预算、工具 observation 截断、取消检查与模型/工具调用超时。

`SteerMailbox` 支持三类运行时控制：

- `REPROMPT`：加入新的用户信息后继续决策；
- `FORCE_TOOL`：在受信任的进程内调试路径跳过模型、直接构造工具调用；
- `DEFER`：任务进入 `DEFERRED`。

## 5. 能力、工具与策略安全边界

### 5.1 能力模型

`CapabilityRegistry.createDemoRegistry()` 提供当前可运行的演示能力。能力通过 `CapabilityDefinition`、`ToolDefinition` 与参数 schema 描述；`MockCapabilityProvider` 是当前实际 Provider，因此现阶段车辆控制/查询是模拟状态，不会访问真实 ECU/VHAL。

`ToolExecutor` 负责把模型建议的 ToolCall 交给 Provider，并处理：超时、协作取消、可中断/不可中断命令、Provider 异常和验证策略。

### 5.2 PolicyEngine

工具调用不会因模型输出而直接执行。`PolicyEngine` 在 Provider 之前检查：

- capability 是否存在、是否可用；
- 输入参数是否符合 Canonical Schema；
- 风险等级，`R3_PROHIBITED` 直接拒绝；
- actor/zone 等约束；
- 当前车辆状态是否满足 `requiredVehicleStates`，例如驻车要求；
- 显式长期记忆写许可。

对需要可信回读的能力，`ToolExecutor` 的结果即使成功但未验证，也会被转换为 `VERIFICATION_FAILED`。因此“命令接受”不等于“操作完成”。

## 6. 模型接入与端侧推理

### 6.1 三种当前网关

| 网关 | 类 | 作用 |
|---|---|---|
| Demo | `DemoModelGateway` | 默认离线演示路径。 |
| 云端 | `LlmModelGateway` + `ModelApiClient` | 根据 `ModelConfig` 调用兼容 API；`LlmPlanner` 输出结构化工具调用。 |
| 端侧 MNN | `OnDeviceModelGateway` | 经 `:ondevice` 的 `OnDeviceLlm`/MNN native session 生成结果。 |

`ModelGatewayRepository` 负责验证与创建网关：

- 云端：连接测试、保存配置、创建 `LlmModelGateway`；
- 端侧：仅 arm64-v8a，模型目录固定为 `filesDir/models/mnn/<model>`，拒绝 `..` 路径；加载后创建 MNN session；
- 端侧模型不会使用 LLM 意图分类器，分类回退为关键词规则；云端模型使用 LLM 分类器并在低置信度/失败时回退关键词分类。

`SecureModelConfigStore` 使用 Android Keystore 派生密钥保护模型配置中的敏感项。模型切换时，`AgentRuntimeRepository` 配合 `GatewayLifecycleManager` 退役旧 `RetirableModelGateway`，等待使用中的 native lease 释放再关闭，避免端侧新旧模型并存造成内存峰值。

### 6.2 模型下载

模型市场/下载由 `ModelMarketClient`、`ModelScopeClient`、`ModelDownloadManager` 完成：

- 每个模型记录下载状态、已下载/总字节数、文件信息与错误；
- 文件先下载到 `.tmp`，支持 HTTP Range 断点续传；
- 启动时同步数据库和文件系统：处理中断、已完成文件丢失等状态漂移；
- `DownloadService` 在独立下载线程运行，使用通知栏前台服务显示进度，完成或失败后停止自身；
- 数据库不可用时，下载 UI/Service 应显式失败，而非悄悄忽略。

## 7. 会话、记忆、数据库与审计

### 7.1 存储层

`MatrixDatabase` 是 SQLCipher Room 数据库，当前 schema version 为 4，采用 WAL。主要表：

| 表/实体 | 用途 |
|---|---|
| `trajectory` | Agent 轨迹持久化。 |
| `session_history` | 终态后的 episodic 会话历史。 |
| `memory_record` | 语义/偏好记忆。 |
| `audit_event` | 增量审计事件。 |
| `model_download` | 模型下载及断点恢复状态。 |

数据库密钥由 `AndroidKeyStoreMasterKeyProvider` 提供。数据库或 Keystore 初始化失败时，系统会降级，但使用显式状态：Memory 会改为非持久内存实现并设置 `memoryDegraded`，下载能力因缺 DAO 不可用；不是静默改用明文持久化。

### 7.2 记忆

记忆检索通过 `MemoryRecaller` 汇集：会话工作记忆、episodic、semantic 与 preference 等 source。当前持久路径使用 `RoomMemoryStore`、`RoomMemoryWriter`、`EpisodicMemorySourceImpl`、`SemanticMemorySourceImpl`。

长期记忆的写入不是默认行为：`memorySaveAllowed` 必须由显式记忆意图检测器放行；未命中时 `memory.semantic.save` 会被策略层拒绝。

清除数据采用“取消 → 等待 → 原子清除”的序列：取消在途 token、等待写操作收敛、清理 SteerMailbox/会话/指定用户区域的 Memory，并推进 epoch。旧 epoch 的异步写入会被 gate 拒绝，降低清除后又写回旧数据的风险。

### 7.3 审计与脱敏

审计包含两层：

- `AuditRepository`/`RoomAuditRepository`：保存终态结果；
- `AuditEventRecorder`：记录模型前后、工具前后、Steer 等增量事件。

`AuditRedactor` 将轨迹和参数投影为审计安全视图；模型所需的上下文只做必要脱敏，以保留任务语义；展示/审计的 `Trajectory` 则隐藏敏感记忆、输入和工具参数。HMAC digest 由 Keystore 支持，装配失败时有降级实现以保证主流程不因审计中断。

## 8. 语音闭环（当前为 debug 能力）

语音核心已抽象为可测试的端口与状态机：`WakeWordPort`、`AsrPort`、`TtsPort`、`AudioFocusPort`、`AgentRunner`，由 `VoiceSessionController` 协调。

主要状态覆盖：`IDLE → WAKE_ACCEPTED → LISTENING → ENDPOINTING → RECOGNIZING → THINKING → SPEAKING`，并处理打断（barge-in）、取消、焦点丢失、TTS 错误与重试。

debug 构建进一步提供：

- Vosk 唤醒/ASR 适配与模型下载；
- `VoiceCaptureController` 负责 PCM 采集和按状态分发；
- Android TTS 与 AudioFocus 适配；
- `MatrixVoiceInteractionService`、`MatrixRecognitionService` 与 Session Service，将系统语音入口规范化为 `WakeEvent`；
- 真机认证相关 instrumented tests。

正式构建没有 Vosk 依赖、没有这些系统语音 service 声明，也没有对外 Voice Service。

## 9. 测试与当前工程质量门槛

项目包含 JVM 单测与 Android instrumentation 测试，覆盖 Agent、策略、schema、调度、Memory epoch、SQLCipher Room migration、审计、MNN native load/inference、下载及 debug 语音等。

常用验证命令：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:connectedVoiceCertificationAndroidTest
```

最后一条是面向真机语音认证的专门任务，需要 Vosk 模型、真实麦克风、中文 TTS 与焦点行为满足前提；它不同于普通单测或模拟器验证。

## 10. 当前边界与尚未落地项

以下内容属于后续重构/产品化目标，不应误认为当前已经实现：

1. 独立 Launcher APK 与 Runtime APK 拆分；
2. Runtime、Model、Voice、Download 四个正式 Android Service；
3. AIDL 接口、Maven service-lib、Manager 门面和跨 APK Binder；
4. ServiceManager 注册、系统 UID/SELinux/hidden API 产品集成；
5. 真实车辆 Provider、VHAL/ECU、乘员/座舱 zone 的 OEM 数据源；
6. 面向跨进程 UI 的 task snapshot、operation journal、幂等控制操作与服务死亡恢复；
7. release 语音助手入口与生产级 Wake/ASR/TTS 资源治理；
8. 当前代码中的 `demo-driver`、`demo-passenger`、`demo-vehicle` 身份/仲裁键替换为真实系统身份。

## 11. 关键源码索引

| 主题 | 关键源码 |
|---|---|
| 应用启动与组合根 | `app/src/main/java/com/matrix/agent/app/MatrixAgentApplication.java`；`app/src/main/java/com/matrix/agent/app/AppContainer.java` |
| 主 UI | `app/src/main/java/com/matrix/agent/MainActivity.java`；`app/src/main/java/com/matrix/agent/presentation/` |
| Runtime 请求入口 | `app/src/main/java/com/matrix/agent/data/AgentRuntimeRepository.java` |
| Agent 循环 | `app/src/main/java/com/matrix/agent/core/agent/AgentEngine.java` |
| 调度 | `app/src/main/java/com/matrix/agent/core/agent/TaskScheduler.java` |
| 策略与能力 | `app/src/main/java/com/matrix/agent/core/policy/PolicyEngine.java`；`app/src/main/java/com/matrix/agent/core/capability/`；`app/src/main/java/com/matrix/agent/core/tool/` |
| 模型与端侧 MNN | `app/src/main/java/com/matrix/agent/data/ModelGatewayRepository.java`；`app/src/main/java/com/matrix/agent/platform/OnDeviceModelGateway.java`；`ondevice/src/main/java/com/matrix/agent/ondevice/` |
| 下载 | `app/src/main/java/com/matrix/agent/data/download/` |
| 数据库/记忆/审计 | `app/src/main/java/com/matrix/agent/data/db/`；`app/src/main/java/com/matrix/agent/data/memory/`；`app/src/main/java/com/matrix/agent/data/audit/` |
| 语音 | `app/src/main/java/com/matrix/agent/core/voice/`；`app/src/main/java/com/matrix/agent/data/voice/`；`app/src/debug/java/com/matrix/agent/voice/` |
