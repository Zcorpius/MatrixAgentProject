# Matrix 对话输入交互增强任务设计

> **状态**：待实施设计稿  
> **编写日期**：2026-09-21  
> **目标**：在不削弱 Matrix Host 的身份、策略、审计、幂等、能力 readback 与语音所有权边界的前提下，吸收 Operit Agent 输入区中真正有价值的交互能力：全屏编辑、统一提交动作、真实运行状态、草稿保护、模型状态展示、受控上下文附件，以及结构化 `@` / `/` 引用。

---

## 1. 结论与范围

Matrix 的对话页已经拥有比普通聊天应用更严格的底座：文字、PTT 与唤醒 final 都进入 `ConversationCoordinator`；同一会话串行执行；用户消息、任务链接、能力事实轨迹和助手结果可恢复地持久化；写能力必须经过 `PolicyEngine` 并以 readback 为准。

因此，本任务借鉴 Operit 的是 **输入体验、状态组织和上下文显式化**，不是把 Operit 的应用内 Agent 控制台搬进 Launcher。特别是，Launcher 不能因输入框多了一个开关，就获得模型密钥、工具权限、车辆能力或敏感系统数据的决定权。

本任务覆盖七项能力：

| 编号 | 能力 | 目标版本 | 核心收益 |
| --- | --- | --- | --- |
| I1 | 全屏编辑输入 | Phase 1 | 车机横屏与长输入可用，不改变提交语义 |
| I2 | 输入动作状态机 | Phase 1 | 发送、追加 steer、取消、PTT 具有真实且一致的行为 |
| I3 | Host 驱动的输入处理状态 | Phase 1 | 用户知道系统正在哪个真实阶段，而非 Launcher 猜测 |
| I4 | 多行、回车发送、加密草稿 | Phase 1 | 长输入与切换会话不会丢失草稿 |
| I5 | 只读模型胶囊 | Phase 2 | 会话所用模型状态清楚可见，配置仍由 Host 管理 |
| I6 | 受控 `+` 上下文附件 | Phase 2 | 用户明确选择的资料可成为可审计模型上下文 |
| I7 | `@` / `/` 结构化引用 | Phase 3 | 引用车辆状态、导航目的地和历史消息，而不是把信息悄悄拼进 Prompt |

本任务明确**不做**：

- 不在对话输入框直接切换模型、编辑模型密钥、开关/排序能力、或设置“自动批准工具”。
- 不增加 `FORCE_TOOL` 的用户入口；它不是普通补充文本，而是直接能力调用，必须独立评审。
- 不复制 Operit 的全屏悬浮语音实现。Matrix 继续复用唯一的 `VoiceRuntime`、PTT、唤醒、焦点、打断、generation 和 TTS 链路。
- 不让通知、屏幕内容、已安装应用、记忆目录等敏感数据因为一个 `+` 按钮而默认流入模型。
- 不把草稿、partial 转写、运行中阶段或模型原始推理文本当作普通聊天历史落库。

---

## 2. 事实基础：Operit 的输入框与 Matrix 的现状

### 2.1 Operit 截图对应的真实代码

截图对应 Operit 的 `AgentChatInputSection`，而不是其功能较少的 Classic 输入样式。关键实现路径如下：

| 截图区域 | Operit 实际实现 | 已确认行为 |
| --- | --- | --- |
| 多行文本与右上全屏 | `AgentChatInputSection.kt` → `FullscreenInputDialog.kt` | 最多 6 行；全屏只编辑并回填，不自动发送 |
| 模型胶囊 | `AgentModelSelectorPopup` | 选择 Chat 功能对应的配置及配置内模型；可调整推理模式与上下文模式 |
| 调节图标 | `AgentExtraSettingsPopup` | 记忆、工具权限、流式输出、朗读、插件等运行参数 |
| `+` | `AttachmentSelectorPopupPanel` | 图片、相机、文件、记忆、屏幕、通知、位置、包/Skill/MCP |
| 右侧主动作 | `InputProcessingState` 分支 | 草稿为空为麦克风；有草稿为发送；运行中变为排队或取消 |
| 提交前 Hook | `ChatInputHookRegistry` | 插件可以允许、阻断、替换或消费提交 |

Operit 的 UI 值得参考，但其输入层可以直接修改应用内模型、工具和上下文。Matrix 不能复制该权力分配。

### 2.2 Matrix 当前可复用的事实

| Matrix 已有能力 | 对本任务的意义 |
| --- | --- |
| `IConversationService.sendText(...)` 与 `appendMessage(...)` | 已区分新主轮次和 `REPROMPT` steer；输入层不应另建 Agent 调用 |
| `ConversationCoordinator` | 唯一提交、幂等、会话串行、终态投影的正确落点 |
| `ConversationMessage` 的 `INPUT_PRIMARY` / `INPUT_STEER` 与投递态 | 能把追加输入显示为事实，且不把它谎报成新任务 |
| `ConversationDomain` 的持久状态与 transient 概念 | 运行中阶段应走内存订阅，终态才进入持久消息 |
| `IVoiceService` 与一次性 voice binding | PTT final 可以自然复用同一条提交入口；Launcher 不接触 PCM |
| `ConversationStore` / SQLCipher / `clearUserData` | 草稿、附件元数据和引用应进入同一用户作用域、epoch 与清理规则 |
| `ModelRuntimeStatus`、`ModelManager`、`SecureModelConfigStore` | 模型胶囊应读取 Host 真相，不从 Launcher 表单猜当前模型 |
| 对话订阅模式与 `CallbackRegistry` | 可复用 oneway 回调、节流、生命周期退订与 `RemoteException` 摘除纪律；正式运行阶段协议仍是本任务首次新增 |

### 2.3 不变的架构原则

1. **一个提交点**：TEXT、PTT final、WAKE final、带引用或附件的文字都只到 `ConversationCoordinator`。
2. **一个执行权威**：仅 Host 经 `AgentRuntimeRepository`、`TaskScheduler`、`PolicyEngine` 和 Capability Provider 执行副作用。
3. **展示历史不等于模型上下文**：附件与引用入模型前仍须经 `ConversationContextAssembler`、预算、压缩和 `ModelSanitizer`。
4. **用户选择不是授权**：用户选择“当前位置”或“导航目的地”只是请求提供某项上下文；Host 仍需判断身份、范围、策略和可用性。
5. **运行态不伪造持久事实**：暂态阶段可丢失；任务终态、能力轨迹、用户输入和助手回复必须可恢复且语义准确。

---

## 3. 目标交互与页面布局

### 3.1 基础布局

