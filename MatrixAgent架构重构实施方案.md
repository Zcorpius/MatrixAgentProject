# MatrixAgent 架构重构实施方案

> **状态**：已完成本轮内部结构重构。公开 ABI、Service 入口 FQCN 与 Room schema 保持不变；demo/production flavor 与模块坐标改名属于独立的产品发布迁移，不在本次结构性改造范围内。<br>
> **适用基线**：当前工作区 `v0.6.13` / commit `62ef8e1`。<br>
> **目标**：在不改变公开 Binder ABI、权限边界、持久化语义和任务执行行为的前提下，恢复单向依赖、降低包结构认知成本，并把后续接入真实车控能力的替换点收敛出来。

---

## 1. 决策摘要

当前项目的模块边界是正确的：Host APK 是唯一执行权威，`matrix-agent-service-lib` 是唯一公开 SDK，Launcher/Test 不依赖 Host 实现，`ondevice` 也没有泄露到 SDK。

主要问题发生在 Host APK **内部的物理组织**：任务、模型、数据对象和装配职责在包之间交错；`task` 根包承担了过多角色；一些持久化实现直接接收任务领域对象；Demo 实现仍是默认装配的一部分。这些问题不妨碍当前功能运行，但会让真实 Vehicle Capability、更多模型协议或多进程演进的成本持续升高。

本方案采用“先断依赖环，后搬文件；先保持 ABI，后改善命名”的策略。

**本轮必须完成：**

1. 将 task/model 的共享类型抽到纯 Java `contract` 包，消除双向依赖。
2. 让 `data` 只接收自己的扁平持久化命令，消除 `data -> task` 的上行依赖。
3. 拆分 `task`、`task/identity`、`host` 的混合职责，并将 Host 组织为组合根与 RPC 边界。
4. 增加可执行的依赖方向门禁；每一批迁移后运行完整非设备质量门禁。

**本轮明确不做：**

- 不更改 AIDL 方法、Parcelable 字段、错误码、`ContractVersion` 的生成规则。
- 不改 SQLCipher schema、迁移路径、epoch gate、审计脱敏和任务调度/锁拓扑。
- 不合并通用模型下载与 Vosk 下载；两者行为差异较大，应单独立项。
- 不在结构迁移中夹带真实 VHAL、生产模型策略或 UI 行为变更。

## 实施结果（2026-09-18）

- `contract/` 承载模型回合、工具 schema、模型配置与生命周期契约；`model` 和 `task` 不再互相 import 实现类。
- `data.audit` 与 `data.memory` 仅接收 `AuditOutcomeEntry`、`EpisodicWrite` 等扁平命令；领域投影集中在 `task/persistence`。
- 身份、意图、车辆状态和会话分别归入 `identity/`、`intent/`、`vehicle/`、`session/`；新增 `ClassifierFactory` 统一云端/端侧意图分类选择。
- `host/di` 只负责对象图，`host/rpc` 承载领域 Binder Stub；持久任务迁至 `task/durable`。原 `MatrixAgentManagerService` 保持原包名，保障 manifest 与显式绑定兼容。
- 任务内部进一步按 `compress`、`redact`、`scheduler`、`steer`、`durable`、`persistence` 划分；线程与审计摘要基础设施归入 `platform`。
- 架构门禁覆盖 data 去领域化、model/task 解耦、工具投影唯一性、Host DI/RPC 与 durable 物理边界，以及叶子包禁止回流依赖。

---

## 2. 当前状态与重构依据

### 2.1 已具备且必须保留的资产

- Host 通过 Root Binder 发现四个领域 Binder，并在每个事务重新捕获、验证调用方身份。
- SDK 负责连接协商、Binder death 重连和订阅恢复；公开 ABI 集中在 `matrix-agent-service-lib`。
- SQLCipher + Android KeyStore 不可用时显式 fail-closed；不能为“方便”退回明文或内存伪成功。
- `task -> voice`、`download -> host`、`voice/system -> host` 的反向依赖已经被窄 Runtime Provider 消除，不能回退。
- 现有 `RuntimeBoundaryTest` 和各模块 `*ModuleBoundaryTest` 已是治理基础，应扩展而不是删除。

### 2.2 当前需要处理的结构债务

