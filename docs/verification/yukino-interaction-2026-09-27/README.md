# 全屏触点注视与挥手：接入及真机验证

2026-09-27，Mi 9 SE / LineageOS 22.2 / Android 15（API 35）。Host、Launcher 和 Launcher 测试 APK 已配套安装，版本仍为开发构建 `0.7.1 / 7001`，Parcel schema 更新至 13。基于 main `40e868c`，实现分支 `codex/pet-touch-greeting`。

## 用户行为

- 雪乃在屏幕上显示时，点击其他应用或桌面也会朝触点看；滑动使用 16 个方向姿态，中心 12dp 使用中立帧。抬手保留 800ms，再恢复之前的任务动作。
- 多指始终跟随第一根手指；第一指抬起即结束，不接管剩余手指。新的触碰会刷新注视与恢复计时。
- 同一会话第一次可见时挥手一次；离开至少 5 分钟后回来再挥手。展开、收起面板和短暂切换不会重复挥手。问候历史在当前 Launcher 进程内保留，最多 64 个会话。
- 触碰可以打断挥手；拖动角色优先左右跑步；新的完成、失败、待确认结果会打断注视或挥手，拖动期间的新结果在松手后显示。任务角标始终来自真实任务状态。
- 面板展开、窗口主动隐藏、锁屏、切换用户、断线和关闭会停止订阅。失效会话的回调不能重新改变角色。

## 架构

```text
Host：InputMonitor（目标 ROM 的输入副本）
  → PrimaryTouchTracker（稳定跟随第一指）
  → 同用户可信 Launcher 身份校验 / 30 秒租约 / Binder 死亡释放
  → 独立 SDK：IOverlayInteractionService、OverlayPointerSample、OverlayInteractionManager
Launcher：OverlayPointerClient（可见期订阅、10 秒续约、主线程合并回调）
  → OverlayController / OverlayWindow（所有权与窗口生命周期、屏幕到头部坐标）
  → LookDirection / PetGreetingPolicy / YukinoPetView（方向、问候、动画优先级与恢复）
```

输入回执始终使用 `finishInputEvent(event, false)`，从不调用 `pilferPointers`；原窗口继续接收其输入事件。没有全屏透明触摸层，也不使用会拦截应用事件的无障碍触摸监听。依据 [Android 15 InputMonitor 源码](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/InputMonitor.java) 的输入副本语义实现。

Host 每 33ms 至多分发一次 MOVE，DOWN/UP/CANCEL 及时分发；客户端只保留一个待处理 UI 样本，丢弃超过 250ms 的迟到样本。屏幕旋转会取消旧手势，坐标不跨旋转沿用。原始触点不进入日志、数据库或模型请求。

图片沿用现有后台解码与 3MiB LRU；`look` 是 16 个静态姿态，不是循环帧动画。临时动作共享同一个恢复目标，避免“拖动结束又回到旧注视/旧挥手”的动作链。异步解码订阅取消及 generation 校验继续阻止旧图片替换当前状态。

边界：全屏观察需要与系统匹配的 framework、平台签名及 `MONITOR_INPUT` 权限，本次验证仅覆盖上述 Android 15 设备。API 35 以下不发布输入观察服务。系统设置等主动隐藏悬浮窗的页面仍遵循 Android 的显示限制；不会绕过系统隐藏策略。挥手问候在结果角标存在或重要结果初次展示时不抢占结果提示。

## 结果

