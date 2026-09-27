# LineageOS 平台哔哩哔哩与 QQ 音乐 Tools / Skills 实现方案

## 1. 文档目标

本文给 MatrixAgent 增加 LineageOS 第三方媒体应用能力制定可执行方案。首批目标应用是连接设备上已安装的哔哩哔哩与 QQ 音乐。方案区分一次设备操作的 Tool，以及组织多次 Tool 调用的 Skill，并沿用项目现有的 Capability、PolicyEngine、ToolExecutor、Provider、Observation 与审计链路。

本文同时记录已落地的实现和后续扩展边界。当前真机已验证 QQ 音乐搜索页的 View 树读取和候选歌曲枚举；唯一匹配项需在下一轮收到用户肯定确认后才播放，多候选时需下一轮明确选曲。

## 2. 当前基线与已核实事实

### 2.1 设备和应用

| 项目 | 当前观察 |
| --- | --- |
| 设备 | Mi 9 SE，代号 grus，通过 ADB 连接 |
| 系统 | LineageOS 22.2，Android 15，API 35；构建标识 22.2-20260918_222238-UNOFFICIAL-grus |
| MatrixAgent Host | 包名 com.matrix.agent，版本 0.6.14，运行于 android.uid.system |
| 哔哩哔哩 | tv.danmaku.bili，版本 9.12.0 |
| QQ 音乐 | com.tencent.qqmusic，版本 20.8.0.8 |
| 设备类型 | 当前设备未报告 Automotive feature，是手机运行配置 |

本机 macOS 还装有 QQMusic.app，但本方案的运行目标是连接的 Android 设备，不涉及跨机器控制 macOS 应用。

### 2.2 应用暴露的入口

- 哔哩哔哩声明了接收 https://www.bilibili.com/video/... 的 ACTION_VIEW 入口；包解析结果指向其 IntentHandlerActivity。此事实只证明可以将视频链接交给 App，不证明页面加载或播放成功。
- QQ 音乐声明了 qqmusic: 等自定义 URI scheme、媒体按钮组件及第三方服务。安装声明没有给出可依赖的歌曲搜索、点播参数契约。当前设备上，普通 y.qq.com 歌曲详情 URL 未解析到 QQ 音乐 Activity。
- 检查时系统媒体会话列表没有两款应用的活动会话。需要在两款应用实际播放内容时，核对会话、PlaybackState 动作位、元数据和回读行为。

### 2.3 项目实现现状

- CapabilityRegistry.createDemoRegistry() 注册现有能力，并按区域生成给模型的 ToolDefinition；AgentEngine 负责模型—工具循环。
- PolicyEngine 校验能力白名单、R3 禁止能力、只读任务约束、车辆状态、参数 Schema、区域及部分显式用户意图。
- ToolExecutor 负责每个能力的超时与取消；写操作结果无法确定时使用 EXECUTION_UNKNOWN。
- AppContainer 将系统音量和亮度路由到真实 Android Provider，其余领域能力主要路由到 MockCapabilityProvider。
- 项目没有独立的 Skill 定义、选择、加载或执行层。
- Host Manifest 目前没有 MEDIA_CONTENT_CONTROL，也没有针对两款媒体应用的 queries 包可见性声明。
- DefaultVehicleStateSource 在无 OEM 车辆数据时返回 unavailable。给手机视频 Tool 直接配置 PARKED_ONLY 会导致该 Tool 恒被拒绝。
- CapabilityDefinition 已有 idempotent、maxRetries 字段，但生产执行路径尚未根据这些字段自动重试。RiskLevel 虽有 R2_CONFIRM_REQUIRED 枚举，PolicyEngine 尚无通用 R2 确认闭环。
- AgentBudget 默认最多 8 次迭代、8 次 Tool Call、60 秒总期限；CapabilityDefinition 默认单次超时 3 秒。媒体能力必须明确配置超时，Skill 必须限制步骤数。
- AgentEngine 在 verificationRequired=true、ToolResult 为 SUCCESS 但 verified=false 时，会将结果改为 VERIFICATION_FAILED。因此每个媒体能力都要明确选择 VerifyMethod，不能把“启动请求已提交”误配置为必须回读的播放动作。

相关入口：[CapabilityRegistry](../matrix-agent-service/src/main/java/com/matrix/agent/task/capability/CapabilityRegistry.java)、[AgentEngine](../matrix-agent-service/src/main/java/com/matrix/agent/task/AgentEngine.java)、[PolicyEngine](../matrix-agent-service/src/main/java/com/matrix/agent/task/policy/PolicyEngine.java)、[ToolExecutor](../matrix-agent-service/src/main/java/com/matrix/agent/task/tool/ToolExecutor.java)、[AppContainer](../matrix-agent-service/src/main/java/com/matrix/agent/host/di/AppContainer.java)、[Host Manifest](../matrix-agent-service/src/main/AndroidManifest.xml)。

## 3. 范围与目标

### 3.1 首期交付

1. 检测目标 App 是否安装、启用，以及 MatrixAgent 是否具备读取跨应用 MediaSession 的条件。
2. 打开指定 BV 号的哔哩哔哩视频页面。
3. 打开 QQ 音乐首页。
4. 查询目标应用的活动媒体会话、播放状态、动作位和必要元数据。
5. 在目标会话明确支持时执行播放、暂停、下一首、上一首及 seek，并回读结果。
6. 用内置 Skill 处理“打开这个 B 站视频”“继续播放 QQ 音乐”“暂停 QQ 音乐”“切换媒体来源”等用户任务。
7. 对指定歌手或歌名，读取 QQ 音乐搜索页的 View 树并列出候选歌曲；用户在下一轮明确指定歌名或编号后，再核对页面并点击该歌曲。

### 3.2 后续扩展条件

“按歌名/歌手直接点播 QQ 音乐”只在以下任一条件成立后进入可调用 Tool 列表：

1. 实测当前版本的 QQ 音乐 MediaSession 宣告 ACTION_PLAY_FROM_SEARCH，能正确响应 playFromSearch，并能通过播放状态和曲目元数据确认目标；或
2. 取得有文档、有授权的腾讯音乐正式接入协议。

点赞、收藏、投币、评论、购买、下载和账号管理属于另外的用户账户操作，需要独立的能力契约和真实确认流程。首期不注册这些能力。

## 4. 分层架构

~~~text
用户文字 / 语音
    ↓
AgentRequest + RuntimeProfile + 应用可用性快照
    ↓
SkillSelector → 当前相关 Skill 说明
    ↓
ModelGateway → ToolCall
    ↓
CapabilityInvoker
    ├─ PolicyEngine + CanonicalSchema 校验
    ├─ 审计 PRE_TOOL
    ├─ ToolExecutor（截止时间、取消、写操作未知态）
    └─ 审计 POST_TOOL + ToolObservation
                         ↓
                MediaCapabilityProvider
                   ├─ PackageProbe
                   ├─ MediaSessionPort
                   └─ AppLaunchPort
                         ↓
                  Android / LineageOS
~~~