输入区在对话列表底部，使用一个视觉上轻量、但不是深色大卡片的容器。它由三层组成：

```text
┌──────────────────────────────────────────────────────────────┐
│ 输入消息…                                             [全屏] │
│ 可显示已选上下文 chip，长文本自动向上扩展，最多 6 行          │
├──────────────────────────────────────────────────────────────┤
│ [GLM-5.2 · 已连接]       [运行状态]        [+] [PTT] [发送] │
└──────────────────────────────────────────────────────────────┘
```

- **文本区**：普通状态下最多六行，超过后内部滚动；内容不为空时右侧始终保留全屏编辑入口。
- **底栏左侧**：Phase 1 没有模型时显示“Host 已连接”或简短状态；Phase 2 替换为只读模型胶囊。
- **底栏中部**：只在任务存在真实运行阶段时出现一句有限状态；没有状态时不占据视觉空间。
- **底栏右侧**：`+`、始终可见的 PTT 次级按钮和文字主动作。即使已有草稿，PTT 仍可按住说话，且绝不清空草稿；主动作在有内容时为“发送”或“追加”。取消不是主按钮，见 §5.3。
- **附件/引用 chips**：在文本区与底栏间一行横向滚动；每个 chip 均有来源图标、摘要、删除按钮和可访问性描述。

布局参考 Operit 的“上方输入、下方模型/设置/附件/主动作”层级，但 Matrix 不放置工具权限或模型编辑入口。车机触控目标最小 48dp，文字对比度满足 Material 可读性要求；键盘弹起时输入区与状态区整体上移，不遮挡最后一条消息。

### 3.2 统一用户意图

输入区只产生以下四类用户意图，而不是直接调用工具：

| UI 意图 | Host 命令 | 可能结果 |
| --- | --- | --- |
| 提交主消息 | `sendText` / 后续 `submitTextOrAppend` | 创建 `INPUT_PRIMARY` 用户消息和新任务 |
| 追加到执行中请求 | `appendMessage` / 后续 `submitTextOrAppend` | 创建 `INPUT_STEER` 消息，尝试 `Steer.REPROMPT` |
| 取消当前任务 | `cancelMessage` | 请求取消；最终显示由 Host 收敛，不能预先写“已取消” |
| 开始 PTT | `createVoiceBinding` → `IVoiceService` | ASR final 回到同一个 Coordinator；partial 只临时显示 |

最终是否走主提交还是 steer，不能由 Launcher 先读一次状态后自行决定。因为在 UI 判断和 Binder 调用之间，运行中任务可能已经终态。

Phase 1 应新增/收敛一个 Host 内部统一方法：

```java
ConversationSubmission submitTextOrAppend(
        String conversationId,
        String text,
        List<String> contextAttachmentIds,
        String draftInstanceId,
        long draftRevision,
        String clientOperationId);
```

它在 `ConversationCoordinator` 的同一会话门控内原子判定：若存在可接收的 RUNNING 宿主任务，走持久化 steer；否则建立主轮次。对外 AIDL 可保留旧 `sendText` / `appendMessage` 的兼容语义，并在 SDK 的较高版本增加 `submitTextOrAppend`；Launcher 新版本只调用新方法。返回值必须明确 `PRIMARY_ACCEPTED`、`STEER_ACCEPTED`、`INVALID_STATE`、`REJECTED` 等结果，禁止让 UI 通过异常文字猜测。

`contextAttachmentIds` 是为 Phase 2A 预留的 append-only 参数，但 **Phase 1 只接受空列表**。Host 对非空列表返回 `INVALID_ARGUMENT`，Launcher Phase 1 固定传 `Collections.emptyList()`，不得静默忽略或自行删除参数。Phase 2A 才允许同 owner/zone 下、状态为 `READY` 的 staged attachment ID；任一 ID 不存在、未 ready、越权或已过期，整次提交必须在创建任务前拒绝。

---

## 4. I1：全屏编辑输入

### 4.1 产品契约

点击输入框末尾的“展开”图标，仅打开一个全屏编辑器：

- 初始值、光标、选区、已选附件和结构化引用与底部草稿完全一致。
- 只有点击“完成”才把全屏编辑结果写回草稿；关闭、系统返回或“取消”保留打开编辑器前的草稿。三种离开方式都**不会提交 Agent 请求**。
- 发送仍由底部输入区的统一提交路径完成；全屏编辑器可有“完成”但不可偷放第二个 `sendText` 实现。
- 在编辑期间收到任务终态、PTT partial 或其他订阅事件，不覆盖用户正在修改的草稿。
- 旋转、进程重建、切换会话后恢复的是同一条草稿记录，见 §7。

### 4.2 Matrix 实现结构

```text
ConversationFragment
  └─ ConversationViewModel.draft(conversationId)  ← 单一 UI 状态
       ├─ InlineInputRenderer
       └─ FullscreenInputDialog
              └─ updateDraft(TextFieldValue)      ← 不发送

ConversationDraftRepository (Launcher SDK adapter)
  └─ IConversationService.saveDraft/getDraft       ← Host 加密持久化
```

`FullscreenInputDialog` 应是无业务副作用的 Launcher 组件。它只接受 `DraftState`、`onDraftChanged` 与 `onDismiss`，不得 import Host 内部类，更不得持有语音或任务对象。

### 4.3 验收

- 2000 汉字输入可在全屏编辑，无卡顿、无截断。
- 打开—修改—关闭后内联输入内容、选区、chips 一致。
- 全屏中按返回不会发送消息；下一次发送只产生一条幂等提交。
- 运行中、接收 partial、切换深浅色或旋转时，不覆盖已编辑草稿。

---

## 5. I2：输入动作状态机与 steer 呈现

### 5.1 为什么不能直接照抄 Operit

Operit 运行中可把文字加入 pending queue。Matrix 的运行中补充文本已具有更精确的语义：`Steer.REPROMPT` 能影响当前 Agent 轮次，并且已有 `INPUT_STEER`、`steerHostUserMessageId` 与 `steerDeliveryState` 作为持久化模型。因此 Matrix 不应再引入第二条“排队聊天”路径。

### 5.2 可见状态矩阵

