# MatrixAgent 项目详细评审报告

| 项 | 内容 |
|---|---|
| 项目 | MatrixAgent · 面向 Android 系统镜像的本地智能 Agent 运行时 |
| 版本基线 | v0.6.14 / versionCode 6014 |
| 评审日期 | 2026-09-22 |
| 评审范围 | 五个模块全部自有源码、Gradle/构建脚本、Manifest、Room schema、两份设计文档、README；**不含** `ondevice/src/main/cpp/MNN` 子模块内部实现（按供应链依赖视角评审） |
| 评审方式 | 逐文件精读 + 分域并行深读 + 安全/密钥扫描 + 构建工程化核查 |
| 工作区状态 | 评审时约 67 个未提交修改（对话输入增强进行中），结论基于当前脏树 |

---

## 一、项目概览

### 1.1 项目是什么

MatrixAgent 把身份、调度、模型、记忆、语音、下载与审计收敛到 **platform 签名、`android.uid.system` 的可信系统进程**，应用只通过版本化 SDK（AAR）使用 Agent 能力。这不是普通可安装 APK，而是面向定制 Android ROM / LineageOS 系统镜像集成的系统基础设施。

认证基线：Mi 9 SE（`grus`）、Android 15、LineageOS 22.2、Java 17、arm64-v8a。

### 1.2 模块划分与规模

| 模块 | 交付物 | 角色 | 主代码 | 测试代码 |
|------|--------|------|--------|----------|
| `matrix-agent-service` | `com.matrix.agent` APK | system-UID Host：任务/对话/语音/模型/下载/审计 | 350 文件 / 41,607 LOC | 214 文件 / 34,516 LOC |
| `matrix-agent-service-lib` | AAR | 纯 Java SDK：AIDL + Parcelable DTO + 五 Manager | 56 文件 / 5,108 LOC | 5 文件 / 336 LOC |
| `matrix-agent-launcher` | `com.matrix.agent.launcher` APK | SDK-only 工作区（Java + XML + MVVM） | 25 文件 / 5,975 LOC | 6 文件 / 327 LOC |
| `matrix-agent-test` | trusted / untrusted APK | 跨 APK 权限、契约与 Binder 行为验证 | 双 flavor | instrumented |
| `ondevice` | Android Library + JNI | MNN-LLM Java 边界与 Sherpa 语音封装 | Java 76 文件 / 6,972 LOC + cpp | 5 文件 / 230 LOC |

合计自有 Java 约 **9.5 万行**（主代码约 5.9 万 + 测试约 3.5 万）。测试文件约 200 个 JVM + 22 个 androidTest。

### 1.3 架构示意

```text
可信客户端 (Launcher / OEM / Tests)
        │  仅 service-lib AAR
        ▼
  MatrixAgent 门面 ── 五 Manager ── 契约协商 (major + minor + SHA-256 hash)
        │
        ▼  Binder
  根 Binder (ServiceManager.addService + 显式 bind 双通道)
        │  CallerContext.enforceTrusted (signature 权限 + 签名复验)
        ▼
  五域 Stub (Task / Conversation / Model / Download / Voice [+ DebugTrace])
        │
        ▼
  AppContainer / *RuntimeGraph
        │
        ├── AgentEngine（任务循环）
        ├── ConversationCoordinator（对话串行 lane）
        ├── VoiceRuntime / VoiceSessionController（语音状态机）
        ├── ModelGatewayRepository（多协议模型入口）
        └── ModelDownloadManager / 语音资源下载
        │
        ▼
  Room+SQLCipher · AndroidKeyStore · MNN · Vosk/Sherpa · OkHttp
```

### 1.4 边界原则（与代码一致性）

README 声明的四条边界原则在代码中基本兑现：

1. **Host 拥有事实** — 身份、调度、密钥、模型文件、录音、持久化均在 Host。
2. **SDK 拥有契约** — 客户端只见稳定 DTO、错误码、Manager。
3. **Launcher 只负责呈现** — presentation 禁止 import `client.*`（由 `LauncherModuleBoundaryTest` 扫描强制）。
4. **失败必须可见** — fail-closed 错误码，不静默降级。

---

## 二、总体评价

**综合评级：B+**

> 工程成熟度显著高于同规模业余项目：有架构测试、有 fail-closed 纪律、有契约治理。  
> 但存在若干应立即修复的安全/正确性缺口，且发布链门禁与文档卫生偏弱。

### 2.1 核心优点

1. **可执行架构边界**  
   `RuntimeBoundaryTest`、`LauncherModuleBoundaryTest`、`SdkModuleBoundaryTest`、`HostModuleBoundaryTest`、`OnDeviceModuleBoundaryTest` 等用**源码扫描 import 禁令**锁依赖方向，而不是靠文档约定。

2. **fail-closed 贯穿全链**  
   SQLCipher passphrase 缺失抛 `IllegalStateException` 禁止明文回退；持久化/恢复/下载三 gate；契约协商 major+minor+hash；审计 HMAC 不可用退 `UnavailableAuditDigest` 而非弱算法。

3. **契约治理工程化**  
   - 构建期 `generateContractHash`：AIDL + 公开 api 规范化后 SHA-256 生成 `ContractVersion`  
   - `PublicAbiGoldenTest` 冻结错误码/状态/feature bit  
   - `verifyPublishedPom` 保证发布 AAR 无 Kotlin runtime  
   - ParcelSchema append-only + DTO `schemaVersion` 分支读取

4. **测试契约可读**  
   测试名几乎即规格：`TaskSchedulerCallerRunsPolicyNeverRejectsTest`、`RoomMemoryWriterEpochReadFailClosedTest`、`ConversationCompressorEightyPercentTriggerTest` 等。

5. **EXECUTION_UNKNOWN 语义**  
   写操作在进程中断、超时、取消后**不伪装“已撤销”**，贯穿 ToolExecutor、TaskDispatch、持久化恢复。