Tool 是原子动作；Skill 负责选择和排列动作。无论 ToolCall 来自模型还是未来的确定性 SkillRunner，都必须经过同一个 CapabilityInvoker。不能让 Skill 直接调用 Android Provider，以免绕过 Policy、超时、脱敏和审计。图中的 CapabilityInvoker 是计划从现有 AgentEngine 工具循环中抽取的组件，当前代码还没有这个类。

首期不必先开发通用脚本引擎。内置 Skill 由 APK 静态打包，SkillSelector 只把相关流程说明注入本轮 Prompt，实际操作仍通过现有模型—Tool 循环完成。对“切换媒体来源”这类需要可靠恢复的流程，第二期可以增加受限的确定性 SkillRunner。

## 5. Tool 目录

### 5.1 命名和参数原则

首期对模型暴露应用与动作均明确的能力名。底层可以共享 MediaSessionPort，但不提供任意包名、任意 URI、任意 Intent action、任意组件名或任意 shell 命令作为模型参数。这使 CapabilityDefinition 的风险、幂等性、车辆状态约束和审计规则保持精确。现有 system.media.set_volume 管系统媒体音量；新 media.* 管目标应用会话。两者互不替代，转换成模型名后由 CapabilityRegistry 的冲突检查兜底。

| Capability | 参数 Schema | 风险 / 幂等性 | VerifyMethod | timeoutMillis | 成功条件或 Provider 回读 |
| --- | --- | --- | --- | ---: | --- |
| media.qqmusic.get_state | 空对象 | R0 只读 | NONE | 1,500 | 返回目标会话状态和支持动作 |
| media.qqmusic.open_app | 空对象 | R1 写，可幂等 | NONE | 8,000 | 启动 Intent 已解析，startActivity 调用未失败；不证明 App 已到前台 |
| media.qqmusic.play | 空对象 | R1 写，可幂等 | READBACK_FIELD | 6,000 | QQ 音乐会话回读为 PLAYING；BUFFERING 视为待确认 |
| media.qqmusic.pause | 空对象 | R1 写，可幂等 | READBACK_FIELD | 4,000 | QQ 音乐会话回读为 PAUSED |
| media.qqmusic.next | 空对象 | R1 写，不可幂等 | NONE，Provider 自行验证变化 | 2,500 | 可识别的曲目或队列项发生变化 |
| media.qqmusic.previous | 空对象 | R1 写，不可幂等 | NONE，Provider 自行验证变化 | 2,500 | 可识别的曲目或队列项发生变化 |
| media.qqmusic.seek | position_ms：非负整数 | R1 写，可幂等 | READBACK_FIELD | 4,000 | 位置回读达到目标附近 |
| media.qqmusic.search_songs | query：1～64 字符 | R1 写，可幂等 | NONE | 12,000 | QQ 音乐搜索页返回至多 8 条编号、歌名、歌手/专辑候选；不播放 |
| media.qqmusic.play_search_result | index：1～8 的整数 | R1 写，不可幂等 | READBACK_FIELD | 15,000 | 仅接受另一轮用户明确选中的候选；页面重查一致，且会话回读所选曲目为 PLAYING |
| media.bilibili.get_state | 空对象 | R0 只读 | NONE | 1,500 | 返回目标会话状态和支持动作 |
| media.bilibili.open_video | bvid：受限字符串；page：可选正整数 | R1 写，可幂等 | NONE | 8,000 | 视频 Intent 已解析，startActivity 调用未失败；不证明页面显示或视频播放 |
| media.bilibili.search_videos | query：1～64 字符 | R1 写，可幂等 | NONE | 15,000 | 搜索页返回至多 8 条编号候选；不点击视频 |
| media.bilibili.open_search_result | index：1～8 的整数 | R1 写，不可幂等 | NONE | 15,000 | 仅接受另一轮用户明确选中的候选；重新搜索并核对一致后点击；不证明视频已播放 |
| media.bilibili.pause | 空对象 | R1 写，可幂等 | READBACK_FIELD | 4,000 | B 站会话回读为 PAUSED |
| media.bilibili.resume | 空对象 | R1 写，可幂等 | READBACK_FIELD | 6,000 | B 站会话回读为 PLAYING；BUFFERING 视为待确认 |

上表超时是首期配置候选值，阶段 A 测量当前设备和弱网场景下的状态传播延迟后再定稿。所有首期媒体能力显式设置 maxRetries=0；当前生产路径并不消费 maxRetries 或 idempotent，这两个字段分别记录未来重试上限与动作语义，现阶段“不自动重发”依赖 ToolExecutor 无重试实现、Provider 不重发和 Skill 停止规则。Provider 的回读轮询必须占用上表的单次超时预算，并同时检查 request.remainingMillis() 与取消标志；ToolExecutor 只负责外层截断，不能替 Provider 证明播放已生效。play/resume 回读中 BUFFERING 只是中间态；内部回读窗口结束仍未达到 PLAYING（包括持续 BUFFERING）时返回 VERIFICATION_FAILED。Provider 应在外层 timeoutMillis 之前留出结果返回余量；若 ToolExecutor 或任务总截止时间先触发，仍按写操作 EXECUTION_UNKNOWN 处理。

next / previous 比较的是调用前后的曲目 ID 或队列项变化，不是某字段等于命令值，故 VerifyMethod 设为 NONE，验证逻辑留在 Provider。Provider 能确认变化时返回 SUCCESS、verified=true；命令已下发但回读缺少可比较的元数据时返回 VERIFICATION_FAILED，不宣称成功，也不重发；超时或取消仍返回 EXECUTION_UNKNOWN。play / pause / seek 由 Provider 先回读；READBACK_FIELD 再让 AgentEngine 对错误的 SUCCESS、verified=false 结果执行兜底改写。

B 站会话控制以及 QQ 音乐 seek 只在阶段 A 的真实播放检查确认后进入注册表。能力列表只依据发布时已验证的版本/功能配置生成，不按某一瞬间是否有活动会话动态隐藏；运行时无会话由 Provider 返回 NO_ACTIVE_SESSION。这样打开应用之后，后续模型轮次仍能看到已注册的控制 Tool。READBACK_FIELD 在媒体 Provider 中表示“必须回读并设置 verified”；seek 的容差比较由 Provider 实现，不套用现有车辆字段严格等值的 ReadbackFieldStrategy。

首期“媒体来源切换”严格限定在 B 站与 QQ 音乐之间，不增加全设备媒体会话枚举 Tool。上述两个 get_state 只能证明这两个包的状态，不能识别或控制网易云、浏览器、播客等第三方来源。若用户明确指定其它来源，或两款目标 App 均未处于 PLAYING，Skill 按 SOURCE_NOT_CONTROLLABLE 规则停止；不声称知道全局当前媒体来源。首期只有 Prompt 注入，这个码是 Skill 流程中的规范化分支标识，由模型转成用户答复；第二期 SkillRunner 才生成机器可读的 SkillOutcome。将来若要支持所有播放器，必须一并设计只返回包名的 media.sessions.list 和经过授权的按包控制能力，单独增加 list 仍无法暂停第三方 App。

### 5.2 media.bilibili.open_video

