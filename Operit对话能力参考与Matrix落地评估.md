# Operit 对话能力参考与 Matrix 落地评估

> 编写日期：2026-09-20  
> 目的：以 **Operit 已落地的代码** 为事实依据，评估哪些对话能力值得被 Matrix Agent 吸收，以及应以何种方式吸收。本文不是 Operit 功能清单的转写，也不是立即实施的需求单；任何落地仍须经过 Matrix 的安全、ABI、迁移与真机验收。

## 1. 结论先行

Operit 值得参考的核心不是某个聊天气泡样式，而是它把“一个聊天”当作独立、可恢复、可管理的工作空间：有历史窗口、消息操作、标题、分支、导出、流式状态与语音交互。Matrix 适合吸收其中的**用户可理解性与会话管理能力**，不应复制其“应用内模型客户端可以自由改写上下文”的运行方式。

Matrix 的边界更严格：它是 Host 拥有的系统 Agent，可能调用车辆、亮度、音量等有副作用的能力；每个请求都要经过身份、策略、审计、幂等与结果核验。因此，推荐的总原则是：

1. **聊天记录是产品数据，不是模型的可编辑提示词。** 用户可浏览、标记、导出自己的记录；模型工具轨迹、策略决策和写操作事实不可在 UI 中“编辑后重放”。
2. **会话体验与任务执行分层。** 对话页负责展示、输入、历史定位和语音入口；`ConversationCoordinator`、任务调度、PolicyEngine 与 VoiceRuntime 仍是唯一执行权威。
3. **同一输入只存在一个提交点。** 文字、PTT final、唤醒后的 final 都最终走 Matrix 的 `ConversationCoordinator`；不能为聊天页另造一套请求、TTS 或麦克风所有权。
4. **先做低风险、高可见的会话管理，再做会改变执行语义的能力。** 标题、列表、分页、收藏、导出比“重新生成”“编辑历史”“插件 Hook”更适合先落地。

建议优先级如下：

| 优先级 | 建议吸收的能力 | 结论 |
| --- | --- | --- |
| P0 | 会话列表/切换/重命名、自动标题、稳定分页与定位、含能力事实轨迹的消息状态卡、助手回复朗读、摘要续聊标记 | 应实现；不改变 Matrix 的执行与安全边界 |
| P1 | 收藏/置顶、只读导出、引用回复、分支式追问 | 应适配后实现；需要数据模型和权限设计 |
| P2 | 受控附件管线、对话内搜索、上下文用量的量化视图 | 值得规划；依赖隐私、存储和上下文预算评审 |
| 延后 | 多候选回复、再生成、工作区/角色/群组、云端 ASR 多供应商 | 有价值但会显著扩大执行与产品语义，后置评审 |
| 不采纳 | 可任意注册的 Prompt Hook、插件改写历史、内置 HTTP 聊天服务、展示/编辑原始思维链或模型生成的工具 XML、靠固定延时抢麦 | 与 Matrix 的 Host 安全模型冲突；这不排斥展示 Host 投影的结构化执行事实 |

## 2. 调研范围与证据

本文只将可从代码确认的行为视为 Operit 已实现能力。主要阅读路径包括：

| 范畴 | Operit 实现证据 |
| --- | --- |
| 聊天模型、持久化与导入导出 | `app/src/main/java/com/ai/assistance/operit/data/model/ChatMessage.kt`、`ChatEntity.kt`、`MessageVariantEntity.kt`、`data/repository/ChatHistoryManager.kt` |
| 发送、流式运行时、取消与再生成 | `services/ChatServiceCore.kt`、`services/core/MessageProcessingDelegate.kt`、`services/core/MessageCoordinationDelegate.kt`、`services/core/ChatHistoryDelegate.kt` |
| 聊天 UI 与消息操作 | `ui/features/chat/components/ChatArea.kt`、`MessageEditor.kt`、`ui/features/chat/viewmodel/ChatViewModel.kt` |
| 语音识别与播报 | `api/speech/SpeechService.kt`、`SpeechServiceFactory.kt`、`ui/floating/voice/SpeechInteractionManager.kt` |
| 附件、记忆、外部接口与插件扩展 | `core/chat/AIMessageManager.kt`、`api/chat/library/MemoryAutoSaveScheduler.kt`、`integrations/http/ExternalChatHttpServer.kt`、`plugins/chatmessage/ChatMessageMenuItemRegistry.kt`、`core/chat/hooks/PromptHookRegistry.kt` |

对照 Matrix 的主要事实来源包括：

- 对话提交、幂等、排队和终态收敛：`matrix-agent-service/src/main/java/com/matrix/agent/conversation/ConversationCoordinator.java`、`ConversationStore.java`。
- SQLCipher/Room 对话数据与恢复：`data/conversation/*`、`persistence/RoomConversationStore.java`、`ConversationRecoveryCoordinator.java`。
- SDK 对话 ABI：`matrix-agent-service-lib/src/main/java/com/matrix/agent/api/conversation/*` 与 `client/ConversationManager.java`。
- 上下文拼装、压缩与助手投影：`matrix-agent-service/src/main/java/com/matrix/agent/task/conversation/*`、`task/compress/ConversationCompressor.java`。
- Launcher 对话页：`matrix-agent-launcher/src/main/java/com/matrix/agent/launcher/presentation/ConversationFragment.java`、`ConversationViewModel.java`、`data/ConversationRepository.java`。
- 唤醒/语音对话桥接：`matrix-agent-service/src/main/java/com/matrix/agent/voice/VoiceConversationBridge.java`、`WakeConversationRouter.java`。

## 3. 两套系统的本质差异