6. **ondevice 生命周期纪律**  
   `MNNLlmSession` activeCalls 计数 + 10s 硬超时 + 注入式 deferred scheduler；架构测试锁死 AutoCloseable、禁 finalize/自建线程。

7. **注释与决策留痕**  
   大量审计条目号、设计文档章节引用、取舍说明，可维护性强。

### 2.2 主要短板

| 类别 | 概述 |
|------|------|
| 安全 | DebugTrace 域 Stub 缺逐调用鉴权；调试轨迹开关默认 true；签名口令明文落盘（未入 git） |
| 正确性 | SDK 对话订阅断线不重注册；多处资源泄漏（FGS、deque、fd、registry） |
| 工程化 | 完全无 CI；`verifyArchitecture` 无人执行；发布链最后一步无门禁 |
| 文档 | README 4 个设计文档断链；设计文档状态头过期；截图版本滞后 |
| 代码卫生 | 单文件过大（AgentEngine 1110 行等）；67 个未提交修改；死代码残留 |

---

## 三、安全评审

### 3.1 安全模型（设计意图）

| 层 | 机制 |
|----|------|
| 进程身份 | `android:sharedUserId="android.uid.system"` + platform 签名 |
| 权限 | `com.matrix.agent.permission.ACCESS_AGENT`（`protectionLevel="signature"`） |
| Binder | 根 Binder 单点 `enforceTrusted`；域 Binder 经 `getMatrixService` 私有分发；方法级二次 `caller()` |
| 存储 | SQLCipher（KeyStore 派生 passphrase）+ API Key/腾讯凭证 AES-GCM KeyStore |
| 网络 | release `cleartextTrafficPermitted=false`；元数据强制 https + host 钉死 + 禁 redirect |
| 日志 | SafeLog 占位符 / AuditRedactor schema 脱敏 / DebugTraceRedactor 禁词与 PII 打码 |
| 备份 | `allowBackup=false` + data extraction 全排除 |

### 3.2 安全发现（按严重度）

#### Critical

**S1. DebugTraceServiceStub 缺少逐调用调用方校验**

- 位置：`matrix-agent-service/src/main/java/com/matrix/agent/host/rpc/DebugTraceServiceStub.java:37,68,78`
- 现象：`subscribe` / `getHistory` / `unsubscribe` 仅检查 `hostUiEnabled`，**无** `callerResolver.caller()` / `enforceTrusted`。
- 对比：`ModelServiceStub`、`VoiceServiceStub`、`ConversationServiceStub`、`DownloadServiceStub`、根 `MatrixAgentManagerService` 均在入口校验。
- 风险：设计假设域 Binder 仅在可信 `getMatrixService` 后获得，但 Binder 可被二次转交；一旦 `matrix.debugTraceUi=true`，轨迹订阅成为相对绕过点。
- 建议：与其它 Stub 对齐，方法首行补 `callerResolver.caller()`。

**S2. 平台签名口令明文落盘（当前未入 git）**

- 位置：  
  - `matrix-agent-launcher/tools/key/signing.properties:1-3`  
  - `matrix-agent-test/tools/key/signing.properties:1-3`  
  - `matrix-agent-service/tools/key/signing.properties`（同构）
- 证据：`storePassword=...`、`keyPassword=...` 明文；同目录 `platform.p12` / `platform.pk8` / `platform.x509.pem`。
- **已验证**：`git ls-files` 不含 `tools/key`；`git log -S` 口令字符串无历史命中；`.gitignore:21` 的 `**/tools/key/` 生效。
- 风险：磁盘泄露 = platform 签名材料 + 口令齐失，可伪造同签名 `android.uid.system` 应用。
- 建议：口令迁环境变量/密钥环并轮换；三份副本合一；保持 ignore。

**S3. `matrix.debugTraceUi=true` 入库为默认值**

- 位置：`gradle.properties:14`
- 现象：service/launcher 三变体均读取该属性；release 也消费。`DebugTraceEmitter` 日志侧量产仍无条件 `Log.i`。
- 风险：发布面默认开调试轨迹 UI；虽有脱敏，仍扩大信息面。
- 建议：默认 `false`；量产禁止无条件轨迹日志；release 包验证 `debuggable=false`。

#### High

**S4. 语音模型 SHA-256 可空则跳过校验（fail-open）**

- 位置：`VoskModelDownloader.java:347-351`（`expected` 空 → warning 跳过）
- 相关：`ModelDownloadEntity.sha256` 可选；Sherpa 侧 spec 钉死 hash（更好）。
- 建议：强制非空 SHA-256，缺失即拒绝安装。

**S5. conversationId 存在性侧信道**

- 位置：`ConversationServiceStub.java:648-653` `requireOwnedConversation`
- 现象：不存在 → `IllegalArgumentException`；越权 → `SecurityException`。
- 建议：统一为同一异常/错误码，不区分“不存在”与“无权”。

**S6. system/root 无条件信任 + 共享 UID 选包**

- 位置：`CallerContext.java:30-32,42-43`
- 现象：任意 system-UID 进程直接放行；`getPackagesForUid` 取字典序首包做签名复验。
- 风险：设计放宽；共享 UID 多包时可能选错包。
- 建议：明确白名单；共享 UID 场景校验全部包或调用方指定包。

#### Medium

**S7. release 网络安全与 debug 放行**

- `network_security_config.xml:18-43`：release 禁明文；debug-overrides 全量 cleartext + user CA（有意开发设计）。
- `ModelConfig` 允许 `http://` scheme，依赖 NSC 拦截。
- 建议：确认 release 签名包 `debuggable=false`；可选 provider `CertificatePinner`。

**S8. MNN 模型市场无内容签名**

- `ModelMarketClient` 自认无发布者签名/逐文件摘要；HTTPS 是唯一信任根（README「当前边界」亦承认）。
- 建议：上游有 digest 时强制校验；否则文档保持“不可描述为独立可信根”。

