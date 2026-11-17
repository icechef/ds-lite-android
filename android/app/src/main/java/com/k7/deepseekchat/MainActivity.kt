package com.k7.deepseekchat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * DeepSeek Chat 的安卓 WebView 壳。
 *
 * 页面本身是 deepseek-ai/chat-demo 的构建产物（打包在 assets/web 里，虚拟域名
 * https://appassets.androidplatform.net），因此它拿到的是一个「安全上下文」的 https
 * origin：localStorage / IndexedDB / getUserMedia（语音输入）/ 跨域调 api.deepseek.com 都正常。
 *
 * 原生侧补齐网页做不到的部分：
 *  - 麦克风权限（WebChromeClient.onPermissionRequest + 运行时权限）
 *  - 文件选择器（上传附件、导入聊天记录 ZIP）
 *  - blob: 下载（导出聊天记录 ZIP）→ 保存到系统「下载」
 *  - 剪贴板 / 外部链接 / 状态栏染色（见 assets/web/shim.js）
 *  - 系统返回键：先关浮层，再网页后退，最后双击退出
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "DeepSeekChat"
        private const val HOST = "appassets.androidplatform.net"
        private const val ENTRY = "https://$HOST/index.html"
        private const val CDN_BASE = "https://cdn.deepseek.com/chat-demo/"
        private const val REQ_FILE_CHOOSER = 0x101
        private const val REQ_AUDIO_PERM = 0x102
        private const val REQ_CAMERA = 0x103
        private const val REQ_GALLERY = 0x104
        private const val REQ_FILES = 0x105
        private const val REQ_MEDIA_PERM = 0x106
        private const val EXIT_WINDOW_MS = 2000L
        private const val MAX_STAGE_BYTES = 32L * 1024 * 1024
    }

    private class Staged(val name: String, val mime: String, val bytes: ByteArray)

    private lateinit var web: WebView
    private lateinit var root: FrameLayout

    /** 页面当前底色（shim 每 700ms 同步一次），窗口/WebView 背景跟着它，键盘动画期间不会露白。 */
    private var pageBg = Color.WHITE

    /** 当前 root 的上下内边距，避免重复 setPadding 触发无谓的 WebView 重排。 */
    private var padTop = 0
    private var padBottom = 0

    /** 键盘/系统栏 inset 状态（见 installImeInsetsDriver）。 */
    private var lastInsets: android.view.WindowInsets? = null
    private var lastImeBottom = 0

    /** 原生选好的附件先暂存在这里，由页面按 token 通过 /__file/<token> 取回（避免超大 base64 字符串）。 */
    private val staged = LinkedHashMap<String, Staged>()

    /** 最近注入过的附件（按文件名），供「点缩略图放大/裁剪」取原始字节；最多留 8 条。 */
    private val recent = LinkedHashMap<String, Staged>()

    /** 关闭看图/裁剪弹窗后 600ms 内忽略缩略图点击，避免「完成」那一下的 touch-up 穿透回页面又打开一次。 */
    private var suppressPreviewUntil = 0L
    private var lastChromeHex = "#ffffff"
    private var cameraUri: Uri? = null
    private var mediaPermCallback: (() -> Unit)? = null
    private var sheet: AttachmentSheet? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingAudioRequest: PermissionRequest? = null
    private var lastBackAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val remoteCache = HashMap<String, ByteArray>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)
        web = WebView(this)
        web.setBackgroundColor(Color.WHITE)
        root.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        this.root = root

        // 键盘弹出时，Android 默认是把窗口「瞬间」缩到键盘上方，键盘本身却还在缓动上滑，
        // 于是页面内容先跳上去、键盘后到，中间留一条底色（以前是硬编码的白色）。
        // 从 Android 11 起改成贴边绘制 + 自己按键盘动画的每一帧给 root 加底部内边距：
        // WebView 高度逐帧跟着键盘走，页面底边和键盘同一时刻到位。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            installImeInsetsDriver()
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // 语音输入需要 getUserMedia
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 页面内不再需要 file:// 之类，关掉更安全；跨域由服务端 CORS 决定
            setGeolocationEnabled(true)
        }

        web.addJavascriptInterface(Bridge(), "AndroidApp")
        web.webViewClient = ShellClient()
        web.webChromeClient = ShellChrome()

        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            @Suppress("DEPRECATION")
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // 网页里 <a href="...zip"> 这类直链下载（本 demo 主要是 blob，这里兜底）
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    guessFileName(url, contentDisposition, mimeType)
                )
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                toast(getString(R.string.saved_to_downloads, guessFileName(url, contentDisposition, mimeType)))
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                toast(getString(R.string.save_failed, e.message ?: "unknown"))
            }
        }

        if (savedInstanceState == null) {
            web.loadUrl(ENTRY)
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // ------------------------------------------------------------------ 返回键

    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){try{return (window.__deepseekCanCloseOverlay && window.__deepseekCanCloseOverlay()) ? '1' : '0'}catch(e){return '0'}})()"
        ) { value ->
            val hasOverlay = value != null && value.contains('1')
            if (hasOverlay) {
                web.evaluateJavascript("window.__deepseekCloseOverlay && window.__deepseekCloseOverlay()", null)
            } else if (web.canGoBack()) {
                web.goBack()
            } else {
                confirmExit()
            }
        }
    }

    private fun confirmExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            finish()
        } else {
            lastBackAt = now
            toast(getString(R.string.back_again_to_exit))
        }
    }

    // --------------------------------------------------------- 本地资源 / 兜底

    private inner class ShellClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url ?: return null
            if (url.host != HOST) return null
            return serveLocal(url.encodedPath ?: "/")
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url
            if (url.host == HOST) return false
            return openExternal(url)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            Log.w(TAG, "resource error ${request.url}: ${error.description}")
        }
    }

    /** 把 appassets.androidplatform.net/xxx 映射到 assets/web/xxx；缺失的资源回源 CDN。 */
    private fun serveLocal(rawPath: String): WebResourceResponse? {
        var path = rawPath.trimStart('/')
        if (path.isEmpty() || path.endsWith("/")) path += "index.html"
        if (path.contains("..")) return notFound()

        // 原生选中的附件：/__file/<token>
        if (path.startsWith("__file/")) {
            val token = path.removePrefix("__file/")
            val item = synchronized(staged) { staged[token] } ?: return notFound()
            val headers = HashMap<String, String>()
            headers["Cache-Control"] = "no-store"
            return WebResourceResponse(
                item.mime, null, 200, "OK", headers, ByteArrayInputStream(item.bytes)
            )
        }

        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = mimeOf(ext)

        var payload: ByteArray? = assetsOrNull("web/$path")
        if (payload == null) {
            Log.i(TAG, "asset missing, falling back to CDN: $path")
            payload = fetchFromCdn(path)
        }
        if (payload == null) {
            return notFound()
        }

        val body = if (ext == "html") {
            rewriteIndex(String(payload, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        } else {
            payload
        }

        val headers = HashMap<String, String>()
        headers["Access-Control-Allow-Origin"] = "*"
        headers["Cache-Control"] = if (ext == "html") "no-cache" else "max-age=31536000"
        val stream = ByteArrayInputStream(body)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return WebResourceResponse(mime, "utf-8", 200, "OK", headers, stream)
        }
        return WebResourceResponse(mime, "utf-8", stream)
    }

    private fun assetsOrNull(path: String): ByteArray? = try {
        assets.open(path).use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    /** 本地资源缺失时按原路径去 CDN 取（带内存缓存，进程内只取一次）。 */
    private fun fetchFromCdn(path: String): ByteArray? {
        synchronized(remoteCache) {
            remoteCache[path]?.let { return it }
        }
        return try {
            val conn = URL(CDN_BASE + path).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "DeepSeekChatAndroid/1.0")
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "cdn fallback HTTP $code for $path")
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            synchronized(remoteCache) { remoteCache[path] = bytes }
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "cdn fallback failed for $path", e)
            null
        }
    }

    private fun rewriteIndex(html: String): String {
        if (html.contains("shim.js")) return html
        // shim.js：壳能力桥（下载/剪贴板/外链/主题/附件/录音/返回键）
        // usage.js：旁路嗅探 SSE 里的 token usage，并在标题栏「+」左侧挂「用量」入口
        val tag = "<script src=\"shim.js\"></script><script src=\"usage.js\"></script>"
        return when {
            html.contains("</head>") -> html.replace("</head>", "$tag</head>")
            html.contains("<body") -> html.replace("<body", "$tag<body")
            else -> tag + html
        }
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", HashMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(ext: String): String = when (ext) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "map" -> "application/json"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    private fun guessFileName(url: String, disposition: String?, mime: String?): String {
        val fromDisposition = disposition
            ?.split("filename=")
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }
        if (fromDisposition != null) return fromDisposition
        val last = Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: "download"
        val hasExt = last.substringAfterLast('.', "").isNotEmpty()
        val ext = when {
            hasExt -> ""
            mime == "application/zip" -> ".zip"
            else -> ""
        }
        return last + ext
    }

    // -------------------------------------------------------------- 网页能力桥

    private inner class ShellChrome : WebChromeClient() {

        override fun onPermissionRequest(request: PermissionRequest) {
            val wantsAudio = request.resources.any {
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                    it == "android.webkit.resource.AUDIO_CAPTURE"
            }
            if (!wantsAudio) {
                request.deny()
                return
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                pendingAudioRequest?.deny()
                pendingAudioRequest = request
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERM)
            }
        }

        override fun onShowFileChooser(
            webView: WebView?,
            callback: ValueCallback<Array<Uri>>?,
            params: FileChooserParams?
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback

            val accepts = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
            val mimes = accepts.filter { it.contains('/') }.toMutableList()
            val exts = accepts.filter { it.startsWith(".") }
            if (mimes.isEmpty() && exts.isNotEmpty()) {
                // 例如 accept=".zip" -> application/zip
                for (e in exts) {
                    when (e.lowercase(Locale.ROOT)) {
                        ".zip" -> mimes.add("application/zip")
                        ".pdf" -> mimes.add("application/pdf")
                        ".doc" -> mimes.add("application/msword")
                        ".docx" -> mimes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                        ".xls" -> mimes.add("application/vnd.ms-excel")
                        ".xlsx" -> mimes.add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        ".ppt" -> mimes.add("application/vnd.ms-powerpoint")
                        ".pptx" -> mimes.add("application/vnd.openxmlformats-officedocument.presentationml.presentation")
                        ".txt", ".md" -> mimes.add("text/plain")
                        ".json" -> mimes.add("application/json")
                    }
                }
            }

            val allowMultiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (mimes.size == 1) mimes[0] else "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            }

            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent.createChooser(intent, getString(R.string.pick_file)), REQ_FILE_CHOOSER)
                true
            } catch (e: Exception) {
                Log.w(TAG, "file chooser failed", e)
                fileCallback = null
                false
            }
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            // window.open -> 系统浏览器（shim 已拦了一层，这里再兜一层）
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(this@MainActivity)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.let { openExternal(it) }
                    popup.destroy()
                    return true
                }
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
            msg?.let { Log.d(TAG, "js/${it.messageLevel()} ${it.message()} @${it.sourceId()}:${it.lineNumber()}") }
            return true
        }
    }

    @Deprecated("Activity result for file chooser / audio permission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE_CHOOSER) {
            val cb = fileCallback ?: return
            fileCallback = null
            val result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            cb.onReceiveValue(result)
            return
        }
        if (requestCode == REQ_CAMERA) {
            val uri = cameraUri
            cameraUri = null
            Log.i(TAG, "camera result=$resultCode uri=$uri exists=${uri?.let { existsNow(it) }}")
            if (resultCode == RESULT_OK && uri != null) {
                stageThenNotify(listOf(uri)) { deleteQuietly(uri) }
            } else if (uri != null) {
                deleteQuietly(uri)
            }
            return
        }
        if (requestCode == REQ_GALLERY || requestCode == REQ_FILES) {
            if (resultCode != RESULT_OK) return
            val uris = ArrayList<Uri>()
            val clip = data?.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i)?.uri?.let { uris.add(it) }
                }
            }
            data?.data?.let { if (uris.isEmpty()) uris.add(it) }
            if (uris.isNotEmpty()) stageThenNotify(uris, null)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERM) {
            val req = pendingAudioRequest
            pendingAudioRequest = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                req?.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                req?.deny()
                web.post {
                    toast(getString(R.string.mic_denied))
                    web.evaluateJavascript(
                        "document.dispatchEvent(new CustomEvent('deepseek-mic-denied'))", null
                    )
                }
            }
            return
        }
        if (requestCode == REQ_MEDIA_PERM) {
            val cb = mediaPermCallback
            mediaPermCallback = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                cb?.invoke()
            } else {
                toast(getString(R.string.sheet_need_permission))
            }
        }
    }

    // ------------------------------------------------------------ 附件：面板/取图

    private fun showAttachmentSheet() {
        sheet?.dismiss()
        // 上一轮暂存的字节在这里释放；本轮选完再重新填进去，页面取完之前不会清
        synchronized(staged) { staged.clear() }
        sheet = AttachmentSheet(
            host = this,
            onCamera = { openCamera() },
            onGallery = { openGallery() },
            onFiles = { openFiles() },
            onPhotos = { uris -> stageThenNotify(uris, null) }
        ).also { it.show() }
    }

    fun isPageDark(): Boolean {
        val c = try {
            Color.parseColor(lastChromeHex)
        } catch (e: Exception) {
            return false
        }
        val l = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        return l < 128
    }

    fun thumbFor(uri: Uri, px: Int = 512): android.graphics.Bitmap? =
        AttachmentSheet.thumbOf(this, uri, px)

    fun toastShort(text: String) = toast(text)

    /** 关闭看图/裁剪后的一小段时间内忽略缩略图点击，避免 touch-up 穿透。 */
    fun suppressPreviewFor(ms: Long) {
        suppressPreviewUntil = System.currentTimeMillis() + ms
    }

    /** 系统是否深色（页面里的「跟随系统」直接用这个值，见 shim 的 matchMedia 补丁）。 */
    fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 页面里的 prefers-color-scheme 由 shim 接管，系统切换深浅色时通知它重算
        web.post {
            web.evaluateJavascript(
                "window.__deepseekSystemThemeChanged && window.__deepseekSystemThemeChanged()", null
            )
        }
    }

    private fun rememberRecent(name: String, mime: String, bytes: ByteArray) {
        synchronized(recent) {
            recent.remove(name)
            recent[name] = Staged(name, mime, bytes)
            while (recent.size > 8) {
                val first = recent.keys.iterator().next()
                recent.remove(first)
            }
        }
    }

    /** 点附件缩略图 → 打开原生看图/裁剪。裁剪完成后再以同名替换的方式回灌给页面。 */
    fun openAttachmentPreview(name: String) {
        if (System.currentTimeMillis() < suppressPreviewUntil) return
        val item = synchronized(recent) { recent[name] }
        if (item == null) {
            toast(getString(R.string.preview_miss))
            return
        }
        if (!(item.mime.startsWith("image/") || item.name.lowercase(Locale.ROOT)
                .let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") })
        ) {
            toast(getString(R.string.preview_image_only))
            return
        }
        val bmp = try {
            android.graphics.BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size)
        } catch (e: Exception) {
            null
        }
        if (bmp == null) {
            toast(getString(R.string.preview_miss))
            return
        }
        ImageInspector(this, bmp, item.name) { cropped ->
            replaceAttachment(item, cropped)
        }.show()
    }

    private fun replaceAttachment(original: Staged, cropped: android.graphics.Bitmap) {
        suppressPreviewUntil = System.currentTimeMillis() + 600
        val out = java.io.ByteArrayOutputStream()
        cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
        val bytes = out.toByteArray()
        val newName = "crop_" + System.currentTimeMillis() + ".jpg"
        synchronized(staged) {
            staged.clear()
            staged[java.util.UUID.randomUUID().toString()] = Staged(newName, "image/jpeg", bytes)
        }
        rememberRecent(newName, "image/jpeg", bytes)
        Log.i(TAG, "crop done ${original.name} -> $newName bytes=${bytes.size}")
        web.evaluateJavascript(
            "window.__deepseekReplaceAttachment && window.__deepseekReplaceAttachment(" +
                org.json.JSONObject.quote(original.name) + ")", null
        )
    }

    fun hasMediaPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 33 ->
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else ->
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun requestMediaPermission(onGranted: () -> Unit) {
        mediaPermCallback = onGranted
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestPermissions(arrayOf(perm), REQ_MEDIA_PERM)
    }

    /** 拍照：写到应用缓存里的临时文件（自建 provider 暴露 content://），拍完读成字节立刻删。 */
    private fun openCamera() {
        val file = File(TempFilesProvider.dir(this), "DS_${System.currentTimeMillis()}.jpg")
        val uri = try {
            file.createNewFile()
            TempFilesProvider.uriFor(file)
        } catch (e: Exception) {
            Log.w(TAG, "cannot create temp file", e)
            toast(getString(R.string.camera_failed))
            return
        }
        cameraUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            file.delete()
            cameraUri = null
            toast(getString(R.string.camera_failed))
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_CAMERA)
        } catch (e: Exception) {
            Log.w(TAG, "no camera app", e)
            file.delete()
            cameraUri = null
            toast(getString(R.string.camera_failed))
        }
    }

    private fun openGallery() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_GALLERY)
        } catch (e: Exception) {
            Log.w(TAG, "gallery failed", e)
            toast(getString(R.string.camera_failed))
        }
    }

    private fun openFiles() {
        val mimes = arrayOf(
            "image/*", "application/pdf", "text/plain", "text/markdown", "text/csv",
            "application/json",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        )
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_FILES)
        } catch (e: Exception) {
            Log.w(TAG, "open files failed", e)
            toast(getString(R.string.camera_failed))
        }
    }

    /** 把选中的 URI 读成字节暂存，再让页面来取（页面自己构建 File 并走它原有的上传流程）。 */
    private fun stageThenNotify(uris: List<Uri>, afterEach: ((Uri) -> Unit)?) {
        Thread {
            var stagedCount = 0
            var tooBig = 0
            for (uri in uris) {
                try {
                    val (name, mime) = describe(uri)
                    val size = sizeOf(uri)
                    if (size > MAX_STAGE_BYTES) {
                        tooBig++
                        Log.i(TAG, "stage skip (too big) uri=$uri size=$size")
                        continue
                    }
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) {
                        Log.w(TAG, "stage empty uri=$uri size=$size")
                        continue
                    }
                    val finalMime = mime.ifBlank { guessMimeByName(name) }
                    val token = java.util.UUID.randomUUID().toString()
                    synchronized(staged) {
                        if (staged.size > 12) staged.clear()
                        staged[token] = Staged(name, finalMime, bytes)
                    }
                    stagedCount++
                    rememberRecent(name, finalMime, bytes)
                    Log.i(TAG, "staged uri=$uri name=$name mime=$finalMime bytes=${bytes.size}")
                } catch (e: Exception) {
                    Log.w(TAG, "stage failed for $uri", e)
                } finally {
                    afterEach?.invoke(uri)
                }
            }
            if (tooBig > 0) main.post { toast(getString(R.string.file_too_big)) }
            if (stagedCount > 0) {
                main.post { web.evaluateJavascript("window.__deepseekTakeStaged && window.__deepseekTakeStaged()", null) }
            }
        }.start()
    }

    private fun describe(uri: Uri): Pair<String, String> {
        var name = ""
        var mime = contentResolver.getType(uri) ?: ""
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: "" }
        } catch (e: Exception) {
            Log.w(TAG, "describe failed", e)
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            if (!name.contains('.')) name += extForMime(mime)
        }
        return name to mime
    }

    private fun sizeOf(uri: Uri): Long = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun guessMimeByName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun extForMime(mime: String): String = when (mime) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "application/pdf" -> ".pdf"
        "text/plain" -> ".txt"
        else -> ""
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
            Log.i(TAG, "temp deleted uri=$uri")
        } catch (e: Exception) {
            Log.w(TAG, "delete temp failed", e)
        }
    }

    private fun existsNow(uri: Uri): Boolean = try {
        contentResolver.openInputStream(uri)?.use { it.read() != -1 } ?: false
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------- JS 调原生的入口

    inner class Bridge {

        @JavascriptInterface
        fun log(message: String) {
            Log.i(TAG, "shim: $message")
        }

        @JavascriptInterface
        fun toast(message: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Log.w(TAG, "openUrl failed", e)
                }
            }
        }

        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("DeepSeek", text))
                    toast(getString(R.string.copied))
                } catch (e: Exception) {
                    Log.w(TAG, "copy failed", e)
                }
            }
        }

        @JavascriptInterface
        fun setChromeColor(hex: String) {
            lastChromeHex = hex
            runOnUiThread { applyChromeColor(hex) }
        }

        /** 点页面里的「+」时打开原生附件面板。 */
        @JavascriptInterface
        fun openAttachmentSheet() {
            runOnUiThread { showAttachmentSheet() }
        }

        /** 点附件缩略图 → 原生看图/裁剪。 */
        @JavascriptInterface
        fun openAttachmentPreview(name: String) {
            runOnUiThread { this@MainActivity.openAttachmentPreview(name) }
        }

        /** 系统深色状态，供 shim 接管页面里的 prefers-color-scheme。 */
        @JavascriptInterface
        fun systemDark(): Boolean = isSystemDark()

        /** 页面把暂存附件的清单取走（返回 JSON 数组字符串）。 */
        @JavascriptInterface
        fun takeStaged(): String {
            val arr = org.json.JSONArray()
            // 注意：这里只交出清单，不清空暂存区 —— 页面接下来还要按 token 去 /__file/ 取字节，
            // 提前 clear 会让 fetch 拿到 404（空 body），注入一个 0 字节的坏图。
            synchronized(staged) {
                for ((token, item) in staged) {
                    arr.put(
                        org.json.JSONObject()
                            .put("token", token)
                            .put("name", item.name)
                            .put("mime", item.mime)
                    )
                }
            }
            return arr.toString()
        }

        /** 页面把 blob: / data: 转成 dataURL 后交过来，由原生写进系统「下载」目录。 */
        @JavascriptInterface
        fun saveDataUrl(name: String, dataUrl: String) {
            Thread {
                try {
                    val saved = writeDataUrl(name, dataUrl)
                    main.post { toast(getString(R.string.saved_to_downloads, saved)) }
                } catch (e: Exception) {
                    Log.w(TAG, "save failed", e)
                    main.post { toast(getString(R.string.save_failed, e.message ?: "unknown")) }
                }
            }.start()
        }
    }

    // ------------------------------------------------ 键盘 / 系统栏 inset（API 30+）

    /**
     * 键盘处理（做法对齐 WebView 社区的通行结论：native 拥有数字，页面拥有布局）：
     *
     *  - WebView 自己不缩：改它的尺寸会重建绘制表面，动画期间就是「跳一下、空两帧、再跳」；
     *    而且 edge-to-edge 之后系统也不会帮你缩（adjustResize 在 SDK 35+ 已失效）。
     *  - 键盘高度只在 native 可见（WebView 138 的 visualViewport 看不到 IME，M139 才支持），
     *    所以这里把 ime inset 逐帧推进页面：`__dsKeyboardInset(devicePx)`。
     *  - 页面把它「铺满一屏的根容器」高度改短（渲染器内重排，纯布局，不换表面），
     *    于是输入区/消息区和键盘同一帧往上走。
     */
    private fun installImeInsetsDriver() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            lastInsets = insets
            handleInsets(insets)
            insets
        }
        root.setWindowInsetsAnimationCallback(
            object : android.view.WindowInsetsAnimation.Callback(
                android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onProgress(
                    insets: android.view.WindowInsets,
                    runningAnimations: MutableList<android.view.WindowInsetsAnimation>
                ): android.view.WindowInsets {
                    lastInsets = insets
                    handleInsets(insets)
                    return insets
                }
            }
        )
        web.post { web.requestApplyInsets() }
    }

    private fun handleInsets(insets: android.view.WindowInsets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
        if (bars.top != padTop || bars.bottom != padBottom) {
            padTop = bars.top
            padBottom = bars.bottom
            root.setPadding(0, padTop, 0, padBottom)
        }
        val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
        if (ime != lastImeBottom) {
            lastImeBottom = ime
            evalJs("window.__dsKeyboardInset && window.__dsKeyboardInset($ime)")
        }
    }

    private fun evalJs(script: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                web.evaluateJavascript(script, null)
            } else {
                @Suppress("DEPRECATION")
                web.loadUrl("javascript:$script")
            }
        } catch (e: Exception) {
            Log.w(TAG, "evalJs failed", e)
        }
    }

    private fun applyChromeColor(hex: String) {
        val color = try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            return
        }
        // 窗口 / WebView 底色跟着页面走：键盘缓动期间露出来的那条缝、页面重绘的空档
        // 都应该是页面自己的底色，而不是写死的白
        if (color != pageBg) {
            pageBg = color
            root.setBackgroundColor(color)
            web.setBackgroundColor(color)
        }
        window.statusBarColor = color
        window.navigationBarColor = color
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color))
        val light = luminance > 150
        var flags = 0
        if (light) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    /** data:<mime>;base64,<payload> -> 系统「下载/DeepSeek/<name>」。 */
    private fun writeDataUrl(rawName: String, dataUrl: String): String {
        val comma = dataUrl.indexOf(',')
        require(comma > 0) { "dataURL 格式不对" }
        val meta = dataUrl.substring(0, comma)
        val payload = dataUrl.substring(comma + 1)
        val mime = meta.substringAfter("data:").substringBefore(';').ifBlank { "application/octet-stream" }
        val bytes = if (meta.contains(";base64")) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }
        val name = sanitizeName(rawName, mime)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DeepSeek")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法在「下载」目录创建文件")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("无法写入文件")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "下载/DeepSeek/$name"
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "DeepSeek"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建目录 ${dir.absolutePath}")
        }
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(bytes) }
        return "下载/DeepSeek/$name"
    }

    private fun sanitizeName(rawName: String, mime: String): String {
        var name = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        name = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        if (name.isBlank() || name == "." || name == "..") name = "download"
        if (!name.contains('.')) {
            val ext = when (mime) {
                "application/zip" -> ".zip"
                "application/json" -> ".json"
                "text/markdown" -> ".md"
                "text/plain" -> ".txt"
                else -> ""
            }
            name += ext
        }
        if (name.length > 120) {
            val ext = name.substringAfterLast('.', "")
            val base = name.substringBeforeLast('.')
            name = base.take(110) + if (ext.isEmpty()) "" else ".$ext"
        }
        return name
    }

    private fun openExternal(uri: Uri): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (e: Exception) {
        Log.w(TAG, "no activity for $uri", e)
        false
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        try {
            web.loadUrl("about:blank")
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy webview failed", e)
        }
        super.onDestroy()
    }
}
