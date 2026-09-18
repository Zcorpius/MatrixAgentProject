# matrix-agent-service 代码架构评审报告

- **评审对象**：`matrix-agent-service` 模块（含与 service-lib / launcher / test / ondevice 的模块边界），以代码为准，不以文档为准
- **代码快照**：v0.6.13（工作区；本报告在 v0.6.12 复审后完成了本轮纠偏，未提交工作区仍需按主题拆分提交）
- **评审方法**：全量子系统走读（host/task/model/voice/data/download/platform/ondevice 共约 240 个 main 源文件）+ 包间 import 依赖矩阵统计 + 架构门禁测试核对；所有结论均给出文件/行号证据
- **评审轮次**：三轮（2026-09-17 首轮、2026-09-18 复审、v0.6.13 整改复核）；本文保留原始发现，并明确记录已修复、未修复与校正项

---

## 一、总体结论

**微观代码纪律远高于平均水平，宏观包边界治理失守。**

### 本轮整改复核与结论校正（v0.6.13）

以下不是“把风险降级”，而是对复审证据做了逐项验证后的状态更新：

| 原结论 | 复核结果 | 当前处理 |
|---|---|---|
| `task ↔ voice` 包级循环 | **成立**，且是 P1 | 已修复：新增 task 自有的 `AgentInvocation`；语音在 `VoiceRuntime` 侧适配 `VoiceAgentRequest`，`AgentRuntimeRepository` 不再 import voice DTO。新增架构门禁。 |
| Repository / Graph 的 `MockCapabilityProvider` 签名泄漏 | **成立**，即使当前 emulator 默认实现有意为之，具体 Mock 类型仍不应进入运行时 API | 已修复：Repository 已移除未使用的 Provider 构造参数；`TaskRuntimeGraph.Dependencies` 使用 `CapabilityProvider`，Mock 只留在 Host 的当前装配选择。 |
| `download → host`、`voice/system → host` | **成立** | 已修复：下载域通过 `DownloadRuntime`/`DownloadRuntimeProvider`，系统语音通过 `VoiceRuntimeProvider` 取得窄依赖，不再 import `AppContainer`、`MatrixAgentApplication` 或 Host 类型。Android 组件从 Application 恢复进程依赖这一机制保留，但具体实现已隔离。 |
| `TrustedHttpsJson.DEFAULT_CLIENT`、`JsonHttpTransport` 默认构造 | **成立** | 已修复：生产构造必须注入 Host 管理的 OkHttp client；`ModelApiClient.forTesting()` 是明确命名的 JVM 测试便利入口，不参与生产装配。 |
| `MatrixDatabase.getInstance` 是并列服务定位器 | **需校正** | 当前只有 `PersistenceRuntimeGraph` 调用一次来创建加密数据库；它是组合根内的受控工厂调用，不是业务层静态取依赖。原报告把它与 Application/Holder locator 并列不准确。 |
| 下载进度“只有轮询” | **成立，但严重度为 P5** | 现有 DAO 轮询适合当前单进程 UI；没有证据表明它已造成正确性或资源问题。需要跨进程即时订阅/推送时再引入观察端口，不应与 P1 边界问题混为一谈。 |

本轮同时统一了装配命名：运行时装配为 `*RuntimeGraph`（含
`PersistenceRuntimeGraph`、`DownloadRuntimeGraph`），Binder 边界为 `*Graph`
（含 `DownloadGraph`）。`VoiceRuntimeHolder` 仍是为系统入口与页面共享唯一采音运行时而保留的进程级注册点；它已不再承担 Host 依赖取得，后续可在切换完整 DI 容器时继续收敛。

"乱"的感觉不是来自代码质量——fail-closed 门控、epoch 版本号治理、有界队列、双数据脱敏边界、PII 防线这些横切纪律在全工程高度一致，且注释里全部有据可查。乱的根源是**四个治理维度上各自并存 2~3 套体系**，认知负担相乘：

| 维度 | 并存的体系 | 后果 |
|---|---|---|
| 契约类型摆放 | model 风格（端口在 task）/ data 风格（接口实现在 data 同包）/ port 包（仅 2 接口） | 内核反向依赖外层，三种范式混用 |
| 依赖获取方式 | 构造注入（主流，91 处）/ 静态单例 / Application 提取 | 5 种取依赖方式并存且仍在增殖 |
| 装配类命名 | `XxxRuntimeGraph` / `XxxGraph` / `XxxServiceGraph` | 12 个装配类 2 层职责无命名标识 |
| demo 与生产 | main 混 demo（5 处）/ debug 源集空置（0 文件） | 测试替身类型进了生产 API 签名 |