**S9. semantic 记忆写 zone 靠契约**

- `RoomMemoryWriter.java:144-151` userId/zone 无 CallerContext 强制校验（注释自认）。
- 建议：在 Binder/写入口强制 zone 绑定。

#### 安全做得好的地方（确认项）

- 主工程 **0 处** `printStackTrace`；未发现 API Key 明文入 log。
- 密钥经一次性 Binder pipe 传输，char[] 可擦除；`SecureModelConfigStore` 只打密文长度。
- `TrustedHttpsJson`：https + expectedHost + 禁 userinfo/自定义端口 + 字节上限。
- `JsonHttpTransport`：2MB 响应上限、raw body 不落日志、endpoint 去 query。
- OkHttp **无** 自定义 TrustManager / ALLOW_ALL HostnameVerifier。
- 无主路径 DexClassLoader / addJavascriptInterface / ObjectInputStream。
- `local.properties` 仅 `sdk.dir`；`.claude/settings.local.json` 无密钥且已 ignore。
- 下载路径：模型名正则白名单、canonical 防逃逸、文件数/字节预算、Range 续传、`.tmp`→正式目录 rename。

---

## 四、架构与模块评审

### 4.1 matrix-agent-service-lib（SDK）

#### 结构

- `api/`：Parcelable DTO + AIDL + 常量（ParcelSchema 当前 v7）
- `client/`：`MatrixAgent` 门面 + `MatrixAgentManager` / `ModelManager` / `VoiceManager` / `DownloadManager` / `ConversationManager` / `DebugTraceManager`
- 回调 AIDL 均为 `oneway`；listener 统一 `eventHandler().post` 到主线程

#### 优点

1. 连接双通道（可选 OEM discovery + 显式 bindService）；`connectionGeneration` 作废旧轮次。
2. `DeathLinkCoordinator`：reserve→promote 每代唯一 link，纯 JVM 可测。
3. 协商 fail-closed：major 相等 + minor 区间 + hash 非空相等（`ContractNegotiationPolicy.java:7-14`）。
4. 凭证走一次性 pipe 不进 Parcel；发布面排除 Kotlin runtime。
5. 断线语义：Manager 实例稳定、`SERVICE_NOT_READY` 稳定不可用、不伪造终态。

#### 问题

| # | 位置 | 类别 | 说明 |
|---|------|------|------|
| L1 | `VoiceManager.java:250` | 正确性/资源 | `provisionTencentTts` 非 SUCCESS 直接 return → `writeEnd` 未关、payload 未擦除（fd 泄漏 + 凭证残留） |
| L2 | `MatrixAgent.java:389-391` | 正确性 | `if (!isHostBound()) unbindHost();` 死代码且条件倒置 |
| L3 | `MatrixAgent.java:470-472` | 健壮性 | 重连 30 次放弃仅打日志，无终态回调，永久 DISCONNECTED 不可观测 |
| L4 | `MatrixAgent.java:83-100` | 健壮性 | `binderDied` 与 `onServiceDisconnected` 可叠加重连循环 |
| L5 | `MatrixAgent.java:461` | 正确性 | 重连不回 `CONNECTING`，违背 `ServiceLifecycleListener` 文档 |
| L6 | `ConversationManager.java:304-318,352` | 正确性 | service 为 null 时订阅 no-op 且重连**不重注册**；`DebugTraceManager.java:48` 同病。与 Task/Voice 重注册策略不一致 |
| L7 | `ContractNegotiationPolicy.java:12-13` | 安全 | 双端空串 hash 可通过协商 |
| L8 | 各 Manager 同步方法 | 健壮性 | 无主线程护栏，主线程调用同步 Binder 有 ANR 风险 |
| L9 | `CapabilityTraceEntry` 等 | 一致性 | 部分 DTO 缺 schemaVersion |
| L10 | `ConversationManager` 多处 | API | 以 `null` 作不可用信号，与其它 Manager 稳定 DTO 风格不一致 |
| L11 | `build.gradle.kts:73` | 健壮性 | hash 归一化剥 `//` 会破坏含 URL 字面量的未来源码 |

#### 测试缺口

1. **全部 Parcelable 无 round-trip 测试**（版本分支容错未验证）。
2. 无订阅重注册/断线恢复行为测试。
3. `MatrixAgent` 状态机（重连、generation）无集成 JVM 测试。
4. `PublicAbiGoldenTest` 未冻结 `FEATURE_CONVERSATION_DOMAIN` / `DEBUG_TRACE_SERVICE` 等新增常量。
5. `generateContractHash` 任务本身无测试。

---

### 4.2 matrix-agent-service · host / contract / identity / platform

#### 系统服务与 Binder 安全

- `MatrixAgentManagerService.onCreate` → `ServiceManager.addService` 失败即 fail-fast（`:176-186`）。
- 根 Binder 是唯一发现与授权边界；`getMatrixService` 分发五域 + DebugTrace。
- `CallerContext.enforceTrusted`：system/root 放行，否则 signature 权限 + `checkSignatures`。
- Manifest 对 Service 声明同名权限，双重防护。
- 输入校验：`AgentRequestValidator`（schema、小写 UUID、UTF-8 双重上限）+ `HostInputValidator`（operationId/session/language/`boundUtf8`）。

#### DI / Graph

- `AppContainer` 双检锁懒构建；`Persistence/Memory/Download/Audit/Model` runtime graph → `TaskRuntimeGraph`（`requireComplete`）→ `MatrixServiceGraph`。
- SQLCipher 不可用时 memory/audit/conversation 全链 fail-closed。
- 不足：`AppContainer` 构造约 160 行仍偏重；字段声明位置混乱（`sharedBudget` 等滞后于使用）。

#### 契约 / Schema