| 维度 | Operit 的实现取向 | Matrix 必须坚持的取向 | 落地含义 |
| --- | --- | --- | --- |
| 运行位置 | 以应用内聊天与模型供应商调用为中心 | 以系统 Host、受控能力与跨进程 SDK 为中心 | 不能把 UI 的便利逻辑变成执行权威 |
| 一条消息 | `ChatMessage` 同时承载正文、模型、token、流状态与变体 | `ConversationMessage` 是不可变的用户/助手事实；任务链接与审计独立 | 不能把模型内部状态塞回可编辑聊天正文 |
| 副作用 | 主要是对话和工具调用体验 | 可能控制车辆/系统，写操作要策略授权、readback 和未知态 | “重试/再生成/编辑后发送”必须重新走策略，绝不能复用旧成功 |
| 历史 | 可通过 UI、插件 Hook 及 XML 富文本灵活加工 | 历史进入上下文前须经 `ConversationContextAssembler`、压缩和脱敏 | 聊天 UI 不能直接决定模型看到什么 |
| 语音 | 多种识别服务和 UI 层交接 | 单一 `VoiceRuntime` 管采音、焦点、打断、代次及 TTS | 不可给对话页再建一套录音/TTS 生命周期 |
| 可恢复性 | 侧重应用体验和数据库历史 | 必须把进程死亡中的执行收敛为准确终态 | `RUNNING` 不得永久悬挂，写操作不能被谎报为取消 |

这也说明 Matrix 不是“做一个缩小版 Operit”。正确方向是：借鉴 Operit 的产品能力，保留 Matrix 更强的执行安全与事实一致性。

## 4. Operit 已实现能力及其可借鉴部分

### 4.1 会话档案、标题与组织

Operit 的 `ChatEntity` 保存标题、创建/更新时间、token 统计、工作区、父聊天、角色、分组、置顶与锁定等信息。`ChatHistoryManager` 负责新建、切换、重命名、删除、锁定、置顶和分组；锁定的聊天不能被删除。`MessageCoordinationDelegate` 会在首个输入后异步生成标题，但只在标题仍是默认标题时写回，避免覆盖用户手动命名。

**Matrix 值得借鉴：会话列表、标题来源和“最近使用”的管理，而不是完整的工作区/角色体系。**

推荐设计：

- `ConversationInfo` 与 `ConversationEntity` **已经有** `title`、`archived`、`updatedAtMs` / `updated_at_ms`，这些字段不迁移也不重复定义。v7→v8 真正新增且按 Parcelable/AIDL append-only 暴露的只有 `titleOrigin`（`DEFAULT`/`AUTO`/`USER`）、`pinned` 与 `lastInputChannel`；后者直接复用冻结的 `ConversationMessage.CHANNEL_TEXT`、`CHANNEL_PTT`、`CHANNEL_WAKE`，不再创建第三套通道枚举。
- 迁移必须明确旧数据回填：`pinned=false`、未知历史通道为既有 `CHANNEL_NONE`；当前 title 列可空、没有默认标题字符串常量，因此规则简化为 `null → DEFAULT`、非空 `→ USER`，避免升级后自动标题覆盖用户可见历史。实体注释本已将“首条消息后异步生成”写为 title 列的设计意图，这一方案与 schema 原意一致。`updated_at_ms` 不迁移，因为其语义只是扩展而非换列。
- 会话的“最近活动时间”直接映射既有 `updatedAtMs`，不增加 `lastActivityAt` 列；需要补的是它的语义：除消息写入外，唤醒被接受并续接既有会话时也必须 touch `updated_at_ms`。这样既不破坏旧 ABI，又让最近使用排序反映真实交互。
- 标题触发条件精确定义为：**该会话第一条到达 `COMPLETED`、`FAILED` 或 `EXECUTION_UNKNOWN` 终态的主用户消息所关联轮次**。`EXECUTION_UNKNOWN` 同样是该轮的终态，因为标题描述对话主题而非执行成败；`REJECTED` 与 `CANCELLED` 不触发标题生成；`inputKind=STEER` 的附属输入没有独立轮次，永不触发标题生成。
- Host 在上述轮次终态收敛后，以 `ConversationCompressor` + `LlmSummaryProvider` 已有的“功能型轻量调用”模式运行独立 `TitleGeneration`：单次、无工具循环、低优先级、入审计；离线或失败时静默保留默认标题，绝不影响该轮任务。
- 标题写入时比较 `titleOrigin` 与版本号，只有当前仍是默认标题才允许落 `AUTO`。这让标题成为继摘要之后第二个明确的“按功能路由模型”实例，而不是绕过既有模型治理新开聊天通道。
- 用户重命名将来源改为 `USER`，此后自动标题永不覆盖。自动任务失败只保留默认标题，不影响对话执行。
- `archived`、`pinned` 是会话展示元数据，不得进入模型上下文，也不得影响任务策略。
- “锁定”先不作为本地安全能力承诺。Matrix 的数据安全由用户身份、SQLCipher、`clearUserData` 和 Host 权限负责；若未来需要防误删，应叫“删除保护”，并要求明确的二次确认语义。

`WakeConversationRouter` 的十分钟续接窗口也必须被会话展示层如实反映：唤醒接受后应 touch 既有 `updated_at_ms`，使它在“最近使用”排序中正确出现；`lastInputChannel=CHANNEL_WAKE` 只作为“由语音续接”的轻量标识。不要新增 `VOICE` 标题来源——标题来源表达的是谁设置了标题，而不是本轮输入来自何种通道。

不建议早期引入 Operit 的工作区、角色卡和分组。它们会使同一用户输入的系统提示词、工具集和权限来源变得不透明，和 Matrix 当前的 Host 受控能力注册表不匹配。

### 4.2 历史窗口、分页与消息定位

Operit 的 `ChatHistoryDelegate` 有前后分页、显示窗口和“定位某条消息”的能力；UI 可以加载更早或更新的内容，而不是一次渲染完整历史。它的聊天区也支持多种针对消息的操作。

Matrix 当前已有基于序号的持久化消息和较早历史分页能力，Launcher 为了渲染保护限制了显示条数。这是正确基础，但用户无法舒适地浏览多个会话或稳定地回到指定上下文。

**建议 P0 吸收，且必须以 `sequenceNo` 而非时间戳作为唯一锚点。**

