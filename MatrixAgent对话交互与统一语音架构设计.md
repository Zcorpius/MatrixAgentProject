# MatrixAgent 对话交互与统一语音架构设计

> **状态**：设计稿，尚未开始本功能的实现。
> **依据**：当前 MatrixAgent 的 Host / SDK / Launcher 源码，以及 Operit 的实际语音实现源码；不依赖历史设计文档。
> **目标**：新增独立的“对话交互”侧边栏页面，使文字输入、按键录音和唤醒后的语音遵循同一条对话与 Agent 执行链路，同时不削弱现有 Host 权限边界、任务仲裁、审计、车控安全策略和语音生命周期治理。

---

## 1. 结论与范围

建议新增一个一级页面 **对话交互**，而不是把聊天 UI 塞进现有“任务工作”或“语音功能”。三者的职责应当明确：

| 页面 | 用户目标 | 页面职责 | 不承担的职责 |
|---|---|---|---|
| 对话交互 | 连续交流；文字或语音发消息；查看回复与动作结果 | 对话线程、输入、按键录音、转写中间态、Agent 回复、任务卡片 | 直接管理离线模型安装、持续唤醒开关 |
| 任务工作 | 查看/调试一次任务的执行轨迹与运行状态 | 任务、能力、策略、审计的专业工作台 | 充当聊天历史 |
| 语音功能 | 配置和观测语音基础能力 | 唤醒开关、模型安装/删除/下载进度、实时语音状态与诊断 | 保存对话或决定业务执行语义 |
| 模型接入 / 模型市场 | 配置与管理模型 | 保持既有职责 | 不直接参与会话编排 |

文字、PTT（按键录音）和唤醒语音的差异，只应停留在“如何取得一句最终文本”这一层。得到**已确认的最终文本**后，它们必须进入同一个 `ConversationCoordinator`；该协调器再以现有 `AgentRuntimeRepository` 为唯一业务执行入口。这样语音唤醒不是一条隐蔽的“直接车控捷径”，而只是某一条对话消息的输入来源。

本设计不在本阶段做以下事项：

- 不把原始 PCM、Vosk 原始 JSON、访问令牌或模型密钥持久化到对话表或经 Binder 暴露。
- 不因为有对话 UI 而绕过 `PolicyEngine`、`TaskScheduler`、车辆状态约束、能力 readback、审计和取消收敛。
- 不把 Operit 的代码直接复制进 MatrixAgent；它们的进程模型、UI 架构和安全边界不同。
- 不承诺 Vosk 小模型的识别率会因 UI 改造而提升。ASR 引擎替换是可独立进行的后续阶段。

---

## 2. 当前实现事实与设计起点

### 2.1 MatrixAgent 已有的可复用能力

当前工程并不是从零开始做对话。

1. `matrix-agent-launcher` 已是独立 Launcher，所有业务调用经 `matrix-agent-service-lib` SDK 到 Host；Launcher 没有、也不应拥有麦克风 PCM。
2. `AgentRuntimeRepository` 已支持文本入口 `execute(String, Actor, CancellationToken)`，也支持中立的 `AgentInvocation`。后者已携带 `InputSource`、语言、ASR 置信度、音区与取消令牌，因此正是统一输入落点。
3. `AgentEngine` 已建立模型 conversation，内含系统消息、用户消息、工具调用和结果；还具备输入预算、结构化压缩、工具 observation 脱敏、会话 working memory、会话历史与审计写入。
4. `SessionManager` 以 `sessionId` 隔离内存上下文，`TaskScheduler` 以同车 `arbitrationKey` 串行化任务；二者不能被“一个聊天页面”混为同一个概念。
5. Host 的 `IVoiceService` 已经坚持“音频只在 Host 进程内采集，跨 Binder 只传受限文本/状态”。`VoiceServiceStub` 对 PTT 建立短会话、推送 partial/final 回调并限制文本长度。
6. `VoiceSessionController` 已有明确状态机：`IDLE → WAKE_ACCEPTED → LISTENING → ENDPOINTING → RECOGNIZING → THINKING → SPEAKING`，并覆盖超时、取消、打断、代次校验、音频焦点和旧回调丢弃。
7. `VoiceEntryCoordinator` 已处理系统唤醒事件来源白名单、event id 去重、乱序、冷却和“活动会话不重复唤醒”。这是一层安全入口，而不是聊天 UI 可以替代的逻辑。

### 2.2 当前缺口

现有能力的“最后一公里”尚未闭合：

| 缺口 | 当前表现 | 需要补上的边界 |
|---|---|---|
| 可展示的用户对话 | `AgentEngine` 的模型上下文是一次任务内存列表；并非用户可见、可回放的消息流 | 独立 `Conversation` / `ConversationMessage` 领域模型与持久化 |
| 会话归属 | 现有默认 session 如 `demo-driver` 服务于 working memory | 用户可见对话线程 ID，和 Agent session / 调度键显式映射 |
| 文本输入 | “任务工作”可提交命令，但不具有聊天消息、流转状态、持续对话体验 | 对话页面和面向会话的 Host RPC |
| PTT | 语音页可以录音、展示 partial/final，但 final 并不是一条对话消息 | PTT final 先成为用户消息，再进入统一调度 |
| 唤醒 | Voice controller 当前 final 后直接调用 `AgentRunner` | 由 Voice 把最终输入投递给对话入口，再由统一协调器决定线程与执行 |
| Agent 输出 | 语音有 TTS，任务有 outcome/trajectory；没有统一消息投影 | Assistant 文本、工具/执行状态、失败和取消均映射为可见消息 |

### 2.3 关键约束

本设计中的“对话”不等于把任意聊天内容直接传给模型。

- **对话 ID 不等于权限主体**：身份、用户、座位、音区、车辆状态仍由 Host 推导，Launcher 不可伪造。
- **对话 ID 不等于调度键**：同一车辆上的写操作仍要进入既有仲裁队列。
- **已转写不等于可执行**：ASR 最终文本仍要经过 `TranscriptValidator`、输入长度限制、策略和能力判定。
- **展示历史不等于模型上下文**：用户能看到的历史应是产品记录；给模型的上下文仍必须应用预算、压缩和 `ModelSanitizer`。
- **语音来源是元数据，不是豁免权**：`WAKE`、`PTT` 与 `TEXT` 的每次命令都走同一条 Agent 安全链路。

---

## 3. 从 Operit 实际代码中可参考的内容

本节只评价其现有代码实现，不依据 README 或产品宣传。

### 3.1 值得吸收的工程做法

| Operit 实现 | 价值 | MatrixAgent 的采用方式 |
|---|---|---|
| `SherpaSpeechProvider.kt` 的本地流式识别 | 16 kHz、单声道、流式 partial、final；初始化与识别各自使用互斥锁；每轮启动前防御性释放遗留 `AudioRecord` | 保留“引擎在 `voice/platform`，会话在 `voice`”的分层。未来新增 `SherpaAsrAdapter`，不污染 `VoiceSessionController` |
| `OnnxSileroVad.kt` | ONNX Silero VAD 保存内部 RNN state；对连续说话和静音帧做时序判断，不以单帧音量判断结束 | 将来作为 `VadPort` 的实现替代/增强，VAD 阈值属于语音策略，不属于聊天 ViewModel |
| `SpeechPrerollStore.kt` | 16 kHz 环形缓冲保留最多 2.5 秒语音；抓取、arm、单次消费、最大时效均有边界 | 做成 Host 内部 `AudioPrerollBuffer`，只有一次“唤醒→命令识别”交接令牌能消费；不跨 Binder，不落库 |
| `SpeechServiceFactory.kt` 的本地引擎 lease/ref-count | 同一时刻只有一个本地 ASR 实体，调用方拿 lease；避免两个页面各起一个 native recognizer 抢麦 | MatrixAgent 已有 `VoiceRuntime` 生命周期锁；应升级为明确的 `AudioOwnershipCoordinator`，避免 Voice 页和对话 PTT 竞争麦克风 |
| wake 时强制选本地引擎 | 云 STT 不能承担持续本地唤醒 | MatrixAgent 保持此原则：唤醒检测必须本地、低延迟；命令 ASR 可以以后按策略选择本地/云端 |
| 前台服务声明 microphone 和外部录音监测 | 持续监听/后台采音必须清晰管理系统限制和音频占用 | 继续由 Host 管理前台服务和音频焦点；Launcher 只订阅状态 |
| PTT/唤醒均提供 partial 流 | 用户能区分“没有录到”“录到了但没识别”“已提交” | 对话页面把 partial 显示为临时气泡，final 原子替换/确认，不把每个 partial 写入数据库 |

### 3.2 必须避免照搬的部分