- `CanonicalSchema`：`$ref` build 期 eager inline + DFS 环检测。
- `SchemaJsonWriter`：OpenAI strict 降级集中（const→enum、强制 required）。
- `ModelTurn` 工厂严格化，拒绝静默降级。
- 缺口：`SchemaValidator` 空串一律拒即便 minLength=0；`detectCycle` 兄弟节点复制 visited，复杂 DAG 有指数风险。

#### 身份 / 音区

- `AgentRequest.Builder` 收拢 deadline/cancel/epoch/memorySave；`arbitrationKey` 与 `sessionId` 分离。
- `ExplicitIntentConstraints` 四态 fail-closed 拒多目标/否定冲突。
- **已知债**：`ActorUsers` 硬编码 demo-driver/passenger；对话统一投影 `DRIVER` / `demo-vehicle`。

#### 线程与审计

- `MatrixExecutorRegistry` 全局约 19 线程预算、全 AbortPolicy 有界队列。
- `KeyedSerialDispatcher` 总 pending 上限 + 池拒绝精密回滚。
- HMAC：Keystore 派生 HMAC-SHA-256 截断 16 hex；失败退 Unavailable 而非 SHA-1。
- `AndroidKeyStoreMasterKeyProvider` 首次生成用 `commit()` 消除断电丢库窗口。

#### 问题清单

| # | 位置 | 类别 | 说明 |
|---|------|------|------|
| H1 | `DebugTraceServiceStub.java:37,68,78` | 安全 | 全部方法缺 caller 校验 |
| H2 | `ConversationServiceStub.java:648-653` | 安全 | 存在性/越权异常类型不同 → 侧信道 |
| H3 | `CallerContext.java:42-43` | 安全 | system 无条件信任；OrSelf 语义偏宽 |
| H4 | `VoiceServiceStub.java:157-190` | 正确性 | session busy/persistence 拒绝前已 `bindings.consume`，绑定被吞 |
| H5 | `VoiceServiceStub.java:128-131` vs `627-648` | 竞态 | `enabled` 读改非原子于 sessionLock 外 |
| H6 | `VoiceServiceStub.java:101-102` | 竞态 | 构造中 `new MatrixHttpClient()` 旁路共享 client |
| H7 | `DownloadServiceStub.java:246-265` | 竞态/泄漏 | `futureRef.set` 晚于 schedule，death 先触发 cancel(null) |
| H8 | `ConversationServiceStub.java:575-577` | 泄漏 | 空 `CallbackRegistry` 只增不删 |
| H9 | `ModelServiceStub.java:112` | 阻塞 | Binder 线程 `probe.get` 最长 20s |
| H10 | `ModelServiceStub.java:158` | 内存卫生 | API key 经不可清零 String 持有 |
| H11 | `VoiceServiceStub.java:179` | 死代码 | `runtime == null` 在已赋值后不可达 |
| H12 | `ConversationServiceStub.java:678-680` | 疑误 | `toWindowDto` 将 `hasBefore` 同时作 `hasMore` 传入，需核对契约 |
| H13 | `MatrixAgentApplication.java:76-82` | 生命周期 | `onTerminate` 真机不调用，shutdown 依赖进程回收 |

#### 测试缺口

- `CallerContext` 鉴权（system/root 放行、共享 UID 选包）无单测。
- 根 Binder 方法级门禁无测。
- `CallbackRegistry` linkToDeath 清理无测。
- `DebugTraceServiceStub` 无 caller 门禁测试。
- `BoundedScheduledExecutor` 无专属测试。

---

### 4.3 matrix-agent-service · task / model / intent / session / vehicle

#### Agent 引擎循环

- `AgentEngine.execute`：会话锁 → 主循环每轮 cancel/deadline → 80% 主动压缩 → 字符预算 → Steer drain → `ModelCallExecutor.decide` → 四类终止 → Tool 前二次 cancel + DEFER peek → 逐 tool cancel。
- 五维预算真正参与判定（`AgentBudget`）。
- Steer：REPROMPT 并入、FORCE_TOOL 跳过 LLM 仍走 Policy、DEFER 终态；epoch gate 过滤 clearUserData 前入队旧指令。
- `computeFinalState` 保证异常终止永不误判 SUCCEEDED。

#### 调度与持久化

- `TaskScheduler`：arbitrationKey 与 sessionId 解耦；主驾只读抢占副驾只读；CANCELLED→PREEMPTED 重映射含 trajectory 改写。
- `TaskDispatchCoordinator`：写超时 500ms 收敛，否则 EXECUTION_UNKNOWN。
- `PersistentTaskStore`：全同步 + Room 事务；进程死亡标 EXECUTION_UNKNOWN 而非自动重放。
- `PersistentTaskManager.subscribe`：`eventDeliveryLock` 保证 replay 与 live 不重不漏。

#### Capability / Tool / Verify

- `PolicyEngine`：R3 → memory gate → 意图阻断 → readOnlyHint → vehicleState → CanonicalSchema → 业务 validator → zone。
- CAPABILITY/PARAMETER 二分：能力拒绝累计 blocked；参数拒绝回传可重试。
- `ToolExecutor`：capability timeout ∩ request deadline；写超时/拒绝 → EXECUTION_UNKNOWN。
- verify 三策略；Engine 强制未 verified → VERIFICATION_FAILED。
- **弱点**：`ReadbackGetStrategy` 仅判非空，不比对 expected。

#### Prompt 与压缩

- `PromptContextAssembler` 召回失败降级基础 prompt。
- `DefaultPromptBuilder` 记忆 value 白名单 + 范围校验投影。
- `ConversationCompressor`：transaction-aware 切分；structured 事实不进 LLM 摘要；分批摘要 + 显式裁剪提示；80% 主动 + 100% 被动双触发。

#### 模型网关