第二轮复审确认：0.6.3 → 0.6.12 的 80 文件改动**全部是功能推进（网络层 OkHttp 化、下载 WorkManager admission、双公平锁、门禁测试扩张），没有动任何包边界**——上述问题零变化，个别加重（download→host 反向依赖 2→4）。

**同时浮现的关键积极事实**：项目的文本断言式架构门禁（`RuntimeBoundaryTest` 系）已扩张到 4 个模块、成为治理基础设施，质量很高——但**只用于守护新改动语义，从未用于清算历史包边界债**。治理工具已全部就位，当前是还债成本的历史最低点。

---

## 二、证据：包间依赖矩阵

下表保留 v0.6.12 复审的原始统计，便于追溯整改来源；v0.6.13 已验证的变化是
`task → voice: 2 → 0`、`download → host: 4 → 0`、`voice/system → host: 1 → 0`。
其余数字尚未因本轮有限整改而重算，不应把原表误读为当前状态。

```
task             →  model:6   voice:2   data:23     ← v0.6.12 基线；voice 已于 v0.6.13 清零
task/identity    →  model:2
task/tool        →  task:7  data:5
task/prompt      →  task:4  data:7
model            →  task:72  data:10  platform:2     ← 契约类型枢纽效应（P2）
data/db          →  task:9   platform:1
data/memory      →  task:11  data:24
data/audit       →  task:12  data:9
host             →  task:59  model:21  data:57  voice:7  download:7  platform:6   ← 组合根，正常
voice            →  task:13  data:2                    ← 正常（task 的客户端）
voice/system     →  host:1   voice:9                   ← v0.6.12 基线；host 已于 v0.6.13 清零
download         →  host:4   data:4                    ← v0.6.12 基线；host 已于 v0.6.13 清零
platform         →  task:1                              ← 实现 task 端口，方向合规
```

异常边明细（均有行号核对）：

| 边 | 证据 | 性质 |
|---|---|---|
| task → data ×23 | `MemoryStore`(4)、`AuditEventRecorder`(4)、`SessionManager`(3)、`AuditRepository`(3)、`SessionLockManager`(2)、`NoopAuditRepository` 等被 task 根包及子包直接 import | 内核依赖外层接口与实现 |
| task → voice ×2 | `AgentRuntimeRepository.java:27-28` import `voice.FinalTranscript`/`voice.VoiceAgentRequest` | **已于 v0.6.13 修复**：由 voice 侧适配为 task `AgentInvocation`，当前为 0 |
| task → model ×6 | `AgentRuntimeRepository.java:5-6` 直连 `GatewayLifecycleManager`/`ModelRuntimeCoordinator`（实现类，非接口）；`LlmSummaryProvider.java:6-8` 直连 `SecureModelConfigStore` | 门面直连实现类 |
| download → host ×4 | `DownloadService.java:20-21`、`ModelDownloadStartWorker.java:9-10` 直接 `MatrixAgentApplication` → `AppContainer` | **已于 v0.6.13 修复**：改依赖 download 域自有的 `DownloadRuntimeProvider` |
| voice/system → host ×1 | `SystemVoiceRuntimeOwner.java:6` | **已于 v0.6.13 修复**：改依赖 voice 域自有的 `VoiceRuntimeProvider` |

---

## 三、问题清单（按严重度）

### P1 依赖方向失守（最伤架构）