| 当前草稿 | 宿主任务状态 | 主动作 | 次级动作 | 真实提交结果 |
| --- | --- | --- | --- | --- |
| 空且无附件 | 无运行任务 | 无文字主动作 | 按住说话 | 创建 PTT binding，final 再由 Host 判定主轮次 |
| 非空或有附件 | 无运行任务 | 发送 | 按住说话 | `PRIMARY_ACCEPTED` |
| 空且无附件 | 有可接收任务 | 无文字主动作 | 按住说话、“取消当前执行” | PTT final 由 Host 原子判定为 steer 或新轮次 |
| 非空或有附件 | 有可接收任务 | 追加 | 按住说话、“取消当前执行” | `STEER_ACCEPTED`；不新建独立任务 |
| 任意 | 取消/终态竞争中 | 禁用一次点击并显示短暂处理中 | 保留状态订阅 | 以 Host receipt 为准；不可乐观标记成功 |

PTT 是固定可见的 48dp 次级圆钮，不与文字主动作互斥。用户已经输入草稿后按住 PTT，原草稿保持不变；语音 final 会按 Host 原子判定追加或建立主轮次。这样“打了一半字想改用说的”不需要先清空内容，也符合 Matrix 的语音优先定位。

PTT 的按压反馈是正式契约：`ACTION_DOWN` 立即进入视觉 pressed 态（强调色、缩放/波纹），但只有 Host 确认 capture started 后才显示“录音中”和动态进度环；`ACTION_UP` 请求现有的正常 finish/flush 路径，`ACTION_CANCEL` 才请求取消。按钮须有“按住说话/松开完成”的无障碍描述和触觉反馈，避免用户把它误解为点击开关。任何启动失败、焦点丢失或权限拒绝都要清除 pressed 态并给出受限错误说明。

上述可视化由 Launcher 的 `PttPhase` 阶段机统一投影（`IDLE → ARMING → LISTENING → PROCESSING → IDLE`）：按下即进入 `ARMING`，输入卡内同时出现状态条（“正在开启麦克风…”——冷启动装配期不再只有无解释的底色变化）；Host 首次 `SESSION_LISTENING` 或首条 partial 到达即确认采音（`LISTENING`：按钮圆形底转暖橙 accent、状态条显示“录音中…”与实时转写；转写只上屏、不落库）；松开进入 `PROCESSING`（“已松开，识别中…”），CHANNEL_PTT 用户消息落库或会话终结后收敛回 `IDLE`。阶段转移是纯函数（`nextPhase`，全组合单测覆盖）；语音回调按按压代次（attempt）门卫，旧按压的迟到回调与上一轮迟到的消息回流都不得污染新一轮的进行中状态。“手指按着”（pressed 加深）与“麦克风已在听”（selected 转色）是两个不同事实，按钮配色必须可区分。

取消是有副作用、且可能落 `EXECUTION_UNKNOWN` 的操作，不能和“发送/追加”复用同一个容易误触的圆形主按钮。它应作为运行状态旁的文字次级操作，点击后显示“正在请求取消”，直到 Host 的用户消息状态真正变为 `CANCELLED`、`FAILED` 或 `EXECUTION_UNKNOWN`。取消目标固定为最新未终态的 `INPUT_PRIMARY` 消息；`INPUT_STEER` 没有独立 task link，只能随宿主任务一并收敛，绝不可被当作单独可取消任务。

### 5.3 Steer 的完整语义

新增 UI 不改变既有 steer 设计约束：

1. 输入在 Host 确认运行任务后，先持久化为 `ROLE_USER + INPUT_STEER`，分配正常 `sequenceNo`。
2. 它没有独立 `conversationTaskId`、能力轨迹或标题触发资格；通过 `steerHostUserMessageId` 关联主用户消息。
3. 初始显示“正在并入执行中的请求”；只有 `STEER_DELIVERY_OFFERED` 才显示“已并入第 N 轮”。
4. `PENDING` 经重启恢复后只能说“未确认是否并入”，不能伪造“已并入”。
5. 宿主终态在同一事务中镜像收敛 steer 的状态。写能力被打断时，应保守显示 `EXECUTION_UNKNOWN`。
6. 同一 `clientOperationId` 重放返回既有 steer 记录；运行时以 `(hostTaskId, steerId)` 去重，最多投递一次。

本输入交互只允许 `Steer.REPROMPT`。`FORCE_TOOL` 与 `DEFER` 不属于“发送补充话语”的实现范围：前者若有用户入口必须独立 task link、策略、确认、readback 和能力事实轨迹；后者属于显式终止信号，另行定义用户语义。

### 5.4 PTT 与语音的关系

PTT 不创建另一套“语音版追加”逻辑。Launcher 依旧：

```text
按住 PTT
  → createVoiceBinding(conversationId, clientOperationId)
  → IVoiceService.startUserInitiatedSession(bindingOperationId)
  → partial：仅订阅态 UI
  → final：VoiceConversationBridge / Coordinator.submitTextOrAppend(..., PTT)
```

这让文字、PTT 与 WAKE 的最终文本拥有同一幂等、steer、状态卡和能力审计语义。Launcher 永远不传 PCM、不开新的 `AudioRecord`、也不自行 TTS。

### 5.5 验收

- 连续点击发送、Binder 重试、旋转恢复均不会重复创建任务或重复 offer steer。
- 运行中追加一条文字后，页面立即有可恢复的用户消息，并在 Host receipt 后正确显示 `PENDING/OFFERED/FAILED`。
- 任务完成后追加与任务完成并发，最终只能是“新的主轮次”或明确 `INVALID_STATE`；不会留下悬挂 `RUNNING` steer。
- PTT final 在任务运行中遵循同一原子判定；partial 不落库。
- 取消后的最终 UI 忠实区分 `CANCELLED` 与 `EXECUTION_UNKNOWN`。

---

## 6. I3：Host 驱动的轻量运行状态

### 6.1 目标、观测边界与第一版阶段集

输入区需要告诉用户“系统正在做什么”，但不是展示模型原始思维链或工具 XML。阶段必须由真实 Engine 事件产生；不能把一次原生 tool-calling 模型请求凭空拆成“理解”和“规划”，也不能把 Provider 内部同步执行的 readback 假装成可独立观测的“核验中”。

因此第一版只使用三个**严格可观测**的阶段：

| 阶段 wire 值 | 用户文案 | 精确观测点 | 不声称什么 |
| --- | --- | --- | --- |
| `QUEUED` | 等待执行 | Coordinator 已原子持久化 receipt，尚未从 keyed serial lane 出队 | 不承诺模型已收到输入 |
| `PLANNING` | 正在理解和规划 | `AgentEngine` 每次进入 `LlmPlanner` / `ModelGateway` 前 | 不把同一次模型调用拆成两个虚假阶段 |
| `EXECUTING` | 正在执行“调整媒体音量” | Engine 在每个 `ToolExecutor.execute(...)` 前发布，返回后结束 | 不声称 readback 已完成或成功 |