| 问题 | 影响 | 本方案的处理方式 |
|---|---|---|
| `task` 与 `model` 共享模型回合、工具定义、网关等类型，同时互相 import | 模型域不能独立替换；任务编排也被模型实现细节污染 | 提取 `contract`，双方只依赖契约 |
| `data.audit`、`data.memory` 直接认识任务领域对象 | 持久化层被任务表达方式绑定，形成依赖环 | 以扁平 command DTO 代替领域对象入参 |
| `task` 根包同时存放引擎、调度、审计、持久化、压缩和运行时协调 | 阅读一个请求全链路需要跨越大量无语义层级 | 按职责拆子包，根包只留真正核心类型 |
| `task/identity` 混入身份、车辆状态、意图分类 | 变化原因不同的代码被迫共同演进 | 拆成 `identity`、`intent`、`vehicle` |
| `host` 既是组合根、Binder Stub，又有 durable task 和基础设施 | 容器膨胀，Android 入口的依赖归属不清 | 拆为 `host/di` 与 `host/rpc`，durable task 回归 task |
| Demo/Mock 位于 production 主路径 | 真实能力接入时容易误把 demo 副作用当生产行为 | 首先集中到 `demo`，随后用 product flavor 取代默认装配 |

---

## 3. 目标架构

### 3.1 模块边界：本轮不改交付坐标

```text
matrix-agent-service        Host APK（保持现有 artifact/module 名称）
  ├── matrix-agent-service-lib  公开 AIDL、DTO、SDK
  └── ondevice                  MNN JNI 封装

matrix-agent-launcher       仅依赖 service-lib
matrix-agent-test           仅依赖 service-lib
```

`matrix-agent-host` / `matrix-agent-sdk` 是更清楚的名字，但本轮不直接重命名模块。模块改名会波及构建脚本、Maven 坐标、IDE 配置、外部客户端依赖和设备部署清单；它应在内部包边界稳定后作为单独、可回滚的发布迁移进行。

### 3.2 Host 目标包图

```text
com.matrix.agent
├── platform/                 线程、HTTP、KeyStore、存储预检等横切基础设施
├── contract/                 跨任务/模型/协议共享的纯领域契约（叶子）
│   └── schema/               Tool/JSON schema 与 wire-neutral 类型
├── identity/                 Actor、请求内部身份、取消、输入来源
├── intent/                   分类器与唯一的 ClassifierFactory
├── vehicle/                  VehicleState、状态源和 predicate
├── session/                  进程内会话与锁
├── data/                     Room/SQLCipher、审计、记忆；不依赖 task/model
├── task/
│   ├── engine/               AgentEngine 与配置
│   ├── scheduler/            调度、恢复、重置、请求工厂
│   ├── persistence/          task -> data 的唯一投影/适配层
│   ├── durable/              持久任务管理、公开状态映射、持久化 gate
│   ├── capability/           能力定义、风险与 registry
│   ├── tool/ policy/ prompt/ token/ steer/ redact/ compress/
│   └── runtime/              AgentRuntimeRepository、ModelRuntimeHotSwap
├── model/                    协议适配、网关实现、配置存储；只依赖 contract/data/platform
├── demo/                     DemoModelGateway、MockCapabilityProvider 等替身
├── voice/                    保持现有 port/platform/vosk/system 分层
├── download/                 模型下载域
└── host/
    ├── di/                   AppContainer 与 RuntimeGraph，唯一组合根
    └── rpc/                  Root Binder、domain Stub、CallerContext、validator
```

### 3.3 依赖规则

```text
platform, contract
       ↑
identity, intent, vehicle, session
       ↑
data  ← task → model
       ↑          ↑
   voice / download / demo
       ↑
    host (仅组合与 Binder 边界)
```

规则说明：

1. `platform` 与 `contract` 不 import 任何 `com.matrix.agent.*` 实现包；它们是叶子。
2. `data` 不 import `task`、`model`、`host`、`voice` 或 `download`。数据库/仓储只认识它自己声明的行对象、命令对象和接口。
3. `model` 不 import `task`。模型协议通过 `contract` 的 `ModelGateway`、`ModelTurn`、`ToolDefinition` 等契约工作。
4. `task` 可在受控位置依赖 data 的存储契约；任务领域对象到持久化命令的映射只能位于 `task/persistence`。
5. `host` 是唯一允许同时依赖多个领域的组合根；Android 入口组件只能依赖本域的窄 Provider，不得直接取得 `AppContainer`。
6. SDK 永远不得 import Host 内部任何包；这条现有规则继续保留。

---

## 4. 关键设计

### 4.1 建立 `contract`：只容纳真正的跨域纯契约

建议迁移至 `contract` 的候选类型：

