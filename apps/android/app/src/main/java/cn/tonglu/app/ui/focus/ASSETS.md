# 番茄酱原创头像 · Codex root

2026-10-17 用户指定全部Q版美少女由root亲自绘制。采用内置 imagegen；不使用外部 C 历史候选。母图为番茄红短发、琥珀眼睛、绿叶与小白花发饰、奶油白衬衣和红色披肩，同一身份延展六表情。透明PNG直接作为drawable-nodpi资源，UI按布局尺寸等比适配；没有手工裁切或去水印。原始生成PNG完整保留在work/pomodoro-core/art（本机证据，不依赖它运行）。

## 生成提示词组

母图：Original premium anime chibi bishoujo study companion, young adult woman, 2.5-head proportions, front three-quarter bust, warm amber eyes, fluffy tomato-red bob with peach tips and green tomato-leaf hairpin, tomato-red capelet with cream lining and cream blouse, green leaf collar bow. Friendly small wave and closed cream notebook. Beautiful girl with tomato themed accessories, not vegetable face or blob. Clean silhouette, delicate warm cel shading, readable at 44 pixels, transparent background, no lettering or watermark.

全部表情编辑均以同一母图为reference，锁定脸型、发色、发饰位置、服装、画风和胸像构图，要求透明底、完整头发与手部、安全边距、无文字/边框/水印：

- idle：母图微笑挥手，另一手抱学习笔记。
- focus：认真看打开的奶油白笔记本，一手执笔，嘴部安静专注。
- encourage：温暖鼓励笑容，一手竖拇指，另一手放在胸前。
- rest：放松微笑，双手捧素色奶油白水杯。
- paused：耐心微笑，一手托腮，另一手抱合上的笔记本。
- complete：弯眼开心笑，双手小拳头举到脸旁庆祝。

## 资源指纹

| 资源 | 像素 | 字节 | SHA256 |
|---|---|---|---|
| tomato_idle.png | 1310×1200 | 1783887 | `c93b33ce018fb286721eb574294cd29bf5c955e761f985823b547441854348ff` |
| tomato_focus.png | 1254×1254 | 1870974 | `5064e8964f14a0168f6f5806cf736c6f44bd86eed6edbf1511855aae69e9dc41` |
| tomato_encourage.png | 1254×1254 | 1896174 | `b1a78073ddad8b6612aad9af1c05c1a0324838838bf6acbff9d7791e1fc001f8` |
| tomato_rest.png | 1254×1254 | 1849642 | `17163a6b6759acabfd3cf68ee84923b9c235cd109350e579e1679d59f26c5074` |
| tomato_paused.png | 1254×1254 | 1856932 | `e7992f95b8c2ea2210bea2dbb5c48c9bf6fa7ef4d27180d2aa23a1fa54cbaf6b` |
| tomato_complete.png | 1254×1254 | 1851066 | `26273b8a374d904e929ee945e9ece01609309323285a3e0d74c5ef58ce00f52a` |

生成来源：Codex root / built-in imagegen；原创生成，无第三方角色图素材。PNG头为8-bit RGBA；六图已逐张目视检查，尚未把真机32dp观感/浅深边缘验收写为通过。
