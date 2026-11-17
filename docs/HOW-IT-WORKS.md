# 它怎么工作

面向想改这个壳的人：三件事——网页资源怎么加载、原生层补了哪些网页做不到的能力、Token 用量面板的数字从哪来。

代码只有 5 个文件：

| 文件 | 内容 |
| --- | --- |
| `app/src/main/java/com/k7/deepseekchat/MainActivity.kt` | 资源映射、权限、文件选择、下载、返回键、键盘 insets、状态栏配色 |
| `app/src/main/java/com/k7/deepseekchat/ImageInspector.kt` | 图片查看与裁剪（自绘 View） |
| `app/src/main/java/com/k7/deepseekchat/AttachmentSheet.kt` | 附件面板（拍照 / 相册 / 文件 / 最近照片） |
| `app/src/main/java/com/k7/deepseekchat/TempFilesProvider.kt` | 把原生选中的文件借给 WebView 读取 |
| `app/src/main/assets/web/shim.js` | 注入页面的桥：主题同步、下载拦截、剪贴板、外链、键盘高度 |
| `app/src/main/assets/web/usage.js` | 注入页面：旁路嗅探 API 响应里的 `usage`，渲染用量面板 |

## 一、网页资源怎么加载

```
assets/web/            ← chat-demo 的构建产物（index.html + main bundle + 88 个异步 chunk）
    ↓ MainActivity.shouldInterceptRequest
https://appassets.androidplatform.net/index.html
    ↓ 这个 origin 是 https，属于「安全上下文」
localStorage / IndexedDB / getUserMedia / 直连 https://api.deepseek.com 全部正常
```

用本地虚拟域名而不是 `file://` 有三个原因：`file://` 不是安全上下文（麦克风直接不可用）；
`file://` 下 localStorage / IndexedDB 行为不可靠；`file://` 页面的 Origin 是 `null`，拿不到 API 的 CORS 放行。

**离线包 + CDN 兜底**：本地缺失的资源路径会同步回源 `cdn.deepseek.com/chat-demo/`（进程内缓存），
所以 CDN 更新主线、老包里缺个 chunk 也不会白屏。

`index.html` 在返回给 WebView 之前会注入 `shim.js` 与 `usage.js`（`rewriteIndex`），
仓库里的 `index.html` 保持上游原样。

## 二、原生侧补了什么

| 网页能力 | 网页里的实现 | 壳里的处理 |
| --- | --- | --- |
| 语音输入 | `getUserMedia` + `AudioContext` 频谱 | `onPermissionRequest` 授予 `AUDIO_CAPTURE`，运行时申请 `RECORD_AUDIO` |
| 附件 / 导入 ZIP | `<input type="file">` | `onShowFileChooser` → 系统文件选择器（多选、accept 映射） |
| 导出聊天 ZIP | JSZip + `<a download>` + `blob:` | shim 拦 `HTMLAnchorElement.click` → `fetch(blob)` → dataURL → 原生写入「下载/DeepSeek/」 |
| 朗读 / 播放音频 | 自动播放 | `mediaPlaybackRequiresUserGesture = false` |
| 复制代码块 | `navigator.clipboard` | 走原生 `ClipboardManager`，避免 WebView 静默拒绝 |
| 外链 | `window.open` / `target=_blank` | 转交系统浏览器 |
| 深浅色 | 页面内切换 | shim 把页面底色交给原生，状态栏 / 导航栏与图标明暗跟随 |
| 返回键 | 浏览器行为不可控 | 有浮层先发 `Escape` → 否则网页后退 → 首屏双击退出 |

### 键盘：native 出数字，页面出布局

WebView 里改尺寸会重建绘制表面 + 整页重排，逐帧追不上输入法动画（观感是「跳一下、空一两帧、再跳」）；
而 `visualViewport` 在 WebView 里看不到 IME（要 WebView M139 起才通过 visual viewport 生效），
`navigator.virtualKeyboard` 也拿不到高度。

所以分工是：`MainActivity.handleInsets` 在 `WindowInsetsAnimation.Callback.onProgress` 里逐帧读 ime inset，
调 `__dsKeyboardInset(devicePx)`；`shim.js` 把这个值换算后写到「铺满一屏的根容器」的 `height` 上
（inline + `!important`，防止页面自己的 `--app-viewport-height` 抢回去）。这是渲染器内部的纯布局变化，
不换绘制表面，所以能跟着输入法动画逐帧走。