终态 assistant 消息一旦写入即直接替代运行状态；第一版**不发布 `RESPONDING`**。在原生 tool-calling 路径中，只有模型返回无 tool call 后才能知道这是一轮收尾，而此时终态投影几乎同步发生，预先显示“正在生成回复”只能是启发式。`UNDERSTANDING`、`VERIFYING` 同理延后：只有在模型层出现明确的解析/规划边界，或 `VerifyStrategy`/Provider 暴露 begin/end verify port 后，才可作为 append-only 新 stage 加入。

这些仅是阶段投影，不能包含原始 prompt、reasoning、密钥、未脱敏参数、路径、通知正文或工具原始返回。详细执行结果继续由用户消息下的终态能力事实轨迹承载。

### 6.2 Task 事件端口、传输与生命周期

现有 task 域没有向 conversation 域发布进度的正式端口；debug trace 是调试旁路，明确不能充当量产 UI 数据源。Phase 1 新增 task 窄端口 `TaskProgressSink`（形式参考 `TaskAuditSink` / `TaskMemoryWriter`，但职责仅为受限运行阶段），作为 `AgentEngineConfiguration` 的一次性基础设施装配，而**不是**塞入每个 `PreparedTask` / `AgentRequest` 的 conversation 领域对象。Engine 发布事件时携带既有 `runtimeRequestId`；Coordinator 维护受限映射 `runtimeRequestId → conversationTaskId/conversationId`，在 Host 边界投影成对话阶段：

```text
ConversationCoordinator
  → TaskProgressSink.publish(QUEUED)
  → keyed lane 出队
  → AgentEngine 在每次模型调用前 publish(PLANNING)
  → AgentEngine 在每次 ToolExecutor 前 publish(EXECUTING, capabilityId)
  → terminal projector 写最终消息并 clear(taskId)
```

Provider 内部命令—readback 的同步过程仍归 `EXECUTING` 覆盖；本阶段不要求改造 `VerifyStrategy`。未来若要加入 `VERIFYING`，必须先把 verifier 的开始/结束做成显式、可测试的端口事件，而不是在 UI 侧用时间猜测。

新增 SDK Parcelable `ConversationRuntimeStage`，并以 AIDL append-only 方式给 `IConversationCallback` 新增 `oneway onRuntimeStageChanged(...)`。字段至少包含：

```java
String conversationId;
String conversationTaskId;
long generation;
int stage;
String safeLabel;       // Host 已脱敏、长度受限
long occurredAtMs;
boolean snapshot;       // subscribe 受理后发送的当前快照
```

- Host 在实际状态转换时发布，Launcher 不从 `RUNNING` 自行推断“正在调用工具”。
- 同一 `conversationTaskId` 以 generation 单调递增；Launcher 丢弃旧 generation 和未知 task 的迟到事件。
- Host 合并高频进度，最短 200 ms 推送一次；`QUEUED`、`PLANNING`、`EXECUTING` 的真实转换可立即推送。Binder 失败的 callback 沿用 `CallbackRegistry` 摘除模式。
- `subscribeConversation(...)` 完成兼容性校验并登记 callback 后，Host 从内存 `RuntimeStageRegistry` 读取该会话的当前活跃 task；若存在，立即以 `snapshot=true` 发送一次 `onRuntimeStageChanged`，不存在则不发送。Launcher 在新订阅建立前先清除本地运行阶段，因此不会把旧会话残影留在输入栏。重连只得到这个当前快照，不补历史。
- 运行阶段只存内存，不进入 `conversation_message`；进程死亡后的恢复直接走既有终态对账，不尝试伪造过去的阶段。
- 新增 callback transaction 依赖**强制 contract-hash 锁步协商**：Host 只接受声明支持本协议版本的订阅者；协商失败时在注册 callback 前返回 `SERVICE_VERSION_UNSUPPORTED`，既不调用未知 transaction，也不把旧 callback 放入 `CallbackRegistry`。`SERVICE_VERSION_UNSUPPORTED` 必须 append-only 登记进 `MatrixErrorCode` 并补黄金测试。严格 hash 相等的正常协商路径理论上不会到达此分支；保留它仅作纵深防御，防止未来有人放松协商纪律后把未知 onTransact 误判为客户端死亡并摘掉整个订阅。
- 终态上报后，Host 先推 message upsert，再清空该 task 的 runtime stage，避免输入区短暂显示“正在执行”而消息已经“已完成”。

### 6.3 UI 规则

- 输入底栏同一会话存在多个活跃 task 时，优先显示 keyed lane **当前执行中**的 task；只有没有执行中 task 时，才显示最近入队的 `QUEUED` task。多会话互不污染。
- `EXECUTING` 显示一个 capability 友好名，超长截断；不显示参数。
- 状态文案可点击进入对应用户消息位置，但不跳转到调试 trace 面板。
- 无活跃任务时整块隐藏，不留灰色占位。
- 调试 `matrix.debugTraceUi=true` 的思考/工具面板维持现有独立展示；它不是本运行状态组件的数据源。

### 6.4 验收

- 断开 Host、版本协商失败、订阅重连时，UI 显示明确连接/不可用状态，不把所有问题笼统写成“处理中”。
- 真机调整亮度/音量可依次看到排队、规划、执行；断网模型失败只显示真实终态，阶段自动消失。readback 的最终事实在能力轨迹中展示，而不是假称“核验中”。
- `ConversationCompressor` 的功能型 LLM 摘要调用不发布本轮任务的 `PLANNING` 阶段。它不是用户请求的 Agent 规划回合，且若在工具执行后显示会造成 `EXECUTING → PLANNING` 的误导性倒退；压缩失败/启用事实通过既有摘要续聊标记与终态诊断表达。
- 日志中不出现原始 reasoning、API key、文件路径或附件正文。

---

## 7. I4：多行输入、回车发送与加密草稿

### 7.1 文本编辑规范

- 内联编辑器默认单行高度，可扩展至 6 行；超过后在编辑器内部滚动。
- 设置项“回车发送”默认关闭：关闭时 Enter 换行，开启时 Enter 提交、Shift+Enter 换行；软键盘 action 同步显示“发送”。
- 文字长度上限由 Host 声明，Launcher 在接近限制时提示但不自行截断；最终校验始终由 Coordinator 完成。
- Phase 1 不实现“长粘贴自动转文件”。超长粘贴仍作为文本，并受长度限制；附件管线完成后再独立评估转换阈值、可见预览与用户确认。

### 7.2 为什么草稿应由 Host 持久化