- **保留现有** `getMessages(conversationId, beforeSequenceExclusive, limit)` 的单向、排他语义不变；以 append-only 的 AIDL 新方法增加 `getMessagesAfter(...)` 与 `getMessagesAround(...)`。现有 `ConversationPage(schemaVersion, messages, hasMore)` 也只追加 `hasBefore`、`hasAfter`、`anchorExists` 三个字段（或为 Around 专用查询新增同语义 DTO），不能改变 `hasMore` 的旧含义。
- UI 首屏读取最新 N 条。加载旧页时保留第一条可见消息的像素锚点，避免列表跳动；收到订阅的新增终态时，只在用户处于底部时自动滚到底部，否则显示“有新回复”。
- 定位只做数据库查询与滚动，不重新提交 Agent 请求；被清理、越权或不存在的锚点统一返回**空页且 `anchorExists=false`**，而不是区别化错误码，不泄漏存在性。
- 推荐的持久化 steer 本身也获得单调 `sequenceNo`，因此自然参与旧页、后页和 Around 定位，不需要为它增加另一套分页通道。
- 不能用“截断的 UI 显示窗口”替代真实上下文。模型的历史仍由 `ConversationContextAssembler` 和 `ConversationCompressor` 按预算挑选与压缩。

### 4.3 发送运行时、流式显示与可解释状态

Operit 的 `MessageProcessingDelegate` 为每个 chat 维护独立运行时（发送 Job、流、活动轮次、取消互斥、工具状态等），并用 revision tracker 处理流式内容的 savepoint/rollback。这里需要精确区分：其 `TurnCancellationSnapshot` / `captureCurrentTurnTokenSnapshot()` 用于在取消收敛时保存 **token 统计快照**；并没有周期性把流式**文本**写回数据库。`ChatServiceCore` 将发送、加载、流状态与令牌统计向 ViewModel 暴露。

Matrix 已经做得更稳健：`ConversationCoordinator` 的提交先原子落用户消息和任务链接，再按会话键串行派发；终态由存储层收敛，恢复逻辑能把非终态变为正确的失败或 `EXECUTION_UNKNOWN`。这套语义不得为“像打字一样显示”而退化。

**可借鉴的是状态呈现与运行时隔离，不能把暂态输出误当作持久历史。**

推荐 UI 将每条**主提交**用户消息投影为简洁状态卡；下文 steer 是无独立任务的明确例外，复用状态但增加附属输入注记：

| 持久状态 | 面向用户的显示 | 行为要求 |
| --- | --- | --- |
| `ACCEPTED` | 已接收，等待处理 | 可取消；不承诺已执行 |
| `RUNNING` | 正在理解/执行 | 展示有限、脱敏的阶段；主提交仍可取消 |
| `COMPLETED` | 已完成 | 展示助手最终回答与已核验结果摘要 |
| `REJECTED` | 未执行 | 显示策略/校验原因，不伪装成失败 |
| `FAILED` | 未完成 | 显示可理解的失败原因与安全重试入口 |
| `CANCELLED` | 已取消 | 仅在确定未继续执行时使用 |
| `EXECUTION_UNKNOWN` | 执行结果未知 | 对写操作尤其重要；提示用户检查实际状态 |

#### Steer 输入：持久化事实、宿主任务的附属输入

上述“一条用户消息对应一条任务”的表述只适用于主提交。Matrix 已有真实的 steer 路径：`ConversationCoordinator.appendToRunningTask(...)` 在存在运行中任务时将文本交给 `SteerSink`，用于 reprompt；当前实现不落消息、不建 link。这会造成用户已影响执行却无法回看自己的输入，违反“聊天记录是产品数据”的原则。

本方案选择**持久化 steer 输入**，但不把它伪装成一个新的任务：

```text
用户在宿主轮次 RUNNING 时追加文本
  → Coordinator 在同一会话门控内确认宿主未终态
  → 原子写入一条 USER / inputKind=STEER 的消息（分配 sequenceNo）
  → 成功投递 Steer.reprompt 给宿主 Agent session
  → 该消息随宿主轮次的终态同步收敛
```

- steer 消息的 `conversationTaskId` 保持 `null`，因此没有自己的 task link、不能单独取消、不会产生能力轨迹，也不触发标题生成；它通过 `steerHostUserMessageId` 指向宿主的主用户消息，并持久化 `steerDeliveryState`（`PENDING` / `OFFERED` / `FAILED`）。
- steer 消息初始为 `RUNNING + PENDING`，在线运行时展示“正在并入第 N 轮”；宿主确认接收后变为 `RUNNING + OFFERED`/“已并入第 N 轮，正在执行”。宿主到达 `COMPLETED`、`FAILED`、`CANCELLED` 或 `EXECUTION_UNKNOWN` 时，Store 在与宿主终态相同的事务内将所有附属 steer 消息同步为该终态。**展示是否已并入只由 `steerDeliveryState` 决定**：仅 `OFFERED` 可显示“已并入”；恢复后仍为 `PENDING` 的记录必须显示“未确认是否并入第 N 轮”，即使宿主已按保守语义收敛为 `EXECUTION_UNKNOWN`；`FAILED` 显示“未能并入”。这不是第八个状态，而是现有七态加 `inputKind=STEER` 与投递状态的展示规则。
- 通过 `(conversationId, clientOperationId, inputKind)` 建立唯一幂等约束，并将持久化 steer `messageId` 作为 `steerId` 放入 `Steer.reprompt`；运行时以 `(hostTaskId, steerId)` 去重。现有 `IConversationService.appendMessage(..., clientOperationId)` 已有该入参，实施时必须从 Stub 传入 Coordinator/Store，不能像当前路径一样在到达 `appendToRunningTask` 前丢失。只有首次插入 `PENDING` 的记录可请求投递；Binder 幂等命中返回既有记录，绝不无条件再 offer。若因“已投递但尚未来得及标记”需要安全重试，运行时的 `steerId` 去重保证引擎最多消费一次。
- `SteerSink` 需要从当前的 fire-and-forget `void offerSteer(...)` 演进为可报告“已入宿主队列/拒绝”的轻量确认，才能将 `PENDING → OFFERED/FAILED` 如实落库。线性化点必须在 Coordinator 的同一会话门控内：宿主终态写入与 steer 接受相互排斥，不能出现“宿主已终态、却插入一条显示 RUNNING 的 steer”。若投递被拒绝，保留用户消息并将它收敛为 `FAILED`/“未能并入宿主请求”；若进程在投递前死亡，宿主的恢复对账会将附属 steer 一并收敛，写能力保持 `EXECUTION_UNKNOWN` 的保守语义。
- 已被成功投递的 steer 在**当前宿主轮次**只由 `SteerSink` 传入，`ConversationContextAssembler` 不得又从刚落库的记录重复拼入同一轮上下文；在后续新轮次中，它作为已定稿用户输入可按既有预算/脱敏规则参与 assembler，从而保留“用户后来补充了什么”的对话事实。
- 当前 `IConversationCallback` 已有 `onMessageUpsert`，因此新 steer 记录及其终态更新沿用该回调推送；不为它另开回调。`onMessageStatusChanged` 继续只发送冻结的状态枚举，UI 依靠 upsert 中追加的 steer 元数据渲染“已并入”注记。