根容器的找法：从 `textarea` 往上找最外层 `position: fixed` 的祖先，**不写死 CSS-module 哈希**；
不要退回 `body.firstElementChild`（那是 frame 的父节点，设了高度完全没效果）。

### 图片裁剪

`ImageInspector` 是自绘 View，不依赖第三方库：

- 预览态全屏黑底，双指缩放（1x–8x）、双击放大、拖动；右下角胶囊「裁剪」。
- 裁剪态裁剪框「屏幕固定 + 跟随图片矩阵」：`ZoomView` 提供 `imageRect()` / `pinchTo()` / `panBy()`，
  框内单指拖动是**拖图**，选区只由四角 / 四边中点控制；结果用矩阵逆映射算原图像素，不靠屏幕比例换算。
- 打开看图与进入裁剪时整体缩一点（`min(0.86, 让开上下 UI 带的最大倍数)`），所以框不会贴屏幕边。
- 提交 = 替换：结果作为同名新附件注入页面，旧卡片移除。只能编辑还没发出去的输入区附件。

## 三、Token 用量面板的数字从哪来

页面只打一个端点：`POST https://api.deepseek.com/anthropic/v1/messages`（Anthropic 兼容格式，SSE 流式）。
`usage` 只出现在流里，而且页面写 IndexedDB 时会把它丢掉，历史消息无法回填。
所以 `usage.js` 在页面上下文里包一层 `window.fetch`，对响应 `clone()` 一份自己读，**不碰页面原本的流**。

**口径**

- 以最后一个 `message_delta.usage` 为准，不与 `message_start` 相加（后者只是本轮新增部分）。
- `input_tokens` = 缓存未命中，`cache_read_input_tokens` = 缓存命中；`output_tokens` 已含思考 token
  （另存 `thinking_tokens` / `safety_tokens`）。
- 每轮请求的输入包含完整对话历史，所以累计「输入」远大于对话字数——不是重复计费。
- 点了「停止生成」、没收到 `message_stop` 的请求拿不到最终 usage：只记一行「未完成」，
  不进任何合计、不算费用。

**费用**：官方人民币价目 × 峰谷估算。高峰 = 北京时间工作日 9:00–12:00、14:00–18:00（没有法定节假日表）。
搜索 / 抓取 ¥5 每千次；语音识别 0.39 元/小时、朗读 1.5 元/万字符（当前限时 5 折）。
文档解析 ¥1.5/千页——页数在客户端拿不到，只记「N 个（未计入）」。

**视觉 token 单列**：图片走视觉 token，折算后并进 `input_tokens`，API 的 `usage` 不单列。
这里按官方「图片 Token 计算器」的算法自己算（patch 14 / 下采样 3 / 上限 1024 / 最小像素 295936，
`tokens = nH×(nW+1)+2`），计费不变，只是展示时从「未命中输入」里扣出来单列。
上传成功后用响应里的 `file_id` 记住这张图的 token；之后每轮请求按请求体 `content[].source.file_id`
反查（历史消息里重发的图也算），所以重复发送的图片不会重复计费、也不会重复展示。

**会话归集**：标题会重复，所以用 App 自己的 IndexedDB `sessionSummaries`
（`{id, title, createdAt, updatedAt}`）把请求挂到会话 id 上；取不到 id 的记录退回标题精确匹配。

**入口与存储**：标题栏「开启新对话」左侧常驻当前会话费用（首页退成今日费用），点开是底部面板；
记录存 `localStorage["deepseek-usage:v1"]`（最多 1000 条 + 终身累计），可一键清空。

## 四、改的时候注意

- 注入脚本里的选择器只用语义锚点（`div[class^="header-"]`、`button[aria-label="开启新对话"]`、
  `.ds-assistant-message-main-content`），不要用 CSS-module 哈希——重新抓包后哈希会变。
- 「最近请求」列表会跳过图片 / 文档上传事件（`kind=image|file`）：它们 `c=0`、没有会话，
  视觉 token 已经体现在对应请求那一行里。
- 新增费用类别要同时改三处：`CATS` 色表、`costCats()` 的分摊、`sessionTable()` / `noteLines()` 的行。
