# 第三方组件与许可

本仓库的**壳代码**以 MIT 许可发布（见 [`LICENSE`](LICENSE)）。下面列出的第三方内容按各自许可分发。

范围说明：根目录的 `LICENSE` 只覆盖自行编写的安卓壳代码与注入脚本（`MainActivity.kt`、
`ImageInspector.kt`、`AttachmentSheet.kt`、`TempFilesProvider.kt`、`shim.js`、`usage.js`、`tools/`）；
`android/app/src/main/assets/web/` 下的网页构建产物版权归 DeepSeek，以同样的 MIT 许可分发。

## 1. chat-demo —— 网页前端构建产物

| 项 | 内容 |
| --- | --- |
| 上游仓库 | <https://github.com/deepseek-ai/chat-demo> |
| 线上版 | <https://cdn.deepseek.com/chat-demo> |
| 版权 | Copyright (c) 2026 DeepSeek |
| 许可 | MIT License（原文：<https://github.com/deepseek-ai/chat-demo/blob/main/LICENSE>，本仓库副本：`android/app/src/main/assets/web/LICENSE`） |

本仓库的 `android/app/src/main/assets/web/index.html` 与 `static/`（含 88 个异步 chunk）是上述项目的
**构建产物**，以原样方式打包分发给 App；`tools/fetch_assets.py` 从 CDN 重新抓取同一份产物。
上游 README 明确要求「构建会把 LICENSE 复制到 `dist/`，分发时请一并保留」，因此副本随本仓库与 APK 一同分发。

我们对这份产物的改动仅限：在 `index.html` 里额外注入两个本地脚本（`shim.js`、`usage.js`），
以及 `usage.js` 在页面运行期旁路读取 API 响应中的 `usage` 字段。未修改打包进 `static/` 的任何文件。

## 2. 安卓框架与构建工具

只使用 Android 平台自带的 `Activity` / `WebView` / `WindowInsets` 等框架 API，
没有引入任何第三方运行期依赖（`app/build.gradle.kts` 的 `dependencies` 为空）。
构建期依赖为 AGP、Kotlin 标准库与 Gradle，按各自许可使用，均不随 APK 分发。

## 3. 商标与身份声明

「DeepSeek」及其相关标识是 DeepSeek 的商标。本项目是**非官方**的第三方客户端，
与 DeepSeek 没有隶属、赞助或背书关系，仓库名与代码中的品牌字样仅用于说明本项目的用途与数据来源。
App 的桌面名称（`app_name`）可在 `android/app/src/main/res/values/strings.xml` 中自行修改。

## 4. 数据与隐私

App 不含任何后端：对话请求直接发往 `https://api.deepseek.com`，使用用户自己填写的 API Key；
Token 用量记录保存在设备本地 `localStorage`，不对外发送。