输入仅接受 BV 号。首期在 contract/schema 中定义 BVID_PATTERN=^BV[1-9A-HJ-NP-Za-km-z]{10}$、BVID_LENGTH=12、MAX_VIDEO_PAGE=999，供 CanonicalSchema 和 Provider 共用；page 可省略，提供时必须是 1～999 的整数；对象禁止额外字段。实施时可写成：

~~~java
public final class BilibiliVideoSchema {
    public static final String BVID_PATTERN = "^BV[1-9A-HJ-NP-Za-km-z]{10}$";
    public static final int BVID_LENGTH = 12;
    public static final int MAX_VIDEO_PAGE = 999;

    private BilibiliVideoSchema() {}

    public static CanonicalSchema arguments() {
        return CanonicalSchema.object()
                .property("bvid", CanonicalSchema.string()
                        .pattern(BVID_PATTERN)
                        .minLength(BVID_LENGTH).maxLength(BVID_LENGTH).build())
                .property("page", CanonicalSchema.integer()
                        .minimum(1).maximum(MAX_VIDEO_PAGE).build())
                .required("bvid")
                .additionalProperties(false)
                .build();
    }
}
~~~

Provider 根据已验证的 bvid 构造标准 URL：https://www.bilibili.com/video/{bvid}。page 是否映射为查询参数以安装版本的真实行为为准；若该版本未验证多 P 页映射，就暂不接收 page 参数，并从 Schema 中移除该属性。

启动使用 ACTION_VIEW、明确的目标包 tv.danmaku.bili，以及系统解析得到的公开入口。不依赖应用内部 Activity 类名。无匹配 Activity、应用停用、系统拒绝后台启动、用户界面不可用时返回具体错误码。

此 Tool 的目标定义为“提交打开视频入口的请求”，VerifyMethod=NONE。SUCCESS 仅在 AppLaunchPort 已判定存在合法启动上下文、目标包已安装、Intent 可解析且 startActivity 调用未失败时可达；Provider 返回 verified=false，observedState 记 media.dispatch_state=REQUESTED，AgentEngine 不会因 verified=false 改写它。启动资格预检仍不证明页面实际显示；这里不调用 UsageStatsManager，也不声称目标 App 已在前台。若用户说“播放这个视频”，Skill 需要随后读取播放状态；状态无法确认时答复“已提交打开视频的请求，播放状态尚未确认”。

### 5.3 QQ 音乐打开和会话控制

open_app 通过 PackageManager 获取启动 Intent；AppLaunchPort 先确认合法启动上下文，VerifyMethod=NONE，SUCCESS 只表示启动请求已解析并下发，不证明 QQ 音乐已到前台或自动播放。

会话控制通过 MediaSessionManager 获取活动 MediaController，按 getPackageName() 精确匹配 com.tencent.qqmusic。下发前检查 PlaybackState.getActions()。若不存在匹配会话，返回 NO_ACTIVE_SESSION；若同包出现多个无法判定的会话，返回 AMBIGUOUS_SESSION。不能将系统全局媒体按钮作为回退方式，因为它可能命中别的 App。

播放和暂停在目标状态本已满足时可以直接成功；next、previous 不得因超时自动重发。seek 需确认 ACTION_SEEK_TO 且已知媒体时长，限定 position_ms 范围。

真机检查到当前 QQ 音乐会话声明了常规传输动作，但未声明 Android `ACTION_PLAY_FROM_SEARCH`（对应 `MediaController.TransportControls.playFromSearch`）。`media.qqmusic.play` 因而只恢复当前队列，不能拿它代替按条件点播。指定歌手/歌曲走 `search_songs` → 列表展示 → 用户下一轮明确选择 → `play_search_result`。未来若 QQ 音乐提供经授权且可验证的搜索点播接口，可另设计直接 `play_from_search` 能力。

### 5.3.1 QQ 音乐 View 树搜索与明确选曲

`QQMusicAccessibilityService` 由用户在 Android 辅助功能设置中启用，服务元数据将事件包限定为 `com.tencent.qqmusic`，只在 Tool 调用期间主动读取当前前台 View 树；不收集全局事件，不用任意坐标点按。AndroidQQMusicUiPort 先确认前台树属于 QQ 音乐，再通过当前版本实测的控件 ID 定位搜索入口、搜索框、歌曲 Tab、歌曲行以及标题和歌手/专辑。输入搜索词后提交搜索；等待结果页和搜索词一致，并检查至少一条候选标题或详情包含查询词，避免把上一次搜索的列表误报成新结果。页面结构改变、辅助功能未启用、查询无结果或未到前台时返回稳定错误码，不能拿旧候选充数。

搜索结果只包含当前页至多 8 条可见歌曲，按页面顺序编号。Provider 把查询词、候选列表、搜索时的 QQ 音乐版本号、请求 ID 和创建时间暂存在进程内，以 Agent sessionId 隔离；上下文有效期 10 分钟，最多保存 32 个会话。模型可以收到候选以便向用户展示；审计和普通日志对搜索词、曲名、歌手及完整列表脱敏。搜索 Tool 的 SUCCESS 只说明候选已读到，绝不启动播放。

若用户原请求同时明确包含歌手和具体歌名，Provider 只在候选中存在唯一精确匹配时给出 `media.confirmable_index`。Skill 此时直接报出该编号、歌名及歌手/专辑，询问“是否播放这首？”，不要求用户再次从列表挑选。下一轮用户回复独立的“是”“确认播放”“播放吧”等肯定答复时，只允许播放该 `confirmable_index`；“否”“不要播放”等否定答复直接停止。若同名歌曲或版本无法唯一确定，则展示完整候选列表，请用户指定歌名或编号。

播放 Tool 同时要求：本次请求 ID 与搜索请求不同；用户原文明确包含所选歌名或对应序号，或者是对唯一提议项的独立肯定答复；index 落在本会话先前展示的列表中。点击前重新执行同一搜索，检查应用版本、搜索词以及目标行的标题和详情都与先前候选一致，且唯一匹配。点击后只接受 QQ 音乐 MediaSession 回读为 `PLAYING`，并且标题、歌手对应目标候选，才返回 SUCCESS。回读窗口结束仍无法确认时返回 VERIFICATION_FAILED，不自动点击另一首，也不调用恢复当前队列的 `media.qqmusic.play`。用户明确确认前，本 Tool 返回 `SELECTION_NOT_CONFIRMED`。

### 5.4 返回数据和状态语义

Provider 观察值建议统一使用带 media. 前缀的字段；不适用字段省略：

~~~json
{
  "media.app": "qqmusic",
  "media.installed": true,
  "media.session_state": "ACTIVE",
  "media.playback_state": "PAUSED",
  "media.supported_actions": ["PLAY", "NEXT"],
  "media.title": "当前曲目",
  "media.media_id": "可获得时填写",
  "media.position_ms": 42000,
  "media.observed_at_ms": 123456789,
  "media.error_code": null
}
~~~

语义约束：

