# Agent 评测运行指南（P0-1）

评测入口复用生产 `AgentEngine`、`PolicyEngine`、`CapabilityRegistry`、Prompt/Skill、媒体确认网关、`MediaCapabilityProvider` 和 `ScheduleCapabilityProvider`。固定语料与评分器源码位于 `matrix-agent-service/src/test/java/com/matrix/agent/evaluation/`；debug 构建定向复制所需评分器，release APK 不含评测器。媒体只替换底层 App/UI/Session 端口；计划使用已有的事务内存数据库夹具和生产 `ScheduleStore`，不会注册 Android 闹钟，也不会访问用户的记忆、轨迹或计划数据库。

## 运行

仓库使用 Java 17 语法和 Android 模块 JVM 测试环境；本次验证使用 JDK 21。遵循仓库现有 Android SDK 配置。

```sh
./gradlew :matrix-agent-service:runEvaluations
./gradlew verifyArchitecture
```

`runEvaluations` 总是重新采样，同一语料运行两遍；`verifyArchitecture` 已依赖它。该模式不构造真实模型客户端，不访问外部服务或产生模型费用。依赖的普通单元测试包含本机 MockWebServer 协议检查。

报告：`matrix-agent-service/build/reports/evaluations/offline/report.json`。本次 110 个独立场景分为媒体 60、计划 30、多轮 20；共 132 个用户轮次，两遍产生 220 个场景样本、264 个轮次样本。场景通过要求其每一轮均通过，不能把轮次当作独立场景增加分母。

真实模型需要显式配置：

```sh
./gradlew :matrix-agent-service:runModelEvaluations -Pmodel.config=/absolute/path/to/profiles.json
```

报告位于 `matrix-agent-service/build/reports/evaluations/model/report.json`。该任务不进入默认门禁。推荐将本机配置放在已忽略的 `.model-evaluation/` 目录；密钥仅通过环境变量提供。配置示例见 [model-profiles.example.json](../matrix-agent-service/src/test/resources/evaluation/model-profiles.example.json)，需替换 endpoint 和 model，并在调用 Gradle 前安全设置其中指定的凭据环境变量。不要把密钥放进命令行、报告或版本库。

配置 ID 使用小写字母、数字、下划线，最长 40 字符。每个配置运行 1–10 遍，最多 8 个配置。至少两个不同配置各运行两遍，才能满足计划中的真实模型采样要求；重复命名相同配置会被拒绝。同一模型的不同协议/PlannerMode 属于不同配置，不等于比较了两个不同模型。HTTP 模型走真实 `LlmModelGateway` / `ModelApiClient`；ON_DEVICE 原生推理仍需单独真机认证，不能在此 JVM 任务里伪装成端侧测试。

在线模式的目标得分是测量值，不预设虚假的通过阈值；任何实际误执行/越权副作用仍使任务失败。未完成运行保留 `complete=false` 的逐例检查点。环境和模型不可用不能被视为通过。

## 语料与评分

唯一固定语料为 [corpus-v1.json](../matrix-agent-service/src/test/resources/evaluation/corpus-v1.json)，包含 `id / suite / tags / fixtures / turns / scoringVersion`。每轮有原始用户输入、独立脚本和独立期望。真实模型永远看不到脚本及期望；脚本只用于检查已知提案经过生产编排、策略、执行后是否符合预期，不能衡量模型理解能力。

- `fixtures` 固定候选、安装/会话状态、搜索失败、时间和时区。候选行格式与 Android 端口一致。`expireCandidates` 通过删除待确认上下文模拟过期；它不测量真实 TTL 等待时间。计划业务时钟固定，Engine 超时仍使用实际运行时钟。
- `expect.alternatives` 表示不同的完整正确结果，各结果包含无序的必要效果、必要工具观察和回答规则。允许读取额外状态，不强迫唯一工具顺序。`RESOLUTION` 用于“按原授权完成普通提醒”或“先澄清额外能力”都合理的情况。
- 实际副作用来自低层端口及事务提交后的状态变化，独立于模型提案、工具 `SUCCESS` 和任务 `SUCCEEDED`。报告分别记录模型提案、确定性网关提案、策略拒绝、Provider 观察及实际效果。
- 参数按部分结构匹配，数值比较不依赖 Integer/Long 序列化差异，`$contains` 用于保留指定实体/提醒内容，`$absentOr` 允许未指定的实体省略或留空；计划以实际触发时刻、时区和动作类型评分，不强制提醒标题的固定措辞。必要动作采用一对一匹配，重复执行不能被一个期望抵消，也不能把两个互斥正确方案的副作用拼在一起。
- 回答采用版本化正/负正则规则。这是可复核的窄规则，不是开放语义评审；真实模型报告需要人工复核，尤其注意改述导致的误判。`SUCCEEDED` 加一句“已完成”不能替代期望效果。