**范围声明：本节只覆盖 `Steer.REPROMPT`。** 当前 Launcher 的 `appendToRunningTask` 也只构造这一类型。`Steer.FORCE_TOOL` 是跳过本轮 LLM、直接构造 `ToolCall` 进入工具执行分支的直接能力调用；若未来成为用户可触发入口，它必须创建独立任务链接、经 PolicyEngine/确认/readback，并拥有自己的能力事实轨迹，绝不可复用本节的“无 task link、镜像宿主终态”模型。`Steer.DEFER` 是显式终止信号，会收敛为 `StopReason.DEFERRED` / `TaskState.DEFERRED`，同样需要单独定义用户可见语义。未来若支持跨座席共享 steer mailbox，`FORCE_TOOL` 还必须额外评审命令发起身份、宿主身份与能力授权的归属；本节不为这两种 steer 授权，也不暗示它们可由对话 UI 发送。

- 状态卡不只是“正在执行”的文案。对每个执行过的能力，Host 应投影一条**能力事实轨迹**：稳定 capability ID 经 `CapabilitySpeechNames` 映射后的友好名、经白名单裁剪的参数摘要、请求/执行/核验结果态，以及必要时的 readback 摘要。它的数据源必须是 `ConversationTaskLink`、审计事件与 readback 等结构化执行事实，而不是解析模型输出 XML。
- 每个可核验参数固定使用两段式字段：`requestedDisplay` 与 `verifiedDisplay`，另有 `verificationState`（例如 `VERIFIED`、`MISMATCH`、`UNAVAILABLE`、`UNKNOWN`）。UI 固定渲染为“请求 X → 核验为 Y”；二者不同必须以 **Y/readback** 为准并显式显示差异。例如 Android 15 级音量请求 30% 后读回 33%，应显示“请求 30% → 核验为 33%”，不能压扁成“已设为 30%”。音量档位量化、亮度曲线和空调温度回差都属于正常设备现实，而不是展示层可以忽略的小数误差。
- 原始 CoT、原始 prompt、推理草稿、凭据、原始工具参数/返回、未脱敏 readback 一律不展示；模型生成的工具 XML 也不作为 UI 协议。能力事实轨迹是 Host 的安全投影，且在 `EXECUTION_UNKNOWN` 时必须保留“尝试了什么、最后得到何种核验态、为何未知”，使用户可以真正核验设备现实。
- ASR partial、模型 streaming token 和工具进度都应是**内存态/订阅态**，断开或进程死亡时自然消失；最终用户文本、最终助手答复、状态和安全事实轨迹才按各自的数据保留规则落库。

#### 能力事实轨迹的数据、写入与客户端通道（P0 必备）

选择**写时净化的会话投影**，而不是在翻页时按 `runtimeRequestId` 实时 join 审计：为 `ConversationTaskLinkEntity` 在 v7→v8 追加 `execution_trace_json`（可空，默认空列表）及其必要的投影版本字段。它保存的是已净化的 capability 列表、白名单参数摘要、请求值、核验值、核验状态和执行结果态；不保存原始审计 payload、模型文本、凭据或可逆推出敏感数据的字段。

终态收敛时，`ConversationAssistantProjector` 从结构化执行结果构建 `CapabilityExecutionTrace`，经过专门的白名单/脱敏器后，与 assistant 最终投影和 task link 终态在**同一 Store 事务**写入。原始审计仍是审计用途的权威记录；`execution_trace_json` 是为会话 UX 设计、留存策略独立的不可变安全投影。这样做有三项刻意收益：

1. 对话分页只读取消息及其一对一 task link 投影，不依赖审计库仍在保留、仍可访问或仍使用同一脱敏版本。
2. 轨迹天然继承会话的 owner、清理 epoch、链接级联与分页性能边界；导出也只读取该安全投影，而非临时再净化审计原文。
3. 终态后不可由客户端或模型改写，避免“助手文本说已完成、审计却显示未知”的双权威展示。

客户端通道也必须随页面一起定义：新增 Parcelable `CapabilityExecutionTrace`（及参数/核验值子项），并在 `ConversationMessage` **追加** `List<CapabilityExecutionTrace> executionTraces` 字段；轨迹以 `TaskLink.user_message_id` 关联到**用户消息**，由用户消息的状态卡承载，绝不挂到 assistant message。读取旧 schema 时为空列表。`ConversationPage` 的每页消息因此一次携带已净化轨迹，Launcher 无需为每个气泡再发 RPC，更不会暴露审计查询接口。AIDL、`ConversationMessage` 的读写顺序和 `schemaVersion` 必须同步递增并覆盖新旧端互操作测试。

