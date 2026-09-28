# README 图片来源与维护

主 README 的六张页面图及外观模式、导航抽屉链接，均来自 **2026-09-28 23:31–23:34** 同一轮真机采集。此前的 2026-09-27 页面与小窗图保留为历史记录，不再用作 README 的当前界面预览。所有真机图片均保留设备实际内容，未合成角色、替换图标或修改页面文案。

## 当前界面：2026-09-28

- 设备：Mi 9 SE（`grus`），LineageOS 22.2 / Android 15；Launcher 显示 `HOST ONLINE`。
- 已安装 Launcher：`0.7.1 / 7001`，最近更新时间 2026-09-28 23:03:09（UTC+8）。从设备导出的 APK 与本地 debug APK 的 SHA-256 均为 `561a14003580b762922ca8fb03f3ca6f8d901f86144292acc7c19922ce827b92`。
- 图片：1080 × 2340 PNG，经 `adb exec-out screencap -p` 直接采集。仅浏览页面、滚动对话和设置，没有提交消息、修改模型或角色配置、开始录音和下载资源。

| 图片 | 实际展示内容 |
|---|---|
| [对话交互](2026-09-28/launcher-refresh/conversation.png) | 已有媒体测试会话的消息气泡、历史提示、模型胶囊和输入区；搜索未成功与候选列表属于当时会话内容。 |
| [任务中心 · 模板](2026-09-28/launcher-refresh/tasks-templates.png) | 四个页签，以及“每日行程提醒”和“Agent 日程简报”两个模板。 |
| [语音功能](2026-09-28/launcher-refresh/voice.png) | 等待开始状态、录音控制、空转写区和未配置的腾讯云 TTS 入口。 |
| [模型接入](2026-09-28/launcher-refresh/models.png) | 当前云端运行时 `glm-5.3`、推理来源选择和空凭据输入框；未进行连接测试。 |
| [模型市场](2026-09-28/launcher-refresh/market.png) | 没有进行中的下载；本地模型库中显示已安装的 `Qwen3.5-2B-MNN`。 |
| [设置 · 悬浮形象](2026-09-28/launcher-refresh/settings-pets.png) | 八套颜色主题的下半部分与四个角色卡片；宁姚是采集时已保存的选择。 |
| [设置 · 外观模式](2026-09-28/launcher-refresh/settings-theme.png) | 跟随系统、浅色、深色以及颜色主题的上半部分。 |
| [导航抽屉](2026-09-28/launcher-refresh/navigation.png) | 六个页面入口与版本号；背景是同一设置页面。 |

尺寸、哈希与来源映射见[本轮 manifest](2026-09-28/launcher-refresh/manifest.json)。

## 此前采集基线

- 设备：Mi 9 SE（`grus`），Android 15 / LineageOS 22.2。
- Host / Launcher：开发版本 `0.7.1 / 7001`，当前 Parcel schema 13；不同开发构建以对应验证记录的 APK 摘要区分。
- 图片：1080 × 2340 PNG，ADB 原始截屏，保留状态栏与导航栏。README 中仅通过 HTML 宽度缩小显示，点击可查看原图。
- 工作区：2026-09-27 20:20–20:26（UTC+8）重新浏览采集，Host 在线；没有为截图创建计划、提交对话、播放媒体、开始录音、修改模型配置或下载资源。
- 设置页：2026-09-28 23:24（UTC+8）从已连接的 Mi 9 SE 原始截屏；Host 在线，选中宁姚，仅滚动页面查看设置，没有修改选择。
- 小窗：来自 2026-09-27 20:02 左右的一轮 47 项 Launcher 真机测试，重新从设备导出；该轮证据见[注视与挥手验证](../verification/yukino-interaction-2026-09-27/README.md)。

## 此前页面与小窗图片