- `ModelGateway`、`RetirableModelGateway`、`CancellableModelCall`
- `ModelTurn`、`ModelTurnRequest`、`FinishReason`、`AgentMessage`
- `ToolCall`、`ToolDefinition`、`ToolParameterDefinition` 及 schema 类型
- `ModelConfig`、`ApiProtocol`、`LlmClient`、`ModelApiException`
- `GatewayLifecycleManager` 只在它确实不泄露 Host/数据实现时进入 contract；否则改为 task 侧端口。

不应把所有“看起来通用”的类都丢进 `contract`。其准入条件是：至少被两个领域消费、没有 Android/Room/网络实现依赖、没有运行时装配职责。否则 `contract` 会从“循环断点”退化为新的上帝包。

### 4.2 data 去领域化：扁平命令加 task 侧投影

将持久化 API 从“接收完整任务对象”改为“接收已准备好的写入命令”。例如：

```java
// data/audit：不再 import task.*
public interface AuditRepository {
    void persist(AuditOutcomeEntry entry);
}

// task/persistence：唯一的领域投影点
public final class AuditOutcomeEntryFactory {
    public static AuditOutcomeEntry from(AgentOutcome outcome, AgentRequest request) { ... }
}
```

同样地，记忆写入使用 `EpisodicWrite`，由 `EpisodicMemorySink` 完成：

1. 只允许 `SUCCEEDED` / `FAILED` 终态进入投影。
2. 构建现有安全摘要，保持大小限制、PII 规则和 epoch 原样不变。
3. 调用 data 层的单写者事务；data 层仍在事务内校验 epoch，并且仅在真写入后失效缓存。

两个写入路径（Engine 出口的 `TaskAuditSink` 与调度/恢复兜底路径）必须共用同一个 `AuditOutcomeEntryFactory`。否则“断环”可能引入字段不一致或破坏 requestId 幂等写入。

### 4.3 任务与模型热切换收敛

当前模型切换涉及网关生命周期、端侧 runtime、意图分类器和任务引擎。应新建 `task/runtime/ModelRuntimeHotSwap`，把一次激活定义为一个同步边界：

```text
构造新 gateway / 探测成功
        ↓
在既有模型 mutation lock 内
        ↓
替换 Engine 使用的 gateway，退役旧端侧 gateway
        ↓
按新 ModelConfig 重建 IntentClassifier
        ↓
持久化 active model，并通知回调
```

`ModelServiceStub` 不应再分别调用多个 setter。`AgentRuntimeRepository` 也不应同时拥有“任务门面”和“模型热切换协调”两套可变状态。所有既有锁顺序、取消行为和失败时“不持久化无效 active model”的语义必须保留。

### 4.4 意图与车辆语义拆分

- `identity/`：`Actor`、内部 `AgentRequest`、`InputSource`、`CancellationToken`、明确意图约束。
- `vehicle/`：`VehicleState`、`VehicleStateSource`、`VehicleStatePredicate`、生产默认状态源。
- `intent/`：关键词、LLM、fallback、记忆意图检测和 `ClassifierFactory`。

`ClassifierFactory` 应成为“端侧模型使用关键词分类、云端模型使用 LLM + fallback、构造失败退关键词”的唯一实现点。这样模型启动路径与热切换路径不会维护两份相似逻辑。

### 4.5 Host 的职责收束

`host/di` 只负责创建对象图、绑定 executor 生命周期和注入替代实现；`host/rpc` 只负责 Binder 参数校验、调用方授权、短事务和将长任务移交领域管理器。

以下类应优先迁入对应位置：

| 当前位置 | 目标位置 |
|---|---|
| `AppContainer`、`*RuntimeGraph`、`MatrixExecutorRegistry` | `host/di` |
| `MatrixAgentManagerService`、`*ServiceStub`、`CallerContext`、`CallbackRegistry`、输入校验器 | `host/rpc` |
| `PersistentTaskManager`、`PersistentTaskStore`、`PersistenceGate`、`PublicTaskStateMapper` | `task/durable` |
| `SessionManager`、`SessionContext`、`SessionLockManager` | `session` |

第一阶段不修改系统服务的 manifest 名称和 SDK 显式绑定常量。若物理移动 `MatrixAgentManagerService`，必须保留旧类名的 delegating Service 或在同一发布批次同步更新 SDK、Host、Launcher、Test 和系统镜像预置项。

### 4.6 Demo 的正确落点

第一步：将 `DemoModelGateway`、`MockCapabilityProvider`、`MockVehicleStateSource` 和 demo capability 注册表集中在 `demo/`，使生产装配点只剩两个明确 import。