- SUCCESS：只用于该 Tool 所定义的目标已达到。open_video/open_app 的目标是“启动请求已解析并下发”，不包含前台或播放验证；play 的目标是“进入播放状态”。
- EXECUTION_FAILED：未下发或明确被系统拒绝，例如未安装、无权限、不支持动作。目标包安装/启用状态由 Provider 的 PackageProbe 在执行时检查；缺失时返回 EXECUTION_FAILED + media.error_code=APP_NOT_INSTALLED。对所有会话写能力（play、pause、resume、seek、next、previous），只有传输命令尚未下发时才用此状态并记录 media.dispatch_state=NOT_SENT；命令下发后无法证实结果则按 VERIFICATION_FAILED 或 EXECUTION_UNKNOWN 处理。open_app/open_video 的 REQUESTED 表示启动请求已下发。
- VERIFICATION_FAILED：已下发但在限定回读窗口内不能证明目标状态；用户答复不能写成完成。
- EXECUTION_UNKNOWN：写操作超时或取消，无法判断目标应用是否稍后完成；沿用 ToolExecutor 的未知态语义。已收到 Provider 结果但缺少可比较元数据属于 VERIFICATION_FAILED。
- POLICY_REJECTED：静态 capability 白名单、运行配置、车机视频限制或参数违反策略，Provider 不应被调用。目标包是否安装不由 PolicyEngine 同步查询。

新 MediaCapabilityProvider 使用 observedState 的 media.error_code 承载稳定诊断码，例如 APP_NOT_INSTALLED、MEDIA_CONTROL_UNAVAILABLE、NO_ACTIVE_SESSION、ACTION_UNSUPPORTED、AMBIGUOUS_SESSION、UI_ACTION_REQUIRED、BACKGROUND_LAUNCH_BLOCKED、PLAYBACK_NOT_VERIFIED。SOURCE_NOT_CONTROLLABLE 是双应用切换 Skill 的流程结果，不伪装成某次 Provider 执行错误。现有 SystemControlCapabilityProvider 将部分诊断码放在 message 文本中；媒体新实现不沿用该模式。面向模型和用户的自然语言消息可以变化，Tool 业务分支只依据 media.error_code。

media.title、media.media_id、搜索词和观看内容都可能体现个人偏好。模型上下文可获得完成任务所需的信息；审计与日志使用 CapabilityDefinition 的敏感字段和固定消息模板脱敏，不能依赖 BUILTIN_CREDENTIAL_KEYS 自动识别媒体标题。

例如 media.qqmusic.get_state 的能力定义应配置：

~~~java
.auditMessageTemplate("已查询 QQ 音乐播放状态")
.auditFailureMessageTemplate("QQ 音乐状态查询失败，详情见 media.error_code")
.sensitiveObservedField("media.title", "<media>")
.sensitiveObservedField("media.media_id", "<media>")
.auditObservedAllowlist(
        "media.app", "media.installed", "media.session_state",
        "media.playback_state", "media.error_code")
~~~

B 站 open_video 的 bvid/page 参数应按需标记敏感，成功和失败 message 均使用不回显观看内容的固定模板。AuditRedactor 对 allowlist 以外的 observedState 字段会遮蔽，但每个媒体能力仍需显式配置可放行的诊断字段。

## 6. Android / LineageOS 适配

### 6.1 包可见性

Host targetSdk 为 36，应在 Manifest 中声明精确 queries：

~~~xml
<queries>
    <package android:name="tv.danmaku.bili" />
    <package android:name="com.tencent.qqmusic" />
</queries>
~~~

PackageProbe 使用 PackageManager 检查安装与启用状态。可以记录版本用于诊断，但可用动作以实际 MediaSession 和 Intent 解析结果为准。若正式产品需要防止同包名被非预期安装源替换，可增加签名证书校验；受信任证书列表必须来自产品发布流程，不能猜测或在运行时接受模型提供的指纹。

### 6.2 跨应用 MediaSession 权限

Android 15 的主路径使用 MediaSessionManager.getActiveSessions(ComponentName)；getActiveSessionsForPackage 是更高 API 级别的接口，不能作为当前 API 35 设备上的实现。读取其他应用会话需要 MEDIA_CONTENT_CONTROL，或用户已启用的 NotificationListenerService。

实施顺序：

1. Manifest 声明 android.permission.MEDIA_CONTENT_CONTROL。
2. 在当前 LineageOS 构建安装新 Host 后，检查该权限是否实际授予，并调用 API 验证。Host 使用 system UID 不等于可以省略这一步。
3. 若正式分发环境无法获得该权限，再加入经用户显式启用的通知监听方案；只提取目标媒体会话所需信息，不保存无关通知。
4. 权限不可用时，状态和控制 Tool 返回 MEDIA_CONTROL_UNAVAILABLE；应用打开能力仍可单独工作。

### 6.3 后台 Activity 启动

Android 15 对后台 Activity 启动有限制。AppLaunchPort 需要区分：

- Launcher Activity 当前可见且请求由用户直接触发：从可见界面启动目标应用。
- 默认语音助手会话有合法的用户交互上下文：按该上下文的真实启动资格发起，并检查启动请求是否被系统拒绝。
- 仅后台 Host 服务在运行且无启动资格：返回 UI_ACTION_REQUIRED；交给 Launcher 或用户点击的通知操作执行。

Intent.FLAG_ACTIVITY_NEW_TASK 只解决任务栈要求，不能替代后台启动资格。不能仅因 startActivity 未抛异常就宣称目标页面已显示。

当前生产部署有一个明确例外：Host 的 Manifest 已声明 `android.permission.START_ACTIVITIES_FROM_BACKGROUND`，在当前平台签名、system UID 的 LineageOS 真机安装中也已实际授予（`dumpsys package com.matrix.agent` 显示 `granted=true`）。因此 `AndroidAppLaunchPort.requireLaunchContext()` 在权限检查处直接返回；上面的 `UI_ACTION_REQUIRED` 分支在这一生产构建中不可达。上述三情境仍用于未获得该权限的未来构建或权限发生变化时的退化路径；应用启动仍只保证请求已受理，不证明目标页面已显示。

### 6.4 手机与车机策略：选定动态门控

本方案选择 PolicyEngine 内的 MediaUsagePolicy 动态判定，**不按 profile 构造两份 CapabilityRegistry**。所有 media.bilibili.* 能力定义的 requiredVehicleStates 留空，避免现有静态 checkVehicleState 在 PHONE 上读取 unavailable 后恒拒。车机视频限制由 MediaUsagePolicy 针对 AgentRequest 的 RuntimeProfile 和同一请求中的可信 VehicleState 快照组合判定。

PolicyEngine 的判定顺序为：能力已注册 / R3 → 显式意图 → readOnlyHint 写操作约束 → MediaUsagePolicy → 现有静态 checkVehicleState → Schema 和参数。MediaUsagePolicy 只处理媒体专属规则；其他车控能力继续走静态 checkVehicleState。MediaUsagePolicy 持有不可变、逐项列举的媒体能力分类表：media.bilibili.open_video、media.bilibili.resume 归 VIDEO_RESTRICTED，media.bilibili.pause 归 SAFE_WHILE_MOVING，get_state 是只读；未来增加 B 站视频 seek 时必须归 VIDEO_RESTRICTED。启动时校验所有已注册的 B 站媒体写能力都有明确分类，漏项视为配置错误，不靠包名前缀或分散的 if-else 默认放行。

