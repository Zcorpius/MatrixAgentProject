# MatrixAgent 重构架构设计与功能实现（重整版）

> 本文描述目标与实现边界；已落地项、架构校正与剩余风险见同目录
> 《matrix-agent-service代码架构评审》。
>
> 架构参考：`/Users/zhangyongbin/Desktop/Old File/nio_packages_services_panocinema` 的 PanoCinema/service-lib/PanoBox 实现。

## 1. 目标与边界

### 1.1 目标

MatrixAgent 由五个模块/交付物组成：

```text
Service APK       com.matrix.agent
Launcher APK      com.matrix.agent.launcher
Binder Test APK   com.matrix.agent.test
Maven 接口库       matrix-agent-service-lib.aar
端侧推理库         ondevice.aar
```

服务 APK 通过一个受签名权限保护的 Android Manager Service 发布根 Binder；根 Binder
下有四个 AIDL 业务域与四个 Maven Manager：

| Binder 域 | AIDL | 客户端 Manager | 职责 |
|---|---|---|---|
| Agent Manager | `IMatrixAgentManager` | `MatrixAgentManager` | Agent 任务、服务发现、身份与恢复。 |
| Model | `IModelService` | `ModelManager` | 模型配置、选择、端侧运行状态。 |
| Voice | `IVoiceService` | `VoiceManager` | 受控语音会话与系统语音入口协调。 |
| Download | `IDownloadService` | `DownloadManager` | 模型市场、下载与模型文件生命周期。 |

Launcher、MatrixAgent Test 和其它获准客户端只依赖 Maven service-lib；不依赖 Service APK 的代码实现。

### 1.2 边界

- 不进入 `system_server`，不实现 framework Java SystemService；
- 不做插件化；这是一个预装服务 APK 加独立 Launcher APK；
- 不让 Launcher 直接使用 `AppContainer`、Room、MNN、Keystore、Provider 或内部 Android Service；
- 第一阶段允许 `MockCapabilityProvider` 留在 main/release，支撑无 VHAL 的功能闭环：它只修改进程内模拟状态，没有真实车控副作用；真实车控接入前不得将其表述为车辆执行成功；
- `MatrixAgentManagerService` 是根 Android Service；下载 FGS 与系统语音 Android Service
  只承载各自平台生命周期，业务 API 始终经根 Binder 获得。所有组件第一阶段同进程，
  不声明 `android:process`；仅在 MNN 崩溃或内存隔离成为硬需求后再拆 Model 进程。

## 2. 服务整体架构

### 2.1 产物与模块

| 产物/模块 | namespace/applicationId | 职责 |
|---|---|---|
| Service APK | `com.matrix.agent` | 根 Manager Service、四个 Binder 域、业务核心、SQLCipher 数据库、模型文件。 |
| Launcher APK | `com.matrix.agent.launcher` | 独立 UI。 |
| MatrixAgent Test APK | `com.matrix.agent.test` | 独立外部 Binder 测试客户端，只通过 service-lib 调用服务。 |
| `:matrix-agent-service-lib` | `com.matrix.agent.service` | AIDL、Parcelable DTO、常量、`MatrixAgent`、四个 Manager、Java callback/listener 适配；发布 `matrix-agent-service-lib.aar`。 |
| `:ondevice` | `com.matrix.agent.ondevice` | MNN Java/JNI 封装，不发布给客户端。 |

Maven 使用形式：

```kotlin
implementation("com.matrix.agent:matrix-agent-service-lib:<locked-version>")
```

service-lib 是唯一公开 ABI，包含 AIDL、Parcelable、常量、错误码、连接门面、Manager、Java callback/listener adapter 与 consumer ProGuard；禁止包含 `AppContainer`、SQLCipher、MNN、Provider、模型/下载实现和 Keystore 数据。

### 2.1.1 一个 Android Studio 总工程

所有 APK 与 library 都是**同一个 Gradle 根工程**的模块，不建立多个独立 Android Studio 工程。Android Studio 打开根目录一次，即可同步、跳转源码、运行配置和指定模块编译：

```text
MatrixAgent/                         ← Android Studio 打开的唯一根目录
  settings.gradle.kts
  build.gradle.kts
  gradle/  gradle/libs.versions.toml
  matrix-agent-service/              ← :matrix-agent-service，com.matrix.agent
  matrix-agent-service-lib/          ← :matrix-agent-service-lib，发布 service-lib.aar
  matrix-agent-launcher/             ← :matrix-agent-launcher
  matrix-agent-test/                 ← :matrix-agent-test，com.matrix.agent.test
  ondevice/                          ← :ondevice
```

依赖图必须是单向的：

```text
:matrix-agent-service       → :matrix-agent-service-lib, :ondevice
:matrix-agent-launcher      → :matrix-agent-service-lib
:matrix-agent-test          → :matrix-agent-service-lib
:matrix-agent-service-lib   → 不依赖 service/launcher/test 实现
:ondevice                   → 不依赖其它 Matrix 模块
```

`matrix-agent-test` 不得依赖 `:matrix-agent-service`。测试通过才能证明的是跨 APK 的公开 Binder ABI，而不是同进程调用或测试代码绕过边界。

### 2.2 服务关系

```text
Launcher APK                 MatrixAgent Test APK
  │  仅依赖 service-lib         │ 仅依赖 service-lib
  └───────────────┬───────────┘
                  ▼
matrix-agent-service-lib: MatrixAgent / 4 Managers / Java listener adapter
                          MatrixServiceConstants / AIDL / DTO
  │
  ▼
ServiceManager[唯一全局 Agent Manager Binder]
  │
  ▼
MatrixAgentManagerService
  ├─ MatrixAgentManagerBinder : IMatrixAgentManager.Stub
  ├─ AppContainer / AgentRuntimeRepository / TaskManager
  ├─ Model Service    → MatrixModelBinder
  ├─ Voice Service    → MatrixVoiceBinder
  └─ Download Service → MatrixDownloadBinder
```

`MatrixAgentManagerService` 既运行 Agent，又是其它三个 Service 的总入口。`Runtime` 只保留给内部执行实现，例如 `AgentRuntimeRepository`、TaskManager、executor；不作为对外 Service 名称。

### 2.3 四个 Binder 域的迁移来源

| 目标 Service | 复用当前源码 |
|---|---|
| Agent Manager | `AppContainer`、`AgentRuntimeRepository`、`TaskScheduler`、`AgentEngine`。 |
| Model | `ModelGatewayRepository`、`GatewayLifecycleManager`、`OnDeviceModelGateway`。 |
| Voice | `VoiceRuntime`、`VoiceSessionController`、release VoiceInteraction/Vosk 实现；系统入口与 Launcher PTT 共享受控运行时。 |
| Download | `ModelDownloadManager`、现有前台 `DownloadService`。 |

### 2.4 技术基线与开发约束