第二步（独立行为变更批次）：建立 `demo` / `production` flavor 或显式 OEM provider binding。

- production 默认没有真实 Provider 时，必须以 `SERVICE_NOT_READY` 或能力不可用失败，不能静默执行 Mock 车控。
- demo flavor 可以装配 Mock，但必须在 UI、日志和审计事件中显式标记为 simulation。
- 不应仅用包名迁移来宣称 Demo 已从生产移除。

---

## 5. 分批实施计划

每批都必须独立可提交、可回滚，并在合并前运行 `./gradlew verifyArchitecture`。

### 批次 0：建立基线和冻结规则

1. 记录 `ContractVersion.CONTRACT_HASH`、公开 AIDL 清单、Room schema 版本、APK versionCode。
2. 保存当前 `verifyArchitecture` 成功结果；在目标 ROM 上记录 trusted/untrusted Binder、语音和端侧模型认证结果。
3. 增加包级依赖审计测试，先只禁止已被清除的回归边：`task -> voice`、`download -> host`、`voice/system -> host`、SDK -> Host。
4. 将当前所有结构性迁移按主题提交；禁止“重构 + 功能 + 版本升级”混在一个 commit。

**验收**：无行为改动，构建、单测、lint、SDK POM 验证全绿。

### 批次 1：提取纯 contract（无公开 ABI 变化）

1. 用 `git mv` 迁移共享类型，先从无 Android、无持久化依赖的值对象/接口开始。
2. 更新 task/model/voice 的 import；不改变任何方法签名的参数、返回值或序列化格式。
3. 新增门禁：`model` 禁止 import `task`；`contract` 禁止 import 本工程实现包。
4. 对工具 schema、所有模型协议（OpenAI、Anthropic、Gemini、Ollama、MNN）运行现有契约测试。

**验收**：task 与 model 之间没有直接 import；各协议工具调用输出保持字节级或结构等价。

### 批次 2：data 去 task 化

1. 在 `data/audit` 新增 `AuditOutcomeEntry`，在 `data/memory` 新增 `EpisodicWrite`。
2. 在 `task/persistence` 新增两个投影适配器；先并行覆盖测试，再删除旧签名。
3. 数据库 Entity/DAO/schema 不改；只改变 Repository 和 Writer 的内存入参。
4. 为以下场景补回归测试：终态过滤、重复 requestId、epoch 清理竞争、摘要脱敏、写失败不污染缓存、恢复任务的兜底审计。

**验收**：`data` 对 `task` 的 import 为零；Room schema JSON 完全不变。

### 批次 3：拆 task、identity 与 session

1. 先移动无依赖/低扇出的值对象，再移动 scheduler、steer、redact、compress、persistence、durable。
2. 将当前 `task/identity` 拆到 `identity`、`intent`、`vehicle`；将 `data/session` 移到 `session`。
3. 每移动一个职责簇，同时迁移同包测试，避免测试仍以旧包名“偶然通过”。
4. `task` 根包只保留真正的跨 task 子域对象；不得把移动不知归属的文件继续堆回根目录。

**验收**：每个子包有一句明确职责说明；`AgentEngine` 只依赖 task 端口而不是 Room/审计实现。

### 批次 4：Host 装配与热切换收敛

1. 划分 `host/di` 和 `host/rpc`，但保持 Android manifest 入口和服务 FQCN 不变。
2. 将 durable task 类迁到 `task/durable`；Host 仅注入和调用。
3. 将 `AgentRuntimeRepository` 的可变 setter 收敛为构造器依赖；引入 `ModelRuntimeHotSwap`。
4. 新建 `ClassifierFactory` 和 `StoragePreflight`，删除重复实现前先以参数化测试固定现有结果。

**验收**：模型切换、端侧 runtime 退役、分类器切换、连接失败和取消场景均有回归测试；没有新增静态 Service Locator。

### 批次 5：Demo/生产分层

1. 先集中至 `demo/`，确保只由 Host 组合根引用。
2. 新增 product flavor 或 OEM binding 接口；production 不装配 Mock。
3. 为 demo 和 production 分别写安装/功能测试，避免编译成功却将错误实现预装到系统镜像。

**验收**：release/production 的 capability 执行不可能落到 Mock；demo 执行可被显式识别。

### 批次 6（可选）：模块和 API 命名迁移

完成前五批且至少一次完整设备认证后，再评估：

- `matrix-agent-service` → `matrix-agent-host`
- `matrix-agent-service-lib` → `matrix-agent-sdk`
- 公开 DTO `AgentRequest` → `TaskSubmission`，以区别 SDK 输入和内部身份化请求。