- OpenAI / Anthropic / Gemini / Ollama / MNN 端侧统一 `ModelApiClient` + 协议适配。
- `RetryPolicy`：仅 429/5xx 指数退避 + jitter，感知 cancel/deadline。
- `OnDeviceModelGateway`：串行 sessionLock、per-call cancel、lease 退役 drain。
- `ModelCallExecutor` abort-hook 链压低取消延迟。

#### 问题清单

| # | 位置 | 类别 | 说明 |
|---|------|------|------|
| T1 | `TaskScheduler.java:114-115,166-182` | 泄漏 | 先入队再 submit；拒绝路径不 `remove` → deque 永久残留 |
| T2 | `SteerMailbox.java:59,245-248` | 泄漏 | `clearAll` 只清 queues，不清 `seenSteerIds` |
| T3 | `ModelRuntimeCoordinator.java:36-60` | 锁阻塞 | `switchGateway` 持锁 `awaitDrained(5s)` 阻塞 `currentEngine()` |
| T4 | `ModelCallExecutor.java:159-160` | 取消副作用 | 模型超时直接 `token.cancel()` 整个请求 token，连带其它 abort hook |
| T5 | `PersistentTaskManager.java:125-133` | TOCTOU | cancel 先查 terminal 再 `tokens.get` 竞态窗口 |
| T6 | `AgentEngine.java:862-866,961` | 性能 | 每次 append 全量重算字符，长对话近 O(n²) |
| T7 | `ReadbackGetStrategy.java:27-28` | 弱 verify | 只验非空，写入错误值仍 verified |
| T8 | `PersistentTaskManager.java:223-226` | 死锁风险(低) | `publish` 持锁调跨进程 Binder |
| T9 | `TaskScheduler.java:119-120` | 调度 | 双重等待可能放大排队时间 |
| T10 | Engine 与 Dispatch 双 persist | 写放大 | 依赖 REPLACE 幂等 |

#### 测试薄弱区

1. `PersistentTaskManager` 并发 publish/replay 与 Binder 死回调压测。
2. `TaskScheduler` 拒绝后 deque 残留、`seenSteerIds` 泄漏无断言。
3. `ModelRuntimeCoordinator.switchGateway` 持锁阻塞无并发测试。
4. `ReadbackGetStrategy` 无负例约束。
5. 端侧 MNN 多协议 wire 缺跨 Provider 契约矩阵。
6. `estimateConversationChars` O(n²) 无性能基准。

---

### 4.4 matrix-agent-service · voice / data / conversation / download / debugtrace

#### 语音会话

- `VoiceSessionState`：12 态显式状态机，非法事件返回原态不抛。
- `VoiceSessionController`：generation + ASR sid + wake epoch + utteranceId **四重代次**防迟到回调。
- 唤醒仅 IDLE 接受；PTT flush 走 LISTENING→ENDPOINTING；`WakeConversationRouter` 10 分钟窗口续接。
- `AudioPrerollBuffer` 环形缓冲单次消费；`BargeInDetector` RMS 计数，AEC 不可用降级半双工。
- THINKING 独立 watchdog（70s）。

#### ASR / TTS 路由

- 端口：`AsrPort` / `TtsPort` / `WakeWordPort` / `VadPort` / `AudioFocusPort`。
- Vosk 与 Sherpa 双工厂可插拔；Sherpa 缺模 fail-closed 不隐式下大文件。
- TTS：腾讯云凭证可读 → 云主本地备 → Piper 已装 → 系统 TTS。
- 腾讯凭证 AES-GCM/Keystore 隔离（`SecureTencentTtsConfigStore`）。

#### 录音前台服务

- `VoiceCaptureForegroundService` 引用计数 + `FOREGROUND_SERVICE_TYPE_MICROPHONE`。
- 启动被拒不建 AudioRecord；权限撤销经 `CAPTURE_PERMISSION_REVOKED` 上送。
- 焦点 loss 回 IDLE 重布防 KWS。

#### 数据层

- Room version=**11**，17 实体；migration 1→11 共 10 段；schema 导出 `schemas/.../11.json`。
- SQLCipher passphrase 缺失抛异常；显式 WAL。
- userZone：`MemoryRecordDao` 强制 `(userId, zone)`；审计 4 表事务 clear。
- 记忆 epoch：`RoomMemoryWriter` 与 `__system__/__epoch__` 同事务比较，stale/fail-closed 拒绝；读失败返回 null 不兜底 0。

#### 下载

- `ModelDownloadManager`：串行下载、断点续传、原子 rename、per-model 公平锁、存储预检、文件数/字节预算。
- WorkManager 只做 admission，不持有字节传输（架构测试强制）。

#### 问题清单

| # | 位置 | 类别 | 说明 |
|---|------|------|------|
| V1 | `VoiceCaptureController.java:130,230-238` | FGS 泄漏 | acquire 成功后 Session 创建前抛异常不 release |
| V2 | `VoiceCaptureForegroundService.java:61-62,76-81` | 竞态 | onDestroy 全局 set(0)；计数与 startForeground 异步窗口 |
| V3 | `AuditEventRecorder.java:383-389` | fail-open | 队列溢出静默丢 POST/POLICY |
| V4 | `RoomAuditRepository.java:91-95` | fail-open | persist 失败仅 log |
| V5 | `RoomMemoryWriter.java:144-151` | 越权风险 | semantic 写 userId/zone 无强制 |
| V6 | `VoskModelDownloader.java:347-351` | fail-open | SHA-256 空跳过校验 |
| V7 | `ModelDownloadManager.java:201-203` | 非原子窗口 | 先删 final 再 rename，崩溃窗口模型短暂不可用 |
| V8 | `VoiceConversationBridge.java:103-166` | 正确性 | 硬编码 DRIVER/demo-vehicle |
| V9 | `AudioPrerollBuffer.java:42-47` | 性能 | 锁内逐 byte 拷贝，热路径竞争 |
| V10 | `VoiceSessionController.java:700-709` | 可用性 | 唤醒重建重试耗尽后永久关闭，无自动恢复 |