仅放 `ViewModel` 可支持切换页面，却无法覆盖进程回收；放 Launcher 普通 SharedPreferences 又会绕开 SQLCipher、用户作用域和 `clearUserData`。草稿虽不是已提交对话，但同样可能含敏感个人数据，应按产品数据处理。

建议新增 Host 内部表 `conversation_draft`：

```sql
conversation_draft (
  owner_user_id       TEXT NOT NULL,
  vehicle_zone        TEXT NOT NULL,
  conversation_id     TEXT NOT NULL,
  draft_instance_id   TEXT NOT NULL,
  revision            INTEGER NOT NULL,
  text                TEXT NOT NULL,
  selection_start     INTEGER NOT NULL,
  selection_end       INTEGER NOT NULL,
  updated_at_ms       INTEGER NOT NULL,
  PRIMARY KEY (owner_user_id, vehicle_zone, conversation_id)
)

conversation_consumed_draft (
  owner_user_id       TEXT NOT NULL,
  vehicle_zone        TEXT NOT NULL,
  conversation_id     TEXT NOT NULL,
  draft_instance_id   TEXT NOT NULL,
  consumed_at_ms      INTEGER NOT NULL,
  PRIMARY KEY (owner_user_id, vehicle_zone, conversation_id, draft_instance_id)
)
```

- 数据库仍由现有 SQLCipher 管理；草稿不进模型上下文、不进审计、不参与自动标题、也不是 `ConversationMessage`。
- `privacy_epoch` 不进入草稿主键。它由 Host 清数据时整体推进，而草稿只由当前 Binder caller 的 owner/zone 作用域读写；在 epoch 变化时 `clearUserData` 会删除整张草稿表及附件草稿关联。Launcher 不得指定 owner 或 zone。
- 每次开始一份新草稿生成高熵 `draftInstanceId`；同一草稿内每次编辑使 `revision` 单调递增。`ConversationDraftStore.save` 只接受相同 instance 的更大 revision，或替换为新的 instance；它同时拒绝存在于 `conversation_consumed_draft` 的 instance。
- **草稿复活防线必须双层成立。** Launcher 的 `DraftCommandLane` 按 `conversationId` 建立 FIFO 串行器（不同会话可并行），串行 `saveDraft`、`submitTextOrAppend` 与 discard，不能把它们直接交给 `LauncherHostGateway.sdkCalls` 的两线程池并发执行。点击发送时 Launcher 冻结 `(draftInstanceId, revision)`，立刻轮换本地编辑到新 instance；Host 在 submit receipt 的同一事务中只删除**这个冻结 instance**（若当前行已是新 instance 则绝不删除），并无论旧行是否已到达都写入 `conversation_consumed_draft` tombstone。即使一个旧的 debounce save 已在 Binder 路上或未来有人绕过 Launcher lane，它带着旧 `draftInstanceId` 到达时也会被拒绝，不能把已发送文本复活。tombstone 随会话删除/`clearUserData` 级联删除；清理任务对每会话同时施加两个硬上界：最多 **128** 条、最长 **7 天**。正常 Lane 已保证不存在需要跨越该保守窗口的合法旧 save。
- 输入变化以 350 ms debounce 保存，失焦、切换会话、全屏关闭与 `onStop` 立即 flush；每条草稿上限 12 KiB，超过时拒绝后续保存并保留内存编辑值/提示用户缩短。
- 成功创建 `INPUT_PRIMARY` 或成功 `STEER_ACCEPTED` 后由 Host receipt 消费冻结的 `draftInstanceId`；Launcher 在**发起 Binder 调用前**已清空 UI 并创建后续编辑的新 instance，因此 receipt 永远不能误清新输入。失败、超时或 `REJECTED` 不消费草稿，以便用户修订重试。
- 旧版本没有草稿表，迁移只建新表，无历史回填。

### 7.3 AIDL 与数据对象

在 `IConversationService` append-only 新增：

```aidl
ConversationDraft getDraft(String conversationId);
void saveDraft(in ConversationDraft draft);
void discardDraft(String conversationId, String draftInstanceId, long revision);
```

`ConversationDraft` 包含 version、conversationId、draftInstanceId、revision、text、selection 与 updatedAtMs；不携带 owner、密钥、语音 partial 或任意附件字节。草稿是同一 instance 内按 revision 的 last-write-wins，而不是业务提交，故不使用 `clientOperationId` 伪装幂等。SDK 中每个新 Parcelable 字段仍遵循 schemaVersion 容错读取；旧客户端完全不调用新方法。

“回车发送”是纯 Launcher UI 偏好，存入 `matrix-agent-launcher` 的私有 SharedPreferences（例如 `conversation_input_preferences.enter_to_send`）。它不是敏感数据、不影响 Host 任务语义、不进入 AIDL、审计或 SQLCipher；用户清除 Launcher 应用数据时自然恢复默认关闭。

### 7.4 验收

- 在会话 A 输入草稿、切到 B、再回 A，内容与光标恢复。
- 强杀 Launcher 或发生 Activity 重建后重新进入会话，草稿仍恢复；清除用户数据后不会恢复。
- 发送成功只清除已提交会话草稿；Host 拒绝或网络异常后草稿仍在。
- 12 KiB 边界、中文 surrogate pair、Emoji 选区和多行内容均不会产生非法 selection。

---

## 8. I5：只读模型胶囊

### 8.1 第一版行为

模型胶囊显示在输入区左侧，例如：

```text
[ GLM-5.2 · 云端 · 已连接 ]
```

它是状态入口，不是编辑器：点击后只跳转“模型接入”页面，或弹出只读简表（provider、model ID 的安全显示、后端类型、连通性、最近错误码）。API key、endpoint 完整地址、内部诊断、模型 reasoning 配置一律不在对话页呈现。

数据唯一来自 `ModelManager.getRuntimeStatus()` / `ModelRuntimeStatus`。Launcher 不读取模型表单缓存来决定显示内容，也不根据某次成功回复猜测当前模型。

### 8.2 与会话可追溯性的关系

胶囊展示的是“**下一次提交将使用的当前 Host 模型**”。它不追溯地修改历史：