profile=AUTOMOTIVE 时，VIDEO_RESTRICTED 必须满足 PARKED_ONLY；VehicleState 为 unavailable 或不满足谓词时返回 PolicyDecision.denyCapability，即 CAPABILITY_REJECTED，本轮不再尝试同一能力。pause 与 get_state 不需要停车。profile=PHONE 时，不给媒体能力附加车辆状态要求。profile=UNKNOWN 时拒绝视频写能力，避免由缺失的 profile 默认值放行。

RuntimeProfile 不能由用户文本、模型 ToolCall 或外部 Binder 请求指定。新增 RuntimeProfileResolver，由 Host 组合根装配，结合 PackageManager.FEATURE_AUTOMOTIVE 和受信任部署配置判定 PHONE、AUTOMOTIVE 或 UNKNOWN；冲突或无法判定时为 UNKNOWN。当前 Mi 9 SE 得到 PHONE。未来乘客独立屏幕的移动中视频策略需要单独设计，首期不放开。

### 6.5 RuntimeProfile 的请求注入链路

在 identity/ 下新增不可变 RuntimeProfile 枚举，并给 AgentRequest 增加 runtimeProfile 字段、getter 和 Builder.runtimeProfile()。直接构造的兼容默认值为 UNKNOWN，媒体视频写能力在 UNKNOWN 下拒绝；生产请求必须显式注入。现有 AgentRequest.currentVehicleState 缺省为 satisfyAllPredicates()，属于历史兼容的乐观默认；新增 RuntimeProfile 不沿用该默认，防止漏注入时放行视频写能力。

Host 的 AppContainer 创建 RuntimeProfileResolver，经 TaskRuntimeGraph.Dependencies 传给 TaskRuntimeGraph，再传给 AgentRuntimeRepository 和 task/scheduler/TaskRequestFactory。TaskRuntimeGraph 是 AgentRuntimeRepository 的实际构造点；TaskRequestFactory 的两个入口都在真正构造 AgentRequest 时调用 resolver.snapshot()：

1. newRequestBuilder：覆盖普通文字、语音和 executeForSession 等运行时入口。
2. newPreparedRequestBuilder：覆盖对话任务在 keyed lane 出队后的执行入口。

两条路径随后都调用 Builder.runtimeProfile(snapshot)。这与每请求注入 vehicleStateSource.snapshot() 的现有模式一致。ConversationTaskSubmitter 的提交期 ClassificationSnapshot 仍只保存 readOnlyHint / memorySaveAllowed；运行配置和车辆状态在任务真正出队时取快照，不由外部对话提交参数伪造。AgentEngine、SkillSelector 和 PolicyEngine 均读取同一个 AgentRequest，避免同一任务内 profile 漂移。

## 7. Skill 设计

### 7.1 打包格式

建议将内置 Skill 随 APK 一起签名发布：

~~~text
matrix-agent-service/src/main/assets/skills/
  bilibili-open-video/
    manifest.json
    SKILL.md
  qqmusic-control/
    manifest.json
    SKILL.md
  media-source-switch/
    manifest.json
    SKILL.md
~~~

manifest.json 仅描述 id、版本、触发条件、目标 App、依赖的 Capability、可用运行配置及说明文件。SKILL.md 仅描述任务步骤与结果措辞，不包含直接执行代码或高于 PolicyEngine 的权限。

示例：

~~~json
{
  "id": "qqmusic-control",
  "version": 1,
  "target_app": "qqmusic",
  "required_capabilities": [
    "media.qqmusic.get_state",
    "media.qqmusic.play",
    "media.qqmusic.pause"
  ],
  "profiles": ["PHONE", "AUTOMOTIVE"],
  "instructions": "SKILL.md"
}
~~~

SkillCatalog 加载并验证清单：id 唯一、版本合法、依赖能力已注册、文件只从 APK assets 读取。SkillSelector 根据用户意图、目标包是否安装、两款目标应用的活动会话和运行配置选择相关 Skill。每轮只注入匹配的少量说明，避免把全部 Skills 塞进系统 Prompt。

包安装与会话可用性来自后台刷新、不可变的 MediaAvailabilitySnapshot；建议包状态 TTL 为 30 秒，会话状态 TTL 为 5 秒，并用包变更广播和 MediaSessionManager.addOnActiveSessionsChangedListener 回调主动失效。刷新须限频；无活跃媒体任务时注销会话监听并暂停会话刷新，只保留包状态级监听。新请求的用户文本意图命中应用名、BV 号或媒体动作词时，就重新注册会话监听并异步预热快照；不以会话状态本身作为注册前置条件。首轮快照仍为 UNKNOWN 时按可用性未知降级。Prompt 组装和 SkillSelector 只读内存快照，不同步发起 Binder 查询；Provider 执行时重查安装、会话与动作，避免 TTL 内的状态变化被当成授权依据。

### 7.2 三个首期 Skill

**打开 B 站视频**

1. 从用户文本提取 BV 号或严格限定的标准 B 站视频 URL。
2. 从可用性快照获取安装提示；运行配置由 PolicyEngine 判定，安装和页面启动条件由 Provider 执行时确认。
3. 调用 media.bilibili.open_video。
4. 如确实存在 B 站媒体会话，再调用 get_state 核验播放；没有会话时只说明“已提交打开视频的请求”。
5. 无法确定唯一 BV 号时请求用户给出链接，不把关键词误当 BV 号。

**控制 QQ 音乐**

1. 对当前队列的播放、暂停、切歌或 seek，先调用 get_state，确认唯一会话和动作位后执行对应 Tool；写 Tool 自带回读，不能确认时保持未知或验证失败表述。
2. 用户指定歌手和具体歌名时，调用 search_songs；若返回 `media.confirmable_index`，只展示唯一匹配项并问“是否播放这首？”。否则逐项反馈编号、歌名及歌手/专辑，请用户选歌。该轮不得调用 play 或 play_search_result。
3. 用户在下一轮回复“是”等肯定答复时，仅允许对先前唯一提议的 index 调用 play_search_result；用户也可以明确回复歌名或第几首。Provider 会核对用户原文、搜索上下文及当前页面，并回读所选歌曲的播放状态。否定答复、上下文过期或结果变化时停止，不猜测另一首。
4. 无活动会话时可以打开 QQ 音乐，但不能向其他播放器发送全局媒体键；`play` 不得用于模拟指定歌曲点播。

**在 B 站与 QQ 音乐之间切换媒体来源**

