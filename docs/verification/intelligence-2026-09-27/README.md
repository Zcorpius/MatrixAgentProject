# 智能层首版验证索引

验证设备为 Mi 9 SE（Android 15 / SDK 35，arm64）。代码基于 `690adee` 工作区变更，实际设备执行跨 2026-09-27/28；保留目录名用于连续追踪。原始 P0-1 真模型基线使用独立早期 APK，不能自动覆盖后续实现。

| 证据 | 内容与判读 |
| --- | --- |
| [intelligence-device.json](intelligence-device.json) | 8 项通过：生产加密 Room 20、旧库 16→20、可选 APK 模型冷安装、向量生命周期、附件生命周期、真实空管道取消、教训生命周期、真实 JNI/Room 召回 |
| [embedding-native-report.json](embedding-native-report.json) | 固定 48 次原生编码，耗时、峰值 RSS、阈值扫描与 calibration/holdout；对应原始向量在 `.native.json` |
| [model-feature-device.json](model-feature-device.json) | 云端及 MNN 流式/取消、5 类附件回答、首轮 24 次记忆回答；记忆 correct 属旧评分，需结合下项，不能直接当最终验收 |
| [memory-legacy-score-audit.json](memory-legacy-score-audit.json) | 指出关键词评分把“拒答中举例豆浆”算成功的问题；原报告不修改，后续采用 evidence-v2 |
| [memory-answer-evidence-v2.json](memory-answer-evidence-v2.json)、[summary](memory-answer-summary.json) | 收紧规则后重新实际采样：词面 11/12、混合 12/12；正例均要求 verified 读取对应事实 |
| [ui-attachment-observation.json](ui-attachment-observation.json) | 真实 Launcher 显示文末来源块、原文区间与覆盖范围，最终正确回答 |
| [ui-stream-observation.json](ui-stream-observation.json) | 实际截图逐帧人工核验记录、时间区间与帧哈希、取消终态、Host 重连和 Launcher 冷启动正文一致性 |
| [research-first.json](research-first.json) | 首次 87.216 秒多来源研究部分成功并交付 |
| [research-independent-completion.json](research-independent-completion.json) | 独立重复，58.199 秒部分成功并交付；Wikipedia 超时如实保留 |
| [research-before-restart.json](research-before-restart.json)、[after](research-after-restart.json)、[result](research-recovery-result.json) | 原来源检查点复用、只读分析重试；用户交互抢占摘要后取消且不交付 |
| [research-autorecovery-recovery-result.json](research-autorecovery-recovery-result.json) | Launcher 自动重连唤醒 Host 后交付；明确 90 秒人工等待不等于实际停机时长 |
| [research-fault-summary.json](research-fault-summary.json) | cancel/revoke/expire/exhaust_active/exhaust_models 五类；各 `research-fault-*.json` 保存注入前、注入后、终态 |
| [research-long-recovery-result.json](research-long-recovery-result.json) | 逐秒确认 90 秒真实进程停机后恢复；183.282 秒活动账本，检查点复用并交付，停机与连续推理明确区分 |
| [reflection-device.json](reflection-device.json)、[summary](reflection-summary.json) | 16 次 A/B：OFF 5/8、ON 4/8，均无越权；3 次诊断失败回退 UNKNOWN，0 条有效教训，默认关闭 |
| [test-counts.json](test-counts.json) | 最新 JVM 测试分母和失败/跳过数；runEvaluations 与普通单测的显式入口分开 |
| [apk-verification.json](apk-verification.json) | APK 哈希/大小、release 不含 debug 探针、可选 embedding 文件清单 |

`intelligence-device-first.json`、`model-feature-first.json`、`reflection-pre-deadline-fix-incomplete.json` 保留早期失败/中断证据。第一版冷模型迁移探针误用必填 indices、空工具请求构造、注入评分误判，以及单次 JSON HTTP deadline 未约束 body 读取等问题均已修复并回归；未完整结束的采样不参与最终 A/B。

模型出口的 firstVisibleMillis、屏幕截图采样的首次可见区间、完整 Engine 时延和研究活动账本有不同计时范围，禁止合并比较。负例误召回分母为每个 split 的 6 条，正例 Recall 分母各 12 条。真实回答实验不含 Skill；Skill 投影由独立确定性预算分支测试覆盖，不能据此声称实际带 Skill 场景的模型回答成功率。

部署和复现参见 [工具说明](../../../tools/evaluation/README.md)，实现、开关与限制参见 [实施记录](../../智能层实施记录-2026-09-27.md)。