| Operit 当前行为 | 为什么不适合直接带入 MatrixAgent |
|---|---|
| 用 wake phrase 的正则/子串匹配 partial 直接触发后续流程 | partial 可回滚且易误匹配。MatrixAgent 的车控必须继续只接受已完成、验证后的唤醒事件 |
| 用固定 `delay(180ms)` 等待麦克风交接，再最多重试 12 次 | 这是时序猜测，设备差异会导致偶现失败或抢麦。应以明确的所有权释放/获取完成事件驱动 |
| 把 partial/final 按短周期直接推进应用工作流 | 对车控会把不稳定中间结果过早变为业务动作。MatrixAgent 必须只以 final 创建可执行消息 |
| `SpeechPrerollStore` 作为全局 object 且没有会话 token | 多入口时可能消费到别的轮次的音频。MatrixAgent 需用 wake generation + `AudioLeaseId` 绑定 |
| Sherpa 回调中默认 confidence 为 `0f` | 不能把没有可靠置信度理解成低或高置信度。应继续用 `confidenceAvailable` 区分“不可得”与“数值很低” |
| 云端 STT 的录音后上传模式 | 可作为明确授权的 fallback，但不得在用户不知情下把唤醒后的语音上传；还需单独定义网络、隐私、失败与延迟策略 |
| 个人唤醒的 MFCC + DTW 模板 | 它近似声学模板匹配，不是安全身份认证。不得用来提升车控权限或替代 Actor 认证 |

### 3.3 对 ASR 技术路线的建议

短期不修改现有 Vosk 自由识别链路，只做“输入进入统一对话”的结构改造。这样可以把功能正确性与识别率问题隔离。

第二阶段再用可插拔的 `AsrPort` 进行引擎升级：本地 Sherpa 流式 ASR + Silero VAD 是值得优先验证的候选。它必须先完成真机的录音、延迟、内存、中文车控语料和连续会话测试，不能只因 Operit 使用就直接替换 Vosk。云 ASR 可以作为用户显式开启、网络可用时的增强路径；唤醒词检测始终保留端侧路径。

---

## 4. 目标架构

### 4.1 总体数据流

```text
文本输入 ───────────────┐
                         │
PTT: PCM → ASR partial ──┼─→ ConversationIngress
        └─ ASR final ────┤        │
                         │        ▼
系统/本地唤醒 → ASR final ┘  ConversationCoordinator
                                      │
                       ┌──────────────┴──────────────┐
                       ▼                             ▼
             ConversationRepository           AgentRuntimeRepository
             写用户消息、状态投影                    │
                                                  TaskScheduler
                                                      │
                                                  AgentEngine
                                                      │
                       ┌──────────────────────────────┴──────────────┐
                       ▼                                               ▼
             ConversationRepository                           VoiceResponseBridge
             写 assistant / task / error                       回注既有 Controller 的唯一 TTS 路径
                       │
                       ▼
      Host Binder subscription（受限消息/状态，无 PCM）
                       │
                       ▼
      ConversationFragment / ConversationViewModel
```

这张图有两个刻意的分叉：

1. 对话持久化与 Agent 执行是两个职责。先创建不可变的用户消息，再调度 Agent；进程死亡后用户消息可见，并由启动对账收敛其非终态，绝不永久停在“执行中”。
2. TTS 是对 assistant 结果的**呈现通道**，不是 Agent 的输出真相。文字页面总能看到完整输出；语音仅播报经 `ResponsePresenter` 截断、脱敏、适合口语的版本。
3. `VoiceResponseBridge` 不是第二套 TTS。它只把带关联键的 task terminal 回投给原 `VoiceSessionController`；Controller 仍是焦点、utteranceId、播报 watchdog、generation、barge-in 和 KWS 重布防的唯一权威。

### 4.2 包与模块归属

```text
matrix-agent-service-lib
  api/conversation/          新增稳定的 Binder DTO、服务接口、callback

matrix-agent-service
  conversation/              纯领域：线程、消息、输入、状态机、协调器端口
  conversation/persistence/  conversation -> data 的唯一投影
  data/conversation/         Room entity、DAO、repository 实现
  host/rpc/                  ConversationServiceStub；调用者验证、订阅、限流
  voice/                     只负责声音采集、ASR、VAD、唤醒与 TTS
  task/                      继续唯一负责 Agent、安全策略、调度、能力执行

matrix-agent-launcher
  data/                      ConversationRepository（SDK 适配）
  presentation/              ConversationFragment / ViewModel / UI state
```

依赖规则如下：

- `conversation` 可以依赖 task 的**窄执行端口**以及 task 明确导出的“种子上下文”契约；不能依赖 Host、Binder 或 Launcher。`AgentBudget`、`ConversationCompressor`、`ModelSanitizer` 仍属于 task，assembler 不得复制其规则。
- `voice` 不直接依赖 `AgentRuntimeRepository`。它提交 `ConversationInput` 给 `ConversationIngress`，避免语音成为第二条业务执行链。
- `data/conversation` 不依赖 task、voice、host；只认识自己的 entity/command。
- `host/rpc` 是唯一跨域装配和 Binder 映射位置。
- Launcher 只能见 SDK 的对话 DTO；不能 import Host 内部 `ConversationMessage`、`VoiceRuntime` 或音频对象。

### 4.3 建议的核心对象

```java
public enum ConversationInputChannel { TEXT, PTT, WAKE }

public enum ConversationMessageRole { USER, ASSISTANT, SYSTEM }

/** 仅能写入 conversation_message.status 的状态。 */
public enum PersistedMessageStatus {
    ACCEPTED,              // 用户 final 已被 Host 接受，等待/正在调度
    RUNNING,               // 对应 Agent task 运行中
    COMPLETED,             // assistant 已给出终态
    FAILED,
    CANCELLED,
    EXECUTION_UNKNOWN,     // 写操作可能已发出但进程死亡、超时或取消后无法确认
    REJECTED               // ASR 校验、输入校验或策略前置拒绝
}

/** 仅 Launcher/Host subscription 内存投影；绝不写入 conversation_message。 */
public enum TransientInputState { DRAFT, TRANSCRIBING }

public record ConversationInput(
        String conversationId,
        String text,
        ConversationInputChannel channel,
        String languageTag,
        Float asrConfidence,              // nullable：引擎不提供时为空
        boolean confidenceAvailable,
        String voiceSessionId,            // TEXT 时为空
        String wakeEventId,               // 仅 WAKE；用于幂等
        CancellationToken cancellationToken) {}
```

Java 17 的 `record` 很适合 Host 内部不可变命令；但不要直接把 record 当作现有公开 Parcelable ABI。SDK 继续使用显式、版本化的 Parcelable DTO，避免新增字段破坏跨版本客户端。

`ConversationCoordinator` 的最小职责：

1. 校验/归一化输入，生成 server-owned message ID、`conversationTaskId`、提前生成的 `runtimeRequestId`（稳定小写 UUID）与不可变的分类快照（至少含 `readOnlyHint`；`memorySaveAllowed` 不入快照——恢复对账不需要，审计走既有 per-request 事件）；
2. 用幂等键拒绝同一次语音 final 的重复投递；
3. 调用 task 域 `ConversationTaskSubmitter.prepare()` 获得纯 task 类型的 `PreparedTask`（该端口不读写任何 conversation 表，见下）；
4. 在**一项 Room 事务**中原子写入用户消息（`sequence_no` 在同一事务内分配，见 §5.2）、task link（含 `runtimeRequestId`、`read_only_hint`）与 accepted event；事务提交后在 keyed serial lane 登记预订。两者共同构成 `SubmissionReceipt`——“消息/task link 已原子持久化且任务已被接纳”的唯一凭据（事务为同步完成语义，经异步通道送达 Controller，见 §6.4）；
5. keyed lane **出队时**才把 `PreparedTask` 构造为 `AgentRequest` 并经既有 `AgentRuntimeRepository` 阻塞执行；同一 `conversationId` 串行，跨 conversation 才允许并发；绝不在 Binder、voice state 或 DB 线程上等待；队列深度有界，满则拒绝（OVERLOADED）；
6. seed 上下文装配（§5.4）在**出队时、构造 request 前**完成——此时同 conversation 的前序任务已终态，seed 不会遗漏上一轮结果；
7. 将 task 生命周期投影为消息状态（`RUNNING` 在 keyed lane 出队时置位，而非 receipt 时）和 assistant 消息，并为语音请求登记 `conversationTaskId ↔ VoiceResponseToken`；
8. 发送受限的消息事件给订阅者；
9. 不拥有麦克风、不解析 PCM、不直接调用能力 Provider。

`ConversationTaskSubmitter` 是 task 域导出的**纯准备端口**（依赖方向 conversation → task，符合 §4.2），不暴露给 Launcher，也不 import 任何 conversation 实体：

