# MatrixAgent 架构图生成记录

生成方式：内置 `image_gen`，用途为主 README 的逻辑分层概览。图中英文标签与代码职责对应；精确模块依赖与数据流以 [Mermaid 源图](../开发与验证.md#architecture) 为准。

- 成图：[matrix-agent-architecture.png](matrix-agent-architecture.png)，1536 × 1024 PNG，生成后原样复制入仓库。
- 日期：2026-09-27，对应 v0.7.1 文档基线。
- 核对范围：客户端 → SDK → Host 的入口关系；对话、任务、计划、语音、模型、下载、记忆、交接 / 触点均位于 Host；执行、MNN 与安全存储是 Host 侧支撑能力。
- 画面省略各域之间的调用边、回调方向及部分扩展接口，模块位置不表示它们互相调用。记忆由 Host 内部使用，不是独立公开的 SDK Manager。

## 成图

![MatrixAgent 的客户端、稳定 SDK 与系统 Host 三层逻辑架构](matrix-agent-architecture.png)

## 提示词

```text
Use case: infographic-diagram
Asset type: polished system architecture illustration for a GitHub README, MatrixAgent Android project.
Create an exceptionally clear, elegant engineering diagram, landscape 3:2 composition, approximately 2400 x 1600 or comparable high resolution. This is a real logical architecture, not a decorative conceptual network.
Style: restrained editorial technical design. Deep teal ink #143942 on a warm pale background #f4f8f6, mint green accents #62dcb2, subtly tinted cards, thin precise connecting lines, generous whitespace, crisp large sans-serif typography. Flat diagram with only very subtle depth. No neon glow, perspective, 3D towers, decorative circuitry, robot mascots or marketing slogans. All labels must be perfectly spelled and easy to read at README width. No logos needed.
Top left title EXACT: "MatrixAgent"
Subtitle EXACT: "SYSTEM ARCHITECTURE"
Use a stacked composition with three clearly separated numbered logical layers. Do not draw unnecessary internal arrows or imply that adjacent domain cards call one another.
LAYER 1: title "01  CLIENTS". Two equal cards side by side:
- "Matrix AI" with smaller text "Workspace · Conversation · Yukino"
- "Trusted Apps" with smaller text "OEM / System Clients"
A single clean connector from each client card joins and points downward to Layer 2.
LAYER 2: one full-width bar titled "02  STABLE SDK" with subtitle "AIDL · Parcelable DTOs · Domain Managers".
One connector downward from SDK into the Host entry gate.
LAYER 3: a large, clearly bounded container titled "03  SYSTEM HOST" with small top-right text "Platform signed · System UID".
Inside Host, a full-width entry gate bar reads "Identity & Contract Validation".
Below this gate, place exactly eight domain cards in a balanced four-column, two-row grid:
Row 1: "Conversation", "Tasks", "Schedules", "Voice".
Row 2: "Models", "Downloads", "Memory", "Handoff & Touch".
Every domain card must be inside the SYSTEM HOST boundary.
At the bottom inside Host, use three wider supporting blocks:
- "Execution" with smaller line "Policy · Capabilities · Providers"
- "On-device Runtime" with smaller line "MNN · Java / JNI"
- "Secure Data" with smaller line "Room · SQLCipher · KeyStore"
Draw only the top-level client → SDK → Host entry-gate connectors. The grid expresses Host responsibilities and shared infrastructure, not a complete dependency graph. No database or native runtime outside the Host. Do not add cloud logos, untrusted clients, direct client-to-database paths, made-up features, extra modules, or extra words. Keep ample margin and a clean quiet footer with the EXACT text "Host owns execution. SDK defines contracts. Clients present state."
```