- `SecureModelConfigStore` 当前没有配置版本或指纹；Phase 2 新增其受控的 `configurationGeneration`（每次成功持久化模型配置/激活选择时单调递增）以及 `configFingerprint`。fingerprint 是 Host 对**规范化、非秘密**字段（provider ID、model ID、backend、端点策略类别与配置 generation）的 UTF-8 canonical form 计算 SHA-256；不保存 API key，不把原始 endpoint 放入 fingerprint 的明文输入或 SDK 投影。
- 每一个新 `ConversationTaskLink` 在 Host 接受时写入内部 `ModelExecutionSnapshot`：provider ID、model ID、backend、configurationGeneration、configFingerprint。它是 task link / 审计事实，不是当前配置的可变引用。
- Phase 2 不把 `ModelExecutionSnapshot` 追加到 `ConversationMessage` 或 `ConversationInfo`，也不为聊天页新增“历史模型详情” RPC；聊天页的胶囊只表示当前模型。未来如需展示历史执行环境，必须新增一条独立、脱敏（redacted）的 `TaskDetails` SDK 契约，而不是临时把内部 snapshot 混入消息正文。
- 历史任务的内部审计以 task snapshot 为准，不以当前胶囊为准。
- Phase 1 不支持对话内模型覆写。未来如需覆写，必须新增显式 `ConversationModelOverride`，并在主用户消息、任务 link、审计与 UI 均可见地记录原因和有效范围；不得静默改变正在运行任务。

### 8.3 验收

- 切换 Host 活跃模型后，胶囊仅在 `ModelRuntimeStatus` 确认新模型 ready 后更新。
- Host 未连接、模型未就绪、版本不兼容各显示不同浅色状态，绝不统称 `Connecting`。
- 对话页不存在编辑/展示密钥的路径；历史任务的 model snapshot 不随当前模型切换改变。

---

## 9. I6：受控 `+` 上下文附件

### 9.1 分期与能力范围

Operit 的 `+` 可访问屏幕、通知、位置、记忆目录和包信息。Matrix 只从用户主动选择、来源明确、可以在 Host 控制下净化的材料开始：

| 阶段 | 允许项 | 不允许项 |
| --- | --- | --- |
| 2A | 可安全提取文本的普通文件、用户主动粘贴的文本片段、**可 OCR 出文本的图片** | 多模态视觉理解、自动扫描文件、隐式读取剪贴板、通知、屏幕、位置 |
| 2B | 用户明确选择的“当前位置”或“当前导航目的地”快照 | 位置持续跟踪、后台刷新、未经确认的联系人检索 |
| 后续独立评审 | 屏幕内容、通知、联系人、车辆诊断 | 一键全量系统数据导出 |

每项附件都必须是一个可见的 chip。用户看得到“将什么提供给模型”，可以在提交前删除；Host 保存来源、创建时间、大小、MIME、净化状态和授权范围。

### 9.2 安全的跨进程摄取

系统 picker 给 Launcher 的 URI 授权不应被假定能永久、安全地交给另一个 Host UID。Phase 2A 采用流式摄取：

```text
用户在 Launcher 选择文件
  → Launcher 仅打开只读 ParcelFileDescriptor
  → IConversationAttachmentService.stage(PFD, declaredMime, displayName, operationId)
  → Host 限流复制、嗅探 MIME、扫描大小/页数/像素，写入加密私有存储
  → 返回 attachmentId + 安全摘要
  → Launcher 显示 chip；提交时只传 attachmentId
```

这避免 Host 依赖 Launcher 的临时 URI grant，也避免把任意文件路径经 Binder 传给系统服务。Host 必须：

- 限制单文件、总附件、图像像素、文本提取字符数和解析时间；达到上限返回稳定错误码。
- 当前 `ModelApiClient`、云端协议适配器、`LlmPlanner` 与 on-device codec 均是纯文本 content 栈，**Phase 2A 不向模型传图像字节或视觉 message part**。图片的唯一模型投影是 Host OCR 得到的受限文本，连同“来自图片 OCR、提取器版本、是否截断”的元数据；预览仅供 Launcher 本地展示。
- 因此 Phase 2A 选择图片时，Host 必须先完成 OCR staging：OCR 有可用文本才返回 `READY` chip；无法提取、图片损坏、或文字为空时返回 `UNSUPPORTED_MEDIA_FOR_TEXT_MODEL`，不得以文件名、EXIF 或模糊描述伪装为“图片已理解”。真正的视觉输入需要另立多模态协议演进任务，覆盖 `ModelApiClient`、三种协议 adapter、planner、on-device codec、预算、sanitizer、审计和 UI，不能夹带在附件功能中。
- 对普通文件只提取白名单格式的可用文本，并记录提取器版本与截断状态。
- 不把源文件名、原始路径、附件原文写入 logcat；审计仅记录 attachment ID、类型、大小等级、用户确认和模型使用结果。
- 存储按 owner/zone/epoch 隔离，引用计数与会话/草稿关系明确；清除会话或 `clearUserData` 时回收孤儿二进制。
- 在模型调用前由 `AttachmentContextProjector` 仅生成有限**文本**投影，再经 `ModelSanitizer` 处理；模型绝不直接读取 Host 文件系统。视觉输入不属于本任务的模型协议能力。

建议新增领域对象：

```text
ConversationAttachment
  attachmentId, ownerScope, privacyEpoch, sourceKind, mimeType,
  safeDisplayName, byteSize, createdAtMs, state, retention

ConversationDraftAttachment
  conversationId, attachmentId, ordinal

ConversationMessageAttachment
  messageId, attachmentId, ordinal, modelProjectionDigest
```

草稿附件在成功提交时从 `DraftAttachment` 原子提升为 `MessageAttachment`；提交失败保留草稿关联，避免用户重新选择。不得把附件内容塞进 `ConversationMessage.text`。

### 9.3 位置与导航快照

Phase 2B 不把“位置”实现为通用 API。用户点击后应看见确认 sheet：

```text
将“当前位置（约 XX 路附近，精度约 50m）”提供给本次对话？
[取消] [添加]
```

确认后 Host 从受控位置/导航端口读取**一次性快照**，生成 `sourceKind=LOCATION_SNAPSHOT` / `NAVIGATION_DESTINATION` 的结构化附件。需要记录精度、时间、可用性、来源和是否已降精度；不能让模型要求后自动继续定位。若未来能力需要更多精度或进入系统权限流程，必须再确认并审计。

### 9.4 Phase 2A 的 OCR 前置决策

图片在纯文本模型栈中是否有价值，完全取决于 Host 是否有可用的 OCR；当前 Host 没有 OCR 引擎，不能把“选择图片”写成已可交付能力。Phase 2A 开始前必须完成一项独立的 `OnDeviceOcrPort` 选型与真机验证，候选仅限：系统可用 OCR API、可离线部署的 ML Kit 方案、或独立纳管的 MNN OCR 模型。选型评审至少比较：

- 中文（印刷体、车机截图、小字号）的字符错误率与混合中英文表现；
- 端侧冷启动/热启动延迟、内存峰值、APK/模型体积和 ARM 设备兼容性；
- 是否完全离线、模型许可、更新和删除机制；
- 图像解码与 OCR 超时、取消、异常隔离，以及输出文本的长度/隐私净化边界。