| 图片 | 实际展示内容与阅读说明 |
|---|---|
| [对话交互](2026-09-27/launcher-conversation.png) | 既有测试会话“播放李健的传奇”；助手找到候选并询问是否播放。图中未展示下一轮确认，也不能据此认定歌曲已经播放。 |
| [外观与悬浮形象](2026-09-28/launcher-settings-appearance.png) | 设置页滚动到颜色主题和四个角色卡片；宁姚为设备当时已保存的选择。截图未展示外观模式控件，它们位于本页上方。 |
| [计划入口](2026-09-27/launcher-plans.png) | 计划 / 运行记录 / 模板 / 临时任务四个页签，新建与日历绑定入口；当时计划列表为空。 |
| [工作流模板](2026-09-27/launcher-workflows.png) | “每日行程提醒”“Agent 日程简报”及其查询、摘要、交付步骤；第二张卡片继续延伸到屏幕下方。 |
| [语音功能](2026-09-27/launcher-voice.png) | 等待开始，录音 / 打断控制，空转写区，腾讯云 TTS 未配置；图片不作为语音闭环通过的证据。 |
| [模型接入](2026-09-27/launcher-models.png) | 当前运行时是 `glm-5.3` 云端；表单的 `glm-5.2` 尚未提交，API Key 为空。运行时与编辑表单不能混为一谈。 |
| [模型市场](2026-09-27/launcher-downloads.png) | 页面滚动到下载、本地库存和模型卡片；当时无下载、无端侧模型。页面目录提示的保证范围以[开发文档中的当前边界](../开发与验证.md#boundaries)为准。 |
| [导航抽屉](2026-09-27/launcher-drawer.png) | 五个工作区入口、跨应用悬浮窗入口与 v0.7.1。抽屉内部仍使用 MatrixAgent 品牌字样；桌面名称与启动图标是 Matrix AI。 |
| [完整会话小窗](2026-09-27/overlay-conversation.png) | QQ 音乐上方展示“打开QQ音乐”的用户请求、折叠执行过程、助手回复和输入区；背景搜索结果不等于这条会话完成了搜索或选曲。 |
| [展开执行过程](2026-09-27/overlay-process.png) | 展开“思考与工具”后列表占据消息区，底部保留未发送草稿；此截图不同时展示完整用户和助手正文。 |
| [草稿与输入法](2026-09-27/overlay-editing.png) | 输入法已打开，草稿及发送入口保持可见；不代表草稿已经发送。 |

三张小窗图的设备来源为 `/sdcard/Android/data/com.matrix.agent.launcher/files/overlay-verification/`，文件分别对应 `02-panel.png`、`03e-process-expanded.png`、`03-editing.png`。它们属于 2026-09-27 的真实 `OverlayDeviceTest` 产物。`matrix.debugTraceUi` 控制过程展示；debug 与 release 均按该显式开关构建。

图片的尺寸、SHA-256 和来源映射见 [2026-09-27 manifest](2026-09-27/manifest.json) 与 [2026-09-28 manifest](2026-09-28/manifest.json)。

## 功能指南引用的验证图片

| 图片组 | 来源与用途 |
|---|---|
| [实心图标](../verification/matrix-ai-icon-solid-2026-09-27/home.png) | 桌面 Matrix AI 名称、中心实心的新版图标；见[安装验证](../verification/matrix-ai-icon-solid-2026-09-27/README.md)。 |
| [向左跑步](../verification/matrix-ai-drag-2026-09-27/screenshots/01-drag-left.png) / [向右跑步](../verification/matrix-ai-drag-2026-09-27/screenshots/02-drag-right.png) | 在实心图标更新后重新采集，桌面背景中的应用图标为新版；见[手势与截图记录](../verification/matrix-ai-drag-2026-09-27/README.md)。 |
| [上方触点](../verification/yukino-interaction-2026-09-27/screenshots/01-look-before-swipe.png) / [下滑跟随](../verification/yukino-interaction-2026-09-27/screenshots/02-look-after-swipe.png) / [挥手](../verification/yukino-interaction-2026-09-27/screenshots/04-wave.png) | 同一角色验证目录；下滑图同时展示底层抽屉正常收起，动作恢复另见[松手后的任务姿态](../verification/yukino-interaction-2026-09-27/screenshots/03-task-resumed.png)。 |

静态图片只展示某一帧。持续动画、输入不拦截、松手恢复与挥手条件应结合测试日志和代码判断，不能仅凭一帧推断完整行为。

## 历史图片

下面六张原图采集于 **2026-09-25 · v0.6.14 / 6014**，设备相同，保留供历史文档使用，不再作为当前主 README 的工作区预览：

| 历史图片 | 当时展示内容 |
|---|---|
| [launcher-conversation.png](launcher-conversation.png) | 音量、亮度等既有对话与工具反馈 |
| [launcher-tasks.png](launcher-tasks.png) | 原临时任务工作台，不能代表当前含四个页签的任务中心 |
| [launcher-voice.png](launcher-voice.png) | 原语音状态与 TTS 入口 |
| [launcher-models.png](launcher-models.png) | 当时的模型运行时与配置表单 |
| [launcher-downloads.png](launcher-downloads.png) | 当时的下载、本地库与模型目录 |
| [launcher-drawer.png](launcher-drawer.png) | 原工作区导航与 v0.6.14 版本 |

## 后续更新方式

1. 确认设备、已安装 APK 与源码基线一致，并记录实际版本和构建摘要。进入 Launcher，等待 `HOST ONLINE`。
2. 浏览目标页面，等待动画、提示与加载稳定。保留真实状态；模型凭据为空，不展示私人配置或私人会话。
3. 保存到独立日期目录；角色、窗口和手势场景优先复用该轮仪器测试的原始产物，不覆盖历史证据。
4. 逐张查看原图，检查页面与标题一致、图标为目标版本、关键控件可见。需要滚动的截图注明滚动位置，执行过程展开图不要标作完整消息图。
5. 同步更新主 README 的图片引用、标题、替代文本，本文件的采集基线和清单，以及 manifest 的尺寸 / 来源 / SHA-256。检查本地链接和页面渲染。

从仓库根目录采集示例，目录日期按实际采集时间调整：

```bash
adb shell am start -n com.matrix.agent.launcher/.LauncherActivity
# 在设备上浏览目标页面后保存原图：
mkdir -p docs/images/2026-09-27
adb exec-out screencap -p > docs/images/2026-09-27/launcher-conversation.png
```