在后续启用 transient 协议后，运行中的卡片只能显示由 Host 生成、可随时消失的阶段提示（例如“正在请求系统音量调整”）；只有轮次进入终态后，才把上述不可变轨迹写入并展示为历史事实。阶段 2 尚不发送此类动态文案。这样既保留未来的即时反馈，也避免把尚未 readback 的“预计会做什么”伪装成已发生的设备事实。

若未来模型协议提供受支持的增量输出，可新增 `ConversationTransientUpdate`，但它是跨 Binder 的暂态协议，不是进程内 `SharedFlow` 的简单搬运，必须同时满足：

1. Host 以 150–250 ms 合并节流，或仅在自然句读边界 flush；绝不按 token 逐个 Binder 回调。
2. 回调使用 `oneway`；复用既有 `CallbackRegistry` 的 `RemoteException` 摘除机制，慢/死亡客户端不得阻塞执行 lane。
3. 每条更新都带 message/task 标识与 `generation`，客户端只接收当前 generation，避免迟到 partial 污染新轮次。
4. 订阅严格绑定消息页/语音页生命周期；退订后 Host 直接静默丢弃，进程死亡后不恢复也不补发。

不可把未完成模型文本写成一条“已完成助手消息”。

### 4.4 消息操作：收藏、复制、引用、编辑与删除

Operit 的聊天区支持复制、编辑、删除单条/尾部、收藏、回复某消息、选择多个消息、回滚、变体选择和创建分支。`MessageEditor` 甚至会把若干 XML 标签分段可视化编辑。

对 Matrix，应逐项拆开判断：

| Operit 操作 | Matrix 建议 | 原因与约束 |
| --- | --- | --- |
| 复制 | 采纳 | 复制经 `ModelSanitizer`/UI 投影后的可见文本；工具凭据、内部审计字段不参与复制 |
| 收藏 | P1 采纳 | 独立 `conversation_message_annotation`，只允许用户标记、备注和取消标记；不改原消息、不自动送入上下文 |
| 引用回复 | P1 采纳 | 新用户输入带 `quotedMessageId`，Host 校验同属一个会话和同一用户后生成简短、可见的引用上下文；不是直接拼入 prompt |
| 编辑已发送消息 | 不直接采纳 | 已发消息可能已经触发写能力。只允许编辑草稿；对历史的“改写”应创建新分支/新消息，永不原地改事实 |
| 删除/回滚历史 | 不直接采纳 | 不能删除与任务、审计关联的事实。可实现“从我的视图隐藏”或按全局数据保留政策清除；`clearUserData` 仍是唯一彻底清除路径 |
| 删除尾部后续消息 | 不采纳 | 会制造“UI 看不到但任务已执行”的错觉，并破坏顺序号和恢复语义 |
| 批量选择 | 延后 | 可先服务于导出/收藏，不服务于重放、删除或上下文拼接 |

### 4.5 分支、再生成与候选变体

Operit 的 `createBranch` 将指定时间点之前的历史及消息变体复制到子聊天；`MessageCoordinationDelegate` 与 `MessageProcessingDelegate` 支持对 AI 回复生成、保存、选择多个候选变体。

这类能力在通用聊天里自然，但 Matrix 不能把“再生成”视作纯文本操作：原请求可能修改过亮度/音量，未来还可能调用车辆能力。重复执行的副作用不是一条候选回答。

**推荐先做“安全分支式追问”，延后“再生成变体”。**

安全分支的建议语义：

1. 用户在某个**已完成**序号处选择“从这里继续”。Host 创建一个新的 `conversationId` 和 `ConversationLineage`，记录父会话与切点。
2. 分支创建时在切点调用**同一个** `ConversationContextAssembler`，将其输出持久化成版本化、受预算限制、已脱敏的种子快照，并通过既有 `ConversationSeedContext` 注入子会话。绝不另写一套“分支历史裁剪”规则；不把父会话的任务链接、未终态消息、音频绑定或语音 token 复制过去。
3. 新对话可显示“从某次对话创建”的来源说明，但源会话后续新增内容不会悄悄影响分支。
4. 分支中的任何输入都是全新的 `ConversationSubmission`，拥有新的幂等键、策略评估、任务 ID 和审计记录。

再生成只有在完成一项独立评审后才可引入：系统必须能在提交前确定该轮为只读，或明确向用户再次确认将重做写操作；被取消/超时的旧调用也不能由“重新生成”自动替代。不要建立“一个 assistant message 下挂多份工具执行结果”的变体表，这会模糊哪一次物理动作对应哪一段文本。

### 4.6 上下文预算、摘要与记忆

Operit 维护 token 总数/窗口统计，存在自动摘要、记忆候选与相关的调度逻辑。它还可以把附件、工作区和回复引用拼成模型输入内容。

Matrix 已有更适合系统 Agent 的 `ConversationContextAssembler` 与 `ConversationCompressor`：上下文不是任意整段历史，而是经预算、摘要和脱敏后的种子。这里最值得借鉴的是**把“上下文是否足够”变成可理解的用户体验**，而不是照搬原始历史拼接。

建议：

- P0 在状态卡/会话头部显示无量化的“已启用摘要续聊”标记，但该标记**不落库为历史事件**。`ConversationCompressor` 当前是在装配期返回 `[SummaryMessage, ...recentMessages]` 的内存结果，摘要既不是 `ConversationMessage` 也不保证跨重启存在；把“曾经压缩过”写进表会引入标记与实际行为漂移的双写义务。
- 因此采用**读时重算**：抽取/复用压缩器相同的纯预算与触发判定——当前锚点为 `ConversationCompressorEightyPercentTriggerTest` 覆盖的 **80% 预算阈值**——在加载会话时对当前历史和当前预算计算“下一次续聊是否会使用摘要”。命中则展示该标记，未命中则不展示；它表达的是当前续聊的真实装配策略，不声称一段不可验证的历史事实。该判定不得调用 LLM、不得生成摘要、不得改变历史或发起任务。
- P2 再在会话信息页提供量化的上下文预算、近期压缩范围与摘要来源范围；这些指标仍是解释性投影，不能暴露系统 prompt、隐私记忆或供应商内部 token 细节。
- 记忆保存继续遵循 Matrix 的显式用户意图、能力权限和审计边界。Operit 的自动候选可作为“建议保存”的交互灵感，但候选绝不能自动成为跨会话事实。
- 引用回复和未来附件都只作为 `ConversationContextAssembler` 的结构化候选输入，由 Host 决定是否与多少内容进入模型；UI 不拥有拼 prompt 的权限。