1. **task 内核直连 data 的接口与实现（23 处）**。`RuntimeBoundaryTest` 只守住了 `AgentEngine` 一个类 × 2 条规则（不得 import `data.audit.AuditRepository`/`data.memory.MemoryWriter`），而 `AgentRuntimeRepository`、`TaskRequestFactory`、`task/prompt`、`task/tool` 等照直连不误——**门禁守了大门，侧门全开**。
2. **task ↔ voice 包级循环**。**已于 v0.6.13 修复**：删除 `execute(VoiceAgentRequest)`，voice 侧将其映射为 task 的 `AgentInvocation`；`AgentRuntimeRepository` 不再 import voice 类型，架构门禁固定为零。
3. **门面直连 model 实现类**（`GatewayLifecycleManager`、`ModelRuntimeCoordinator`、`SecureModelConfigStore`），无接口隔离。**仍未修复**；它需要先抽取模型生命周期/配置的 task 端口，不能用字符串门禁掩盖。
4. **服务定位器绕过组合根**。**部分修复**：`DownloadService`、`ModelDownloadStartWorker`、`SystemVoiceRuntimeOwner` 已删除 Host import，改依赖各自领域的窄 `*RuntimeProvider`。`VoiceRuntimeHolder` 仍是受控的进程级“唯一采音 Runtime”注册点，后续应随完整 DI/生命周期组件化继续收敛。`MatrixDatabase.getInstance` 则校正为组合根 `PersistenceRuntimeGraph` 的单一受控创建点，并非业务层 locator。`TrustedHttpsJson.DEFAULT_CLIENT` 与 `JsonHttpTransport` 默认构造已经删除；测试仅保留显式命名的便利工厂。

**根因**：端口/契约类型的摆放没有统一标准——
- model 风格（正确）：契约 `ModelGateway`/`ModelTurn` 在消费方 task，实现在 model；
- data 风格（反向）：`MemoryStore`/`AuditRepository`/`SessionManager` 等接口与实现**同在 data**，task 反向依赖；
- `task/port` 包形同虚设：仅 `TaskAuditSink`/`TaskMemoryWriter` 2 个接口，对 task 实际消费的约 10 个 data 接口覆盖率约两成。

### P2 task 根包角色过载（"感觉乱"的最大来源）

`task/` 根包 41 个文件混装至少 7 种角色：

| 角色 | 代表文件 |
|---|---|
| 引擎 | AgentEngine、AgentEngineConfiguration |
| 调度/仲裁 | TaskScheduler、TaskDispatchCoordinator、TaskDispatchRecovery、InFlightTaskRegistry、TaskRequestFactory、UserDataResetCoordinator、AgentRuntimeRepository |
| 模型契约（被 model 包 72 处引用） | ModelGateway、ModelTurn、ModelTurnRequest、CancellableModelCall、ModelCallExecutor、FinishReason |
| 会话/轨迹数据类型 | AgentMessage、AgentOutcome、AgentIteration、Trajectory、ToolObservation、StopReason、TaskState、Steer、SteerMailbox、EpisodicSummary、ContextUpdater 等 |
| 审计视图 | AuditRedactor、AuditDigest、Sha1AuditDigest、UnavailableAuditDigest、AuditEventTypes |
| 脱敏/摘要 | ModelSanitizer、SummaryProvider、LlmSummaryProvider、ConversationCompressor |
| demo 与杂项 | DemoModelGateway、SafeLog |

它是全工程类型枢纽（model:72、data:32、host:59、voice:13、platform:1 全部指向它）。连带问题：`AuditDigest` 的三个实现散落两个包（`Sha1AuditDigest`/`UnavailableAuditDigest` 在 task，`KeystoreHmacAuditDigest` 在 platform，见 `platform/KeystoreHmacAuditDigest.java:8`）。

### P3 装配层 12 个类、3 种命名模式、2 层职责无标识

```
AppContainer (279 行)
├─ 域组合根层:  TaskRuntimeGraph  ModelRuntimeGraph  MemoryRuntimeGraph
│               AuditRuntimeGraph DownloadGraph      PersistenceGraph
└─ Binder边界层: TaskGraph         ModelGraph         DownloadServiceGraph
                 VoiceGraph        (MatrixServiceGraph 持有)
```

- 两层职责只靠命名后缀区分，但后缀不统一：Task/Model 是 `XxxRuntimeGraph` ↔ `XxxGraph`，Download 却是 `DownloadGraph` ↔ `DownloadServiceGraph`——哪个是域装配、哪个是 Binder 边界要靠背。**已于 v0.6.13 修复**：运行时装配统一为 `*RuntimeGraph`，Binder 边界统一为 `*Graph`。
- `VoiceGraph` 只在边界层、`MemoryRuntimeGraph`/`AuditRuntimeGraph`/`PersistenceGraph` 只在域层，六个域两层各有缺口，无对称表。
- `AgentRuntimeRepository` 有 **4 个 telescoping 构造器**（最长 12 参），其中第 2 参是 `MockCapabilityProvider`（见 P4）；`TaskRuntimeGraph.Dependencies` 值对象已存在，与散参构造器并存两套传参风格。