失败类别包含参数错误、候选错误、策略过严/过松、协议、超时、Provider、目标未达成和未知。只有有相应结构证据时才归因；参数/候选错误与禁止动作实际发生分别标记。主要和次要原因并列保留。评分器自身有反例测试，覆盖虚假成功、拦截提案与实际副作用的区别、错误候选、互斥方案、一对一匹配及协议错误。

报告包含提交与工作区脏状态、语料/生产源码/Skill/评测器/夹具哈希、每轮 Engine 系统 Prompt 哈希与 HTTP 包体哈希、协议与 PlannerMode、重复次数、任务预算和逐例差异。HTTP 采样记录实际发出的解码参数及可取得的数值 usage；未设置参数标为供应商默认，未返回 usage 和无法确定的费用标为未知。未控制模型随机种子时不能声称完全可重复。脚本耗时也不能作为真实模型或真机的性能数据。

为避免输出凭据和业务原文，报告保存回答/参数哈希及结构证据，不保存请求头、端点原文、模型完整回复或调试推理文本。需要检查具体措辞时，在受控调试环境复现指定用例；不要上传真实用户数据到固定语料。

## 真机抽样

使用现有 `android.permission.DUMP` 保护的 debug `MediaProbeReceiver`。先构建、以 `adb install -r` 更新 debug APK，确认设备已唤醒且已启用搜索辅助服务。不要安装 instrumentation APK：仓库安装保护仍然有效。长探针由不导出的 debug `MediaProbeService` 承载，显示临时前台通知、串行运行并在完成后停止；服务销毁时取消任务并清理线程。原实现使用 `goAsync()`，本次实测在 10 秒及 60 秒广播时限触发 ANR，已据此修复。重试和环境失败在验证记录中保留。

示例（设备序列号按实际替换）：

```sh
adb -s SERIAL shell am broadcast -n com.matrix.agent/.debug.MediaProbeReceiver \
  --es capability media.qqmusic.get_state
adb -s SERIAL shell am broadcast -n com.matrix.agent/.debug.MediaProbeReceiver \
  --es engine_search_text 'QQ音乐播放李健的贝加尔湖畔'
adb -s SERIAL shell am broadcast -n com.matrix.agent/.debug.MediaProbeReceiver \
  --es engine_reject_text '不要'
```

也可运行 `python3 tools/evaluation/device_smoke.py --serial SERIAL --output /path/to/evidence`：脚本串行执行有限样本、核对实际结果与服务退出，并恢复临时保活设置；需要已存在的 QQ 音乐媒体会话，且不会主动选曲或创建计划。

等待每次探针的 `MatrixMediaProbe` 结果再发下一条；广播返回 0 只代表派发，不代表任务成功。Engine 探针走当前已配置模型和真实 App，可能产生诊断记录，应与隔离的固定夹具评测分开统计。本次验证记录放在 [verification/agent-evaluation-2026-09-27](verification/agent-evaluation-2026-09-27/README.md)。

debug 真机固定夹具评测使用 `--ez model_evaluation true`。它从设备已保存的 OpenAI 兼容配置构造 STRUCTURED_JSON_COMPATIBILITY 与 NATIVE_TOOL_CALLING 两种 PlannerMode，各运行固定 110 场景两遍；不会把配置中的凭据写入报告。报告 `verification/real-model-report.json` 逐例原子保存 `complete` 状态、构建时 commit/工作区状态及源码哈希、任务预算、每次请求实际发送的解码参数、HTTP 状态/响应头耗时与可得的数值 usage。未设置的解码参数明确记为 `provider_default`。当前真实模型质量基线须等 `complete=true` 再归档；部分运行只可用于诊断，不能当作完整质量结论。该评测会消耗保存模型的配额，且同一模型的两种 PlannerMode 不构成两个不同模型的比较。

若服务返回 HTTP 429，可在同一个 debug APK、同一模型配置下发送 `--ez model_evaluation_resume true`。续跑会核对生产源码、模型、端点摘要、语料、Skill、评分版本、预算和已完成用例前缀，只补采未完成案例；报告的 `evaluationSessions` 记录每次续跑的评测器哈希，`providerBackoffEvents` 记录限流次数与退避。单案例遇到 429 不入质量分数，按有限退避重试，额度仍不可用则保持 `complete=false`/`incompleteReason=PROVIDER_RATE_LIMITED`，待额度恢复后再次续跑。已经把 429 错计为质量失败的旧版报告不能直接续跑，必须先恢复到第一个限流案例之前的有效前缀，并保留原始报告供审计。续跑仍使用合成隔离夹具，不触碰用户计划。