#### 测试缺口

- `VoiceCaptureForegroundService` 引用计数并发无单测。
- `AudioPrerollBuffer` 并发无压测。
- `WakeConversationRouter` / `VoiceConversationBridge` 缺独立单测。
- MNN 无逐文件 digest 供应链测试。

---

### 4.5 matrix-agent-launcher

#### 架构

教科书式分层 MVVM：

- `LauncherApplication` 持进程级 `LauncherHostGateway` + `LauncherExecutorRegistry`
- Gateway 是唯一认识 `MatrixAgent` 的位置；generation + 源 client 双闸状态单调化
- 五 Repository 经 `LauncherViewModelFactory` 显式注入
- `LauncherModuleBoundaryTest`：presentation 禁止 import `client.*`
- 只依赖 service-lib（符合设计 §5.1）

#### 亮点

- `OperationEpoch` latest-intent-wins
- `DraftCommandLane` 会话 FIFO + Host tombstone 双层防乱序
- 草稿-统一提交状态机（instance 轮换、失败恢复）对齐增强设计 §7.2

#### 问题清单

| # | 位置 | 说明 |
|---|------|------|
| P1 | `ConversationRepository.java:288-331` | `subscribe()` 缺 `closed` 竞态保护（对照 subscribeDebug :344-356）→ 幽灵 Binder 订阅 |
| P2 | `ConversationViewModel.java:224,254` | `send`/`appendToRunning` 死代码，与单一提交点相悖残留 |
| P3 | `ConversationViewModel.java:116` | `bySequence` 无上界，仅渲染截断 200，内存单调增长 |
| P4 | `ConversationFragment.java:438` | 每次事件全量 removeAllViews+重建，高频 partial 下 O(n) |
| P5 | `LauncherActivity.java:89-92` | 每 onCreate 新建 `DraftCommandLane`，Activity 重建可裂 FIFO |
| P6 | `AgentTaskFragment.java:37-38` | 硬编码色值；与 XML 布局体系分裂（Voice 588 行程序化拼 View） |
| P7 | `ModelFragment.java:113-115` | provider 切换重置表单 vs draft 恢复竞争 |
| P8 | `ConversationFragment.java:25` 等 | presentation 直接 import SDK DTO（boundary 测试只拦 `client.*`） |

---

### 4.6 matrix-agent-test

- 双 flavor：trusted（platform 签名 + ACCESS_AGENT）/ untrusted（debug 签名无权限）。
- `testBuildType = "release"`：instrumentation 必须 target trustedRelease。
- trusted 测 domain 协商、feature 位、voice 订阅/PTT/超时收敛；untrusted 断言连不上。
- `connectedLocalHostIntegrationAndroidTest`：先装当前 release Host 再跑双信任边界，解决版本漂移。
- `CrossApkTestModuleBoundaryTest`：verifier 只依赖 service-lib。

**弱点**：

- 硬编码 `OK (2 tests)` / `OK (1 test)`（`build.gradle.kts:126-128`），加用例即碎。
- adb 文本匹配判成败，格式变化易误判。
- Service 侧 `connectedOnDeviceCertificationAndroidTest` 同样用 `contains("OK (1 test)")`。

---

### 4.7 ondevice

#### Java / JNI 边界

- `OnDeviceLlm` 纯接口零 Host 依赖；`OnDeviceModuleBoundaryTest` 禁 import `api.*`/`client.*`。
- `MnnOnDeviceLlm`：cancelChecker 原子取消；LENGTH 判定补截断检测；countTokens 失败 retry 1 次。
- `MNNLlmSession`：activeCalls + 10s 硬超时 + 注入 scheduler；架构测试锁死 AutoCloseable。
- JNI：取消标志按 llmPtr 键控；流式回调 attach/detach；UTF-8 分片；prefill 前二次 cancel。

#### 构建

- MNN submodule 钉死 `18759c83`；CPU-only GPU 全 OFF FORCE；16KB 页对齐。
- sherpa-onnx 1.13.8 官方 AAR 下载任务钉 SHA-256，拆解 classes.jar + jniLibs。
- `MNN_ARM82=OFF`（模拟器 SIGILL 规避，注释说明真机可开）。

#### 问题

| # | 位置 | 说明 |
|---|------|------|
| O1 | `mnnllmnative.cpp:415` | `nativeGenerate` callback 参数未使用（死导出） |
| O2 | `MNNLlmSession.java:242-246` | `enter()` 并发直接抛异常，依赖调用方自觉串行 |
| O3 | `ondevice/build.gradle.kts:99` | `upToDateWhen` 只查文件存在，AAR 篡改不重验 SHA |
| O4 | `ondevice/build.gradle.kts:105-106` | `URL.openStream` 无超时（deprecated） |
| O5 | `ondevice/consumer-rules.pro` | JNI 保持规则仍为占位注释 |

---

## 五、构建与工程化评审

### 5.1 做得好的

| 项 | 说明 |
|----|------|
| 版本锁定 | Gradle 9.3.1 wrapper + `validateDistributionUrl`；AGP 9.0.1；版本目录禁动态版本 |
| 仓库策略 | `FAIL_ON_PROJECT_REPOS` 统一 google/mavenCentral |
| 版本单源 | `gradle.properties` 的 `MATRIX_VERSION_NAME/CODE` 三 APK 共读 |
| 根门禁 | `verifyArchitecture` 聚合五模块 check + `verifyPublishedPom` |
| 签名校验 | `buildTool.sh` apksigner 指纹：trusted 必须 platform、untrusted/debug 必须非 platform |
| 归档原子 | 先暂存后替换 `outputs/`，失败保留上一批 |
| 破坏性测试保险丝 | `-Pmatrix.allowDestructiveConnectedTest` 挡 androidTest 误装 |
| 密钥 ignore | `**/tools/key/` 模式正确且注释说明锚定问题 |
| 二进制钉死 | MNN submodule、sherpa SHA-256、framework jar 文档哈希 |
| SDK 纯净 | 全 configuration exclude kotlin + verifyPublishedPom |