### 4.7 附件、图片与多模态输入

Operit 的 `AIMessageManager.buildUserMessageContent()` 能把图片、文件、音频、视频、工作区等转换成内联模型内容，服务配置决定如何支持。它在通用助手中非常灵活。

Matrix 可以借鉴“一个输入可以带结构化素材”的体验，但绝不可接收原始本地路径、任意 URI 或由 Launcher 拼出来的模型标签。若做，须单列**受控资产管线**：

1. 客户端将内容以受授予 URI 提交给 Host；Host 重新读取，生成内容哈希、长度、MIME、来源与保留期。
2. Host 限制类型/大小/页数/时长，做恶意内容、压缩包、超长文本和敏感信息策略检查；语音录音仍走已有 VoiceRuntime，不走普通文件上传。
3. 持久层只保留受控 `assetId` 和元数据，正文引用不可泄漏设备路径。实际内容加密保存并完整纳入 `clearUserData`。
4. 模型调用侧从 `assetId` 构造供应商允许的多模态输入；供应商不支持时要明确“无法处理此附件”，不应降级为假装已阅读。
5. 对于车辆/系统写操作，附件解释出的指令仍走同一策略与确认流。

因此附件适合 P2，而不是为补齐 UI 功能仓促加入。

### 4.8 语音输入、唤醒和 TTS

Operit 的 `SpeechService` 提供识别状态、结果、错误、音量与 PCM 流；`SpeechServiceFactory` 可选择本地 Sherpa、OpenAI 或 Deepgram 等实现。`SpeechInteractionManager` 支持 partial、静音结束与 TTS，但其唤醒交接中包含固定 `delay(180)` 及多次轮询重试，以等待麦克风释放。

Matrix 已经具备更正确的方向：语音会话的状态机、音频焦点、打断、generation/response token、唤醒和 PTT 最终文本都归 `VoiceRuntime`/`VoiceSessionController` 管；`VoiceConversationBridge` 将最终输入关联到会话，再将完成结果以代次校验回注 TTS。

**应借鉴的内容：**

- 在对话页清晰显示录音中、正在识别、正在思考、正在播报、被打断、识别失败等状态，并实时显示 **暂态** partial 文本。
- 提供“朗读此助手回复”的明确操作。客户端只能传 `conversationId + assistantMessageId`，由 Host 重读已持久化且经投影允许朗读的最终文本，再交给现有 TTS 所有权治理；不能让 UI 任意调用并发 TTS。
- 若存在活跃语音会话（录音、识别、唤醒交接或播报），该朗读入口禁用并明确提示原因；继续复用 `AndroidAudioFocusAdapter` 和既有 VoiceRuntime 仲裁，绝不为此新增第二套焦点规则。
- PTT 松开应走“冲刷 final”而非粗暴取消，且 endpoint final 与 flush final 必须用同一幂等约束防双投递；明确区分“完成录音”与“取消本轮”。
- 唤醒后的 final 与 PTT final 均在 `VoiceSessionController.consumeFinal` 的 accepted 分支生成 token 并提交；binding 只影响路由会话和 partial 展示位置。
- 目标真机没有可用系统 TTS 引擎时，Host 必须把 `TTS_INIT_FAILED`/无可用引擎识别为**播报通道不可用**，显示明确的安装/启用引导；已完成的对话任务仍保持 `COMPLETED`，不能被错误改写为 `VOICE_OUTPUT_UNAVAILABLE` 的任务失败。

**明确不借鉴：** 固定时间等待麦克风释放、UI 自己重试抢麦、每个页面自己创建识别服务。Matrix 应继续由单一所有权协调器用会话 ID、屏障与回调确认完成切换。

### 4.9 导入、导出与外部聊天接口

Operit 的 `ChatHistoryManager` 实现了 Markdown ZIP、JSON、HTML/TXT、CSV 等导入导出，并对大数据流式处理和进度有考虑。它还通过 `ExternalChatHttpServer` 暴露本地 HTTP/SSE、异步回调和 Web/A2A 接口。

对 Matrix：

- **只读导出值得 P1 实现。** 导出应由 Host 生成，支持受保护的 JSON 与用户可阅读的 Markdown/文本；包含会话标题、可见最终消息、时间、状态和安全摘要，但不包含原始 prompt、推理草稿、密钥、能力审计的敏感字段或未脱敏 memory 值。
- 导出任务要有取消、进度、加密临时文件和到期删除；文件分享前需明确用户动作。导出范围只能是当前身份拥有的会话。
- **导入先不做。** 外部文本可以是“参考资料”，却不应伪造成 Matrix 已执行过的可信历史，也不能自动作为模型指令。未来若需要，导入为带来源标签的只读附件/参考会话，并显式提示“不代表系统历史”。
- **不在 Host 内开启 Operit 风格的 HTTP 聊天服务。** 这会引入局域网暴露、认证、回调 SSRF、跨身份会话和远程写能力授权等高风险。未来外部接入应走独立、经过平台审查的 API 网关与细粒度 OAuth/签名授权，不能从应用内临时 server 演化。

### 4.10 插件菜单与 Prompt Hook

Operit 的 `ChatMessageMenuItemRegistry` 允许插件注册消息菜单项；`PromptHookRegistry` 允许处理器在输入、历史、system、tool、final 等阶段链式改写提示与结果。

这在第三方可扩展聊天工具中有用，在 Matrix 中却与“Host 对策略、能力、审计和身份拥有最终权威”直接冲突。尤其是 history/system/tool Hook 能让插件在执行前后改变模型看到的事实，从而造成不可审计的能力调用。