1. 先调用两款应用各自的 get_state；任何一个状态因权限、超时或歧义而不可判定时，停止切换并报告原错误。只有其中恰好一个处于 PLAYING、另一个是用户指定目标时，才能确认“原来源”。用户明确指定第三方来源，或两款均未播放/均在播放时，返回 SOURCE_NOT_CONTROLLABLE 或请求明确目标；不能宣称知道全设备当前播放来源。
2. 目标 App 没有可播放会话时停止自动切换，请用户先手动准备目标内容；不要在原来源仍为 PLAYING 时调用目标 open_app/open_video，因为目标 App 可能自动开始播放，造成两路声音。
3. 目标会话已就绪时，记录原来源状态，调用原来源 pause。仅当 pause 返回 SUCCESS 且 verified=true、回读为 PAUSED 时，才调用目标 play/resume。pause 返回 VERIFICATION_FAILED、EXECUTION_UNKNOWN 或其它未确认结果时立即停止，不启动目标播放，并告知用户“原来源未能确认暂停，为避免两路同时播放，未启动目标播放；请手动确认”。两个写 Tool 的 Provider 各自完成回读，Skill 使用其 ToolResult，不重复 get_state。
4. 目标 play/resume 返回 EXECUTION_FAILED 且 media.dispatch_state=NOT_SENT 时，先再次调用原来源 get_state；只有原来源先前确实处于 PLAYING、暂停结果已确认、当前仍有可播放会话且动作位支持时，才调用一次原来源 play/resume 恢复，并单独报告恢复结果。目标返回 VERIFICATION_FAILED 或 EXECUTION_UNKNOWN 时，不自动恢复原来源：目标可能稍后进入 PLAYING，需报告两边的已确认状态并请用户手动确认，避免恢复出双路播放。

正常切换最多 4 次 Tool Call（两次 get_state、一次 pause、一次 play/resume）；目标无会话时最多再加一次 open；目标命令明确未下发时的状态检查与恢复使总数最多 6 次。Skill 设 6 次软上限，留 2 次给 AgentBudget 默认的 8 次总 Tool Call 上限，迭代也不能超过默认 8 次。Provider 内的动作回读已包含在写 Tool 内，Skill 不再为每个成功动作额外调用 get_state。若预算不足以完成余下步骤，停止并如实报告当前已确认状态。

“下一首”未指定应用、且 B 站与 QQ 音乐都存在可控会话时，Skill 应请求明确目标；首期不推断其它 App 的播放状态。

### 7.3 确定性执行的第二阶段

明确的“播放某歌手的某首歌”已由 `QQMusicWorkflowGateway` 做窄范围确定性决策：只解析歌手和歌曲都明确的请求、唯一匹配项的肯定/否定答复，以及本轮 QQ 搜索/选曲 Tool 的观察值；其余请求转交原模型。它只产出 ModelTurn，实际 Tool 仍由 AgentEngine 经 PolicyEngine、ToolExecutor、Provider、审计和回读执行。`MediaWorkflowIntentClassifier` 对这两类明确命令直接标为写意图，避免提交前额外请求云端模型；其他命令保留云端分类及关键词兜底。这样搜歌、确认问句及确认后的答复无需为每一步再请求云端模型；上下文丢失时也立即提示重搜，不让模型在剩余期限内反复猜测。重复原请求不算肯定确认，Provider 再次核验用户下一轮的明确答复或序号。

跨应用切换若需稳定的失败恢复，仍可增加 SkillRunner 和受限 SkillStep 类型（TOOL_CALL、BRANCH、STOP）。SkillRunner 必须调用从 AgentEngine 中抽取的 CapabilityInvoker；不可直接调用 MediaCapabilityProvider。这样一次 Skill 内每一步仍拥有 PolicyDecision、PRE_TOOL / POST_TOOL 审计、截止时间、取消与结果回读。

## 8. 代码落点

| 位置 | 预期工作 |
| --- | --- |
| task/capability/CapabilityRegistry.java | 增加媒体能力定义；建议抽出 registerMediaCapabilities，避免继续膨胀 createDemoRegistry |
| contract/schema | 将 BVID_PATTERN、BVID_LENGTH=12、MAX_VIDEO_PAGE=999 封装为共享常量，并提供 BV 号、page、position_ms 的严格 Schema |
| identity/AgentRequest.java 与 identity/RuntimeProfile.java | 给每次请求保存可信 RuntimeProfile 快照；直接构造时默认 UNKNOWN |
| host/di/RuntimeProfileResolver.java | 由 Automotive feature 和受信任部署配置解析 profile；AppContainer 装配 |
| task/scheduler/TaskRequestFactory.java | 普通请求和对话任务两个 Builder 入口均注入 profile 快照 |
| task/policy/PolicyEngine.java | 增加 MediaUsagePolicy；在现有静态车辆谓词前检查车机视频条件并归 CAPABILITY 拒绝；不查询目标包安装状态 |
| task/AgentEngine.java | 首期接入 Skill 说明；第二期抽取 CapabilityInvoker |
| task/prompt/PromptContextAssembler.java | 接入选中 Skill 的简短 PromptSegment |
| task/AgentEngineConfiguration.java | 承载一次性注入的 SkillCatalog / SkillSelector，旧构造器保持安全默认值 |
| task/skill/SkillCatalog.java 与 task/skill/SkillSelector.java | 校验签名随包发布的 Skill 清单，基于请求和可用性快照选择说明 |
| task/skill/QQMusicWorkflowGateway.java | 明确歌曲请求及下一轮确认的快速确定性决策；不直接操作应用，普通请求透传模型网关 |
| host/di/AppContainer.java | 装配 Android 媒体适配器，并将所有新能力显式路由到 MediaCapabilityProvider |
| host/di/TaskRuntimeGraph.java | 在 Dependencies 中接收 RuntimeProfileResolver、SkillCatalog 和 SkillSelector；Repository 构造时传递 resolver，engineFactory 创建 AgentEngineConfiguration 时接入 Skills |
| platform/media/ | 增加 PackageProbe、MediaSessionPort、AppLaunchPort、MediaAvailabilitySnapshot 及 Android 实现；QQMusicAccessibilityService 与 AndroidQQMusicUiPort 只处理 QQ 音乐 View 树搜索及明确选曲，MediaCapabilityProvider 保存按 session 隔离的短期候选上下文 |
| model/LlmModelGateway.java 与 model/LlmPlanner.java | 结构化 JSON 兼容路径逐轮消费工具观察值和选中 Skill，每轮最多决定一个后续动作；搜索结果必须能反馈到用户，而不能在第一次 Tool 后直接合成完成答复 |
| model/ModelApiClient.java 与 task/ModelCallExecutor.java | 兼容路径把 CancellationToken 和请求截止时间传到 HTTP transport；到期引发的取消归 TIMEOUT，不误报用户主动取消 |
| intent/KeywordIntentClassifier.java | 可显式加入播放、暂停、继续看、切歌、快进等词，提高写意图分类置信度；未命中时现有规则已保守地按写处理 |
| intent/LlmIntentClassifier.java | 同步扩充云端分类 system prompt 的 READ/WRITE 示例，例如“查 QQ 音乐在放什么”为 READ、“播放 QQ 音乐 / 暂停 B 站视频”为 WRITE；云端 FallbackIntentClassifier 仍以 Keyword 兜底 |
| intent/MediaWorkflowIntentClassifier.java | 对明确歌手+歌曲请求和简短确认/否定答复直接给写意图，跳过高延迟云端分类；其它输入委托原分类器 |
| voice/CapabilitySpeechNames.java | 为所有实际注册的媒体能力补中文播报名，例如“播放 QQ 音乐”“暂停 QQ 音乐”“打开哔哩哔哩视频”“查询播放状态”，避免语音进度回落为“这项操作” |
| src/main/AndroidManifest.xml 与 res/xml/qqmusic_accessibility_service.xml | 增加 queries、媒体会话访问权限及包限定的辅助功能服务；辅助功能需用户在系统设置中显式启用 |
| src/main/assets/skills/ | 增加签名随包发布的 Skill 清单与说明 |