```text
prepare(input) -> PreparedTask {
  runtimeRequestId,        // 提前生成的稳定 UUID；conversation_task_link 的落库字段，
                           // 关联 trajectory/audit；出队构造 AgentRequest 时注入复用
  classificationSnapshot,  // 不可变快照：readOnlyHint + inputSource/languageTag 等
                           // 恢复与审计所需的分类元数据
  invocationSeed           // 构造 AgentRequest 所需的全部已固化输入（seed、session、
                           // token 工厂、取消语义）；不构造 AgentRequest 本体
}
```

**deadline 从出队起算**：`AgentRequest.Builder` 增加内部 `requestId` 注入口（Host 域内对象，非公开 ABI）；keyed lane 出队时以 `PreparedTask.runtimeRequestId` 构造 request，`deadlineAtMillis` 从出队时刻起算。因此同 conversation 的排队等待不消耗任务预算；而“恢复对账使用的 `readOnlyHint` 与实际调度的 request 不漂移”由分类快照持久化保证，与 request 构造时机无关。取消、bridge、审计在提交前即可获得稳定标识（`conversationTaskId` / `runtimeRequestId`）；`completionFuture` 由 lane 执行段返回，是唯一 terminal 来源。

---

## 5. 对话、消息与持久化模型

### 5.1 三个不同层次，不能混用

| 名称 | 生命周期 | 用途 | 例子 |
|---|---|---|---|
| `conversationId` | 用户可见、持久化 | 一个聊天线程；用于页面历史 | “今天的驾驶助手” |
| `agentSessionId` | Host 内部、可恢复 | `SessionManager` working memory 与 steer mailbox 的隔离键 | `conv:<conversationId>:<actor>:<zone>` |
| `arbitrationKey` | 任务运行期间 | 同一车辆任务调度/抢占 | 既有 `demo-vehicle` / 将来真实 vehicle id |

一个 conversation 可以包含多个顺序 task；一个 task 也可能有多条可见消息，例如用户命令、assistant 文本、一个“执行中”的任务卡片、最终 readback。绝不能把 `requestId` 当作 conversationId，也不能用 user 提交的字符串直接当 `agentSessionId`。

### 5.2 建议 Room schema

新增表而不是滥用 `session_history`。后者是 episodic memory 的摘要，服务于召回，不是逐条 UI 聊天记录。

```text
conversation
  conversation_id       TEXT PRIMARY KEY        # Host 生成 UUID
  owner_user_id         TEXT NOT NULL
  vehicle_zone          TEXT NOT NULL
  title                 TEXT NULL               # 首条消息后异步生成；不能阻塞发送
  created_at_ms         INTEGER NOT NULL
  updated_at_ms         INTEGER NOT NULL
  archived_at_ms        INTEGER NULL
  schema_version        INTEGER NOT NULL

conversation_message
  message_id            TEXT PRIMARY KEY        # Host 生成 UUID
  conversation_id       TEXT NOT NULL INDEX
  sequence_no           INTEGER NOT NULL
  role                  INTEGER NOT NULL        # USER / ASSISTANT / SYSTEM
  status                INTEGER NOT NULL
  channel               INTEGER NULL            # TEXT / PTT / WAKE，仅 USER
  text                  TEXT NOT NULL           # 受长度限制的 final 文本；不存 partial
  language_tag          TEXT NULL
  conversation_task_id  TEXT NULL INDEX          # 提交前生成的稳定 task id
  reply_to_message_id   TEXT NULL
  failure_code          INTEGER NULL
  created_at_ms         INTEGER NOT NULL
  updated_at_ms         INTEGER NOT NULL
  idempotency_key       TEXT NULL UNIQUE         # SQLite 允许多个 NULL
  schema_version        INTEGER NOT NULL

conversation_task_link
  conversation_task_id  TEXT PRIMARY KEY
  runtime_request_id    TEXT NOT NULL UNIQUE      # AgentRequest.requestId；关联 trajectory/audit
  conversation_id       TEXT NOT NULL INDEX
  user_message_id       TEXT NOT NULL
  assistant_message_id  TEXT NULL
  read_only_hint        INTEGER NOT NULL        # accept 时 IntentClassifier 的持久化快照
  terminal_status       INTEGER NULL
  created_at_ms         INTEGER NOT NULL
  started_at_ms         INTEGER NULL
  terminal_at_ms        INTEGER NULL
```

`conversation_message` 的实体注解必须显式声明复合唯一索引
`@Index(value = {"conversationId", "sequenceNo"}, unique = true)`；不能只在注释中表达该不变量。
`conversation_task_link.runtime_request_id` 同时必须是唯一索引；`conversation_message.conversation_task_id` 为普通关联索引。`conversationTaskId` 是对话任务控制与恢复主键，`runtimeRequestId` 只负责关联既有 trajectory/audit，二者绝不混用。

设计要点：

- 文本长度在 Host 入口统一限制，与现有 Binder `boundUtf8` 同类；不要把无限模型输出塞入 SQLite/Binder。
- partial 只放在内存事件流，final 才能写 `conversation_message`。这既避免数据库抖动，也避免把不断改写的 ASR 猜测当作用户真实表达。
- `idempotency_key` 对 TEXT 可为 `clientOperationId` 的派生值（派生时必须包含 `conversationId`，防止跨会话的同名操作号碰撞全局唯一索引）；PTT 使用 `voiceSessionId + finalOrdinal`；WAKE 使用 `wakeEventId + finalOrdinal`。同一 final 重放只能返回既有 message/task，不能二次执行车控。
- `sequence_no` 在用户消息插入的**同一 DB 事务**内分配（事务写锁天然串行化 TEXT 与语音 final 的并发提交）；keyed lane 只串行化执行，不串行化提交。
- 空文本/`TOO_SHORT` final 在 `TranscriptValidator` 被拒绝后只结束临时转写，既不占幂等键，也不创建 `REJECTED` 持久化消息；只有已提交到 `ConversationCoordinator` 的用户文本才是产品消息。
- 对话正文（用户原文和 assistant 文本）是有意识的未脱敏产品数据：按 preference value 同等级处理，保存于 SQLCipher 库、受保留策略和 `clearUserData` 覆盖。它与全脱敏的 trajectory/audit 有意不同；日志、callback 错误和审计投影仍必须脱敏。
- 对话表必须纳入既有 `clearUserData` 的事务范围、epoch gate、备份/恢复策略和加密数据库初始化策略。
- 默认保留策略建议为“用户可删除、可清空、可配置期限”；语音原始音频默认**不保存**。

### 5.3 进程死亡后的对账（明确不复用 durable task）

聊天任务首版**不**写入 `agent_task` / `PersistentTaskManager`。后者的 durable session 以 taskId 为隔离键，而连续聊天必须使用稳定的 `conv:<conversationId>:<actor>:<zone>` agent session；把两者强行合并会改变 durable 契约和对话上下文归属，收益不足。

取而代之，`ConversationRecoveryCoordinator` 在 Host 启动、数据库可用且对外暴露会话前完成一次事务性对账；对账完成前，`IConversationService` 的全部方法 fail-closed 返回 `SERVICE_NOT_READY`（对齐既有 `PersistenceGate` / `TaskGraph.recoveryComplete` 的准入模式）：

1. 查询所有 `ACCEPTED` / `RUNNING` 的用户消息及其 task link；
2. 读取 accept 时已持久化的 `conversation_task_link.read_only_hint`。值为 true 时转为 `FAILED`，使用稳定错误码 `PROCESS_INTERRUPTED`；
3. 值为 false、缺失或不可信时，转为 `EXECUTION_UNKNOWN`；
4. 对应未终态 assistant 占位消息也同步为相同终态并写入可读、非误导的说明；
5. 写入一条 sequence 有序的恢复事件，订阅者重连后从数据库看到该终态，而非依赖丢失的 callback。

这与 `PersistentTaskStore.markInterruptedExecutionsUnknown()` 的安全语义对齐，但不假装聊天任务可以自动重放。运行中的 CancellationToken 只存在进程内；重启后的旧消息不可“继续执行”。

### 5.4 模型上下文装配与 Engine 改造

不能把整张 `conversation_message` 直接塞入 `AgentEngine`。建议新增 `ConversationContextAssembler`：

1. 读取当前 conversation 最近的已完成 user/assistant 消息；
2. 过滤 `FAILED`、`REJECTED` 的技术细节和原始错误；
3. 对工具结果沿用 `ModelSanitizer`；
4. 按现有 `AgentBudget` 做截断与 `ConversationCompressor` 压缩；
5. 将当前 user message 放在最后，保证当轮指令优先级；
6. 通过新的 task 内部 `ConversationSeedContext` 交给 Engine，而不是让 UI 拼 prompt。