本次重构后的 MatrixAgent 自有 Android/业务代码统一使用 Java 17，并保持零 Kotlin/零 Compose，禁止引入。原工程 Java 文件是功能与测试迁移来源，应按目标模块、领域边界和 package 重组，不要求保留旧目录。`matrix-agent-service`、`matrix-agent-service-lib`、`matrix-agent-launcher`、`matrix-agent-test` 及自有 `ondevice` Wrapper 均使用 Java（老工程自有代码本就是纯 Java + XML View，这是保持性约束而非迁移动作）；MNN native C/C++ 作为第三方/原生依赖保持原状，上游 MNN 仓库 apps/frameworks demo 树中的 Kotlin 源码仅作源码参考，不进入本工程 source set，主工程不启用 Kotlin 插件。

| 范围 | 语言/框架要求 | 具体约束 |
|---|---|---|
| `:matrix-agent-service` / Service APK | Java 17 | Service、Binder、TaskManager、AgentEngine、Repository、Policy、Tool、Memory、Audit、Model/Voice/Download 全部以 Java 实现。 |
| `:matrix-agent-service-lib` | Java + AIDL/Parcelable | 稳定 ABI，含 AIDL、DTO、常量、错误码、`MatrixAgent` 与四个 Manager；提供 Java callback/listener 与可关闭订阅句柄。 |
| `:matrix-agent-launcher` | XML View + Java ViewModel + LiveData | Launcher 不使用 Compose；页面以 XML/Fragment 或 Activity 实现，ViewModel 暴露 `LiveData<UiState>`。 |
| `:ondevice` | Java Wrapper + 既有 C/C++ JNI | MatrixAgent 自有 JNI 封装以 Java 实现（现状已纯 Java）；MNN 上游 C++ 保持第三方代码，上游 demo 树 Kotlin 不进入 source set，不发布给客户端。 |
| 并发 | `ExecutorService` + `ScheduledExecutorService` + `Future` | 用有界线程池、任务句柄和 `CancellationToken` 管理 Service 生命周期、任务取消和 IPC 适配；禁止自行创建无界线程。 |
| UI 状态 | ViewModel + LiveData + XML View | View 只 observe `LiveData<UiState>`；`SavedStateHandle` 只保存 taskId/页面必要状态。 |
| 网络 | OkHttp | MatrixAgent 自身的模型 API、模型目录、模型/语音包下载统一迁至受控 OkHttp 网络层；不再为这些业务代码新建 `HttpURLConnection`。 |
| AIDL 回调 | Binder callback + Java listener adapter | AIDL 不传 `LiveData` 或 Java 并发对象；service-lib 将 callback 适配为 listener，并返回 `AutoCloseable`/`Subscription` 显式取消订阅。 |

构建环境固定为 AGP `9.0.1`、Gradle `9.3.1`、JDK/JVM `17`。SDK 基线固定：minSdk `28`、compileSdk `36`，三个 APK 的 targetSdk `36`；service-lib 固定 minSdk `28`、compileSdk `36`。MatrixAgent 自有模块保持零 Kotlin/零 Compose/零 coroutines，禁止引入（老工程自有代码本就如此）。Commit 1 锁死 AndroidX Lifecycle、Room、SQLCipher、WorkManager、OkHttp、MockWebServer、JUnit、Espresso 等确切版本，禁止使用 `latest`、动态版本或未验证的版本范围。SDK 与依赖版本统一写入 `gradle/libs.versions.toml`，各模块只引用、不允许自行填写。构建脚本全新编写（`.kts` + version catalog），不迁移老工程 Groovy 脚本。

#### 2.4.1 Service 并发与任务模型

线程资源由 Host 持有的 `MatrixExecutorRegistry` 统一登记，执行全局预算，四个 Binder 域不各自扩池：

| 用途 | 最大线程 | 队列 |
|---|---|---|
| Task Runtime | 2 | 32 |
| Network / Download | 4 | 16 |
| MNN Model | 1 | 2 |
| Host dispatch | 2 | 16 |
| Voice pipeline | 4 + lifecycle 1 + capture 1 | 各串行队列 8/16；capture 为每 Runtime 至多一个受控实时线程 |
| DB | 1 | 32 |
| Timer / Retry | 3 | Audit/catalog 32；Voice timeout 16；MNN retire 8，均有界 |

有界业务 worker 为 15，另有 3 个单线程有界 scheduler 与 1 个会话专属实时采音线程，Java 线程理论上限
为 19；MNN native 内部线程另行配置和统计。所有延迟队列由
`BoundedScheduledExecutor` 限额；MNN native 调用取消后需要延迟释放时走独立的 MNN retire scheduler，
不会被 Audit/catalog 重试挤占。该预算作为
P0 骨架约束与后续 Perfetto 基线。在此预算内，Manager Service 持有任务协调器和定时线程池：

```java
private final ExecutorService serviceExecutor;
private final ScheduledExecutorService timerExecutor;
private final ConcurrentMap<String, TaskExecution> runningTasks;
```

- `serviceExecutor`、网络 executor、运行时 executor 都必须由有界 `ThreadPoolExecutor` 创建；不能把阻塞的 `AgentRuntimeRepository.execute()`、MNN load、Provider I/O 放到无界线程或每次调用新建线程；
- Binder 方法先完成鉴权、DB 短事务和状态写入，再由 `TaskManager` 提交 `Runnable`/`Callable` 并立即返回 handle；
- 每个 `TaskExecution` 持有 `Future<?>`、`CancellationToken` 和底层 `Call`/资源句柄。取消先调用 `CancellationToken.cancel()` 与传输层 cancel，再按需 `Future.cancel(true)`；不得仅依赖 interrupt；
- `onDestroy()` 停止接收新任务、注销 Binder/回调、关闭仅属于该 Service 的 executor；持久 task 不因内存 `Future` 消失而丢失，重启扫描按数据库恢复；
- 每个 task 在顶层捕获异常并写入权威状态，单个任务/子 Service 失败不得终止 Manager Service 的其它任务；MNN 资源退役继续走 `GatewayLifecycleManager` 的 drain 语义。

#### 2.4.2 Java Launcher 模型

Launcher 使用单向数据流：

```text
XML View / Fragment → ViewModel action method → Manager method
Manager callback → ViewModel MutableLiveData<UiState> → LiveData.observe → View
```

Activity/Fragment 不能直接持有 Binder、ServiceConnection 或业务 `Context`。连接、重连、callback 注册与取消都封装在 `MatrixAgent`/Manager；ViewModel 仅处理可渲染的 immutable `UiState`。View 层通过 `LiveData.observe()` 渲染状态；页面恢复依赖 `SavedStateHandle(taskId)` 后重新读取 Runtime snapshot，而不是保存整份聊天或 Binder 状态。使用 Fragment 时，callback/listener 订阅必须在 `onStart/onStop` 或 ViewModel `onCleared()` 对称关闭。

#### 2.4.3 OkHttp 网络模型

