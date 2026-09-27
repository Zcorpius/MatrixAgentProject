# 实心中心编辑提示词

方式：内置 imagegen 图片编辑；`transparent_background=true`。

编辑目标：项目中的 `matrix-agent-launcher/src/main/res/drawable-nodpi/matrix_ai_mark.png`（上一版带菱形镂空的前景）。最终结果替换同一资源路径。

```text
Use case: precise-object-edit
Asset type: existing Matrix AI Android adaptive launcher icon foreground.
Input image: the attached transparent mint/teal metallic M emblem is the EDIT TARGET.
Primary request: fill ONLY the transparent diamond-shaped hole at the very center of the emblem with solid opaque mint/teal satin metal. The central crossing should be a continuous, solid connection. Continue the existing diagonal ribbon surfaces naturally across this hole, with subtle highlights and occlusion shading to show the crossing layers. There must be no transparent pixels, black void, recessed hole, or opening in the central diamond area. Do not simply add a detached diamond jewel; the filled area must feel like part of the existing connected ribbon material.
Keep unchanged: the M-like outer silhouette, the outer folded ribbon geometry, the two side recesses, the mint/jade/deep-teal palette, the frosted metallic texture, existing lighting, centered scale, composition, and transparent padding around the object. Keep the background genuinely transparent. Preserve the existing bottom and top exterior notches; only the enclosed center diamond gets filled.
Constraints: one finished icon asset, no text, no enclosing badge, no background tile, no watermark, no new objects. Preserve the original design as closely as possible outside the center repair.
```