这不是只在 Coordinator 旁边加一个 helper：当前 `AgentEngine` 每次都新建 `system + current user` 的 `ArrayList`，没有种子入口。因此必须在 task 域新增一个受控 request 字段/构造器，例如 `AgentRequest.conversationSeed()`，并由 Engine 在加入当前 user 前，将已经过 `ModelSanitizer`、预算和 transaction-safe 压缩的 seed 写入模型 conversation。`ConversationContextAssembler` 本身应置于 `task/conversation`，由 task 注入并复用既有 `AgentBudget`、`ConversationCompressor`、`ModelSanitizer`；`conversation` 域只传 conversation identity 和已授权的消息读取端口。

现有 `AgentEngine` 内部 conversation 仍保留“单任务内模型推理循环”的含义。新增的对话历史属于“跨任务可见上下文”，两者通过上述 task 内部 seed 契约连接，不应该把 Engine 的 mutable list 暴露给数据库或 Launcher。

---

## 6. 统一输入流程

### 6.1 文本输入

```text
用户点击发送
  → Launcher 本地 DRAFT 气泡
  → IConversationService.sendText(conversationId, text, operationId)
  → Host 验证 caller / 长度 / conversation 归属
  → ConversationCoordinator.accept(TEXT)
  → 持久化 USER: ACCEPTED
  → 订阅者收到 messageAccepted
  → AgentRuntimeRepository.execute(AgentInvocation)
  → task 过程投影 RUNNING
  → assistant final / task result 持久化并推送
```

发送按钮只在 Host 接受后把本地 draft 转为正式消息。Binder 失败时保留输入框文本，并显示“未发送，可重试”；不得以“看起来已经插入 UI”冒充任务已提交。

### 6.2 PTT（对话页录音）

```text
用户按住/点击录音
  → IConversationService.createVoiceBinding(conversationId, clientOperationId)
  → IVoiceService.startUserInitiatedSession(TRIGGER_PTT, bindingOperationId)
  → Host 独占 AudioLease，ASR 产生 partial
  → Launcher 显示 TRANSCRIBING 临时气泡
  → 用户松开（flush）/ VAD endpoint / 最大时长
  → ASR final + TranscriptValidator
     ├─ 拒绝/空文本：临时气泡显示原因，不创建可执行消息
     └─ 接受：ConversationIngress.submit(final, channel=PTT)
                 → 与文本相同的 Coordinator 流程
```

这里是一个**明确的 PTT 契约变更**。当前 `stopSession()` 等同 `cancelCurrentSession()`，会丢弃 `pendingFinal`；对话页的“松开”不能继续调用它。新增版本化的 `finishSession(sessionId, operationId)`（或 append-only 的 `stopMode=FLUSH_FINAL`）表示“停止采音并冲刷当前识别结果”，原有 `stopSession()` 保持取消语义，语音功能页的“取消”继续走原路径。

flush 实现必须复用既有状态机而不是在 `LISTENING` 直接提交：`LISTENING → ENDPOINTING`，取消 speech-start/max-speech watchdog，调用 `asrPort.finish()`；`onFinal` 若早到先保存 `pendingFinal`，再从 `ENDPOINTING` 消费。endpoint final 与 flush final 都使用同一 `voiceSessionId + finalOrdinal` 幂等键，且进入第一个 terminal final 后立即作废 ASR sid，防止双投递。空/过短 final 按 §5.2 只结束临时态。

关于“自动发送”：PTT 的 final 默认自动进入对话是合理的，但必须满足两点：它已是 ASR final，并通过基础校验；用户可以在最终提交前按**取消**（不是 flush）。首版可以实现“final 后立即提交”，同时在 UI 中明确显示“已发送（语音）”。对涉及确认的能力，仍由后续能力策略/确认状态机决定，不能拿 PTT 自动发送取代确认。

### 6.3 唤醒后的语音

```text
KWS / SYSTEM_VIS wake event
  → VoiceEntryCoordinator 验证来源、去重、冷却、会话仲裁
  → VoiceSessionController: LISTENING
  → ASR partial（只显示在选定对话的临时输入态）
  → final + Validator
  → WakeConversationRouter.resolve()
  → ConversationIngress.submit(final, channel=WAKE, wakeEventId=...)
  → 同一 Coordinator / Agent / 持久化 / UI 事件链
```

`WakeConversationRouter` 的默认规则建议如下：

1. 若当前存在该 Actor + zone 的、未归档且最近活动未超过 10 分钟的语音可续接 conversation，则续接它；
2. 否则新建“语音对话”线程；
3. 用户在设置中可选择“唤醒总是新开对话”或“始终续接最近对话”；
4. 被用户显式删除/归档的 conversation 永不被唤醒路由自动复活；
5. 路由发生在 Host，不能依赖 Launcher 是否在前台。

“hi Matrix”本身不是用户消息正文。KWS 检出与命令 ASR 分轮时，最终消息自然不包含它；若未来使用同一条流式转写，必须仅以已确认的 wake span 裁剪前缀，并保存裁剪原因的诊断元数据，不能用字符串全局替换误伤用户内容。

### 6.4 ingress 的唯一提交点

无论来源是绑定 PTT 还是 WAKE，`ConversationIngress.submit(...)` 和 `VoiceResponseToken` 的生成都**只**发生在 `VoiceSessionController.consumeFinal()` 的 accepted 分支：它已经拥有 final、`TranscriptValidator` verdict、当前 `generation`、`voiceSessionId` 与状态转换的原子语义。Controller 将 final、来源和 route target 封装为 `ConversationInput` 后投递 ingress。

ingress 返回异步 `SubmissionReceipt`，但 receipt 的语义必须是同步事务已完成，而不是“已放入某个无保证队列”：`{conversationTaskId, runtimeRequestId, acceptedAt, completionFuture}` 只在用户消息、task link、accepted event 均已提交且 keyed lane 已成功预约时返回成功。Controller 提交后先进入新增的瞬态 `SUBMITTING`，不阻塞 voice state 线程；只有 state executor 收到匹配 token 的成功 receipt 后才 `SUBMITTING → THINKING` 并登记 terminal watchdog。数据库不可用、重复绑定、队列满、分类/组装失败或 receipt 超时均走可见失败收敛，且不遗留 `THINKING`。`VoiceSessionState`、测试和 UI 投影必须新增该状态，不能继续把“已提交给 ingress”误报成“Agent 正在执行”。

`VoiceServiceStub.BinderVoiceListener.onFinal()` 的职责保持为“向 Launcher 分发受限 final 文本”，不得创建消息、调用 ingress、生成 token 或改变任务状态。`ConversationVoiceBinding` 只决定该 Controller 会话的 `conversationId` 路由与 partial 展示去向；它不能成为另一条提交链。这样 PTT、WAKE 的 token 生成方、`THINKING` 等待方和 TTS 回注方永远是同一个 Controller。

`SUBMITTING` 的完整迁移集：`RECOGNIZING → SUBMITTING`（accepted 分支提交后）、`SUBMITTING → THINKING`（匹配 token 的成功 receipt）、`SUBMITTING → CANCELLED`（用户取消“提交中”）、`SUBMITTING → ERROR_ANNOUNCING`（receipt 失败 / 提交段超时 / DB 不可用 / 队列满）。`VoiceEvent` 增加 `SUBMISSION_ACCEPTED` / `SUBMISSION_FAILED` 事件；既有 `confirming_isUnreachableInDemo` 式全遍历状态机测试必须同步扩展。幂等命中（同幂等键返回既有消息/task）按成功 receipt 处理，不产生第二条消息。

---

## 7. 状态机、音频所有权和打断

### 7.1 异步 task 到 TTS 的受控回注

把 `AgentRunner.run()` 从 Controller 的 `voiceAgentExecutor` 同步阻塞调用改为 `ConversationCoordinator` 异步调度后，不能让 Controller 自己订阅数据库或另起 TTS。提交 voice final 时，Controller 生成不可猜测的 `VoiceResponseToken`：

```text
VoiceResponseToken = { voiceSessionId, generation, requestNonce, acceptedAtElapsedMs }
ConversationTaskLink = { conversationTaskId, conversationId, userMessageId, VoiceResponseToken? }
```

`VoiceResponseToken` **绝不落库**，上图的 `?` 表示 `ConversationTaskLink` 在进程内关联对象可选持有 token，而不是 Room 表字段。`acceptedAtElapsedMs` 仅用于 bridge 与日志侧的陈旧性诊断，不参与匹配校验。Coordinator 持久化不含 token 的 task link 并提交任务后，将 token 仅注册在进程内 `VoiceResponseBridge`；token 不经 Launcher 或普通 Binder callback 暴露。进程死亡时 bridge 与 token 自然消失，§5.3 的恢复对账接管，不能向已不存在的 Controller 回投。

task terminal 的唯一回注流程为：

```text
Conversation task terminal
  → Coordinator 在同一逻辑终态中写消息 / assistant 内容 / task link
  → VoiceResponseBridge.deliver(conversationTaskId, token, ConversationTaskResult)
  → VoiceSessionController.onConversationTerminal(token, outcome)
  → 校验 state == THINKING、voiceSessionId、generation、requestNonce
  → 复用既有 onTerminal 内部路径：ResponsePresenter → focus → speakNow → utteranceId/watchdog
```