Matrix 的结论：

- 不引入可变 Prompt Hook 注册表，不允许第三方在请求生命周期中修改 system prompt、历史、工具参数或最终结果。
- 如果将来需要扩展，只接受 Host 签名、版本化、最小权限的**能力描述符**；描述符必须在策略层注册、可审计、fail-closed，并只能追加已定义的结构化上下文，不能任意改写。
- UI 菜单可有受控扩展点，但每一个动作映射到稳定 SDK 方法和权限检查，不能运行插件提供的任意代码。

## 5. 推荐的 Matrix 增量架构

### 5.1 不变的权威链路

```text
Launcher 文本 / PTT final / 唤醒 final
                 │
                 ▼
       ConversationCoordinator  ← 唯一提交、序号、幂等与会话串行化
                 │
       ConversationStore + TaskLink（加密、可恢复事实）
                 │
       AgentRuntimeRepository / PolicyEngine / ToolExecutor
                 │
                 ▼
       ConversationAssistantProjector（安全的最终答复与能力事实轨迹投影）
                 │
            ConversationStore
                 │
        SDK callback / Launcher 投影
                 │
 VoiceResponseBridge（仅对带 voice token 的终态回注现有 TTS）
```

新增聊天功能只能围绕这条链路添加“读取、元数据或显式的新输入”。不得让 `ConversationFragment` 直接调用模型、直接写数据库、直接控制工具或绕开 `VoiceResponseBridge` 播报。

### 5.2 推荐新增的领域对象

以下是后续设计时需要新增或扩展的对象方向，不是要求一次性新增全部表：

| 对象 | 关键字段 | 约束 |
| --- | --- | --- |
| `ConversationPresentation` | `conversationId`、既有标题/归档、`titleOrigin`、`pinned`、既有更新时间、`lastInputChannel` | 以既有 conversation/SDK 字段为基础追加展示元数据，与执行/上下文解耦 |
| `ConversationMessageAnnotation` | `messageId`、owner、favorite、userNote、createdAt | 附属记录；不能修改 `ConversationMessage` 本身；备注默认不入上下文 |
| `SteerInputMetadata` | `inputKind=STEER`、`steerHostUserMessageId`、`steerDeliveryState`、`clientOperationId` | 追加在既有 user message 行，而非创建第二个 task link；主任务终态事务同步收敛其状态，`steerId=messageId` 供运行时去重 |
| `ConversationLineage` | child/parent conversation、fork sequence、种子快照版本、创建者 | 仅从已完成消息建分支；不复制 task link、voice token 或进行中状态 |
| `ConversationQuote` | 新用户 messageId、quoted messageId、可见引用快照 | Host 校验同属同用户会话，assembler 决定是否使用 |
| `CapabilityExecutionTrace` | capability ID、白名单参数摘要、`requestedDisplay`、`verifiedDisplay`、`verificationState`、结果态 | 不是可编辑消息或独立审计表；以 `ConversationTaskLinkEntity.execution_trace_json` 作为写时净化的持久投影，并作为 `ConversationMessage.executionTraces` 追加字段传给客户端 |
| `ConversationTransientUpdate` | message/task ID、阶段、partial 文本、generation、过期时间 | 只在内存/`oneway` 回调中存在；Host 节流合并、退订即丢弃，重启后不恢复、不写最终历史 |
| `ConversationExportJob` | owner、范围、格式、进度、加密临时文件、过期时间 | 可取消、可审计、不可跨用户读取 |

所有表均需要 owner/user scope、SQLCipher、迁移、清理策略和 `clearUserData` 覆盖测试。更重要的是把跨表关系写进迁移：`ConversationMessageAnnotation` 与 `ConversationQuote` 对 message 使用明确的外键 `CASCADE`；`execution_trace_json` 属于既有 task link 行，随 link 删除而天然消失，不能另建无 owner 的轨迹孤表；子会话删除时其 `ConversationLineage` 级联删除，父会话删除时已物化的种子快照仍可让子会话自洽，父引用以 `SET NULL` + “来源已清除”标记收敛；导出任务及临时文件随 owner 清理。新表必须带入与主会话相同的用户清理 epoch，由一次事务/清理编排同时失效，不能留下跨 epoch 的孤儿标记或可见引用。会话正文是用户数据，可以加密保存；审计投影、logcat 与模型上下文仍必须独立脱敏。

### 5.3 SDK 与 UI 的演进原则

- AIDL/Parcelable 只追加字段与新方法，不改变既有字段含义，不把数据库实体直接暴露给客户端。具体而言，`ConversationMessage` 追加 `inputKind`、`steerHostUserMessageId`、`steerDeliveryState` 与 `executionTraces`，`ConversationPage` 追加 Around/After 所需的页面标志；二者都以 schema 递增和新旧端互操作测试保护。
- 会话列表和消息页先完成稳定的只读 API，再加入修改性动作；每个修改接口都带调用用户和明确的目标 ID。
- Launcher 保持单一事实源投影（当前为 `LiveData`），并保留 transient voice/stream 状态与持久最终态的区别。重新绑定时只从 Store 恢复最终态；这不是借机迁移 UI 响应式技术栈。
- 任何“重试”按钮都不是重放旧 task ID，而是创建新提交；对非只读能力先展示策略要求的确认。
- UI 文案把“请求被接受”“正在执行”“结果未知”“已完成”严格区分，特别是不可恢复地避免 `EXECUTION_UNKNOWN` 被误译为“已取消”。

## 6. 分阶段落地计划

### 阶段 0：基线与契约测试

在新增界面前补齐/保持以下测试：会话提交幂等、顺序串行、进程死亡恢复、写操作未知态、`clearUserData` 及其跨表 epoch/级联、voice token 代次隔离、AIDL 兼容性，以及执行轨迹的写时脱敏、requested/verified 分离、分页读取与审计留存解耦。新增 steer 合约测试：仅 `REPROMPT` 可由对话 UI 发起、首次追加只消费一次、Binder 重试不重复消费、宿主终态同步收敛、投递拒绝如实失败、PENDING 恢复后不谎称已并入、进程死亡按宿主恢复结果收敛、当前轮不被 assembler 重复拼入而下一轮可见。任何聊天体验增强都不能使这些测试退化。