在一个方案被选定并通过中文真机语料验收前，`+` 菜单不展示图片入口，或明确显示“当前模型仅支持文本附件”；不得让用户选择后才让绝大多数图片落为 `UNSUPPORTED_MEDIA_FOR_TEXT_MODEL`。OCR 本身只是一项附件预处理能力，不授予模型视觉理解能力。

### 9.5 验收

- 图片、文件和文本从选择到 chip、提交、历史查看均可追溯；删除 chip 后绝不进入模型投影。
- 假 MIME、超大文件、损坏图片、文件描述符中断、Host 重启均被安全拒绝或收敛，不泄漏路径。
- 同一 attachment 的 Binder 重试不产生多份加密文件；引用删除后由回收任务清理。
- 位置/导航必须每次显式确认；无授权/无可用数据时不创建空附件冒充已添加。

---

## 10. I7：`@` / `/` 结构化上下文引用

### 10.1 用户体验

`@` 和 `/` 不是把文字替换成不可见 Prompt，也不应触发任意系统搜索。它们仅打开一个带明确来源类别的建议面板：

| 触发 | 初始候选 | 生成的对象 |
| --- | --- | --- |
| `@` | 当前会话的已定稿消息 | `ContextReference` chip |
| `/` | 车辆状态快照、导航目的地、可解释的能力/动作模板 | `ContextReference` 或待确认 action 草稿 |

用户选择后，输入区显示类似 `[@上一条回复]`、`[当前导航目的地]`、`[车辆状态快照]` 的 chip，文本光标继续在 chip 后。删除 chip 必须是原子操作：通过删除按钮或退格到边界时删除整个引用，而不是留下一个模型无法识别的半截 token。

### 10.2 数据模型

引用先作为草稿的一部分，提交时冻结为消息附件；使用稳定 ID 而不是只存显示文本：

```java
record ContextReference(
        String referenceId,
        int kind,                 // VEHICLE_SNAPSHOT, NAV_DESTINATION, HISTORY
        String safeLabel,
        String sourceId,          // Host 可解析的受限 ID，非原始正文
        long createdAtMs,
        long expiresAtMs,
        int authorizationScope,
        String snapshotDigest) {}
```

- `safeLabel` 仅用于 UI；真正送模型的内容由 Host 在提交时解析并投影。
- 动态信息必须快照化。比如“当前车辆状态”提交时记录状态版本、时间与字段白名单；下一轮不能因为车辆状态变化而重新解释旧消息。
- 历史内容先执行 owner/zone/权限校验；查不到或已被清除时显示“引用已不可用”，不泄漏其存在性。当前 Matrix 没有“已保存地点目录”：导航目的地只是 capability 参数而非可枚举实体，因此地点引用不在本任务 Phase 3 范围内。未来如要加入，必须先单独定义地点实体的存储、owner scope、生命周期、导入来源与 `place.*` / preference 命名约定。联系人同样待通讯录数据权限、搜索范围和副驾/主驾身份语义单独评审后再加入。
- `NAV_DESTINATION` 当前同样没有现成数据源：demo Provider 的 commandedState 是进程内内存，导航目的地并未作为可查询状态暴露。Phase 3 新增内部窄端口 `NavigationDestinationPort`（形式对齐 `VehicleStateSource`）：首版实现从最近一次**成功核验**的导航 set_destination 命令的 commandedState 派生（Host 内存持有，进程重启后不可用）；真实导航应用集成后替换为实时读取。端口无可用目的地时，`/` 面板将该引用置灰并说明原因，不创建空快照冒充已添加。
- `HISTORY` 的“安全摘要”不是新开一次 LLM 调用：只允许引用当前会话、同一 owner/zone 下**已定稿且可见**的 `ROLE_USER` 或 `ROLE_ASSISTANT` 消息。`HistoryReferenceProjector` 取其 `text`，规范化空白、长度上限 512 Unicode code points，再经既有 `ModelSanitizer` 投影；不携带 execution trace、debug trace、附件正文、系统消息、隐藏记忆或未完成/STEER 消息。引用对象同时保存原 messageId、sequenceNo 和投影 digest，确保之后能解释“引用的是哪条历史”，而不是把 UI 当前内容重新读取进模型。

### 10.3 模型上下文与能力边界

引用只是上下文，不等于命令授权：

```text
用户输入：根据 [/车辆状态快照] 现在能把空调调到 24 度吗
  → Host 冻结车辆状态快照并构建受限 ContextAttachment
  → 模型基于快照提议 climate capability
  → PolicyEngine / 实时车辆状态 / 用户确认 / readback
  → 能力事实轨迹写入该用户消息
```

即使 `/车辆状态` 的引用包含“当前为 P 档”，后续写能力仍在执行时重新读取真实 `VehicleStateSource`；历史快照不能成为绕过实时安全条件的凭据。

### 10.4 验收

- 选择、删除、切换会话、旋转、草稿恢复后引用均稳定，不出现裸 ID 或半截 token。
- 过期、越权、数据清除后的引用在发送前被 Host 明确拒绝或要求重新选择。
- 引用同一对象两次拥有独立 reference ID / 时序；重放同一次提交不产生两次附件投影。
- 引用动态车辆状态后再实际执行写操作，PolicyEngine 仍基于实时车辆状态判断。

---

## 11. 数据、ABI 与模块边界

### 11.1 SDK 演进纪律

所有公开 Parcelable、AIDL 和 callback 均只追加：

| 变更 | 方式 |
| --- | --- |
| 草稿读写 | 新 `ConversationDraft` Parcelable + `IConversationService` 末尾追加方法 |
| 统一提交 | 新 `ConversationSubmission` 结果字段/新方法；旧 `sendText` 与 `appendMessage` 保持兼容 |
| 运行阶段 | 新 `ConversationRuntimeStage` + callback 末尾追加 oneway 方法 |
| 附件 staging | 新窄接口 `IConversationAttachmentService`，不污染原始文本 RPC |
| 消息附件投影 | `ConversationMessage` 追加 `contextAttachments`，由 schemaVersion 容错读取 |
| 引用 | `ContextReference` 只嵌入草稿/附件投影；不让 Launcher 传任意 Provider 对象 |
| 模型执行快照 | 本批只存 `ConversationTaskLink` / 审计内部表，不经 SDK 暴露；未来另设脱敏（redacted）`TaskDetails` 契约 |