Service APK 内提供一个进程级 `MatrixHttpClient`：它持有按用途配置、可复用的 `OkHttpClient`（模型 API、catalog、文件下载），并统一配置连接/读写/call timeout、TLS/Network Security Config、受控重定向和 host allowlist。业务 Repository 只依赖该抽象，不自行构建 `OkHttpClient`，更不能接受 Binder 调用方传入的任意 URL、header 或 token。

- `ModelApiClient`、`ModelMarketClient`、`ModelScopeClient`、`ModelDownloadManager`、`VoskModelDownloader` 的 `HttpURLConnection` 实现迁为 Java + OkHttp；`ondevice` 中 MNN 上游代码自带的 OkHttp 客户端在升级前保持隔离，不作为 MatrixAgent 的公共网络栈；
- 阻塞的 `Call.execute()` 仅运行在 2.4.1 所述受控网络 dispatcher；调用被取消、任务超时或 Service 销毁时必须执行 `Call.cancel()`，并将 `IOException`、HTTP 错误、校验失败映射为稳定领域错误码；
- 下载保留 Range 续传语义：明确校验 `206` / `Content-Range`、ETag 或版本变化、重定向目标、Content-Length 上限、最终 SHA-256 与签名；遇到服务端退回 `200` 或元数据变化时安全清理/重建 `.tmp` 状态；
- release 禁用 `HttpLoggingInterceptor` 的 body/header 日志，禁止记录 API Key、Authorization、Cookie、用户文本和本地文件路径；仅允许受脱敏保护的诊断事件；
- 网络迁移要以 `MockWebServer` 覆盖 timeout、取消、Range、`206 → 200` 回退、重定向、hash 不匹配和断点恢复；真实网络不进入 unit test。

#### 2.4.4 高频能力的 P0/P1 实现与验收要求

下表是本项目的技术实现清单。`P0` 是首个可用版本的合入门槛；`P1` 可以在四个 Binder 服务打通后分批完成，但必须在量产发布前关闭。

| 优先级 | 技术与落点 | 必须实现的方式 | 评审/测试重点 |
|---|---|---|---|
| P0 | XML View、UDF、Java ViewModel、LiveData | Launcher 的对话、语音、POI、确认卡、模型页全以 immutable `UiState` 渲染；用户输入统一建模为 `UiAction` 或 ViewModel action 方法。 | 单一权威状态源；页面重建、订阅重连与错误状态不丢失。 |
| P0 | Executor、Future、callback、受控并发 | Agent 流式回复、语音会话、模型下载、取消、超时、重试全部纳入 TaskManager 的受控任务树；Manager 将 Binder callback 适配为可关闭 listener 订阅。 | 取消传播；异常隔离；写车控超时后保留 `EXECUTION_UNKNOWN`。 |
| P0 | AIDL / Binder / 多进程 | Launcher ↔ Service，以及未来车控 APK ↔ Service 都只经 contract AIDL；接口版本协商、调用方鉴权、death 通知、断线重连和 callback 恢复属于 SDK 必备能力。 | Binder 线程安全、调用方 UID/package/签名校验、进程死亡后的 snapshot 恢复。 |
| P0 | Room、SQLCipher、数据迁移 | task snapshot、会话、Memory、Audit、模型元数据均由加密 Room 作为权威存储；每次 schema 变更提供 migration 与 fixture。 | migration test；禁止 destructive migration；数据库不是缓存。 |
| P0 | 依赖注入与模块化 | Java composition root（`AppContainer` 可重命名为 `MatrixServiceGraph`）；Runtime、ModelGateway、CarControlAdapter、VoiceAdapter 只依赖接口，通过构造函数注入生产实现、Fake 和测试替身。 | 不把 DI 框架或 Service Locator 当业务基础设施；可替换实现、测试隔离。 |
| P0 | 测试金字塔 | Policy/状态机 unit、Executor/callback unit、AIDL Fake 集成、Room migration、Espresso UI 和 Test APK instrumentation 测试分层执行。 | 优先 Fake 而不是 Mock：验证状态/行为；关键风险路径可回归。 |
| P1 | WorkManager / 前台服务 | 模型下载由 WorkManager 编排网络、电量、存储等约束及恢复；实际长下载以合规前台服务承载并通知进度。实时语音或可感知的长时反馈才使用前台服务。 | Android 后台限制、何时可启动 FGS、任务恢复；不得把所有任务塞入前台服务。 |
| P1 | 性能治理 | 对 Launcher 冷启动、首次进入对话、长对话滚动和模型切换建立 Macrobenchmark；交付 Baseline Profile，并用 Perfetto Trace 分析瓶颈。 | 有基线和优化前后数据，而非主观“感觉变快”。 |
| P1 | Android 安全 | 签名权限、UID/package 二次校验、Keystore、Network Security Config、日志脱敏都按 release 配置验收。 | 权限不是鉴权的全部；secret 与模型文件不能出 Service 边界。 |
| P1 | Maven Client SDK | 发布语义化版本的 `matrix-agent-service-lib.aar`；其中包含稳定 AIDL/DTO/常量与 `MatrixAgent`/Manager 客户端封装，并提供 consumer ProGuard、sources jar 和兼容性声明。 | AIDL 后向兼容、SDK 示例调用、调用方最小权限与版本组合测试。 |

`WorkManager` 只能负责可延后、可恢复的调度；实时 Agent 执行、正在等待用户确认的任务、Binder 连接和车控事务仍由 `TaskManager`/Service 负责，不能迁移给 WorkManager。

#### 2.4.5 持久化降级

Host 持有唯一的 `PersistenceGate`：SQLCipher 数据库或 Keystore 初始化失败后，四个 Binder 域经同一 gate 统一进入 `PERSISTENCE_UNAVAILABLE`/显式降级状态，对外返回稳定错误码；不允许 Task、Download、Audit 各自静默 fallback。可选的非权威内存缓存必须显式标记 volatile，不得用于任务、下载、确认、审计等权威状态（延续现状“显式降级、不静默改明文”的语义）。

## 3. Binder 与对外接口

### 3.1 三层命名

Android Service、Binder 对象与 Maven Manager 是三个不同层次：

```text
MatrixAgentManagerService
  Android Service：生命周期、前台保活、启动子 Service

MatrixAgentManagerBinder extends IMatrixAgentManager.Stub
  Binder：AIDL 实现、鉴权、服务发现

MatrixAgentManager
  Maven Manager：客户端调用 API
```

Model、Voice、Download 也使用同一规律。Binder 线程不得执行模型调用、下载、网络、MNN load 或 Room 长事务；只做鉴权、校验、幂等查找、短事务和异步投递。

### 3.2 唯一全局 Binder

只有 Agent Manager Binder 注册到 `ServiceManager`：

```java
ServiceManager.addService(
        MatrixServiceConstants.MATRIX_AGENT_SERVICE,
        matrixAgentManagerBinder);
```

它既对应 Agent Manager Service，也提供其它服务发现：

