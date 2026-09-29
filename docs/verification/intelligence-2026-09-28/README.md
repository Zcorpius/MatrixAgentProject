# 智能层评审整改验证（2026-09-28）

本轮从 `690adee` 基线继续修复 [实现评审报告](../../智能层实现评审报告-2026-09-28.md)确认的问题。采样时代码尚未形成新提交；真机报告中的构建溯源用源文件哈希补足当时仅凭 commit 无法定位工作区代码的问题。

| 验证 | 结果 | 证据 |
|---|---|---|
| `verifyArchitecture`，含 110 场景 × 2 遍离线评测与 debug Lint | 通过；离线 220/220 场景通过，违规实际执行 0 | [evidence-v2 逐例报告](../agent-evaluation-2026-09-27/offline-report.json) |
| 常规 JVM 单测 | 1,612 项，失败/错误 0，显式评测入口跳过 2 项 | 各模块 `testDebugUnitTest` XML 汇总；离线入口另由门禁执行 |
| `assembleDebug`、`assembleRelease`、`lintDebug`、`lintRelease` | 均通过；release DEX 无 debug 探针/评测类 | debug APK SHA-256 `3764d87a8e689cf1004ea76741d89c54f28540f88fcc5c44e8960277d66d91bf`；release APK SHA-256 `8795eb48f676f561c568c161116c65cd4377f040d304897ce9b6e7d509f8388a` |
| `compileDebugAndroidTestJavaWithJavac` | 通过，新增 14→15 至 19→20 六条逐版本历史夹具/schema 测试 | [IntelligenceMigrationTest.java](../../../matrix-agent-service/src/androidTest/java/com/matrix/agent/data/db/IntelligenceMigrationTest.java) |
| Mi 9 SE / Android 15 隔离探针 | 11/11 通过；生产加密库 v20，隔离 SQLite v16→v20，Android JSON null，研究摘要双预算，BGE 冷安装/JNI 混合检索，附件与反思生命周期 | [逐项设备报告](intelligence-device.json) |

最大附件样本为 8,160,060 字节、4,000 块，复测 BM25 排名耗时 1,690ms 并命中文末。该隔离检查同时构造合成语料、切块和排名，不能把完整探针耗时作为生产 Binder 受理时延。BGE 独立空目录冷安装加首次编码为 1,334ms；未连接更慢的目标设备，因此“慢设备首次安装”仅有架构与 JVM 超时回归，不能宣称其耗时已经实测达标。新增研究摘要检查使用 HTML 转义及多字节中文压力输入，最终提示词分别为 4,077 字符/4,429 UTF-8 字节和 2,990 字符/8,190 UTF-8 字节，均保留三个来源编号且未接触用户计划。

当前联调机运行 system-UID 应用并保有用户数据；新增 Room `androidTest` 已编译，但未安装 instrumentation APK。仓库对该安装设有显式防护，因为它可能重置应用私有数据。安全的 debug 探针使用独立合成库，验证连续迁移与历史行保留；逐版本目标 schema 测试仍需在专用测试设备执行。

旧版[离线报告](../agent-evaluation-2026-09-27/offline-report-original-v1.json)与[真实模型报告](../agent-evaluation-2026-09-27/real-model-report.json)保留原归因，不与本轮 `evidence-v2` 混算。

评审整改后的首轮[真实模型基线](real-model-before-schedule-guard.json)已完整采样：兼容 JSON 156/220 通过、6 次错误参数实际写入；原生工具调用 157/220 通过、7 次错误参数实际写入。13 次均为 `schedule.create` 的效果与目标参数不符，集中在绝对时间、每周规则和提醒授权场景；原始报告只保留参数哈希，不能再细分具体哪一字段有误。两条协议都可触发。由此增加了共享工具边界的[字面计划约束](../../../matrix-agent-service/src/main/java/com/matrix/agent/schedule/tool/ExplicitScheduleIntent.java)：用户明确给出的绝对时间、相对延迟、日/周钟点、星期、时区、简单提醒主题以及拒绝创建语句必须与模型提案一致；无法唯一解释的周期表达拒绝落库。修复前的[离线报告](offline-report-before-schedule-guard.json)同时保留，修复后的最新离线报告继续见上表。

加入约束后的[真实模型部分复测](real-model-after-schedule-guard-partial.json)先完成兼容 JSON 模式：158/220 达成目标，错误参数实际写入 0；其中计划案例 27/60 达成目标、越权执行 0。原生工具模式完成首遍及第二遍前 50 例，前 160/220 有效案例中 125 例达成目标、错误参数实际写入 0；在第二遍第 51 例起，模型服务连续返回 HTTP 429。旧评测器把限流响应记为模型质量失败的[中断原始记录](real-model-rate-limited.json)仅供诊断；恢复有效前缀时明确剔除其尾部 41 个限流案例和 126 次限流请求，原始文件保留。新 debug 评测器两次从 380 个有效案例的检查点续跑，跨一次 debug 包升级也通过生产源码/语料/预算/前缀校验；累计七次有限退避后服务仍返回 429，保留 `complete=false` / `PROVIDER_RATE_LIMITED`。续跑成功前，不能声称约束后双模式完整质量基线已通过。

同一真机还执行了[合成研究计划](research-device-rate-limited.json)：检索步骤实际进入生产路径，在线核对因模型服务限流以 `POLICY_HALT` 结束，任务最终 `REQUIRED_STEP_FAILED`，没有交付研究摘要。这里的 `POLICY_HALT` 是当时版本对 HTTP 429 的错误归类，并非安全策略真的拒绝；后续已修正为 `MODEL_RATE_LIMITED`。该结果验证了不可用时任务未继续执行，不构成摘要质量验收；合成计划随后已标记删除，外部临时报告归档后清除。研究提示词本身的字符/字节预算由上面的隔离真机检查验证。

限流归类修正后，Mi 9 SE 上重新执行隔离探针：[12/12 检查全部通过](model-rate-limit-device.json)。新增检查以合成异常模拟规划器包裹的 HTTP 429，经生产 `ModelCallExecutor` 得到 `MODEL_RATE_LIMITED`，用户文案包含 `HTTP 429` 且不含“安全策略”；整个检查没有向外部模型服务发请求。其余 11 项为原隔离检查复测，旧版 11/11 报告仍作为此前采样保留。