不匹配、重复、已取消、超时或 Controller 已销毁的 terminal 必须只保留对话记录、丢弃语音播报；它们绝不能污染新的会话。`VoiceResponseBridge` 不接管 `TtsPort`，不请求焦点，也不直接改变 `VoiceSessionState`。因此 TTS、barge-in、播报 watchdog 和唤醒重新布防始终只有 `VoiceSessionController` 一个权威。

异步化还必须给 Controller 增加两段式 watchdog，因为 keyed lane 的**排队等待不属于任务执行**，既不能烧预算（§4.3 已保证 deadline 从出队起算），也不能被误判为执行超时：

1. **提交段（`SUBMITTING`，见 §6.4）**：从 final 被 Controller 接受，到匹配 token 的 `SubmissionReceipt` 返回。上限为显式常量“前置持久化预算”，覆盖落库事务、subscription 分发与 lane 预订，不能假定它为零，并记录耗时。超时或失败按 §6.4 走可见失败收敛，不进入 `THINKING`。
2. **执行段（`THINKING`）**：Coordinator 在 keyed lane 出队、`AgentRequest` 构造完成时，经 bridge 发出携带 `conversationTaskId` 的 `DISPATCH_STARTED`；Controller 收到**匹配 token** 的该事件后才启动 `agentTerminalFuture`，deadline = `AgentBudget.totalDeadlineMillis + 有界调度余量`。排队等待本身由 keyed lane 的有界队列深度给出上界（满则 OVERLOADED 拒绝，§4.3），不占用执行段预算。

两段的 timeout 都捕获同一 `VoiceResponseToken`，只在 token 仍匹配且状态仍为对应瞬态（`SUBMITTING` / `THINKING`）时执行现有失败收敛。Coordinator 正常 terminal、executor 拒绝、恢复对账和 Controller cancel 都必须取消/作废这两个 future。这样 callback/bridge 丢失、长排队或前序任务超时都不会把 Controller 永久留在瞬态，也不会把排队误判成执行超时。

### 7.2 两层状态机

现有 `VoiceSessionState` 继续负责**音频会话**。新建 `ConversationMessageStatus` 负责**业务消息**。它们不是一套状态，不能互相覆盖。

| 语音状态 | 对话临时显示 | 对话消息状态 | 备注 |
|---|---|---|---|
| `IDLE` | 无临时气泡 | 无变化 | 可能只是待唤醒 |
| `WAKE_ACCEPTED` | “已唤醒，请说” | 无 | 尚无用户文本 |
| `LISTENING` | “正在听”+ partial | `TRANSCRIBING`（内存） | partial 不能执行/持久化 |
| `ENDPOINTING` / `RECOGNIZING` | “正在识别” | `TRANSCRIBING` | 等待 final 校验 |
| `SUBMITTING` | “正在提交” | USER=`ACCEPTED` | 等待持久化的 `SubmissionReceipt`；尚未开始 Agent |
| `THINKING` | 用户 final 已显示 | USER=`RUNNING` | 已有 task link；由 `agentTerminalFuture` 防永久悬挂 |
| `SPEAKING` | assistant 已出现 | ASSISTANT=`COMPLETED` | TTS 是额外呈现 |
| `CANCELLED` | “已取消” | 未接受则不写；已提交则 USER=`CANCELLED` 或 `EXECUTION_UNKNOWN` | 根据读写性质和取消发生点区分 |
| `ERROR_ANNOUNCING` | 可读错误说明 | `FAILED` / `REJECTED` | 不泄露底层敏感异常 |

`VoiceViewModel` 目前的 `WAKING/RECORDING/FINISHING/WORKING/RESPONDING/INTERRUPTING` 可继续服务“语音功能”页；对话页不复制这套 state，而是订阅消息事件和绑定的当前 voice session 摘要，避免两个 ViewModel 各自判断事实。

### 7.3 AudioOwnershipCoordinator：吸收既有采音治理，不双权威

新增 Host 内部组件，**替换并收编** `VoiceRuntime` 中的采音启动屏障、capture sid、恢复重试和 KWS stop/start 仲裁段；`VoiceRuntime.lifecycleLock` 继续只保护“装配/销毁”的生命周期，不再与新组件并列决定麦克风所有权。`SystemVoiceRuntimeOwner` 仍负责进程级唯一 runtime，向 coordinator 注册唯一 capture owner。

```java
interface AudioOwnershipCoordinator {
    AudioLease acquire(AudioPurpose purpose, String ownerId, Duration timeout);
    void release(AudioLease lease);
    AudioOwnershipSnapshot snapshot();
}

enum AudioPurpose { WAKE_LISTENING, COMMAND_ASR, TTS }
```

规则：

- 一个物理麦克风在任意时刻只有一个 `WAKE_LISTENING` 或 `COMMAND_ASR` lease。
- `COMMAND_ASR` 获得 lease 时，先请求 wake 引擎停止并等待实际释放完成；不使用固定延时猜测。
- 唤醒会话结束后，只有在没有 PTT / TTS 打断 / 前后台限制时才重新布防 KWS。
- 释放携带 generation；旧 session 的 release 不能释放新 session 的麦克风。
- TTS 不占麦克风 lease，但需要音频焦点；barge-in 时由 Controller 先停止 TTS/取消正在执行的可取消任务，再申请命令 ASR lease。
- 所有 acquire/release、AudioRecord 初始化失败、外部录音占用、焦点丢失均记录结构化日志和指标，不记录 PCM。

此阶段同时新增按需 microphone 前台服务。当前 Manifest 已有 `FOREGROUND_SERVICE_MICROPHONE` 权限，但没有 `foregroundServiceType="microphone"` 的 Service；必须新增独立、非导出的 `VoiceCaptureForegroundService`，不能复用下载服务的 `dataSync` 类型。PTT 只允许从可见的用户触发入口调用 `startForegroundService()`；持续唤醒只允许从已获系统授权的 VoiceInteraction 路径启动。Service 必须在平台时限内调用 `startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)`，之后 coordinator 才能发放命令 ASR / 持续 wake lease；`ForegroundServiceStartNotAllowedException`、权限拒绝、通知不可用或启动超时均映射为可见语音错误并禁止创建 `AudioRecord`。最后一个采音 lease 释放后按平台规则降级/停止。现有 `IVoiceService` 的“会话期间按需升级 microphone 前台服务”承诺由此首次真正落地。

### 7.4 超时策略

当前工作区 `VoicePolicyConfig.defaults()` 的 `speechStartMs` 已是 `3000L`，所以“唤醒后 3 秒未开始说话”既是现状也是合理的交互策略；它必须作为 `VoicePolicyConfig` 的显式参数保留，而不能散落到 UI。建议区分：

| 超时 | 默认建议 | 结果 |
|---|---:|---|
| 唤醒后 speech-start | 3 秒 | 结束本轮，回 `IDLE` 并重新布防；不产生对话消息 |
| 说话中静音 endpoint | VAD 驱动，约 0.8–1.2 秒起步 | 请求 ASR final |
| 最大说话时长 | 当前 15 秒；产品目标可经真机评估到 20–30 秒 | 强制 `finish()`，仍尝试获得 final |
| ASR final 等待 | 1–2 秒 | 标记可读失败，清理音频资源 |
| Agent 总 deadline | 沿用 `AgentBudget` | 产生真实 terminal outcome，而非假装完成 |
| Controller `SUBMITTING` receipt 等待 | 显式前置持久化预算（常量，记录耗时） | 匹配 token 的 receipt 成功→`THINKING`；超时/失败走可见失败收敛 |
| Controller `THINKING` terminal 等待 | 收到 `DISPATCH_STARTED` 后：`AgentBudget.totalDeadlineMillis` + 有界调度余量 | 仅匹配同一 `VoiceResponseToken` 时收敛；排队不计时、不会永久悬挂 |

具体数值必须真机调优并按车型/麦克风环境验证；常量不应散落在 Fragment 或 Operit 式延时中。

---

## 8. SDK 与 Host RPC 设计

### 8.1 新增服务，不改造现有 Voice API 的职责

建议新增 `IConversationService`，避免把文字聊天、历史读取塞进 `IVoiceService`：

```text
IConversationService
  createConversation(CreateConversationRequest) -> ConversationHandle
  listConversations(ConversationListQuery) -> List<ConversationSummary>
  getMessages(conversationId, beforeSequence, limit) -> ConversationPage
  sendText(SendTextRequest, clientOperationId) -> ConversationSubmission
  appendMessage(conversationId, text, clientOperationId) -> ConversationOperationResult
  createVoiceBinding(conversationId, clientOperationId) -> ConversationVoiceBindingHandle
  cancelMessage(conversationId, messageId, clientOperationId) -> ConversationOperationResult
  subscribeConversation(conversationId, IConversationCallback)
  unsubscribeConversation(IConversationCallback)
```