### 5.2 问题清单

| # | 位置 | 说明 |
|---|------|------|
| B1 | — | **完全无 CI**（无 `.github/`、Jenkinsfile、`.gitlab-ci.yml`） |
| B2 | `build.gradle.kts:16-23` | `verifyArchitecture` 不含 assembleRelease + buildTool 签名验证 |
| B3 | `buildTool.sh:181-184` | 只校验 versionCode 正整数，不校验与 versionName 编码一致性 |
| B4 | `ondevice/build.gradle.kts:99` | AAR 缓存不复验 SHA |
| B5 | `ondevice/build.gradle.kts:105` | 下载无超时 |
| B6 | 三模块 build.gradle.kts | 签名配置重复，应抽 convention plugin |
| B7 | `tools/framework/README.md` | 刷新命令含个人服务器 scp（IP/端口）；jar 入库但构建不校验 SHA |
| B8 | — | 无 `gradle.lockfile` / `verification-metadata.xml` / SCA / Dependabot |
| B9 | `libs.versions.toml:5` | 注释含本机绝对路径 |
| B10 | service/test build.gradle | 真机门禁 `contains("OK (1 test)")` 易碎 |
| B11 | `gradle.properties:14` | `matrix.debugTraceUi=true` 默认（见安全 S3） |

### 5.3 供应链

| 源 | 策略 | 评价 |
|----|------|------|
| Maven 依赖 | 版本目录锁定 | 良好；无自动漏洞扫描 |
| MNN | git submodule 钉 commit | 良好；子模块体积大（含 tools/transformers 全量） |
| sherpa-onnx | GitHub release + SHA-256 | 良好；磁盘缓存不复验是缺口 |
| framework-lineage-grus.jar | 入库 + README SHA | 中等；构建不校验；含内部路径信息 |
| platform keys | 本地 ignore | 当前未入 git；明文口令仍是风险 |

---

## 六、文档与设计一致性

### 6.1 README

| 问题 | 位置 | 说明 |
|------|------|------|
| 断链 | `README.md:347-351` | 4 份设计文档不存在：《架构重构实施方案》《重整版》《代码架构评审》《当前架构与功能实现》 |
| 漏收 | 同上 | 已存在的《Matrix对话输入交互增强任务设计.md》未收录 |
| 截图过期 | `docs/images/launcher-drawer.png` | 版本 0.6.13、缺“01 对话”入口；与 0.6.14 五入口不符（README:39 已部分自认） |
| 徽章 | `README.md:11` | 0.6.14 与截图 0.6.13 不一致 |

### 6.2 设计文档 vs 代码

| 文档 | 状态头 | 实际 |
|------|--------|------|
| 《MatrixAgent对话交互与统一语音架构设计.md》 | 标“尚未开始实现” | ConversationCoordinator、对话页、PTT 绑定、Sherpa 全链路（阶段 A–D）已落地 |
| 《Matrix对话输入交互增强任务设计.md》 | 标“待实施” | Phase 1 的 I1–I4 已实现（全屏编辑、submitTextOrAppend、350ms 草稿 debounce、运行阶段条） |
| I2 取消按钮 | 要求非主按钮 | `fragment_conversation.xml:194-209` 次级文字钮，一致 |
| I5/I6/I7 | Phase 2/3 | 未实现，与规划相符 |

文档 1 第 7 行有“统一提交取代双入口”注记，但正文 §10 仍写双入口——读者易误判，应统一改写。

---

## 七、问题总表（按严重度汇总）

### Critical（3）

1. `DebugTraceServiceStub` 缺逐调用鉴权 — `DebugTraceServiceStub.java:37,68,78`
2. 平台签名口令明文落盘（未入 git）— `*/tools/key/signing.properties`
3. `matrix.debugTraceUi=true` 默认 — `gradle.properties:14`

### High（9）

4. SDK 对话/Debug 订阅断线不重注册 — `ConversationManager.java:304-318`
5. 重连放弃无终态 + 不回 CONNECTING — `MatrixAgent.java:461-477`
6. 双通道死亡通知叠加重连 — `MatrixAgent.java:83-100`
7. 空串 contract hash 可通过 — `ContractNegotiationPolicy.java:12-13`
8. 腾讯 TTS 凭证 pipe 泄漏 — `VoiceManager.java:250`
9. Vosk SHA-256 fail-open — `VoskModelDownloader.java:347-351`
10. conversationId 存在性侧信道 — `ConversationServiceStub.java:648-653`
11. FGS refCount 泄漏/竞态 — `VoiceCaptureController.java:130` 等
12. 审计队列溢出/落库失败 fail-open — `AuditEventRecorder.java:383` 等

### Medium（13）

13. TaskScheduler deque 拒绝残留 — `TaskScheduler.java:114-182`
14. SteerMailbox `seenSteerIds` 泄漏 — `SteerMailbox.java:245-248`
15. switchGateway 持锁阻塞 — `ModelRuntimeCoordinator.java:36-60`
16. testConnection Binder 阻塞 20s — `ModelServiceStub.java:112`
17. subscriptions registry 只增不删 — `ConversationServiceStub.java:575-577`
18. Launcher subscribe 竞态 — `ConversationRepository.java:288-331`
19. bySequence 无界 — `ConversationViewModel.java:116`
20. PTT 绑定被吞 — `VoiceServiceStub.java:157-190`
21. 字符预算 O(n²) — `AgentEngine.java:862-866`
22. ReadbackGet 弱 verify — `ReadbackGetStrategy.java:27-28`
23. semantic zone 无强制 — `RoomMemoryWriter.java:144-151`
24. MNN 市场无内容签名 — `ModelMarketClient`
25. 音区硬编码 DRIVER — `ConversationServiceStub.java:54-55`

