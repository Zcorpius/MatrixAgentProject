# README 系统架构图生成记录 · 2026-09-28

使用内置 `image_gen` 的 `infographic-diagram` 模式生成，再用两次定向编辑清理多余文字并校正连线。最终资产为 [matrix-agent-architecture-dark-2026-09-28.png](matrix-agent-architecture-dark-2026-09-28.png)，1536 × 1024 PNG。图中英文模块标签与当前代码职责对应；更精确的域间依赖参见[技术架构图](../开发与验证.md#architecture)。

![MatrixAgent 系统架构图](matrix-agent-architecture-dark-2026-09-28.png)

## 生成提示词

```text
Use case: infographic-diagram
Asset type: production-ready system architecture artwork for the main GitHub README of MatrixAgent, a custom Android ROM system agent.
Primary request: Draw a detailed, visually striking, technically disciplined architecture diagram. This is an accurate engineering blueprint, not a fantasy AI illustration. Landscape 3:2 or 16:10, high-resolution, with exceptionally crisp readable typography and precise connectors.

Visual direction: Premium dark-mode observability dashboard meets architectural blueprint. Deep midnight navy (#071925) backdrop, layered indigo and teal glass panels, luminous sea-glass cyan (#57DFCE) and restrained electric blue accents, tiny warm coral highlights only where useful. Controlled soft glow on the main flow and perimeter, subtle fine technical grid and micro-detail, delicate depth and faint frosted surfaces, strong hierarchy, generous negative space. Cool and cinematic yet clean and professional. Fine vector-like linework, consistent rounded corners, restrained thin icons, balanced alignment. No heavy 3D, no perspective distortions, no decorative chaos. Make text readable at README display width.

Composition: vertically stacked flow with clear downward arrows and four visual zones, all within a wide padded canvas.
Top heading: exact large text "MatrixAgent"; smaller exact subtitle "SYSTEM ARCHITECTURE".
ZONE 1 / CLIENTS: two equal cards, left exact heading "Matrix AI Launcher" with exact small line "Workspace · Conversation · Floating Characters"; right exact heading "Trusted Apps" with exact small line "OEM / System Clients". Two arrows merge downward. Illustrate the Launcher with a tiny abstract phone/window icon, not anime characters.
ZONE 2 / CONTRACT: one wide prominent card exact heading "Versioned SDK"; exact small line "AIDL · DTOs · Domain Managers · Reconnect". One clean arrow into the Host boundary.
ZONE 3 / EXECUTION AUTHORITY: a very clear larger enclosing boundary titled exactly "SYSTEM HOST"; small tag "Platform Signed · System UID". At its top, a luminous access gate bar exact heading "Root Binder"; exact secondary line "Identity · Contract · Policy Validation". A fine branching bus from this gate fans out to eight service-domain cards INSIDE the Host boundary, aligned in two rows of four with EXACT labels:
Row 1: "Conversation", "Tasks", "Schedules", "Voice"
Row 2: "Models", "Downloads", "Memory", "Handoff & Touch".
Use tiny appropriate line icons, but labels are the focus. The bus signifies internal Host-managed capabilities; no direct client path to these cards.
ZONE 4 / HOST FOUNDATIONS: within the SAME SYSTEM HOST boundary, below the domains, three broader supporting cards in one row with EXACT headings and captions:
"Execution" / "Capabilities · Providers"
"On-device Runtime" / "MNN · Java / JNI"
"Secure Data" / "Room · SQLCipher · KeyStore".
Use subtle integration lines from the domain layer into these foundations, but never imply that every domain talks to every foundation. On-device runtime and secure data must remain visibly INSIDE the Host boundary. Keep the overall routing unambiguous: Clients → Versioned SDK → Root Binder → Host domains / Host foundations.

Text accuracy is critical: render every quoted label exactly, with no substitutions, duplicate cards, extra words, random code, pseudo-text or fabricated logos. Correct casing and spelling. Only the specified copy. No Mermaid styling, no stock cloud icons, no robot mascot, no watermark, no footer slogan. The aesthetic should feel like a flagship open-source architecture illustration polished by a senior information designer.
```

## 定向修正

第一次编辑提示词：

```text
Use case: precise-object-edit
Asset type: final MatrixAgent README architecture infographic.
Edit the provided architecture image, keeping the central design, dark midnight-teal palette, title, all architecture cards, exact labels, and top-to-bottom client → SDK → Root Binder arrows intact. Make one focused information-design correction: remove ALL marginal annotations and decorative words outside the central architecture. Specifically delete the small slogan in the upper left, the marketing text in the upper right, the entire left column of "CLIENTS / CONTRACT / EXECUTION AUTHORITY / HOST FOUNDATIONS" captions and their descriptions, the entire right column of pseudo-technical captions, and the Android robot outline. These areas should become clean dark-grid negative space.
Inside SYSTEM HOST, remove the individual downward arrows from domain cards to the three foundation cards; use only a subtle thin horizontal separator between domains and foundations, since those arrows imply unverified direct dependencies. Keep the three foundation cards within the Host boundary.
No other changes. Preserve exact readable spellings: "MatrixAgent", "SYSTEM ARCHITECTURE", "Matrix AI Launcher", "Workspace · Conversation · Floating Characters", "Trusted Apps", "OEM / System Clients", "Versioned SDK", "AIDL · DTOs · Domain Managers · Reconnect", "SYSTEM HOST", "Platform Signed · System UID", "Root Binder", "Identity · Contract · Policy Validation", "Conversation", "Tasks", "Schedules", "Voice", "Models", "Downloads", "Memory", "Handoff & Touch", "Execution", "Capabilities · Providers", "On-device Runtime", "MNN · Java / JNI", "Secure Data", "Room · SQLCipher · KeyStore". Do not invent or duplicate text. Keep generous balanced whitespace and accurate alignment.
```

第二次编辑提示词：

```text
Use case: precise-object-edit
Asset type: final MatrixAgent README architecture infographic.
Preserve this image almost exactly: same composition, all text, all cards, colors, border, spacing, background, clients-to-SDK-to-Root-Binder arrow, and the clean empty margins. Change ONLY one missing connection inside SYSTEM HOST: add a fine elegant cyan connector descending from the bottom center of the "Root Binder" gate into a horizontal branching bus, with four short downward arrowheads ending at the top edges of the first-row domain cards "Conversation", "Tasks", "Schedules", and "Voice". This bus visually communicates that the Root Binder routes to Host domain services. It must remain entirely inside the Host boundary. Do NOT add arrows between domain cards, from domains to foundations, or from clients directly into Host domains. Keep the thin straight separator above the foundation cards. No new words, captions, symbols, marketing text, or layout changes. Text must remain precisely as in the input.
```

图片展示逻辑边界和主要职责，不表示全部回调路径。记忆由 Host 内部使用，并非独立公开的 SDK Manager。
