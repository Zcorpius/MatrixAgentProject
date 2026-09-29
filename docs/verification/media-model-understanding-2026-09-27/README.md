# 媒体语义理解调整与真机验证

## 改动

- QQ 音乐与哔哩哔哩的新内容请求交给当前配置的模型规划，移除按固定话术提取歌曲、歌手、视频标题的快捷路径。
- QQ 搜索工具保留必填 `query`，增加可选 `artist`、`title`。未知实体可省略或使用空字符串，以兼容原生严格 Schema。查询和实体字段纳入参数审计脱敏。
- `SongSearchTarget` 只将结构化实体与实际候选精确比较；同名翻唱、重复版本和不匹配结果不被自动指定。歌名中的“的”和版本后缀不会被拆除。
- `SongSearchResultGate` 分离页面就绪与内容匹配：要求输入一致、候选稳定至少 240ms，更换查询时不接受尚未变化的旧列表；允许应用纠错产生的真实候选返回给用户选择。
- 确认话术、明确的序号/歌名选择和播放回读继续本地执行。搜索同轮不能用结果播放或恢复旧队列跳过确认。新搜索清理前一份待选上下文，失败后不能误确认旧候选。
- 结构化 JSON 兼容规划器加入 CanonicalSchema 参数说明，使用与执行层相同的字段及边界。没有新增一次单独的“歌曲解析”模型调用。
- 删除失效的零模型调用 `workflow_text` 调试入口；保留真实 AgentEngine 入口，补充不含歌曲元数据的状态/确认标记输出。

## 构建与单元测试

执行：

~~~sh
./gradlew :matrix-agent-service:testDebugUnitTest :matrix-agent-service:assembleDebug
adb -s 977d27c4 install -r matrix-agent-service/build/outputs/apk/debug/matrix-agent-service-debug.apk
~~~

- 230 个测试类，1,411 项测试，0 失败、0 错误、0 跳过。
- 构建成功；覆盖安装成功，设备报告更新时间为 2026-09-27 19:10:35（UTC+8）。
- 没有运行会影响 Host 数据区的 instrumentation APK，没有清理应用数据。
- 本轮只更新 Service APK，版本号仍为调试基线 0.7.1 / 7001；未变更 Binder 契约。

新增或调整的回归覆盖：多种歌手/歌曲和自然表达透传模型、取消信号与执行通道保留、真实候选精确匹配、多版本歧义、参数边界、页面稳定性、旧列表拒绝、新搜索失败清除旧确认、同轮恢复队列拒绝、B 站路由以及结构化模型的参数 Schema。

## 真机结果

设备：Mi 9 SE / LineageOS；当前配置模型：GLM-5.3，Structured JSON Compatibility。

通过 DUMP 权限保护的 `MediaProbeReceiver.engine_search_text` 调用生产 `AgentRuntimeRepository → AgentEngine → 配置模型 → PolicyEngine → ToolExecutor → Android Provider`。每次输入单独提交，使用专用 `probe-qq-search` 会话，未注入预设的模型答案。该入口验证运行时链路，不代表新增了 Launcher 聊天 UI 自动化测试。

| 输入 | 真机结果 | Tool 用时 | 请求总用时 |
| --- | --- | --- | --- |
| 播放李健的消失的月光 | QQ 搜索成功，唯一匹配 index=1，输出“是否播放这首” | 2,607ms | 10,128ms |
| 不要播放 | 清除上述确认；0 次 Tool Call | — | 99ms |
| 播放李健消失的月光 | 模型识别完整歌名；QQ 搜索成功并询问确认 | 1,740ms | 12,439ms |
| 播放周杰伦的晴天 | QQ 搜索成功，唯一匹配 index=1，询问确认 | 1,949ms | 5,614ms |
| 播放哔哩哔哩的逃避可耻但是有用 | 仅调用 B 站 search_videos，成功返回候选；没有调用 QQ 工具或打开视频 | 6,034ms | 8,761ms |
| 播放李健的消失的月光（再次搜索） | 搜索成功并询问确认，1 次 Tool Call | 1,968ms | 5,214ms |
| 是（测试中独立提交的下一轮） | play_search_result 成功，verified=true，1 次 Tool Call | 4,169ms | 4,331ms |

首次请求包含进程初始化后的部分冷路径耗时；模型网络耗时也有波动，上表为本次样本，不是延迟承诺。前五次搜索链路均未调用播放工具。最后一次独立确认后，另外执行 `dumpsys media_session`，读到：

~~~text
package=com.tencent.qqmusic
state=PLAYING(3)
description=消失的月光, 李健, 李健
~~~

## 证据与边界

[device-verification.txt](device-verification.txt) 合并本轮逐次读取与最后导出的事件，仅保留请求开始/结束、模型路由、工具耗时、确认标记等事件，不保存模型推理文本或凭据。[media-session.txt](media-session.txt) 为最后一次 QQ 会话状态的定向摘录。

模型仍可能给出错误或不完整的实体，代码不会把模型理解视为播放授权：实际候选必须存在，用户必须在下一轮确认或选择，点击前重新核对条目，点击后读取媒体会话。query-only / artist-only 请求展示列表；没有精确匹配不自动改播。View 树适配仍受目标 App 页面和版本影响。
