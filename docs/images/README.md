# README 界面截图

## 采集基线

- 日期：2026-09-25
- 设备：Mi 9 SE（`grus`），Android 15 / LineageOS 22.2
- Launcher：`com.matrix.agent.launcher`，v0.6.14（versionCode 6014）
- 图片：1080 × 2340，PNG，ADB 原始截屏；保留系统状态栏与导航栏，未合成或替换界面内容。
- Host：截图时为 `HOST ONLINE`。会话、当前模型和安装状态均为采集时的实际状态，不是默认配置。

## 页面清单

| 图片 | 展示内容 |
|---|---|
| [launcher-conversation.png](launcher-conversation.png) | 对话消息、多步执行结果、工具折叠入口、模型胶囊与输入栏 |
| [launcher-tasks.png](launcher-tasks.png) | 自然语言任务输入、快捷入口、任务状态与轨迹区域 |
| [launcher-voice.png](launcher-voice.png) | 等待录音状态、录音 / 打断控制、实时转写与 TTS 配置入口 |
| [launcher-models.png](launcher-models.png) | 生效中的模型运行时与待提交的配置表单；表单值不代表已生效模型 |
| [launcher-downloads.png](launcher-downloads.png) | 模型市场滚动视图，包含下载状态、本地库存与 MNN 模型卡片 |
| [launcher-drawer.png](launcher-drawer.png) | 五个工作区入口与当前版本 |

对话图保留设备上既有的音量、亮度交互记录及转写文本。截图采集仅浏览页面，没有为展示重新执行这些操作。车辆能力的 OEM 集成范围见[主 README](../../README.md#当前边界)。

## 更新方式

在与源码版本一致的设备上启动 Launcher，等待 Host 连接，导航到目标页面；输入法、对话框和瞬时提示收起后再截图。模型表单保持凭据为空，只展示运行时投影；不要将密钥、私人地址或其他个人信息提交到公共仓库。

从仓库根目录采集，例如：

```bash
adb shell am start -n com.matrix.agent.launcher/.LauncherActivity
# 在设备上打开目标页面，再保存对应图片。
adb exec-out screencap -p > docs/images/launcher-conversation.png
```

逐张查看完整图片，确认页面、版本、连接状态与内容适合公开展示。同步更新本文件的采集基线及主 README 的日期、图片标题和引用；保持相对路径，以便各分支与本地预览均可使用。