PTT 仍通过 `IVoiceService` 启动音频会话，首版不修改冻结的 `VoiceSessionRequest` Parcelable。绑定机制必须消除“最近创建 binding”的并发歧义：

1. Launcher 调用 `IConversationService.createVoiceBinding(conversationId, clientOperationId)`；Host 验证 conversation owner/zone 后生成一次性、高熵 `bindingOperationId`，仅保存到进程内、线程安全且带 TTL 的 binding map，并返回它；
2. Launcher 把**这个 bindingOperationId 原样作为既有** `IVoiceService.startUserInitiatedSession(..., clientOperationId)` 的 `clientOperationId`；不新增字段；
3. `VoiceServiceStub` 在接受 PTT 的同一 `sessionLock` 临界区，向 `ConversationVoiceBindingStore.consume(caller, bindingOperationId, voiceSessionId)` 原子领取绑定；一个操作 id 只能成功一次；
4. 未找到绑定时，保持既有独立语音功能页的 PTT 行为；找到绑定时，partial/final 只投递该 conversation；过期、caller 不匹配或重复消费均拒绝而不猜测路由。

`ConversationVoiceBinding` 的有效期短（例如 30 秒），状态为 `PENDING / CLAIMED / EXPIRED / CANCELLED`，由定时清扫和 consume 时检查过期。它不写 SQLCipher、无需 migration；进程死亡即自动失效，Launcher 收到 session 启动失败后重新创建 binding 并重试。clear-user-data 也同步清空 map。未来需要支持第三方客户端任选 conversation 时，再以 ABI vNext append-only 字段替代此复用方案；届时旧客户端仍可走绑定流程。

`appendMessage` 是“追加到当前任务”（§10 阶段 A 末段）的唯一 RPC 入口：仅当该 conversation 存在 `RUNNING` 任务时接受，否则返回 `INVALID_STATE`；实现为向该 conversation 的 conv session 投递 `SteerMailbox` 的 `REPROMPT`（经 `AgentRuntimeRepository.offerSteer(sessionId, Steer.reprompt(text))`，文本长度沿用既有 REPROMPT 上限）。它与 `sendText`（创建新任务并进入 keyed FIFO）语义互斥，UI 必须依据是否存在 `RUNNING` 任务区分展示两个入口。

注册面与部署注记：`MatrixServiceConstants` 新增 `CONVERSATION_SERVICE` 发现键与 `FEATURE_CONVERSATION_DOMAIN` 特性位；`MatrixAgentManagerService.getMatrixService` 增加对应路由分支。`generateContractHash` 将因新增 AIDL/DTO 改变 `CONTRACT_HASH`——按既有发布纪律，Host / Launcher / SDK 必须同版本发布；混版本部署时旧客户端协商失败（SERVICE_NOT_READY）是设计内行为，不提供兼容 hash。`bindingOperationId` 生成时即为小写 UUID，以满足既有 `HostInputValidator.requireOperationId` 校验。

### 8.2 callback 事件

`IConversationCallback` 只传受限事件：

```text
onMessageUpsert(ConversationMessageDto message)
onMessageStatusChanged(conversationId, messageId, status, errorCode)
onTransientTranscript(conversationId, voiceSessionId, text, isFinal)
onConversationError(conversationId, errorCode)
```

事件规则：

- callback 注册后先发一个有界快照（建议最近 50 条；更早历史经 `getMessages` 分页拉取），再发增量，防止 UI 订阅竞态。
- 每个 DTO 有 `schemaVersion`、消息 ID、sequence、时间戳；Launcher 按 sequence 合并，允许重复事件。
- `transientTranscript` 最大长度更小、只保留当前 session，final 后清除。不要将每个 partial 当历史消息。
- Host 做 caller 验证、conversation owner/zone 校验、UTF-8 长度限制和 callback death 清理。
- Launcher 断连时只显示“服务连接中”；连接恢复后分页重拉历史，以数据库为准，不依赖 callback 补齐所有事件。

### 8.3 取消语义

取消有两个不同的用户动作：

1. **取消录音**：final 尚未被 `ConversationIngress` 接受时，停止 Voice session，丢弃临时文本；不创建任务。
2. **停止执行**：final 已提交后，`cancelMessage` 找到 task link，触发现有 `CancellationToken` / scheduler 收敛。只读任务可确定取消；写操作若发送后无法确认，必须沿用 `EXECUTION_UNKNOWN`，不能因为 UI 点击了停止就显示“已撤销”。

---

## 9. 对话页面设计

### 9.1 页面结构

```text
┌──────────────────────────────────────────────┐
│ 对话交互                         新建 · 历史 │
├──────────────────────────────────────────────┤
│ 用户  [语音] 请把音量调到 35%                 │
│        已发送 · 执行中                         │
│                                              │
│ 助手  已将媒体音量调整为 35%。                 │
│        已验证 · 14:05                         │
│                                              │
│ 用户  [唤醒] 把亮度调高一点                   │
│        正在识别：把亮度调高…                   │
│                                              │
├──────────────────────────────────────────────┤
│ [正在聆听 ●●●]                         [停止] │
│ 输入消息…                              [发送] │
└──────────────────────────────────────────────┘
```

页面不应是终端日志。正常用户看到简短状态（正在听、正在识别、执行中、已完成、需要确认、已取消）；详细 ASR JSON、PCM RMS、VAD 事件、Binder 错误码只放语音功能页的诊断区域或受控日志中。

### 9.2 Assistant 消息内容与安全投影

当前 `AgentOutcome` 不包含 `directAnswer`；`directAnswer` 只存在于模型回合 `ModelTurn`，而 outcome 的 trajectory 是审计投影、`internalResults` 也禁止直接渲染。因此 task 域必须新增 `AssistantReply`，由 `AgentEngine` 在可信上下文中从最终模型回答或终态语义构造，并随 `ConversationTaskResult` 一起交给 Coordinator：

```text
AssistantReply {
  text,
  source: MODEL_FINAL | SYNTHESIZED_TERMINAL,
  displaySafe,
  truncated
}
```

聊天里的 assistant 正文只能来自已经 `displaySafe` 的 `AssistantReply`；`ResponsePresenter` 的“已完成。”等短句只用于 TTS，不能替代聊天正文，否则连续对话会退化为无信息的状态播报。

入库前由 task 侧 `ConversationAssistantProjector` 完成唯一投影：保留用户有权在对话中看到的业务语义（例如已验证的音量、亮度值，或用户自己显式保存的 preference value），脱去 API key、token、内部 capability 细节、stack trace、未授权用户/座位数据。projector 只消费 task 类型（`AssistantReply`），不 import 任何 conversation 实体——它是 `AssistantReply` 的展示安全化步骤，归属 task；入库由 conversation/persistence 执行。对话正文按 §5.2 的加密产品数据策略保存；audit/trajectory 和 logcat 仍维持各自更严格的脱敏策略。每条 assistant 消息附带可显示的 execution summary（`COMPLETED`、`FAILED`、`CANCELLED` 或 `EXECUTION_UNKNOWN`），但不能以固定短句覆盖正文。

当终态没有可信的模型回复（例如 `MAX_ITERATIONS`、`BUDGET_EXHAUSTED`、`POLICY_HALT`，或 assistant content 因截断不可作为完整回答）时，`AgentEngine` 必须创建 `source=SYNTHESIZED_TERMINAL` 的 `AssistantReply`，例如“任务未在允许的步骤内完成，未执行后续操作”或“该请求因安全策略未执行”。projector 不得从 trajectory 反解析、不得把截断片段伪装成完整回复，也不得退化成无上下文的“已完成”。

### 9.3 ViewModel 状态

`ConversationViewModel` 建议只保存可渲染的不可变 `State`：当前 conversation、分页消息、输入草稿、绑定的 `VoiceOverlayState`、错误提示与分页状态。它不能保存 `VoiceManager`、Binder callback、`AudioRecord` 或 task executor。

```java
public record ConversationUiState(
        String conversationId,
        List<MessageItem> messages,
        String draft,
        VoiceOverlayState voice,
        boolean sending,
        boolean loadingOlder,
        UiError transientError) {}
```

`VoiceOverlayState` 只包括 `IDLE / WAKING / LISTENING / RECOGNIZING / WORKING / SPEAKING / INTERRUPTING / ERROR`、partial 文本和可见提示。它是 Host 状态的投影，不在 ViewModel 内推导状态机。

### 9.4 无障碍与恢复