```aidl
interface IMatrixAgentManager {
    AgentServiceInfo getServiceInfo();
    IBinder getMatrixService(String serviceName);

    AgentTaskHandle submit(in AgentRequest request, IAgentTaskCallback callback);
    AgentTaskSnapshot getTaskSnapshot(String taskId);
    void subscribeTask(String taskId, long afterSequence, IAgentTaskCallback callback);
    void unsubscribeTask(String taskId, IAgentTaskCallback callback);
    AgentOperationResult cancelTask(String taskId, String clientOperationId);
    AgentOperationResult steerTask(String taskId, in SteerRequest request,
                                   String clientOperationId);
    AgentOperationResult respondConfirmation(String taskId,
                                             in ConfirmationDecision decision,
                                             String clientOperationId);
    AgentOperationResult resumeTask(String taskId, String clientOperationId);
}
```

Model、Voice、Download 的 Binder 只向 Manager Service 内部 `ServiceRegistry` 注册；不各自调用 `ServiceManager.addService()`。`getMatrixService(...)` 返回这些 Binder，因此不存在第五个纯 Registry Service。

### 3.3 常量与 Manager 获取方式

实际服务名字尚未定稿，但客户端不写裸字符串，只使用 service-lib 常量：

```java
public final class MatrixServiceConstants {
    public static final String MATRIX_AGENT_SERVICE = /* 待定 */;
    public static final String MANAGER_SERVICE = /* 待定 */;
    public static final String MODEL_SERVICE = /* 待定 */;
    public static final String VOICE_SERVICE = /* 待定 */;
    public static final String DOWNLOAD_SERVICE = /* 待定 */;
}
```

调用方式对齐 PanoCinema：

```java
MatrixAgent agent = MatrixAgent.create(context, lifecycleListener);

MatrixAgentManager manager = (MatrixAgentManager) agent.getMatrixManager(
        MatrixServiceConstants.MANAGER_SERVICE);
DownloadManager download = (DownloadManager) agent.getMatrixManager(
        MatrixServiceConstants.DOWNLOAD_SERVICE);
```

`MatrixAgent.getMatrixManager(String)` 内部创建并缓存 `Map<String, MatrixManagerBase>`：

```text
MANAGER_SERVICE  → IMatrixAgentManager → MatrixAgentManager
MODEL_SERVICE    → manager.getMatrixService(...) → ModelManager
VOICE_SERVICE    → manager.getMatrixService(...) → VoiceManager
DOWNLOAD_SERVICE → manager.getMatrixService(...) → DownloadManager
```

### 3.4 AIDL 功能边界

```aidl
interface IModelService {
    List<ModelInfo> listModels();
    ModelRuntimeStatus getRuntimeStatus();
    ConnectionTestResult testConnection(in ModelConfigInput config);
    ModelOperationHandle setActiveModel(String modelId, String clientOperationId,
                                        IModelCallback callback);
}

interface IVoiceService {
    VoiceServiceStatus getStatus();
    VoiceOperationResult setEnabled(boolean enabled, String clientOperationId);
    VoiceOperationResult cancelCurrentSession(String clientOperationId);
    VoiceSessionHandle startUserInitiatedSession(in VoiceSessionRequest request,
                                                 String clientOperationId,
                                                 IVoiceSessionCallback callback);
    VoiceOperationResult stopSession(String sessionId, String clientOperationId);
    void subscribeStatus(IVoiceCallback callback);
    void unsubscribeStatus(IVoiceCallback callback);
}

interface IDownloadService {
    List<ModelCatalogItem> listCatalog();
    List<ModelDownloadInfo> listDownloads();
    ModelOperationHandle install(String catalogModelId, String clientOperationId,
                                 IModelCallback callback);
    ModelOperationHandle pause(String modelId, String clientOperationId);
    ModelOperationHandle resume(String modelId, String clientOperationId,
                                IModelCallback callback);
    ModelOperationHandle delete(String modelId, String clientOperationId);
}
```

Parcelable 必须有 `schemaVersion`，只允许追加字段；回调一律 `oneway`。禁止 `Bundle`、`Map`、任意 JSON、异常对象、Repository、`LiveData`、`Executor`、`Future`、内部 listener 对象和 `Trajectory` 跨进程传输。

所有会触发副作用的操作——控制类（cancel/steer/confirmation/resume/voice 启停）与资源类（install/resume download、setActiveModel）——一律携带 `clientOperationId`，共用 §3.4.2 的幂等表。`VoiceSessionRequest` 与 `IVoiceSessionCallback` 遵循同一 DTO 约束：只含 schemaVersion 化的安全字段（触发来源、语言、安全识别文本、状态、错误码），不传 PCM、audio format 或原始中间结果。

#### 3.4.1 版本协商与 DTO 约束

`getServiceInfo()` 必须返回 `contractMajor`、`minClientMinor`、`maxClientMinor`、`featureFlags`、`serviceVersion`、`servicePackage` 与 `contractHash`。客户端取得 Binder 后先协商；没有版本交集不得继续业务调用。`contractHash` 在 CI 由 AIDL/DTO 定义生成，用于阻止“major 相同、接口产物不一致”的安装组合。注意：不返回全局 currentAndroidUser——Service APK 是单一系统实例服务所有 Android user，数据归属只由服务端从调用 UID 派生（§3.6），不暴露任何“当前用户”全局状态。

| DTO | 必填/上限 | 不可包含的内容 |
|---|---|---|
| `AgentRequest` | `clientRequestId`、`clientSessionId`、`text`、`inputSource`、`languageTag`；text 1..4096 UTF-16 chars | actor、user、zone、vehicle state、arbitration key、raw prompt。 |
| `AgentTaskHandle` | `taskId`、`acceptedSequence`、state | runtime requestId、路径、secret。 |
| `AgentTaskSnapshot` | taskId、state、lastSequence、安全文本、错误码、确认卡；最多 16 KiB UTF-8 | `Trajectory`、raw tool/model/provider 数据。 |
| `AgentTaskEvent` | taskId、sequence、elapsedRealtimeMs、type、state、安全 payload；最多 4 KiB UTF-8 | 不能作为唯一恢复数据源。 |
| `ConfirmationDecision` | confirmationId、approve | 自由文本、capability、参数。 |

`clientRequestId` 和 `clientOperationId` 必须是小写 UUID；`clientSessionId` 限制为 `[A-Za-z0-9._-]{1,64}`；`languageTag` 为 BCP-47，长度 1..35。所有规则在 Service 再校验，绝不只依赖 UI 校验。

#### 3.4.2 控制操作的安全语义

`SteerRequest` 仅允许 `REPROMPT`（1..512 chars 的补充用户输入）与 `DEFER`（不得携带文本）。`DEFER` 只能在安全检查点生效：任务进入持久化非终态 `DEFERRED` 并释放调度槽，不自动恢复，恢复必须经 `resumeTask(taskId, clientOperationId)`；已下发不可撤销写命令时返回 `TOO_LATE` 或维持 `EXECUTION_UNKNOWN`，不得假称暂停成功。它不允许 `FORCE_TOOL`、capability name、arguments 或任意 JSON。`FORCE_TOOL` 仅保留进程内 debug/test，避免 Launcher 将 IPC 变成直接车控入口。