### Low / 工程卫生（节选）

26. README 4 处设计文档断链  
27. 两份设计文档状态头过期  
28. drawer 截图 0.6.13 / 缺对话入口  
29. 零 CI  
30. verifyArchitecture 不含发布链  
31. 真机门禁字符串匹配脆  
32. sherpa AAR 缓存不复验  
33. 签名配置三处重复；framework jar 不校验 SHA  
34. toml 注释含绝对路径  
35. 67 个未提交修改  
36. AgentEngine 1110 / VoiceSessionController 992 / ConversationFragment 1067 行  
37. Service 约 618 处直接 Log（多数为结构元数据）  
38. SDK Manager 无主线程护栏  
39. Parcelable 零 round-trip 测试  
40. 测试硬编码 `OK (n tests)`

---

## 八、优先整改建议

### 立即（P0）

1. `DebugTraceServiceStub` 补 `callerResolver.caller()`，并加门禁单测。  
2. `matrix.debugTraceUi` 默认改 `false`；量产关闭无条件轨迹日志。  
3. 签名口令迁出明文文件（环境变量/密钥环）并轮换；保持 `**/tools/key/` ignore。  
4. Vosk/市场下载强制非空 SHA-256，禁止 fail-open。

### 短期（P1）

5. SDK：对话/Debug 订阅与 Task/Voice 对齐重注册；重连终态回调；空 hash fail-closed；修 `VoiceManager.provisionTencentTts` 清理路径。  
6. 修 FGS acquire/异常 release、TaskScheduler deque 移除、SteerMailbox `seenSteerIds` 清理。  
7. 统一 conversationId 归属检查错误码。  
8. 挂上任意 CI 跑 `./gradlew verifyArchitecture`（GitHub Actions 即可）。  
9. `verifyArchitecture` 增加 `assembleRelease` + 签名指纹校验（或调用 buildTool 验证步骤）。

### 中期（P2）

10. Parcelable round-trip 测试矩阵；`CallerContext` / 根 Binder 门禁单测。  
11. 真机门禁改为解析仪器结果结构，而非 `contains("OK (1 test)")`。  
12. 音区真实接入；替换硬编码 DRIVER。  
13. MNN 市场内容摘要策略；sherpa 落盘复验 SHA。  
14. 拆分超大文件（AgentEngine / VoiceSessionController / ConversationFragment）；统一 Launcher UI 体系。  
15. 修复 README 断链、设计文档状态头、截图版本。  
16. 提交或明确搁置当前 67 个脏文件；framework jar 构建期 SHA 校验。

### 长期（P3）

17. dependency-locking + verification-metadata + SCA。  
18. provider CertificatePinner（可选）。  
19. 签名与 framework 构建材料 convention plugin 化。  
20. 性能基准：字符统计、preroll 热路径、长会话渲染。

---

## 九、结论

MatrixAgent 是一套**有架构测试、有 fail-closed 纪律、有契约治理**的系统级 Agent 运行时，整体设计密度和测试契约质量明显高于常见同规模 Android 项目。风险不在于“会不会做架构”，而在于：

1. **DebugTrace 鉴权缺口 + 调试开关默认开启** 在 system-UID 下被放大；  
2. **若干资源/订阅泄漏** 会在长进程与车机常驻场景逐步暴露；  
3. **零 CI** 导致现有架构门禁与 golden test 无法持续自动执行；  
4. **文档与工作区卫生** 会让后续接手者误判完成度。

按上表 P0→P1 顺序收敛后，该项目具备作为定制 ROM 预埋 Agent 运行时继续演进的坚实基础。

---

## 附录 A · 关键文件索引

| 主题 | 路径 |
|------|------|
| 根 Binder | `matrix-agent-service/src/main/java/com/matrix/agent/host/MatrixAgentManagerService.java` |
| 调用方鉴权 | `.../host/rpc/CallerContext.java` |
| DebugTrace 缺口 | `.../host/rpc/DebugTraceServiceStub.java` |
| 请求校验 | `.../host/rpc/AgentRequestValidator.java`、`HostInputValidator.java` |
| 组合根 | `.../host/di/AppContainer.java` |
| 任务引擎 | `.../task/AgentEngine.java` |
| 对话协调 | `.../conversation/ConversationCoordinator.java` |
| 语音控制 | `.../voice/VoiceSessionController.java` |
| 加密库 | `.../data/db/MatrixDatabase.java` |
| API Key 存储 | `.../model/SecureModelConfigStore.java` |
| 网络边界 | `.../platform/MatrixHttpClient.java`、`.../model/JsonHttpTransport.java` |
| 下载 | `.../download/ModelDownloadManager.java`、`TrustedHttpsJson.java` |
| SDK 门面 | `matrix-agent-service-lib/src/main/java/com/matrix/agent/client/MatrixAgent.java` |
| 契约协商 | `.../client/ContractNegotiationPolicy.java` |
| 构建门禁 | 根 `build.gradle.kts`、`buildTool.sh` |
| 版本与开关 | `gradle.properties` |
| 网络安全 | `matrix-agent-service/src/main/res/xml/network_security_config.xml` |

## 附录 B · 评审方法说明

- 遍历五个模块 `src/main`、`src/test`、`src/androidTest`、AIDL、res、gradle、tools；排除 `MNN/` 与 `.cxx/` 生成物。  
- 关键安全/核心路径由主评审直接精读；其余分域并行深读后交叉汇总。  
- git 层面：`git ls-files`、`git check-ignore`、`git log -S`（口令字符串）验证密钥未入库。  
- 未执行：完整 `./gradlew verifyArchitecture` 与真机 connected 测试（需 platform 证书与目标设备）；结论中测试覆盖评价基于静态阅读测试源码。  

---

*本报告由项目源码静态评审生成，不替代真机认证与渗透测试。*