此批是**发布迁移**而非纯重构：需要 AIDL contract hash 更新、Maven 兼容策略、旧服务类显式绑定兼容、设备上 Host/Launcher/Test 同步升级方案。若已有独立三方 SDK 客户端，应保留旧 DTO 一个 deprecation 周期，而不是强制原地断裂。

---

## 6. 验证与门禁

### 6.1 每批必跑

```bash
./gradlew verifyArchitecture
```

该门禁必须持续覆盖：五模块的 JVM tests、lint、SDK 发布元数据、SDK POM 不引入 Kotlin/Host 实现依赖的检查。

### 6.2 新增架构测试矩阵

| 规则 | 推荐测试位置 |
|---|---|
| `contract`/`platform` 不 import 实现包 | Host `RuntimeBoundaryTest` |
| `model` 不 import `task` | Host `RuntimeBoundaryTest` |
| `data` 不 import `task` / `model` | Host `RuntimeBoundaryTest` |
| task 到 data 的领域投影只能位于 `task/persistence` | Host `RuntimeBoundaryTest` |
| SDK 不 import Host/feature 实现 | SDK `SdkModuleBoundaryTest` |
| Launcher/Test 只依赖 SDK | 各自 ModuleBoundaryTest |
| ondevice 不泄露 MNN 实现细节给 Host API | OnDevice boundary test |

文本 import 断言简单且适合当前工程，但不能是唯一保障：同时保留 Gradle 依赖图检查、公开 AIDL golden/hash 检查和关键适配器的行为测试。

### 6.3 设备验证（结构迁移完成后）

1. `connectedTrustedReleaseAndroidTest`：可信调用方可协商、提交、订阅和控制任务。
2. `connectedUntrustedReleaseAndroidTest`：非 platform 签名应用仍被拒绝。
3. `connectedVoiceCertificationAndroidTest`：真实麦克风、权限被撤销、TTS、音频焦点与模型就绪状态。
4. `connectedOnDeviceCertificationAndroidTest`：MNN native load、模型加载、真实 tool-call。
5. 升级/恢复：旧数据库、下载中断、进程重启、Binder death 重连、模型切换中的取消。

当前连接设备若仍运行较旧 Host APK，必须先将 Host、Launcher 和测试 APK 升级为同一 contractHash，再执行跨 APK 结论性测试。

---

## 7. 风险控制

| 风险 | 防护措施 |
|---|---|
| 仅搬包时意外修改锁、线程或时序 | 每个批次禁止功能逻辑重写；并发测试和模型切换测试先行 |
| 持久化投影遗漏字段 | 两条写入路径共用一个 factory；字段级断言；Room schema 不变校验 |
| AIDL/Service 类名变化导致旧客户端不能绑定 | 本轮不改 ABI；未来保留兼容类/旧坐标并做同步发布 |
| `contract` 成为新上帝包 | 明确准入标准；非跨域纯类型必须留在所属领域 |
| Demo 迁包后仍被 release 使用 | flavor/binding 测试；production 默认 fail-closed |
| 仅 JVM 测试通过但 ROM 集成失效 | 每个阶段结束做签名权限、系统服务注册和端侧推理设备认证 |

---

## 8. 完成定义

本方案完成的最低标准为：

1. `task <-> model`、`task <-> data` 不再形成双向 import。
2. `data` 不再了解 task 领域对象；持久化投影集中且被测试覆盖。
3. `task`、`identity`、`host` 的职责可从目录名直接读出，Host 只承担 DI/RPC 边界。
4. Demo 有唯一且显式的装配点，production 具备 fail-closed 的替代路径。
5. 非设备门禁全绿，可信/非可信 Binder 与端侧/语音设备认证在同版本 Host 上通过。
6. 公开 SDK ABI、Room schema 和安全边界若发生变化，均有单独版本说明、迁移策略与回滚路径。

---

## 9. 参考来源与取舍

本方案参考同级目录 `test/MatrixAgentProject` 中的《MatrixAgent重构落地说明》：其“contract 抽取、data 扁平命令、任务侧适配器、Host DI/RPC 分层、HotSwap 收敛、每批绿灯”的思路可以直接借鉴。

本方案未直接采纳该参考工程的模块重命名和 SDK DTO 改名，因为当前项目已经存在 platform-signed 系统服务、Maven SDK 和设备侧部署基线。内部架构治理应先于公开发布迁移；清晰命名很重要，但不能以破坏现有可信客户端连接为代价。