cancel、steer、confirmation、resume 以及资源侧写操作（install/resume download、setActiveModel）都必须把 `(androidUserId, ownerPackage, clientOperationId)` 持久化：相同 operation type、taskId/目标资源、request hash 重放时返回首次 `AgentOperationResult`；任一字段冲突则 `IDEMPOTENCY_CONFLICT`，不改变目标状态。此类 API 不能返回 `void`，必须返回稳定的 `code/acceptedSequence/taskState`（或对应 handle）。

#### 3.4.3 外部状态、sequence 与回调背压

| 状态 | 含义 | 后继 |
|---|---|---|
| `ACCEPTED` | 已持久化并入队 | `RUNNING`、`REJECTED`、`CANCELLED` |
| `RUNNING` | 模型、工具或可信回读中 | `WAITING_CONFIRMATION`、`DEFERRED` 或终态 |
| `DEFERRED` | 安全检查点暂缓，已释放调度槽，持久化非终态 | `RUNNING`（仅经 `resumeTask`）、`CANCELLED` |
| `WAITING_CONFIRMATION` | Provider 尚未调用 | `RUNNING`、`CANCELLED`、`FAILED` |
| `COMPLETED` / `PARTIALLY_COMPLETED` | 已验证成功/部分成功 | 终态 |
| `REJECTED` | 鉴权、参数、过载或 Policy 拒绝，未执行 | 终态 |
| `FAILED` | 明确协议、Provider 或验证失败 | 终态 |
| `CANCELLED` | 可确认未执行的取消/抢占 | 终态 |
| `EXECUTION_UNKNOWN` | 写命令可能下发，无可信最终状态 | 仅 recovery/readback 可修订 |

状态写入使用 CAS；优先级为“任一终态 > WAITING_CONFIRMATION > DEFERRED > RUNNING > ACCEPTED”。唯一例外是带 operation journal 证据的 recovery worker，可用 `WHERE state='EXECUTION_UNKNOWN'` 将其修订为完成/失败/部分完成，并在 snapshot 标记“恢复读回修订”。

每 task 的 sequence 从 1 单调递增；state、sequence、safe snapshot 在同一 DB transaction 中提交，成功后才发送 callback。Phase 1 不持久化 `TEXT_DELTA`；callback gap、队列溢出、Service 重启时发送 `RESYNC_REQUIRED`，客户端回读 snapshot。订阅者队列必须有固定消息/字节上限，满时丢可重建 delta、保留最新状态；`RemoteException` 立即取消订阅。服务端对每个已注册 callback 统一 `linkToDeath`：客户端进程死亡即移除订阅并释放队列配额；`unsubscribeTask`/`unsubscribeStatus` 是显式退订入口，service-lib 的 `AutoCloseable` 订阅句柄关闭时必须调用它们。终态 snapshot 至少保留 24 小时。

### 3.5 连接、启动与死亡恢复

`MatrixAgent` 对齐 `PanoCinema`，负责连接而不承载业务：

```text
1. 反射 ServiceManager.getService(MATRIX_AGENT_SERVICE)
2. Binder 存活：asInterface、linkToDeath、getServiceInfo 协商版本
3. service-lib 私有 explicit bind Agent Manager Service，拉起并维持宿主
4. 首次未找到 Binder：ServiceConnection 回调后重新 getService
5. Binder death：清空四个 Manager cache，通知 DISCONNECTED，有界重连
6. 重连后：已创建 Manager 重新获取 Binder 并替换 Proxy
```

开机路径：

```text
BOOT_COMPLETED / package replaced
  → MatrixAgentBootReceiver
  → startForegroundService(MatrixAgentManagerService)
  → 创建 AppContainer、Manager Binder、ServiceRegistry
  → addService(MATRIX_AGENT_SERVICE, binder)
  → 启动/绑定轻量 Model、Voice、Download Service
  → 子 Service 创建 Stub 并注册 ServiceRegistry
```

Service 初始化不等于加载 MNN、下载或开麦；这些仍按需发生。Manager Service 可用 `START_STICKY` 请求重建，但 Binder death 后仍必须重新注册并由客户端处理重连。

### 3.6 调用方与多用户安全

Service APK 以单一系统实例服务所有 Android user（跨 user Binder 调用），`android_user_id` 是任务、Memory、Audit 的数据隔离维度而非实例维度。ServiceManager 获得 Binder 不等于授权。每个公开 AIDL 方法均须验证：

```text
Binder.getCallingUid()
  → PackageManager.getPackagesForUid(uid)
  → package allowlist + 签名 lineage
  → Android user
  → OEM occupant / zone
  → CallerContext / ExecutionIdentity
```

客户端不能填写真实 user、actor、zone、vehicle state 或 arbitration key；Manager Service 从调用 UID 派生。模型管理、删除下载和语音控制使用比普通任务更窄的权限。

## 4. 功能实现

### 4.0 当前 MatrixAgent 源码参考索引

以下是老工程中已实现功能的源码位置。**老工程包结构仅用于定位行为与测试参考**；目标工程（MatrixAgentProject）按服务端领域归属重组，迁移必须保留功能契约和测试，不要求保留旧包名：

```text
com.matrix.agent.host       ManagerService、BootReceiver、ServiceRegistry
com.matrix.agent.task       TaskManager、Agent Binder、Runtime
com.matrix.agent.model      Model Service / Binder
com.matrix.agent.download   Download Service / Binder
com.matrix.agent.voice      Voice Service / Binder
com.matrix.agent.data       持久层（SQLCipher Room、Memory、Audit）
com.matrix.agent.platform   平台适配（网络、Keystore、MNN 网关）
```

对外协议与客户端封装位于 service-lib 内部两层，单一 Maven 产物不拆：

```text
com.matrix.agent.api.{common,agent,model,download,voice}   双端共享契约：AIDL、Parcelable、常量、错误码
com.matrix.agent.client                                    客户端封装：MatrixAgent 门面、Manager、预校验
```

`agent` 保留给产品名与公开 AIDL/API 语义（api.agent）；服务端执行域使用 `task`，两者语义不冲突。

