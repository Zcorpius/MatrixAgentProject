# P0-1 评测框架实施与真机验证

日期：2026-09-27。基础提交 `690adee`，实施变更尚未提交；精确源码、语料和 APK 哈希在下列记录中。运行说明见 [evaluation.md](../../evaluation.md)。

## 最终结果

| 验证项 | 结果 | 证据 |
|---|---|---|
| 根 `verifyArchitecture` 与 debug APK 构建 | 通过，267 个任务参与（包含缓存/增量结果） | [构建日志](verifyArchitecture.log) |
| 普通 JVM 测试 | 1,539 条通过，2 条显式评测入口默认跳过；无失败/错误 | [按模块及任务统计](test-summary.json) |
| 离线评测入口 | 另行运行成功；110 场景 × 2 遍，220/220 场景、264/264 轮次通过，违规实际执行 0；110 场景重复结果一致 | [当时的完整逐例报告](offline-report-original-v1.json) |
| 真机最终抽样 | Mi 9 SE / Android 15，9/9 项通过 | [真机逐项记录](device-report.json) |
| debug 隔离 | release 合并 Manifest 不含 MediaProbeReceiver / MediaProbeService | [检查结果](release-manifest-check.json) |

语料分母为媒体 60、计划 30、多轮 20。脚本化回归不衡量模型理解准确率，不可将上述 100% 当作模型质量分数。`runModelEvaluations` 入口已实现，但两个真实模型配置的重复评测未执行：尚未获得本机评测配置/凭据环境变量。真机当前配置为 `glm-5.3 / OPENAI_CHAT / STRUCTURED_JSON_COMPATIBILITY`，不满足两个配置的验收条件。P0-1 的工程基础已交付，完整真实模型基线尚未冻结；P0-2 及后续阶段尚未实施。

## 架构与修复

评测器复用生产 Engine、Policy、Registry、Prompt/Skill、媒体确认网关和两类 Provider；媒体只替换底层端口，计划使用生产 ScheduleStore 与事务内存数据库夹具。脚本和评分期望分离，真实网关只接收用户输入、生产提示及观察结果。副作用来自端口或提交后的状态，不从 `SUCCEEDED`、模型答复或工具提案推断。

本轮实际发现并修复：

1. Schema 描述允许未指定歌手/歌名留空，默认字符串校验却拒绝。仅显式 `minLength=0` 的实体字段允许空白，其他字段的默认非空约束保持。
2. 用户明确要求先搜索/确认，或明确要求候选搜索时，QQ play / B 站 resume 仍可能恢复无关队列。新增对应拦截及正常恢复播放的反例回归。
3. “改用 B 站搜索…”被播放切换前置条件拦截，并获得了错误的播放来源切换 Skill。目标搜索可在没有活动源会话时准备候选，提示路由选择目标内容 Skill；播放、打开结果和恢复队列仍保留确认与切换约束。
4. 原 debug `goAsync()` 广播无法承载慢模型请求。长探针迁移到不导出的 debug 前台服务，DUMP 权限广播仅负责派发；服务串行运行、有界排队、销毁时取消、完成后自动退出。release 不包含这两个探针组件。

## 真机验证边界

采用 `adb install -r` 更新 debug APK，未安装 instrumentation APK，也未清除应用数据。最终设备安装包 SHA-256 与本机构建 APK 一致：

```text
99fbb9060eaac2b8e79a3cb4ec1717321a8a10d350f444ff9ef1f3fc3008bb2a
```

最终 9 项包括前后播放状态回读、QQ/B 站先确认策略拦截、空歌名真实搜索、真实模型 QQ 搜索→拒绝确认、真实模型跨应用 B 站搜索→拒绝确认。QQ 音乐空歌名搜索返回 7 条真实候选。当前模型两次搜索分别约 7.0 秒和 11.4 秒；拒绝确认分别约 94/82 毫秒，均为零工具调用。请求未执行选曲或播放写操作，QQ 音乐前后均为 PAUSED；服务退出，临时保活设置已恢复。

这属于有限端到端抽样，不是 110 条语料在真机全量运行，也不证明歌曲实际播放或定时通知投递成功。报告中的脚本耗时不用于推断真实模型性能。

复现最终抽样：

```sh
python3 tools/evaluation/device_smoke.py \
  --serial 977d27c4 \
  --output /tmp/matrix-device-evaluation
```

需要 debug APK、已启用的搜索辅助服务、已配置模型以及 QQ 音乐活动媒体会话。脚本会核对实际完成日志，广播派发成功不会被当作测试通过。

## 保留的失败及中间记录

- 原广播实现两次出现 ANR：22:22:35（息屏时使用前台广播，约 10 秒时限）和 22:26:38（慢模型请求超过约 60 秒广播时限）。设备进程退出记录确认原因；迁移前台服务后的运行没有新增 ANR。
- 服务修复后，跨应用请求完整返回 TIMEOUT，耗时约 73.3 秒、工具调用 0；耗时包含 Engine 前的请求准备，Engine 自身记录的超时约 60 秒。该次共 8 项，7 项通过、1 项失败，见 [修复搜索提示路由前的记录](device-report-before-search-routing-fix.json)。没有将超时或服务恢复记为目标成功。
- 代码复核确认该请求误选播放切换 Skill，修正后两轮抽样均通过。先一次 9 项成功记录见 [搜索路由修复后记录](device-report-after-search-routing-fix.json)，最终包的记录为上面的 `device-report.json`。不能仅凭重试变快断言所有延迟都由提示路由造成；供应商抖动仍需独立重复评测。

固定夹具评测不读写用户数据库。真机 Engine 探针使用生产运行链路，会留下合成测试请求的正常诊断记录；报告不包含密钥、端点原文或模型推理文本。

## 2026-09-28 归因版本复算

[当前归档](offline-report.json)由 `verifyArchitecture` 重新执行生成，`attributionVersion=evidence-v2`、`complete=true`、`repetitions=2`。旧报告另存为上文链接，二者源码哈希与归因口径不同；不可把旧版真实模型质量分数直接当成 v2 的测量值。