| 验证 | 结果 |
| --- | --- |
| 根 `verifyArchitecture`（五模块 check、SDK 发布依赖检查） | 通过 |
| JVM 测试 | 1,516 项，0 失败/错误/跳过：Host 1,414、SDK 20、Launcher 70、独立测试 APK 两变体合计 4、ondevice 8 |
| Host / Launcher / Launcher androidTest 构建及覆盖安装 | 通过 |
| `GlobalPetInteractionDeviceTest` | 5 项：真实 Host→SDK→UI、桌面点击、抽屉滑动、触碰打断挥手、多指不转移、解除订阅与 30 秒无人续约回收 |
| `YukinoPetDeviceTest` | 15 项：真实 WindowManager/Canvas/Looper、16 方向资源、静态姿态、800ms 恢复、重置计时、优先级、缓存与迟到加载 |
| `YukinoDragWindowDeviceTest` | 3 项：左右跑步、反转、取消、松手和隐藏后的恢复 |
| `OverlayControllerDeviceTest` | 22 项：提交所有权、展开收起、断线、关闭、锁屏广播、会话与面板回归 |
| `OverlayDeviceTest` | 2 项：真实 QQ 音乐交接/面板/草稿/返回，以及已有 QQ 音乐内的搜索与生产 Provider 交接 |
| 最终设备全套 | **47 项通过，96.081 秒** |

5 分钟问候边界采用确定性的 JVM 时钟输入测试；并未让设备空等 5 分钟。设备手势通过 UiAutomation 注入真实 InputDispatcher，不是直接调用 `lookAt` 替代跨进程验证；动画细节另有确定性解码顺序的组件测试。

早期用系统设置做截图背景时，虽然底层列表能滚动，但系统隐藏了悬浮图层；同时发现测试进程直接启动页面会遇到后台启动限制。最终改为 shell 启动系统桌面、在允许悬浮窗的应用抽屉验证真实输入，并修正测试对“抽屉一定可滚动”和非空节点文本的假设。最终完整重跑结果以上表和原始日志为准。

## 真机截图

<table>
  <tr><th>触点在上方</th><th>向下滑动后跟随</th><th>挥手</th></tr>
  <tr>
    <td><a href="screenshots/01-look-before-swipe.png"><img src="screenshots/01-look-before-swipe.png" width="240" alt="应用抽屉上方触点让雪乃抬头注视" /></a></td>
    <td><a href="screenshots/02-look-after-swipe.png"><img src="screenshots/02-look-after-swipe.png" width="240" alt="滑动正常收起应用抽屉，雪乃跟随下方触点" /></a></td>
    <td><a href="screenshots/04-wave.png"><img src="screenshots/04-wave.png" width="240" alt="雪乃播放挥手，之后恢复任务动作" /></a></td>
  </tr>
</table>

[松手恢复任务动作](screenshots/03-task-resumed.png)。截图为设备原图，未合成角色。

## 复现及证据

```bash
./gradlew verifyArchitecture :matrix-agent-service:assembleDebug :matrix-agent-launcher:assembleDebug :matrix-agent-launcher:assembleDebugAndroidTest
adb install -r matrix-agent-service/build/outputs/apk/debug/matrix-agent-service-debug.apk
adb install -r matrix-agent-launcher/build/outputs/apk/debug/matrix-agent-launcher-debug.apk
adb install -r matrix-agent-launcher/build/outputs/apk/androidTest/debug/matrix-agent-launcher-debug-androidTest.apk
adb shell am instrument -w -r -e class com.matrix.agent.launcher.overlay.pet.GlobalPetInteractionDeviceTest,com.matrix.agent.launcher.overlay.pet.YukinoPetDeviceTest,com.matrix.agent.launcher.overlay.pet.YukinoDragWindowDeviceTest,com.matrix.agent.launcher.overlay.OverlayControllerDeviceTest,com.matrix.agent.launcher.OverlayDeviceTest com.matrix.agent.launcher.test/androidx.test.runner.AndroidJUnitRunner
```

此验证安装的是 Launcher instrumentation APK，不安装带系统共享 UID 的 Host 测试 APK。全套流程依赖已配置的 Host、悬浮窗权限、QQ 音乐以及设备当前中文系统桌面；部署新 schema 时所有 SDK 消费端需配套构建。

原始证据：[架构检查](architecture-check.txt)、[最后一次测试包构建与 Lint](launcher-device-build.txt)、[JVM 汇总](jvm-tests.json)、[设备全套结果](device-tests.txt)、[APK SHA-256](apk-sha256.json)。