MediaCapabilityProvider 构造时验证“注册的媒体能力集合”和“实际可执行能力集合”一致，避免遗漏路由后悄悄落到 MockCapabilityProvider 的 unsupported 分支。IntentClassifier 的关键安全边界仍是 PolicyEngine 的 readOnlyHint+writeOperation 拒绝：例如“在放什么歌”含查询词时被归为只读，模型若误发 play 会被拒绝，这是预期行为。

## 9. 安全、幂等与审计规则

1. Tool 参数不能携带任意包名、Intent、组件名、shell 命令或未限定的 URL。
2. 模型给出的能力名、BV 号和 seek 位置均属于不可信输入，Schema 与 Provider 两层都应校验。
3. 权限、应用安装、会话存在及动作支持属于动态事实，由 Provider 在执行时重查；PolicyEngine 负责静态能力与基于 AgentRequest 快照的 profile/车机视频限制，不在策略判定中同步调用 PackageManager。
4. 所有首期 media.* 定义 maxRetries=0；play、pause 可按目标状态幂等处理，next、previous 不自动重试。ToolExecutor 写操作超时/取消时延续 EXECUTION_UNKNOWN 语义。
5. 日志仅记录能力名、目标应用、状态码、耗时与参数形状；标题、搜索词、URL 以及播放历史不能原文进普通日志和审计。每个媒体能力显式配置 AuditRedactor 的 sensitiveObservedField、auditObservedAllowlist 及消息模板。
6. 任何 Skill 都不能把“Intent 已发出”描述为“内容已播放”。
7. R2_CONFIRM_REQUIRED 尚无通用执行门。账号操作若未来进入范围，应先实现真实确认决策与恢复流程，再注册相应能力。

## 10. 实施阶段与验收矩阵

### 阶段 A：能力探测

- 在两款应用实际播放内容时采集 MediaSession 列表、包名、动作位、播放状态和元数据。
- 验证 MEDIA_CONTENT_CONTROL 的实际授权结果。
- 验证 B 站标准视频链接在当前版本的打开结果。
- 验证 QQ 音乐冷启动、前台和后台启动行为。
- 测量两款应用在正常网络与弱网下 play/pause/seek 命令到 MediaSession 状态稳定的延迟分布，包括 BUFFERING → PLAYING；另测 next/previous 后曲目 ID 或队列项元数据变化延迟。据此校准 §5.1 的所有候选回读 timeoutMillis（包括 next/previous 的 2,500 ms）和 Provider 轮询间隔，并确保单次回读仍受 AgentRequest 总截止时间约束。
- 调查 QQ 音乐 playFromSearch 是否公开为会话动作；只有完成点播和回读验证才进入产品范围。
- 读取当前 QQ 音乐版本搜索页 View 树，核对搜索入口、输入框、歌曲 Tab、歌曲行及标题/歌手详情的控件 ID；在两个不同查询词之间反复切换，排除旧结果残留。

交付物：设备能力记录与被证实可用的 Tool 清单。探测失败的能力不向模型宣告可用。

### 阶段 B：原子 Tools

实现三种 Port、Provider、Capability 定义、策略、权限、回读和脱敏。重点验证以下场景：

| 场景 | 预期 |
| --- | --- |
| 目标 App 未安装、停用或更新 | 返回稳定错误码；不路由到其他 App |
| 媒体权限未授予 | 状态/控制返回 MEDIA_CONTROL_UNAVAILABLE |
| 无活动会话 | 返回 NO_ACTIVE_SESSION；不发送全局媒体键 |
| 同包多个会话 | 只能明确选中时执行，否则返回 AMBIGUOUS_SESSION |
| 动作位不支持 | 返回 ACTION_UNSUPPORTED；不尝试私有接口 |
| next 超时或取消 | 不自动重发，结果保持未知态 |
| next/previous 已下发但曲目或队列元数据在回读窗口内不可比较 | 返回 VERIFICATION_FAILED，不自动重发；阶段 A 的元数据延迟实测用于调整 2,500 ms 候选值 |
| 所有 media.* 能力触发超时 | 当前执行路径没有重试器；maxRetries=0 仅作契约配置，Provider/Skill 不重发，写操作保持未知态 |
| B 站 URL 无效 | 参数拒绝，App 不被启动 |
| Android 拒绝后台启动 | 返回 UI_ACTION_REQUIRED 或 BACKGROUND_LAUNCH_BLOCKED |
| 视频启动 Intent 已下发但无播放会话 | 只确认启动请求，不声称页面展示或播放 |
| play/resume 回读窗口结束仍为 BUFFERING | Provider 在外层超时前返回 VERIFICATION_FAILED；总截止先到则按 EXECUTION_UNKNOWN |
| 会话写能力在发送前被拒绝 | EXECUTION_FAILED 且 media.dispatch_state=NOT_SENT |
| 原来源 pause 返回 VERIFICATION_FAILED 或 EXECUTION_UNKNOWN | 切换 Skill 不调用目标 play/resume，报告暂停未确认 |
| 手机与车机配置切换 | 手机不受虚构车速阻断；车机视频需可信停车状态 |
| AUTOMOTIVE profile 且 VehicleState unavailable | 视频写能力被 CAPABILITY 拒绝 |
| 两款目标 App 均未处于 PLAYING，第三方 App 正在播放 | 双应用切换 Skill 返回 SOURCE_NOT_CONTROLLABLE；不声称已识别第三方会话，也不尝试暂停 |
| SkillSelector 可用性快照过期 | Prompt 可降级；Provider 执行时按最新安装/会话状态判定 |
| 语音执行媒体能力 | 进度播报使用目标能力中文名称，不回落“这项操作” |
| 审计与日志 | 不出现曲目标题、观看链接或搜索词原文 |
| 搜索指定歌手 | 只返回编号和候选歌曲；MediaSession 保持原状态，不自动播放 |
| 明确歌手与具体歌名的快速流程 | 确定性产生搜索 Tool，成功后直接给确认问句，无额外云端模型回合；真机搜索到答复的目标耗时以 Tool 窗口为界 |
| 上次搜索上下文丢失后回复“是” | 立即提示重新搜索，不调用播放 Tool，也不等待云端模型直至任务到期 |
| 未经下一轮明确选择就调用 play_search_result | 返回 SELECTION_NOT_CONFIRMED，UI 不点击歌曲 |
| 唯一匹配李健《传奇》并询问是否播放 | 用户回复“是”后只接受提议的 index；回复“否”或让模型改传其它 index 均不播放 |
| 用户明确选择后页面或版本变化 | 重新搜索并严格比对；变化时返回 SEARCH_RESULT_CHANGED 或 SEARCH_CONTEXT_EXPIRED，不点击其它结果 |
| 选择歌曲后回读未达到目标标题、歌手和 PLAYING | 返回 VERIFICATION_FAILED，不声称已播放 |