| 目标功能 | 当前目录/文件 | 重构后的归属 |
|---|---|---|
| Application 与组合根 | `matrix-agent-service/.../host/MatrixAgentApplication.java`；`host/AppContainer.java` | 根 Manager Service 创建并持有。 |
| Agent 请求入口、超时、取消、清数据 | `matrix-agent-service/.../task/AgentRuntimeRepository.java` | `PersistentTaskManager` 调用的核心 Runtime。 |
| Agent 多轮循环 | `matrix-agent-service/.../task/AgentEngine.java`；`task/ModelCallExecutor.java`；`task/SteerMailbox.java` | Agent Manager 内部执行器。 |
| 调度与会话 | `matrix-agent-service/.../task/TaskScheduler.java`；`data/session/` | Agent Manager 内部。 |
| 能力、Schema、策略、工具 | `matrix-agent-service/.../task/capability/`；`task/policy/`；`task/tool/` | Agent Manager 内部；不发布给 Launcher。 |
| 身份、意图、车辆状态 | `matrix-agent-service/.../task/identity/`；`host/CallerContext.java` | 调用 UID 派生身份；OEM 再接入权威 occupant/VHAL 映射。 |
| 模型网关与模型配置 | `matrix-agent-service/.../model/` | Model Binder 域。 |
| 端侧 MNN 推理 | `matrix-agent-service/.../model/OnDeviceModelGateway.java`；`ondevice/src/main/java/...` | Model 域私有实现。 |
| 模型下载 | `matrix-agent-service/.../download/`；`host/DownloadServiceStub.java` | Download Binder 域。 |
| 数据库与 DAO | `matrix-agent-service/.../data/db/` | Service APK 私有 SQLCipher Room。 |
| Memory / Audit | `matrix-agent-service/.../data/memory/`；`data/audit/` | Agent Manager 内部，按调用方/zone 作用域隔离。 |
| 语音运行时 | `matrix-agent-service/.../voice/`；`host/VoiceServiceStub.java` | Voice Binder 域，release 受权限与前台服务边界约束。 |
| UI | `matrix-agent-launcher/.../presentation/` | 独立 MVVM Launcher，仅依赖 service-lib。 |

### 4.1 Agent Manager：任务执行与持久化

Binder 将 DTO 转为可信 `ExecutionIdentity`，投递到新的持久 `TaskManager`，再复用现有执行链路：

```text
AgentRuntimeRepository
  → TaskScheduler（同车仲裁、主驾只读抢占）
  → AgentEngine（模型/工具多轮循环）
  → PolicyEngine（schema、风险、车况、记忆 gate）
  → ToolExecutor（Provider、超时、取消、readback 验证）
  → Memory / Audit / Session
```

读请求超时为 `TIMED_OUT`；写请求已下发但无法确认时必须为 `EXECUTION_UNKNOWN`。`FORCE_TOOL` 不开放给 AIDL，只留进程内 debug/test。

当前 Room v4 升级至 v5，新增：

| 表 | 用途 |
|---|---|
| `agent_task` | taskId、owner、request hash、状态、sequence、安全 snapshot、epoch。 |
| `agent_task_operation` | 工具调用与外部事务状态，区分已验证、失败、未知。 |
| `agent_client_operation` | cancel/steer/confirmation 的 `clientOperationId` 幂等记录。 |
| `pending_confirmation` | 高风险确认卡和过期状态。 |

幂等键是 `(androidUserId, callerPackage, clientRequestId)`：相同请求返回相同 task；同 ID 但不同 payload 返回冲突。

#### 4.1.1 v5 表字段与事务规则

`agent_task` 至少包含：`task_id`、`android_user_id`、`owner_package`、`client_request_id`、`request_hash`、`session_id`、`subject_user_id`、`actor`、`zone`、`request_epoch`、`state`、`stop_reason`、`error_code`、`cancel_requested`、`last_sequence`、`safe_snapshot_json`、创建/更新时间，并对 `(android_user_id, owner_package, client_request_id)` 建唯一索引。

`agent_task_operation` 是恢复权威，按 `(task_id, operation_sequence)` 记录 `capability_name`、`is_write`、arguments hash、安全摘要、phase、confirmationId、外部 transactionId、trusted state、epoch 与时间。phase 至少为：`PLANNED / WAITING_CONFIRMATION / DISPATCHED / ACCEPTED / VERIFIED / FAILED / UNKNOWN / CANCELLED`。

`agent_client_operation` 记录调用方的 cancel/steer/confirm 幂等结果；`pending_confirmation` 记录 confirmationId、task、operation、owner、冻结参数 hash、过期时间和消费时间。

每次状态 CAS 必须在同一 transaction 更新 task state、stop reason、error code、safe snapshot 和 `last_sequence`；提交成功后才 callback。禁止 read-then-write 覆盖并发 cancel、Provider complete 或恢复结果。禁止 `fallbackToDestructiveMigration()`；必须提供 SQLCipher Room v4→v5 migration fixture 测试。

#### 4.1.2 submit、cancel 与 TaskManager 算法

```text
submit(request, callback)
  1. resolve CallerContext；校验 DTO、大小和版本
  2. transaction：按 (androidUserId, ownerPackage, clientRequestId) 查询
  3. 已存在且 requestHash 相同：注册 callback，返回原 handle，不重复 launch
  4. 已存在但 hash 不同：返回 IDEMPOTENCY_CONFLICT
  5. 不存在：insert ACCEPTED / sequence=1 / safe snapshot
  6. commit 后注册 callback，TaskManager.launch(taskId)，立即返回 handle

cancelTask(taskId, clientOperationId)
  1. transaction 写 client operation + cancel_requested=1 + sequence
  2. commit 后才 CancellationToken.cancel()
  3. 若写 operation 已 DISPATCHED 且无可信 readback，终态必须 EXECUTION_UNKNOWN
```

TaskManager 的内存 `taskId → CancellationToken/Future` 仅管理活跃任务，数据库始终是权威。对外暴露第一条 `submit` 路径之前，`agent_task` 最小持久表必须已经存在并承载权威状态；不允许以纯内存任务权威对外提供服务。启动后先扫描恢复状态、再接收新任务；Service 级 in-flight/队列/单 owner 配额必须是显式常量，并在超过配额时事务写入 `REJECTED/OVERLOADED`，不创建内存 Future。真正同车仲裁仍委托既有 `TaskScheduler`，不能再实现第二套 driver/passenger 调度器。

#### 4.1.3 确认与崩溃恢复

写 capability 的路径固定为：

```text
Policy ALLOW
  → persist PendingConfirmation
  → WAITING_CONFIRMATION
  → 校验 confirmationId / owner / expiry / frozenArgumentsHash
  → Provider execute
  → trusted readback / verify
```

确认 token 一次性消费。拒绝、超时、重放、跨 task/user/package、参数变化、Service 重启、user switch、focus loss 或 cancel 任一发生，均 fail closed；批准前 Provider 调用次数必须为 0。

Service 重启扫描规则：`ACCEPTED` 不重放用户文本而改为 `CANCELLED`；只读 `RUNNING` 设为 `FAILED/SERVICE_RESTARTED`；写操作有 transactionId 但无可信状态时调用 `observe/queryState`，仍不能确认则 `EXECUTION_UNKNOWN`；`WAITING_CONFIRMATION` 失效且不恢复；`DEFERRED` 重启后保持 `DEFERRED`，等待 `resumeTask`，不自动恢复；下载中的 `.tmp` 复用现有文件系统对账转 `PAUSED`。恢复按每条 `agent_task_operation` 判断，绝不自动重发写命令。

### 4.2 Model