- 发送、录音、停止、重试都有文字内容描述，不能只用图标。
- partial 频繁变化不应每帧触发 TalkBack；只在“开始识别”“已识别”“识别失败”时播报。
- 旋转/重建时由数据库历史与 subscription 快照恢复；临时 partial 可丢失并显示“语音会话仍在进行”，不伪造一段 final。
- 多窗口或后台时，对话页面可以不可见，但 Host 的语音/任务生命周期不依赖 Fragment；当应用回前台后重新订阅。

---

## 10. 实施顺序

每一阶段均应是可编译、可测试、可部署的独立提交；不要一次替换语音引擎、数据库、Binder 和 UI。

### 阶段 A：对话领域与持久化（不接语音）

1. 增加 `conversation` 纯领域对象、消息状态和 `ConversationCoordinator` 接口。
2. 增加 Room entity/DAO/migration（含复合唯一索引）、`ConversationRecoveryCoordinator`，并纳入 SQLCipher、clear-user-data、epoch gate。
3. 在 `MatrixExecutorRegistry` 增加共享有界 `conversationExecutor`，并在其上实现 keyed serial dispatcher：同一 `conversationId` 串行、不同 conversation 可受限并发；Coordinator 的阻塞 runtime 调用只在此 dispatcher 运行，严禁新建线程池。
4. 实现 `IConversationService`、Host Stub、SDK client、caller/输入/分页校验。
5. 对话页面完成文字发送、历史、assistant terminal 投影；明确 `EXECUTION_UNKNOWN` UI。
6. 在 task 内增加 `ConversationSeedContext`、`ConversationTaskSubmitter`、`AssistantReply`，将上下文装配、同步 receipt 和 assistant 安全投影接入 Engine。

同一 conversation 出现 `RUNNING` 消息时，后续普通发送进入该 conversation 的 keyed FIFO 队列；不得并发构造可能遗漏前一轮结果的 seed。运行中追加信息的“立即影响当前任务”仍使用既有 `SteerMailbox.REPROMPT`，并在 UI 上明确区分“追加到当前任务”和“排队发送下一条”。

验收：文字“调高亮度 → 再低一点”能在同一 conversation 下得到连贯上下文（**此条验收需配置任一已连通的云端模型**——离线 `DemoModelGateway` 不消费 seed，无数字的追加指令不会产生候选步骤；seed 注入链路本身以 JVM 单测证明）；任务仍经过现有 Policy；在 `RUNNING` 时杀 Host，重开后只读消息收敛为 `FAILED`、可能写入消息收敛为 `EXECUTION_UNKNOWN`，没有永久悬挂。

### 阶段 B：把 PTT 接到对话

1. 定义一次性 `ConversationVoiceBinding`（用绑定操作 ID 关联现有 PTT ABI）和临时转写事件。
2. 增加 `finishSession` / `FLUSH_FINAL`，保留 `stopSession` 的取消语义；将 flush 和 endpoint final 做幂等收敛。
3. 在 `VoiceSessionController.consumeFinal()` accepted 分支完成唯一的 `ConversationIngress` 提交、`VoiceResponseToken` 生成和 receipt 处理；PTT/WAKE 共用该改造，`BinderVoiceListener` 只分发文本。
4. 实现按需 microphone 前台服务，并让 `AudioOwnershipCoordinator` 吸收现有 `VoiceRuntime` 采音启动/恢复仲裁，覆盖 PTT 与语音页竞争。
5. 将 final、拒绝、空文本、取消、服务死亡映射为对话临时/终态 UI。

验收：真机上 PTT 说一句命令，partial 显示、final 成为一个 `[语音]` 用户气泡、只有一次 Agent task；按取消不生成消息；重复 final 不会重复调节音量/亮度。

### 阶段 C：把唤醒接到对话

1. 将阶段 B 已完成的统一 ingress 接到 `WakeConversationRouter`；不再存在 `AgentRunner → repository.execute(...)` 直连适配。
2. 实现 `VoiceResponseBridge` 和 Controller 的 `THINKING` terminal watchdog，复用 Controller 的唯一 TTS 治理。
3. 增加 wake idempotency 和 session-bound preroll，解决 KWS stop → command ASR start 间隙丢首字。
4. 将 KWS/ASR 的 transient 事件投影给当前选定/路由的 conversation。
5. 测试唤醒、3 秒未说话、说话后 endpoint、TTS 中打断、连续两次唤醒、外部录音占用、迟到 terminal 不播报。

验收：说“hi Matrix”后不说话，3 秒自动结束且不产生空消息；说完命令只执行一次，文本在对话中可见；UI 不在前台时 Host 也正确路由，回前台后可看到最终记录。

### 阶段 D：可插拔 ASR 升级（独立评审）

1. 保持 `AsrPort` 不变，增加 Sherpa adapter 与 Silero `VadPort`。
2. 增加本地/云端命令 ASR 路由策略、显式用户授权和失败回退。
3. 用目标车机真机语料对比 Vosk 与 Sherpa 的识别率、端到端延迟、内存、CPU、热机/冷机稳定性。

这一步不应与阶段 A-C 捆绑，否则语音准确率问题会掩盖对话架构的错误。

**阶段 D 实现修订（D-补1~D-补4，对齐 Operit 成熟方案）**：

- **JNI 层**：放弃手写 `SherpaNative` 声明，改为消费官方 sherpa-onnx 预编译产物
  （v1.13.8 AAR：官方 Kotlin API + 静态链接 onnxruntime 的 `libsherpa-onnx-jni.so`，
  已验证 16KB page 对齐）。版本与 SHA-256 由 `:ondevice` Gradle 下载任务钉死，
  拆解为 classes.jar + jniLibs（AGP 不允许 library 直接依赖本地 AAR），二进制不入 git。
- **端点判定**：识别器内建 endpoint rules（rule1=2.4s / rule2=1.2s / rule3=20s，
  Operit 实测参数），经共享 engine 桥接为 `VadEvent.ENDPOINT`（与 Vosk 路径同构）；
  Silero VAD 降级为引擎内**语音窗口门控**——静音期不喂识别器（省 CPU + 抑制幻觉文本），
  开窗瞬间回放 0.5s preroll 补首字；`minSilence(1.6s) > rule2(1.2s)` 保证端点先于关窗。
- **唤醒**：`KeywordSpotter` 音素级 KWS（zh-en 3M，chunk-16 int8），唤醒词与 Vosk 路径
  一致（"hi matrix"/"hey matrix"，音素拼写自包内 CMU 词典 en.phone）；未装模型时
  fail-closed，手动/PTT 入口不受影响。
- **模型管道**：三件套 spec（ASR 511MB tar.bz2 / VAD 2.3MB RAW / KWS 33MB tar.bz2）
  URL+SHA-256+尺寸钉死，tar.bz2 走 commons-compress，`requiredFiles` 白名单只落盘
  int8 套件（ASR 安装体积 511MB→198MB）。
- **引擎切换**：`IVoiceService` 追加 `getAsrEngine`/`setAsrEngine`（AIDL append-only），
  Host 按 `VoiceEnginePreference` 路由模型 RPC 并在空闲期热切换（回收 Runtime，
  下次使用懒重建）；Launcher 语音页提供引擎卡片与切换对话框。

---

## 11. 测试与真机验收

### 11.1 单元与集成测试

| 层 | 关键用例 |
|---|---|
| `ConversationCoordinator` | TEXT/PTT/WAKE 三种输入产生相同任务适配；相同幂等键只执行一次；`EXECUTION_UNKNOWN` 不被映射成取消或失败 |
| 数据层/恢复 | sequence 原子递增、分页稳定排序、清数据后旧 epoch 不可回写、迁移数据可读；进程死亡后非终态按读写语义收敛 |
| 上下文 assembler / Engine seed | 不越预算、不泄露凭据、当前消息始终保留、工具 transaction 不被错误拆开；seed 在当前 user 前且不会被 UI 注入 |
| keyed dispatcher / Submitter | 同 conversation FIFO 顺序、跨 conversation 有界并发；`AgentRequest` 出队时构造（deadline 不被排队消耗、requestId 为提前持久化的稳定值）；用户消息+task link+accepted event 同事务原子（半写回滚）；队列满 OVERLOADED |
| SUBMITTING / receipt | 四条迁移全覆盖；幂等命中按成功处理且不产生第二条消息；receipt 失败/提交段超时不遗留 `THINKING` |
| AssistantReply | `MODEL_FINAL` 仅透传 `displaySafe` 文本；`SYNTHESIZED_TERMINAL` 覆盖 `MAX_ITERATIONS`/`BUDGET_EXHAUSTED`/`POLICY_HALT`/截断终态；`truncated` 不冒充完整回复 |
| Voice → conversation | partial 不落库；final 后只调用一次 ingress；validator 拒绝时不产生 task；endpoint 与 flush final 不双投递 |
| Voice response bridge | 仅匹配相同 `VoiceResponseToken` 的 terminal 可进入 Controller TTS；迟到 terminal、已取消和新 generation 均不播报；THINKING watchdog 正确收敛 |
| Audio ownership / FGS | PTT 抢占 wake 后无并发 `AudioRecord`；旧 lease release 不影响新会话；外部占用有界失败；采音前 FGS 已就绪、最后一个 lease 后正确停止 |
| Binding / RPC | 同一 binding operation 只能领取一次；过期/跨 caller/未绑定拒绝或保持独立语音语义；未授权 caller、错误 schema、超长文本、callback death、断连恢复 |
| UI ViewModel | 本地 draft、Host 接受/拒绝、重复 callback、旋转恢复、取消与 `EXECUTION_UNKNOWN` 表示正确 |