当前 `ParcelSchema` 已到 v6；本任务第一批公开 DTO/AIDL 演进统一目标为 **v7**，避免同一发布内零散跳版本。每次 DTO 升级必须：先读取 schemaVersion，再决定是否读取尾字段；写端只写当前 schema；旧客户端获得安全默认值；新客户端面对旧 Host 则禁用对应入口并显示“Host 版本暂不支持”。callback 新 transaction 不采用“未知方法失败后继续”的宽松兼容，而严格依赖 §6.2 的 contract-hash 锁步协商。

### 11.2 包归属

```text
matrix-agent-service-lib
  api/conversation/         Draft、RuntimeStage、Attachment/Reference Parcelable 与 AIDL

matrix-agent-service
  conversation/             submitTextOrAppend、草稿/引用的领域命令与校验
  conversation/persistence/ Draft/attachment/reference Store 接口与实现
  attachment/               PFD staging、加密存储、文本/图像受限投影与回收
  host/rpc/                 caller scope 校验、AIDL Stub、callback 限流
  task/                     继续唯一拥有 ModelSanitizer、PolicyEngine、AgentEngine
  voice/                    继续唯一拥有录音、ASR、KWS、TTS 与 audio focus

matrix-agent-launcher
  data/                     SDK adapter；不访问 Host 私有表或文件
  presentation/             ConversationInputController、Fragment/Dialog/Chip rendering
```

依赖方向必须保持：`Launcher → SDK → Host RPC → conversation/attachment → task narrow ports`。`attachment` 不可直接执行 capability；`conversation` 不可 import Launcher；`voice` 不直接调用 `AgentRuntimeRepository`。

---

## 12. 实施阶段与交付标准

### Phase 0：契约与测试地基

- 为草稿、统一提交 receipt、运行阶段、附件 staging 定义内部领域契约与 SDK append-only 计划。
- 补充 `ConversationCoordinator` 的主提交/steer 竞争、幂等与恢复测试。
- 建立 log redaction 测试：草稿、附件正文、路径、reasoning、模型密钥不得进 logcat。

**退出标准**：新旧 ABI 双向兼容测试通过；无 UI 改动也能验证所有状态转换。

### Phase 1：纯输入体验

- 全屏编辑、多行、回车发送设置、Host 加密草稿。
- `submitTextOrAppend` 统一入口与 steer 可见化。
- PTT 继续绑定既有 VoiceRuntime，固定次级入口与按压反馈，最终文本接入统一入口。
- `TaskProgressSink`、Host 运行阶段订阅与轻量输入状态。

**退出标准**：真机完成“输入草稿 → 切换会话 → 全屏编辑 → 发送/追加 → 取消 → 重启恢复”的闭环；以故意延迟 `saveDraft` 验证已发送文本绝不复活；无重复执行、无悬挂状态、无 PCM 跨 Binder。

### Phase 2：模型状态与安全附件

- 只读模型胶囊、准确连接/模型 ready 状态、内部 `ModelExecutionSnapshot`。
- 文件、图片、粘贴文本附件 staging、chip、提交冻结、加密回收。
- 位置/导航快照仅在显式确认 UI 与 Host policy 完备后进入 2B。

**退出标准**：在图片入口开放前，已选定并集成一项离线 `OnDeviceOcrPort` 实现（系统 API、ML Kit 或 MNN OCR 模型之一），并通过中文/中英混合真机识别质量、冷/热启动、内存、超时和许可验收；随后真机验证 PFD 中断、文件过大、Host 重启、清除用户数据、模型切换和无网络状态；没有明文附件或路径泄漏。

### Phase 3：结构化引用

- `@` / `/` 建议面板、chip 原子删除、Host 解析与提交冻结。
- 首版只接车辆状态快照、受限历史引用，以及由 `NavigationDestinationPort`（§10.2，从最近一次成功核验的导航命令派生）提供的目的地快照；地点目录与联系人等数据源另行评审。

**退出标准**：引用过期、越权、车辆状态变化、重试/重放均有确定且不越权的表现。

---

## 13. 真机验收清单

### 输入与草稿

- [ ] 文字、Emoji、多行、中文输入法、物理键盘 Enter/Shift+Enter 行为正确。
- [ ] 全屏编辑不触发发送；返回、旋转、切换会话、强杀恢复均不丢草稿。
- [ ] 一条草稿成功发送后只删除自己，不影响另一会话草稿。
- [ ] 人为让旧 `saveDraft` 晚于 `submitTextOrAppend` 到达 Host，tombstone 必须拒绝旧 instance；已发送内容不得复活为草稿。

### 任务、steer 与语音

- [ ] 空输入按住说话；有文本时 PTT 仍保持可用且不清草稿；运行中输入显示追加。
- [ ] PTT 仅在 Host capture started 后进入“录音中”视觉态；`ACTION_UP` flush、`ACTION_CANCEL` cancel 的语义可在真机日志验证。
- [ ] 文本追加和 PTT final 均只在 Host 中原子选择主任务或 steer。
- [ ] 取消后只以真实终态显示，写操作未知时明确为结果未知。
- [ ] 语音唤醒、PTT、TTS、音频焦点与打断没有第二套控制器。

### 状态与模型

- [ ] `QUEUED → PLANNING → EXECUTING` 仅由 `TaskProgressSink` 的真实事件驱动；不展示启发式“理解中/核验中/回复中”。
- [ ] 无 Host、协商失败、未 ready 模型和断连呈现不同状态。
- [ ] 量产模式不显示 reasoning / 原始工具参数；调试 trace 仍遵循 `matrix.debugTraceUi`。

### 附件与引用

- [ ] 每项附件/引用在发送前可见、可删、可解释；发送后模型只收到 Host 净化投影。
- [ ] 图片仅在 OCR 文本可用时成为 `READY` 上下文；纯文本模型绝不收到视觉字节。
- [ ] 文件、图片、位置和引用的权限拒绝、超限、失效、Host 重启均安全收敛。
- [ ] `clearUserData` 后草稿、附件、引用、索引和加密二进制无残留。

---

## 14. 最终边界声明

本任务会让 Matrix 的输入区拥有 Operit 级别的编辑效率与上下文可见性，但不会把它变成一个可以在客户端绕过 Host 的“万能 Agent 控制台”。

用户看到并操作的是：**草稿、真实提交状态、明确选择的上下文、当前模型状态，以及可审计的执行结果**。  
Host 继续唯一决定的是：**身份、模型运行、上下文净化、任务调度、策略授权、能力执行、结果核验、语音生命周期与数据清理**。

这条边界是本设计中体验与安全可以同时成立的前提。