复用 `ModelGatewayRepository`：云端配置继续由 `SecureModelConfigStore` 经 Android Keystore 保存，不能回传 API Key；`testConnection` 的 `ModelConfigInput` 仅接受受控 provider 枚举与 Keystore 密钥引用，不接受调用方传入的任意 URL、header 或 token；端侧模型仅从 `filesDir/models/mnn/<model>` 加载，仅 arm64-v8a，拒绝路径遍历。

切换端侧模型保持 retire → lease drain → close，避免新旧 MNN session 并存。`ModelManager` 管理选择和状态，下载传输交给 Download Manager。

### 4.3 Download

复用 `ModelDownloadManager` 与当前前台 `DownloadService`：`.tmp` 文件、HTTP Range 断点续传、DAO/文件系统启动对账、通知栏进度与完成后 stopSelf。Download 子 Service 是单一 Android Service 兼两职：常驻提供 `IDownloadService` Binder，下载期间按需将自身升级为 `dataSync` 前台服务承载长下载，完成后回落；不另设第二个下载 Service 组件。

`listCatalog()` 只读本地已验签缓存，目录的网络刷新由 Download Service 内部调度，不经 Binder 同步触发。release 只接收 catalog 中带签名、hash、版本、许可和最小 Service 版本的 modelId；禁止任意 URL、仓库地址或本地路径。

### 4.4 Voice

复用 `VoiceRuntime`、`VoiceSessionController`、AudioFocus、TTS、ASR/Wake 端口。Voice Manager 仅暴露状态、启停、取消和受控会话；普通客户端不能获得原始 PCM。Launcher 的“按住说话”经 `startUserInitiatedSession` 触发：音频采集全部在 Service 进程内完成，会话期间 Voice 子 Service 按需将自身升级为 `microphone` 前台服务；`IVoiceSessionCallback` 只回传安全识别文本、状态与错误。热词/VoiceInteraction 系统入口与 Launcher PTT 共用同一 `VoiceSessionCoordinator`，会话互斥与仲裁在其中完成。

Vosk、`MatrixVoiceInteractionService`、`MatrixRecognitionService` 已进入 release 主源集；系统语音入口按 VoiceInteraction 框架契约集成，Launcher 不允许直接 bind。

### 4.5 Memory、Audit 与多用户

Memory、Session、Audit、task owner、模型可见范围与数据清除都按 `ExecutionIdentity` 隔离，替换现有 `demo-driver`、`demo-passenger`、`demo-vehicle`。

继续复用 SQLCipher Room、Memory epoch、`RoomMemoryWriter`、`RoomAuditRepository`、`AuditEventRecorder`。清除数据保持“取消在途 → 等待收敛 → epoch++ → 清 Memory/Session/Audit”，避免旧异步结果写回。

## 5. Launcher 实现

### 5.1 依赖边界

Launcher 仅依赖：

```text
matrix-agent-service-lib
AndroidX UI / Lifecycle / SavedStateHandle
```

禁止引用 `AppContainer`、`AgentRuntimeRepository`、`MatrixDatabase`、MNN、`ModelDownloadManager`、Service APK 的 component 及任何 AIDL Stub 实现类。

Launcher 自身的界面偏好（主题、页面设置等非任务状态）使用本地 SharedPreferences 保存，不经 Manager。

### 5.2 ViewModel 与 Manager

Launcher 创建 `MatrixAgent` 并订阅连接状态：

```text
ConversationViewModel → MatrixAgentManager
ModelViewModel        → ModelManager
DownloadViewModel     → DownloadManager
VoiceViewModel        → VoiceManager
```

所有页面必须处理 `CONNECTED / CONNECTING / DISCONNECTED / SERVICE_NOT_READY / PERMISSION_DENIED`，不能把 null Binder 当正常状态。

### 5.3 任务与断线恢复

ConversationViewModel 只在 `SavedStateHandle` 保存 taskId 和必要 UI 状态；权威任务状态来自 `MatrixAgentManager.getTaskSnapshot(taskId)`。

Binder death、Launcher 重启或页面重建后：

1. service-lib 重连并更新 Manager Proxy；
2. 用 taskId 重新读取 snapshot；
3. 按 `lastSequence` 订阅后续事件；
4. 控制操作使用 `clientOperationId` 幂等重试；
5. `EXECUTION_UNKNOWN` 显示“执行结果待确认”，不擅自报告成功；
6. `DEFERRED` 显示“已暂缓”，提供恢复（`resumeTask`）与取消入口。

## 6. MatrixAgent Test：外部 Binder 接口测试

`matrix-agent-test` 是一个独立 Android application 模块，不是 `:matrix-agent-service` 内的 `androidTest` 源集。它模拟真实外部客户端：只依赖 `:matrix-agent-service-lib`，只能通过 `MatrixAgent`、`MatrixServiceConstants` 与四个 Manager 调用服务。

```text
matrix-agent-test/
  src/main/java/                   ← 可选的最小调试 Activity，不承载业务实现
  src/androidTest/java/            ← Java instrumentation tests
  build.gradle.kts                 ← com.android.application；仅依赖 service-lib
```

测试 APK 不允许 import `com.matrix.agent.app.*`、data/core/platform 包、Service component 或任一 Binder Stub 实现。Service APK 作为被测预装/安装产物存在；Test APK 对 Service 的唯一编译期认识是 service-lib。

为验证授权边界，Test 使用两个 product flavor：

| flavor | applicationId | 平台/Service 配置 | 目的 |
|---|---|---|---|
| `trusted` | `com.matrix.agent.test` | 仅 userdebug/eng allowlist 中允许 | 验证四个 Manager 和正常 API。 |
| `untrusted` | `com.matrix.agent.test.untrusted` | 不在 allowlist | 验证即使取到 Binder 也得到 `PERMISSION_DENIED`，且不创建 task。 |

测试矩阵至少覆盖：

1. `MatrixAgent.create()` 的 ServiceManager 查询、启动期未就绪、bind 拉起和版本协商；
2. `MANAGER/MODEL/VOICE/DOWNLOAD_SERVICE` 都返回对应 Manager 或稳定的 `SERVICE_NOT_READY`；
3. submit 的幂等、snapshot 恢复、sequence 去重、callback `RESYNC_REQUIRED`；
4. cancel/steer/confirmation 的 `clientOperationId` 重放与冲突；
5. kill Service 进程后的 Binder death、Manager cache 重建和 task snapshot 恢复；
6. trusted/untrusted、不同 Android user 的访问隔离；
7. 写操作确认、`EXECUTION_UNKNOWN`、重启 recovery 与下载 `.tmp` 对账。

`connectedVoiceCertificationAndroidTest` 属于 `:matrix-agent-service` 自己的 instrumentation test：验证真机麦克风、Vosk、中文 TTS 与内部 Voice 实现，不属于 `:matrix-agent-test` 的跨 APK ABI 测试，不迁移到 Test APK。

推荐的模块级编译/执行入口：