### 11.2 真机闭环清单

此功能的完成标准必须是“真机用户闭环正确”，而非仅代码路径/单测通过。

1. 打开对话页，输入文字控制音量和亮度，现场观察读回与对话最终状态一致。
2. PTT 录音：检查状态按顺序经过录音、转写、执行、回复；partial 与 final 日志能解释每一段结果。
3. PTT：松开是 flush final，取消是显式取消；分别验证 endpoint 与 flush、识别中取消、执行中取消，确保不出现假成功或重复任务。
4. 唤醒后 3 秒不说话：自动结束、重新布防、无空消息、无麦克风泄漏。
5. 唤醒后连续命令：每次只进一个 task，消息按序出现，没有丢尾音或重复唤醒。
6. TTS 过程中说话打断：TTS 停止、任务按既有可取消性收敛、新命令被识别并进入正确 conversation。
7. 切换横竖屏/退后台再回前台：Host 会话不被 Fragment 销毁，历史不重复、不丢最终结果。
8. 模型未安装、下载中、模型删除后、麦克风权限拒绝、AudioRecord 被其他 app 占用：UI 显示可理解原因，日志能区分下载/采音/ASR/策略/任务失败。
9. 清除用户数据：对话、消息、会话记忆、审计的用户可见投影符合产品清除语义，旧异步任务不能写回。
10. 录制真实中文车控短句数据集，统计最终 ASR 与实际 spoken text；不要将“最终执行了”误判为“ASR 正确”。

### 11.3 日志与隐私

保留结构化日志字段：`conversationId`（可 hash）、`messageId`、`voiceSessionId`、`wakeEventId`、channel、状态迁移、音频 lease、ASR 耗时、final 长度、置信度可用性、taskId、terminal code。原始 PCM 永不记录。

当前 Vosk 的完整 ASR 文本日志是为现场排障临时打开的诊断能力。新增对话后，应把它设计成仅 debug/开发者开关可开启、可自动过期的策略；正式版默认只记录长度、置信度、耗时和经脱敏的错误分类，防止聊天内容在 logcat 长期裸露。

---

## 12. 风险与决策门

| 风险 | 处理 |
|---|---|
| 数据库 schema 与用户数据清理遗漏 | 先完成迁移、clear-user-data 与 epoch 的测试，再上 UI |
| 对话与 Engine 双重历史导致上下文重复 | `ConversationContextAssembler` 是唯一跨任务历史入口；Engine 单任务 conversation 不持久化为 UI 历史 |
| 语音页和对话页同时抢录音 | Audio lease 是前置条件；任何“延迟再试”都不能替代所有权事件 |
| 唤醒 final 重放导致重复车控 | 用 wake event + final ordinal 幂等键并在 Coordinator 落库前判重 |
| 异步 terminal 丢失导致 TTS/THINKING 悬挂 | `VoiceResponseToken` + Controller 两段式 watchdog（§7.1）；bridge 不直接触碰 TTS 或状态机 |
| 排队等待被误判为执行超时 / 预算被排队消耗 | requestId 提前持久化、`AgentRequest` 出队时构造（deadline 从出队起算，§4.3）；watchdog 以 `DISPATCH_STARTED` 分段（§7.1） |
| Host 进程死亡遗留 RUNNING 消息 | `ConversationRecoveryCoordinator` 按读写语义转 `FAILED` / `EXECUTION_UNKNOWN`，不自动重放 |
| UI 把执行已提交显示成执行已完成 | 展示 task terminal/readback 之后才显示“已完成”；写操作未知状态必须忠实显示 |
| ASR 低准确率被当作聊天 bug | 分开记录录音电平、partial/final、validator 和 task outcome；ASR 引擎升级独立评估 |
| 云端 ASR 产生隐私与网络问题 | 仅显式开启，明确显示网络状态/提供方；Wake KWS 不上云；默认不保存音频 |

在实现前建议确认的产品决策只有三项：默认的唤醒路由是“续接最近 10 分钟对话”还是“每次新建”；对话记录的默认保留期限；以及持续唤醒默认是否开启——开启意味着常驻 microphone 前台服务与常驻通知、相应电耗（§7.3 的 lease 生命周期前提下 FGS 不会自行停止）。其它技术决定可以按照本文先行落地。

---

## 13. 源码依据（当前检视版本）

### MatrixAgent

- `matrix-agent-service/src/main/java/com/matrix/agent/voice/VoiceSessionController.java`：当前语音控制器、ASR final、AgentRunner 与 TTS 的衔接点。
- `matrix-agent-service/src/main/java/com/matrix/agent/voice/VoiceSessionState.java`：既有语音状态机与取消/打断语义。
- `matrix-agent-service/src/main/java/com/matrix/agent/voice/VoiceRuntime.java`：语音运行时装配、生命周期锁、现有 `AgentInvocation` 适配点。
- `matrix-agent-service/src/main/java/com/matrix/agent/voice/VoiceEntryCoordinator.java`：系统唤醒入口的来源验证、去重、冷却与单会话仲裁。
- `matrix-agent-service/src/main/java/com/matrix/agent/voice/vosk/VoskAsrEngine.java`：16 kHz 流式 Vosk、partial/final、epoch 和诊断边界。
- `matrix-agent-service/src/main/java/com/matrix/agent/task/AgentRuntimeRepository.java`：统一任务请求构造、输入来源、会话/仲裁与调度入口。
- `matrix-agent-service/src/main/java/com/matrix/agent/task/AgentEngine.java` 与 `task/compress/ConversationCompressor.java`：模型 conversation、预算、压缩、记忆与审计。
- `matrix-agent-service/src/main/java/com/matrix/agent/task/durable/PersistentTaskManager.java` 与 `PersistentTaskStore.java`：现有 durable task 的进程死亡收敛语义；本方案明确对齐、但不复用其 taskId session 契约。
- `matrix-agent-service/src/main/java/com/matrix/agent/platform/MatrixExecutorRegistry.java`：Host 固定线程预算；新增 conversation lane 必须登记于此，不能自建 executor。
- `matrix-agent-service/src/main/java/com/matrix/agent/data/db/SessionHistoryEntity.java`：现有 episodic summary，不适合作为 UI message 表。
- `matrix-agent-service-lib/src/main/aidl/com/matrix/agent/api/voice/IVoiceService.aidl`：当前跨进程语音边界。
- `matrix-agent-service/src/main/java/com/matrix/agent/host/rpc/VoiceServiceStub.java`：Host 侧 PTT、模型、callback 和调用者校验。
- `matrix-agent-launcher/src/main/java/com/matrix/agent/launcher/data/VoiceRepository.java`、`presentation/VoiceViewModel.java`：Launcher 的 SDK 语音适配与 UI 状态投影。

### Operit

- `/Users/zhangyongbin/Desktop/Learn/AI/Operit/app/src/main/java/com/ai/assistance/operit/api/speech/SherpaSpeechProvider.kt`：Sherpa 本地流式识别、AudioRecord 防御性清理、VAD 与 endpoint 配置。
- `/Users/zhangyongbin/Desktop/Learn/AI/Operit/app/src/main/java/com/ai/assistance/operit/api/speech/OnnxSileroVad.kt`：ONNX Silero VAD 的 state、阈值与连续帧判断。
- `/Users/zhangyongbin/Desktop/Learn/AI/Operit/app/src/main/java/com/ai/assistance/operit/api/speech/SpeechPrerollStore.kt`：ring buffer、预卷音频抓取/arm/单次消费和时效控制。
- `/Users/zhangyongbin/Desktop/Learn/AI/Operit/app/src/main/java/com/ai/assistance/operit/api/speech/SpeechServiceFactory.kt`：本地引擎 lease/ref-count 与 wake 的本地引擎选择。
- `/Users/zhangyongbin/Desktop/Learn/AI/Operit/app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt`：持续唤醒、前台服务、唤醒/录音交接和外部录音观察的参考点。
- `/Users/zhangyongbin/Desktop/Learn/AI/Operit/app/src/main/java/com/ai/assistance/operit/ui/floating/voice/SpeechInteractionManager.kt`：PTT 与转写展示流程；其中固定延迟/重试机制仅作为反例，不应直接采用。
