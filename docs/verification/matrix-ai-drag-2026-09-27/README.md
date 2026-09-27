# Matrix AI 最新拖动截图

2026-09-27 18:46（Asia/Shanghai），在小米 Mi 9 SE / Android 15 上重新采集。README 原先引用了图标更新前的历史截图，本次改为显示 Matrix AI 实心图标的当前版本。

- Launcher 版本：0.7.1 / 7001；已安装 APK 与本地当前 APK 的 SHA-256 一致，见 `apk-integrity.json`。
- 运行生产 `OverlayWindow` 的 `YukinoDragWindowDeviceTest`，真实输入分发器注入左拖、右拖及松手，`UiAutomation.takeScreenshot()` 采集显示画面。
- 3 项用例通过：左右反转与松手恢复、取消及轻触展开、隐藏后恢复；见 `device-gesture-capture.txt`。本次为已有用例重跑，不增加测试总数。
- 已检查两张拖动图均显示深青色实心新版图标和完整的 `Matrix AI` 名称，人物跑步朝向与图注一致。
- 截图原样从设备拉取，没有拼贴或替换桌面内容。旧批次截图保留在原验证目录，当前预览引用本目录。

| 文件 | 内容 |
| --- | --- |
| [01-drag-left.png](screenshots/01-drag-left.png) | 向左拖动，雪乃向左跑步 |
| [02-drag-right.png](screenshots/02-drag-right.png) | 同一手势反转，雪乃向右跑步 |
| [03-released.png](screenshots/03-released.png) | 松手恢复工作动作 |