```bash
./gradlew :matrix-agent-service:assembleDebug
./gradlew :matrix-agent-service-lib:assemble
./gradlew :matrix-agent-launcher:assembleDebug
./gradlew :matrix-agent-test:assembleTrustedDebug
./gradlew :matrix-agent-test:connectedTrustedDebugAndroidTest
./gradlew :matrix-agent-test:connectedUntrustedDebugAndroidTest
./gradlew :matrix-agent-service:connectedVoiceCertificationAndroidTest
```

这样 Android Studio 中可分别选择 Service、Launcher、Test 的 Run/Instrumentation 配置；CI 也可按模块并行编译，而不是只能构建整个仓库。

## 7. 历史实施时序（已完成，仅作追溯）

> 下列阶段记录的是本次重构的实施顺序，不代表当前待办。实际关闭状态和仍依赖
> OEM/真机条件的验收项，以《matrix-agent-service代码架构评审》为准。

### 阶段 A：骨架与制品

1. 锁定 AGP、Gradle、JDK 17、minSdk 28 / compileSdk 36 / targetSdk 36 与 Lifecycle、Room、SQLCipher、WorkManager、OkHttp、MockWebServer、JUnit、Espresso 的确切版本，统一写入 `libs.versions.toml`；确认自有模块零 Kotlin/零 Compose 并在构建上禁止引入；
2. 创建 `:matrix-agent-service-lib`、`:matrix-agent-launcher`、`:matrix-agent-test`，并建立 Java 的 `src/main`、`src/test`、`src/androidTest` 源集；
3. 将原工程 core/data/platform/presentation/ondevice 的 Java 源码和测试按目标领域逐批迁入；MNN 上游 C++ 保持第三方依赖，上游 demo 树 Kotlin 不进入 source set；每批保持现有行为测试绿；
4. 建 AIDL source set、DTO、`MatrixServiceConstants`、`MatrixAgent`、`MatrixManagerBase`、四个空 Manager；
5. 配置 Maven publication、sources jar、consumer ProGuard、版本协商；
6. 保持 Java Service APK 单独 assemble/test 可用。

### 阶段 B：Agent Manager 入口

1. 增加 `MatrixAgentManagerService`、BootReceiver、`MatrixAgentManagerBinder`、CallerAuthorizer、ServiceRegistry；
2. 注册唯一 ServiceManager Binder；
3. 实现查询、bind 拉起、DeathRecipient 与有界重连；
4. 打通 `MANAGER_SERVICE → MatrixAgentManager`。

### 阶段 C：四项功能竖切

1. Manager：先落 `agent_task` 最小持久表（承载权威状态），再将 `submit/getTaskSnapshot/cancelTask` 接入当前 Runtime；
2. Model：`listModels/getRuntimeStatus/setActiveModel`；
3. Download：下载列表与受控操作；
4. Voice：状态与受控启停；
5. 三个子 Service 的 Stub 注册、注销与重建闭环。

### 阶段 D：持久任务与写能力

1. Room v4 → v5 补全 task/operation/confirmation 表（阶段 C 已落 `agent_task` 最小表）；
2. snapshot、sequence、断线重同步、服务死亡恢复；
3. `DEFERRED/resumeTask`、confirmation、外部事务元数据、启动恢复扫描；
4. fail-closed 测试通过后，才开放真实写 capability。

## 8. 验收标准与产品前置条件

### 8.1 验收

1. 两个 APK 能安装到目标镜像，Manager Service 开机注册全局 Binder；
2. Launcher 只通过 Maven `MatrixAgent` 与 `MatrixServiceConstants` 获取四个 Manager；
3. 四个服务分别取得对应 AIDL Proxy 或明确未就绪状态；
4. Binder death 后 cache/Proxy 重建，Launcher 用 task snapshot 恢复页面；
5. 非 allowlist 调用方与跨 Android user 无法访问他人的 task/memory/audit；
6. 幂等键不重复建 task，未知写结果保持 `EXECUTION_UNKNOWN`；
7. Java unit、SQLCipher migration、MNN、下载、语音认证测试通过；`:matrix-agent-test` 的 trusted/untrusted 跨 APK Binder/instrumentation 测试通过。MatrixAgent 自有 Android/业务代码为 Java 且零 Kotlin/零 Compose；MNN 上游 C++ 作为第三方依赖单独管理，其 demo 树 Kotlin 不参与编译。

### 8.2 产品前置条件

仅放入 `priv-app` 不足以调用 `ServiceManager.addService()`。OEM 平台必须确认：

- system UID / platform 签名；
- Service APK addService 与 service-lib getService 的 hidden API 授权；
- 最终服务名的 SELinux `servicemanager add/find` 权限；
- Service、Launcher、语音框架的 Binder、文件、Keystore 和多用户隔离；
- vendor/product/system_ext 安装分区及 OTA 策略。

不满足时必须回退普通显式 `bindService`，不能假设任意 priv-app 可复刻 PanoBox。

其中当前真正需要 OEM/产品给出实体输入的只有两项：catalog 的签名私钥与发布流程，以及用于 system UID、SELinux、平台签名测试的 userdebug 镜像；本文对其余接口与验收条件先行写死。

## 9. 命名待定与 PanoCinema 参考

### 9.1 命名待定

| 项目 | 约束 |
|---|---|
| `MATRIX_AGENT_SERVICE` 字面量 | 平台/产品统一确定，只存在于 `MatrixServiceConstants`。 |
| `MANAGER/MODEL/VOICE/DOWNLOAD_SERVICE` 字面量 | 同上，客户端只使用公开常量。 |
| Model、Voice、Download 最终 Android component 名 | 实现前确定 package 与 manifest。 |
| Maven groupId/artifactId/version | 接入内部仓库与 release 策略后确定。 |

### 9.2 PanoCinema 本地参考

```text
/Users/zhangyongbin/Desktop/Old File/nio_packages_services_panocinema
```

| 参考点 | 文件/目录 |
|---|---|
| Maven 接口库/AIDL | `service-lib/` |
| 客户端门面、Manager cache、重连 | `service-lib/src/main/java/com/nio/panocinema/PanoCinema.java` |
| 服务名常量 | `service-lib/src/main/java/com/nio/panocinema/constant/PanoServiceConstants.java` |
| 根 AIDL/子 Binder 发现 | `service-lib/src/main/aidl/com/nio/panocinema/IPanoCinema.aidl` |
| Service 宿主 | `PanoBox/src/main/java/com/nio/panobox/PanoBoxService.java` |
| 根 Binder/addService | `PanoBox/src/main/java/com/nio/panobox/IPanoBoxImpl.java` |
| 开机拉起 | `PanoBox/src/main/java/com/nio/panobox/PanoBoxApplication.java`、`PanoBoxReceiver.java` |
| 系统集成 manifest | `PanoBox/src/main/AndroidManifest.xml` |

PanoCinema 仅作架构参考。服务名、签名、system UID、hidden API、权限、SELinux 和调用方 allowlist 必须按 MatrixAgent 产品条件重新设计，不能直接复制。
