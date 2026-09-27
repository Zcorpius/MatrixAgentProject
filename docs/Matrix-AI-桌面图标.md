# Matrix AI 桌面图标

桌面应用名称改为 `Matrix AI`，启动图标改为深青色渐变背景上的薄荷绿立体矩阵符号。按最终确认的设计，中心采用实心金属交叠，用明暗表现前后层次，保留图标外围透明区域。

## 资源结构

- `matrix-agent-launcher/src/main/res/drawable-nodpi/matrix_ai_mark.png`：内置 imagegen 生成的透明前景原图，保留原始像素与透明通道，未使用 API 或 CLI 生成回退。
- `drawable/ic_launcher_matrix_foreground.xml`：通过原生 drawable 的 18% 四边内缩控制前景比例，避免把圆角或底板烘焙进图片。
- `drawable/ic_launcher_matrix_background.xml`：独立的深青色渐变背景。
- `mipmap-anydpi-v26/ic_launcher_matrix.xml`：前景、背景与单色图层共用一份自适应图标定义。项目最低 Android 版本为 API 28，所有支持设备均可使用。Android 13 及以上可用透明前景轮廓参与桌面主题着色；旧版解析器跳过不识别的图层。
- `AndroidManifest.xml`：常规与圆形图标入口共用自适应资源；应用标签仍引用 `app_name`。

Android 桌面负责最终形状裁切。图层和安全区域参考 [Android 自适应图标文档](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。包名、Activity 入口与用户数据不变。

旧版系统对未知图层的兼容行为已核对 [Android 9 AdaptiveIconDrawable 解析实现](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/graphics/java/android/graphics/drawable/AdaptiveIconDrawable.java)：仅处理 `background` 与 `foreground`，跳过其他标签，因此无需复制 API 33 版本资源或抑制 Lint 警告。

## 原版生成提示词

生成方式：内置 imagegen，`transparent_background=true`。以下为实际提交的完整提示词；生成结果尺寸为 1254 × 1254，RGBA。

```text
Use case: logo-brand
Asset type: production Android adaptive launcher icon foreground, square 1024x1024, genuinely transparent background.
Primary request: create a fresh, distinctive icon symbol for the Android AI assistant app Matrix AI, replacing an ordinary blue M in a white square. Create ONE finished foreground mark, no mockup or variations.
Subject: a compact sculptural matrix-core emblem made of two interlocking angular folded ribbons. The outer silhouette subtly suggests an M, while the central negative space forms a small crisp diamond. Visually intelligible as a single symbol at 48dp, bold broad strokes and a memorable silhouette.
Style/medium: carefully designed premium 3D icon, restrained frosted glass and satin-metal depth, crisp geometric edges and softly luminous internal surfaces. The form should feel intelligent, calm and precise.
Color palette: luminous mint, jade and deep teal, with small pale-silver edge highlights, consistent with a dark teal AI assistant interface. Strong contrast when placed over a deep blue-green background.
Composition/framing: one centered floating symbol in a straight-on view with subtle shallow dimensional shading, roughly 80 percent of the image width and 75 percent of the height, balanced and clean around the perimeter. Entire object fully visible with generous alpha padding.
Constraints: transparent background with clean alpha edges, no enclosing badge, no square tile, no white plate, no ground plane, no cast shadow outside the emblem, no text, no wordmark, no tiny circuitry or busy particles, no extra objects, no photographic setting, no watermark. This asset is only the foreground; the Android app supplies its own dark adaptive background.
```

## 验证

中心实心版使用内置 imagegen 编辑原图并替换原前景资源。最终编辑提示词见 [edit-prompt.md](verification/matrix-ai-icon-solid-2026-09-27/edit-prompt.md)，本次验证记录见 `docs/verification/matrix-ai-icon-solid-2026-09-27/`；下列记录对应首次接入。

构建、Lint 与真机验证记录保存在 `docs/verification/matrix-ai-icon-2026-09-27/`。

- `:matrix-agent-launcher:assembleDebug :matrix-agent-launcher:lintDebug`：通过；Lint 为 0 errors、39 warnings，本次图标及 Manifest 资源没有诊断。
- APK 标签检查：`application-label` 为 `Matrix AI`，应用图标引用自适应 mipmap。
- 小米 Mi 9 SE / Android 15：覆盖安装成功，桌面名称完整显示，彩色图标裁切正常，无白色底板。
- 点击桌面 `Matrix AI` 图标：正常进入 `LauncherActivity`，恢复已有对话页面，Host 在线。见 `launch-result.txt` 与 `launched.png`。
- 单色图层已接入并通过 Lint；未切换用户桌面的主题图标设置。