### 阶段 1：会话可管理性（P0）

实现会话列表、新建/切换、用户重命名、默认/自动标题来源、稳定的旧页加载与消息定位。此阶段只新增展示与会话元数据，不改变 `ConversationCoordinator` 的执行入口。

验收示例：

- 第一条到达 `COMPLETED`、`FAILED` 或 `EXECUTION_UNKNOWN` 的用户轮次只生成一次标题；`REJECTED`/`CANCELLED` 不触发，且用户改名后自动任务无论何时完成都不得覆盖。
- 在已建立 `(conversation_id, sequence_no)` 复合索引、目标真机完成 10 次预热后，2000 条消息会话中读取 50 条旧页的数据库到 UI 数据可用 P95 不高于 200 ms，且视觉锚点稳定；收到新回复时用户不在底部不会被强制滚动。阈值若因目标设备性能需调整，必须记录基线、调整理由和新的 P95，不可删除量化验收。
- 重启后列表顺序、标题和归档/置顶信息正确，且跨用户不可见。

### 阶段 2：清晰的终态与朗读（P0）

为每一轮展示持久化状态、能力事实轨迹；增加“朗读该助手回复”，并展示“已启用摘要续聊”标记。对**主任务卡片**，阶段 2 的最小范围是**状态枚举 + 终态轨迹**；steer 的“已并入第 N 轮”来自其持久化输入元数据，不属于动态阶段文案。现有 `onMessageStatusChanged` 不携带动态文案，因此主任务 `RUNNING` 只显示既有状态枚举；“正在请求系统音量调整”这类动态阶段文案明确随 `ConversationTransientUpdate` 的另期落地，不得为一个文案提前引入整套跨 Binder 暂态协议。朗读必须从 Host 存储重新取得最终投影文本，并加入当前 TTS 的焦点、watchdog、打断和 response token 治理。

验收示例：

- 取消正在播报的旧回复后，迟到回调不能播报到新会话。
- 写操作进程死亡后 UI 显示“执行结果未知”，不会显示“已取消”或“已完成”。
- `EXECUTION_UNKNOWN` 能显示已尝试的能力、参数摘要及最后可得的核验态；请求值与核验值分列显示，读回不一致时以核验值为准；不展示原始工具 JSON 或模型草稿。
- 宿主轮次执行中追加 `REPROMPT` steer 后，用户消息立即经 `onMessageUpsert` 出现在时间线；只有 `OFFERED` 才标记“已并入第 N 轮”，恢复后仍为 `PENDING` 的记录显示“未确认是否并入”，它没有独立取消入口、没有能力轨迹，宿主终态后与宿主一同收敛；重复点击/重试不会重复影响引擎。
- partial ASR 可显示但永不出现在重启后的历史列表。
- PTT 正在录音/识别时“朗读该回复”不可点击；无 TTS 引擎的目标真机显示安装/启用引导，同时不改变已完成任务的终态。
- 重进会话后，若与 `ConversationCompressor` 同源的纯判定显示下一次续聊会使用摘要，则显示“已启用摘要续聊”；该判定不调用 LLM、不写库，且不泄漏内部上下文。

### 阶段 3：用户组织和安全分支（P1）

实现收藏、可选个人备注、引用回复，以及基于快照的“从此处继续”分支。不要加入历史编辑、删除尾部或候选变体。

验收示例：

- 收藏和备注不影响模型上下文；另一个用户/会话不能访问标记。
- 分支之后父会话继续产生的消息不会进入子会话的种子。
- 从包含过写操作的节点分支后，新的请求仍重新经过策略、确认和幂等流程。

### 阶段 4：导出、搜索与多模态前置评审（P1/P2）

先提供 Host 生成的只读导出，再评审全文检索和受控资产管线。检索索引、临时导出文件及附件内容必须纳入加密、留存与用户清理模型。

## 7. 必须避免的常见实现错误

1. **把 partial 当聊天记录。** partial 会被修订、撤回或因断线丢失；持久化它会制造不存在的事实。
2. **让 UI 决定上下文。** 引用、收藏、附件和分支都只是结构化候选，最终是否进入模型由 Host assembler 决定。
3. **让“再生成”自动重复写操作。** 每一次实际执行都必须有独立 task、策略评估和幂等范围。
4. **用 timestamp 作为消息唯一定位。** Matrix 应以会话内单调 `sequenceNo` + message ID 做定位，避免并发与时钟问题。
5. **用页面隐藏替代删除语义。** 用户看不到不等于系统未执行；审计与最终事实不可被 UI 操作抹去。
6. **把语音入口分裂到多个所有者。** PTT、唤醒、TTS 都必须继续复用单一 VoiceRuntime 与 token/generation 防迟到机制。
7. **把外部导入文本当可信系统历史。** 外部内容只能是带来源的资料，不能继承权限、审计或执行状态。
8. **开放任意插件改 prompt/tool。** 这会绕开策略、身份与审计，属于架构性安全漏洞而非“可扩展性”。

## 8. 最终建议

短期应把 Matrix 对话页从“单一会话的输入窗口”提升为“可管理、可回看、状态可信的会话界面”：先完成列表、标题、分页/定位、终态卡与受控朗读。这些能力直接改善日常使用，且能完整复用现有加密存储、统一执行器与语音架构。

随后以收藏、引用和快照分支增强思考与回溯能力。附件、多供应商识别、候选变体、远程接入和插件扩展都不应因 Operit 已具备而直接复制；它们必须先证明在 Matrix 的 Host 权限、隐私、策略、审计与真机语音链路中仍然正确。

换言之：**参考 Operit 的产品成熟度，不复制它的信任边界。**