### P4 demo/生产未分层（方向反了）

- `DemoModelGateway`、`MockCapabilityProvider`、`MockVehicleStateSource`、`ActorUsers`（`demo-driver`/`demo-passenger` 字面量）、`CapabilityRegistry.createDemoRegistry` 全部在 main 源集，`AppContainer` 直接装配。
- 最刺眼：**测试替身类型进生产 API 签名**——`AgentRuntimeRepository` 四个构造器第 2 参数均为 `MockCapabilityProvider`（明明有 `CapabilityProvider` 接口）。**已于 v0.6.13 修复**：Repository 已移除该无使用价值的参数，`TaskRuntimeGraph.Dependencies` 改为 `CapabilityProvider`。demo 实现仍在 main 的问题保留，见下文。
- `src/debug/` 源集空置（0 文件），还残留 `voice/ui` 空目录树。分层方向反了：现在是"生产里混 demo"，应为"debug 加 demo、main 只留可上线骨架"（`VehicleStateSource` 的"后续 OEM 版本在此替换"注释正是此意图，未走完）。

### P5 次要问题

1. **两套下载器概念重复**：`download/ModelDownloadManager`（DAO 持久化 + Range 断点续传 + tmp 原子 rename；`sha256` 字段存在但校验未实现，完整性仅靠大小比对 + `llm_config.json` 引用检查）与 `voice/VoskModelDownloader`（zip + SHA-256 校验 + 版本指针 promote/rollback；不走 DAO）。能力互有缺口、抽象不共享。
2. **platform 包语义漂移**：原为 KeyStore 安全基座（3 文件），v0.6.12 `MatrixHttpClient` 进驻后变"安全 + 网络横切设施"，包名与内容脱节。
3. **过时注释误导**：`VoskVoiceAssemblyFactory.java:30`"仅 debug 源集"、`MatrixVoiceInteractionService.java:9`"debug manifest 声明"——代码已在 main，注释停在迁移前。`MatrixVoiceInteractionService` 的描述已于 v0.6.13 校正；Vosk 工厂现有源码已标为 release 主源集，原行号判断已过期。
4. `task/identity` 内的 `LlmIntentClassifier`/`MemoryIntentDetector` 是应用服务而非身份，包名与内容有偏差。
5. **下载进度无订阅通道**：download 域是四域中唯一纯拉取模型（launcher 1s 轮询 `listDownloads()`，见 `DownloadViewModel.java:34-38`；Host 侧 `DownloadServiceStub.watchCompletion` 同样 1s 轮询 DAO）。当前场景合理，但"后台下载完成通知"类需求出现时需补订阅接口。
6. **git 提交粒度失衡**：0.6.3 → 0.6.12 共 9 个版本迭代全部堆在未提交工作区（80 修改 + 10 新增），回滚与审阅无从下手——当前最紧迫的工程卫生问题。

---

## 四、正面资产（整理时必须保留，勿误伤）

1. **分层意图正确**：host = 组合根 + Binder 边界；task = 内核；model/voice/data/download = 适配域；`ondevice` 的 `OnDeviceLlm` 纯接口是全工程最干净的边界（model 零 import mnn 实现类）。
2. **voice 是端口化最彻底的域**（`voice/port` 6 接口 + 引擎无关核心 + vosk 适配器 + JVM 可测），应作为其他域整理的范本。
3. **文本断言式架构门禁已是基础设施**：`RuntimeBoundaryTest`（Engine 依赖方向、装配委托、网络所有权）+ 4 个模块的 `*ModuleBoundaryTest`（host 不 import SDK client、SDK 不碰 Host 实现、ondevice 隔离 MNN 泄漏、跨 APK 测试模块隔离）+ v0.6.12 新增的 OkHttp 边界与 WorkManager admission 九条断言。功能改动必配门禁已是项目惯例。
4. **横切纪律一致**：fail-closed（KeyStore 失败拒开明文库、车辆状态 unavailable 兜底、意图模糊按写处理）、epoch 版本号治理贯穿 memory/audit/steer、全部线程池有界 + AbortPolicy 过载显式失败、双数据脱敏边界（`ModelSanitizer` 喂模型 / `AuditRedactor` 进审计）。
5. **v0.6.12 新增代码质量高**：`MatrixHttpClient` 三档 client 共享连接池、`metadata` 档禁重定向防 host-pinning 失效、`download` 档 `callTimeout(0)` 配合续传；WorkManager admission 的三层所有权（durable admission / 可见传输 FGS / 字节所有权 manager）划分干净并被断言钉死；`ModelGatewayRepository` 双公平锁锁序有注释有专测。