2026-09-25 真机回归：新版完整 Repository/AgentEngine 搜索请求 `播放李健的传奇` 返回 SUCCEEDED、1 次搜索 Tool、确认问句可用，总耗时约 4.8 秒（冷启动包含上下文/存储准备）；紧接的 `不要播放` 返回 SUCCEEDED、0 次 Tool、约 0.1 秒。两轮均未调用播放能力，QQ 音乐会话仍为 NONE。单独的搜索与确认答复编排约 1.7 秒，无云端模型请求。该明确歌曲流程此前会等待 31～47 秒的兼容模型回合，随后可能在 60 秒处误报 CANCELLED；窄范围确定性路径已消除这段等待。

### 阶段 C：内置 Skills

实现 SkillCatalog、SkillSelector、Prompt 注入和三个首期 Skill。用直接请求、模糊请求、无链接、无会话、跨应用切换失败等对话检验结果措辞。确认端侧与云端模型都只能调用同一批被策略批准的 Tools。

### 阶段 D：精确点播与扩展

当前通过用户启用的辅助功能服务实现“搜索 → 展示候选 → 用户明确选曲 → 回读播放”。它依赖当前 QQ 音乐版本的 View 结构，版本更新后需重新探测并维护选择器；它不具备直接按搜索词自动点播的语义。若将来增加官方 `play_from_search`，应将授权、账号范围、歌曲版权/地区限制、错误码和回读契约独立写入该 Tool 的设计；不要把未公开的自定义 scheme 或内部 Binder 服务当作稳定产品接口。

### 哔哩哔哩标题搜索增量（2026-09-25 已实现）

在 BV 号入口之外，新增 `media.bilibili.search_videos(query)` 和 `media.bilibili.open_search_result(index)`。前者通过用户已启用的媒体辅助功能，在哔哩哔哩 9.12.0 的搜索页填写关键词并读取可见结果，不点击视频。返回最多 8 个编号候选，包含标题、类型与可见的 UP 主信息；标题和关键词在审计中脱敏。后者要求同一会话中先有未过期的搜索快照，并要求**新一轮用户输入**明确指定序号或完整标题。唯一候选时可接受肯定确认，多候选时“是”不足以选定目标。

QQ 音乐的 `openSearchSurface` 与 `waitForSongsTab` 仍匹配中文版界面的“返回”“歌曲”等文案；系统或应用改用其他语言时会保守地返回 `SEARCH_UI_CHANGED`。后续扩展多语言时需要按已验证的版本维护资源 ID/语义标识映射。Skill 清单当前只接受 `version=1`，升级清单格式时需同步更新 `SkillCatalog` 的解析规则。

2026-09-25 切换流程复核：对“切换到QQ音乐”等只点名目标的话术，`MediaTargetPolicy` 允许暂停另一个 App，但 `MediaSwitchGuard` 仍须通过双方会话回读确认唯一原来源，且须回读到 `PAUSED` 才能启动目标。尚未读取状态、尚未确认暂停等顺序问题返回可修正的 `PARAMETER_REJECTED`，不会永久封锁目标能力；目标不明、错误来源和其它越权写操作继续按能力拒绝。完整 AgentEngine 回归覆盖“提前 play → 读取双方状态 → 暂停原来源 → 播放目标”的同请求恢复路径。QQ 音乐选曲确认仅接受严格序号、完整标题或唯一匹配项的肯定答复，不接受在否定句中包含标题就放行。

同日真机会话快照为 QQ 音乐 `PAUSED`、哔哩哔哩 `NO_ACTIVE_SESSION`，不满足“从 B 站切换到 QQ 音乐”的前提，因此未在真机下发暂停/播放写命令。最终 APK 已安装，哔哩哔哩标题搜索的完整 AgentEngine 安全回归为 SUCCEEDED、1 次搜索 Tool、约 1.9 秒。

切换意图后续统一由 `MediaSwitchIntent` 判定，SkillSelector、MediaSwitchGuard 与 MediaTargetPolicy 共用结果。“切换音乐来源”即使未点名应用，也注入切换指引并启用硬门禁；未明确目标时写能力被拒绝。“别切换，先暂停QQ音乐”等否定切换话术不进入切换流程，按普通 QQ 音乐暂停处理。两款应用仅被同时提及时，不自动视为切换；“暂停 QQ 音乐再播放 B 站”按“暂停源、再播放目标”的祈使结构解析方向，目标为 B 站，仍须回读确认原来源暂停后才能播放。“B 站在播放，QQ 音乐暂停了”这类状态描述不激活切换 Guard。

选择动作会重新搜索，核对哔哩哔哩版本、关键词和目标结果的标题、类型及附加信息，再点击唯一匹配项；结果变化或过期时停止。成功语义仅为“所选条目的点击已受理”，不声称视频已播放；番剧可能仍需选择集数。两项能力均为 R1 写、`VerifyMethod.NONE`、15,000 ms，车机 profile 归 `VIDEO_RESTRICTED`，执行路径不自动重试。搜索上下文按会话隔离，10 分钟过期，最多保存 32 个会话。

真机验证设备为 LineageOS Android 15，哔哩哔哩 9.12.0。`播放哔哩哔哩的逃避可耻但是有用` 在完整 AgentEngine 路径中只触发一次 `media.bilibili.search_videos`，Tool 为 SUCCESS，任务为 SUCCEEDED，耗时约 1.6 秒；没有 QQ 音乐 Tool，也没有点击候选。页面可见两个相关影视项：《逃避虽可耻但有用 特别篇》和《逃避虽可耻但有用》。搜索结果随 B 站刷新变化，选择时必须按上述规则重新核对。未得到用户具体选择，因此真机未执行打开结果动作；该边界由单元测试覆盖。

2026-09-26 交接方向修复：`ExplicitMediaTarget` 从“暂停 X 再播放 Y”解析 Y 为目标，支持 `QQ 音乐`、`B 站` 等带空格写法；`MediaSwitchIntent` 只在识别到这一祈使结构时把无切换动词的话术判为交接，状态描述不触发 Guard。AgentEngine 回归覆盖“提前播放被可重试地拒绝 → 双方状态回读 → 暂停源 → 播放目标”，全量单元测试 1,340 项通过。更新后的 APK 已安装到同一台 LineageOS 真机；设备 Guard 探针分别返回 `PARAMETER`（交接话术）与 `NOT_ACTIVE`（状态描述）。此次真机验证只下发了安全判定探针，没有执行实际暂停或播放。

## 11. 官方平台依据

- [Android MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager)：跨应用活动会话访问与权限条件。
- [Android MediaController.TransportControls](https://developer.android.com/reference/android/media/session/MediaController.TransportControls)：播放、暂停、切歌、seek 与 playFromSearch 等标准命令。
- [Android PlaybackState](https://developer.android.com/reference/android/media/session/PlaybackState)：目标会话声明的可用动作及回读状态。
- [Android 包可见性声明](https://developer.android.com/training/package-visibility/declaring)：targetSdk 30 以上查询指定应用的 queries 要求。
- [Android Intent 与 Intent Filter](https://developer.android.com/guide/components/intents-filters)：ACTION_VIEW 和组件解析。
- [Android 后台 Activity 启动约束](https://developer.android.com/guide/components/activities/background-starts)：后台服务打开其他应用时的限制。