---

## 五、整改路线图（分批、每批可验证）

> 原则：先恢复可回滚性 → 再冻结债务（门禁指向存量）→ 后分批还债（小步、每批绿灯）。契约统一是中期工程，拆包放在最后，避免两次大挪移。

| 批次 | 内容 | 验证方式 | 预估 |
|---|---|---|---|
| 0 | 按主题分批提交当前 80+10 文件（网络迁移 / 下载 admission / 锁与门禁 / Launcher UI 各一批） | `git log` 分批可读 | 半小时级 |
| 1 | 门禁指向存量：`RuntimeBoundaryTest` 增加包级 import 禁令（task 根包及子包禁 import `data.`/`voice.`/`model.` 实现类；download 禁 import `host.`），以当前数字为基线先断"不再增长"，随还债逐条收紧 | **进行中**：已对 task→voice、download→host、voice/system→host 和 Mock 签名加入门禁；task→data/model 的存量仍待端口化后再禁止 | 大 |
| 2 | 断 task↔voice 循环：`VoiceAgentRequest`→`AgentRequest` 翻译全部收进 voice 侧，`AgentRuntimeRepository` 删除 voice import | **完成（v0.6.13）**：改为 `VoiceAgentRequest`→`AgentInvocation`，全量 JVM 测试已通过 | 小 |
| 3 | 消除服务定位器：`ModelDownloadStartWorker` 换自定义 `WorkerFactory` 或集中定位器；`TrustedHttpsJson.DEFAULT_CLIENT`/`JsonHttpTransport` 默认构造改注入（保留便捷构造器于测试） | **部分完成（v0.6.13）**：三个 Android 入口改窄 Provider；网络默认实例删除。`VoiceRuntimeHolder` 生命周期收敛仍待后续 DI 化 | 中 |
| 4 | demo 下沉：demo/mock 类迁 debug 源集或 `task/demo` 子包；`AgentRuntimeRepository` 构造器第 2 参改 `CapabilityProvider`；构造收敛为 `Dependencies`/Builder 单一风格 | **部分完成（v0.6.13）**：Mock 类型泄漏已消除；demo 源集迁移和构造器 Builder 收敛仍待处理 | 中 |
| 5 | 契约摆放统一（中期）：task 消费的 data 接口迁入 `task/port`（或承认 data 属内核重划边界，二选一）；`AuditDigest` 实现归位 | 门禁逐步收紧 + 全量测试 | 大 |
| 6 | task 根包拆分（最后做）：按"契约类型 / 引擎 / 调度 / 审计视图"分 `task/contract`、`task/engine`、`task/scheduling` 等；同步更新 `RuntimeBoundaryTest` 路径断言 | 全量测试 + 门禁路径更新 | 大 |

---

## 六、附录：评审数据快照

- 模块规模：main 源集约 240 文件（task 含子包 ~112、host 28、model 22、voice 50、data 46、download 7+3 新增、platform 4）；JVM 测试 163+、androidTest 21
- 装配层：AppContainer 279 行 + 11 个 Graph 共 914 行
- 构造器：`AgentRuntimeRepository` 4 重载、最长 12 参
- 依赖异常边合计：task→data 23、task→model 6、task→voice 2、download→host 4、voice/system→host 1
- 门禁测试：`RuntimeBoundaryTest`（service）+ HostModuleBoundaryTest + OnDeviceModuleBoundaryTest + SdkModuleBoundaryTest + CrossApkTestModuleBoundaryTest + JsonHttpTransportBoundaryTest + PublicAbiGoldenTest 等 7+ 个
- 版本：0.6.3（commit 9e93d06）→ 0.6.12（未提交工作区）
