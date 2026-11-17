package com.k7.deepseekchat

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 官方 App 风格的附件面板：底部弹出「拍照 / 相册 / 文件」+「最近照片」。
 *
 *  - 初始只露出 2 行缩略图，抓顶部把手往上拖才展开（最高 72% 屏高，往下拖到底再拉 = 关闭）；
 *  - 缩略图分批解码（每批 8 张）逐行追加，不再等 60 张全部解完才出图；
 *  - 缩略图带内存缓存，第二次打开是瞬间的；
 *  - 支持多选：点一张加进选区（角标显示序号），底部「发送 N」一次性回灌，最多 9 张；
 *  - 面板配色跟随页面当前底色。
 */
class AttachmentSheet(
    private val host: MainActivity,
    private val onCamera: () -> Unit,
    private val onGallery: () -> Unit,
    private val onFiles: () -> Unit,
    private val onPhotos: (List<Uri>) -> Unit
) {

    private var dialog: Dialog? = null
    private val io = Executors.newSingleThreadExecutor()

    private val dark: Boolean = host.isPageDark()

    private val bgColor = if (dark) 0xFF1F2126.toInt() else Color.WHITE
    private val fgColor = if (dark) 0xFFECECEC.toInt() else 0xFF1A1A1A.toInt()
    private val subColor = if (dark) 0xFF9AA0A6.toInt() else 0xFF8A8A8E.toInt()
    private val tileColor = if (dark) 0xFF2B2E34.toInt() else 0xFFF3F4F6.toInt()
    private val accent = 0xFF4D6BFE.toInt()

    private var sheetHeight = 0
    private var minHeight = 0
    private var maxHeight = 0

    /** 已选照片，顺序即角标序号（LinkedHashSet 保序，重复点同一张不会重复加）。 */
    private val selected = LinkedHashSet<Uri>()
    private val tiles = HashMap<Uri, Tile>()

    private var countLabel: TextView? = null
    private var sendButton: TextView? = null

    private class Tile(val badge: TextView, val ring: View)

    private var columns = 4
    private var currentRow: LinearLayout? = null
    private var inRow = 0

    fun show() {
        if (dialog?.isShowing == true) return
        val ctx = host
        val metrics = host.resources.displayMetrics
        val screenH = metrics.heightPixels
        val side = dp(20)
        val gap = dp(6)
        columns = 4
        val cell = ((metrics.widthPixels - side * 2 - gap * (columns - 1)) / columns).coerceAtLeast(dp(64))
        val rowHeight = cell + gap
        // 对话框窗口会顶到屏幕最底（含导航栏区域），这里补出底部安全区，否则第二行会被切掉
        val navId = host.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navBottom = if (navId > 0) host.resources.getDimensionPixelSize(navId) else dp(16)

        selected.clear()
        tiles.clear()
        currentRow = null
        inRow = 0

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, dp(6), side, dp(8) + navBottom)
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadii = floatArrayOf(radius(22f), radius(22f), radius(22f), radius(22f), 0f, 0f, 0f, 0f)
            }
        }

        // 把手 + 三个入口 + 标题 = 拖动区（拖这里改面板高度）
        val dragZone = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        dragZone.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.topMargin = dp(6)
                it.bottomMargin = dp(12)
            }
            background = GradientDrawable().apply {
                setColor(if (dark) 0xFF4A4F57.toInt() else 0xFFD8DADF.toInt())
                cornerRadius = radius(2f)
            }
        })

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(action(ctx, R.drawable.ic_sheet_camera, host.getString(R.string.sheet_camera)) {
            dismiss(); onCamera()
        }, tileParams())
        actions.addView(action(ctx, R.drawable.ic_sheet_gallery, host.getString(R.string.sheet_gallery)) {
            dismiss(); onGallery()
        }, tileParams())
        actions.addView(action(ctx, R.drawable.ic_sheet_file, host.getString(R.string.sheet_file)) {
            dismiss(); onFiles()
        }, tileParams())
        dragZone.addView(actions)

        dragZone.addView(TextView(ctx).apply {
            text = host.getString(R.string.sheet_recent)
            setTextColor(subColor)
            textSize = 13f
            setPadding(dp(2), dp(16), 0, dp(8))
        })
        root.addView(dragZone)

        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                grid,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        // 底部：左边「已选 N 张」，右边「发送 N」。没选东西时发送按钮是灰的。
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        countLabel = TextView(ctx).apply {
            text = ""
            setTextColor(subColor)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(countLabel)
        sendButton = TextView(ctx).apply {
            text = host.getString(R.string.sheet_send)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = radius(21f)
            }
            isClickable = true
            setOnClickListener { send() }
        }
        bar.addView(sendButton)
        root.addView(bar)

        grid.addView(ProgressBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.topMargin = dp(10)
                it.bottomMargin = dp(10)
            }
        })

        val dlg = Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            setContentView(root)
            setCanceledOnTouchOutside(true)
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                setGravity(Gravity.BOTTOM)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.35f }
            }
            show()
        }
        dialog = dlg
        dlg.setOnDismissListener {
            dialog = null
            io.execute { /* 让在跑的批次自己看到 dialog 为空后退出 */ }
        }
        updateSelection()

        maxHeight = (screenH * 0.72f).toInt()
        sheetHeight = min((screenH * 0.46f).toInt(), maxHeight)
        applyHeight()

        // 先按实际测量到的头部高度，把「最小高度 = 头部 + 2 行 + 底栏」算准
        root.post {
            if (dialog?.isShowing != true) return@post
            // 窗口总高 = 上 padding + 拖动区 + 两行(含行间隙) + 底栏 + 下 padding（含导航栏安全区）
            val exact = root.paddingTop + dragZone.height + (cell * 2 + gap * 2) +
                bar.height + root.paddingBottom
            minHeight = exact.coerceAtMost(maxHeight)
            sheetHeight = minHeight
            applyHeight()
            if (host.hasMediaPermission()) loadThumbs(grid, cell, gap)
        }

        val drag = object : View.OnTouchListener {
            private var downY = 0f
            private var startH = 0
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY
                        startH = sheetHeight
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        sheetHeight = (startH + (downY - e.rawY)).toInt().coerceIn(minHeight, maxHeight)
                        applyHeight()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (startH <= minHeight && downY - e.rawY < -dp(36)) dismiss()
                        return true
                    }
                }
                return false
            }
        }
        dragZone.setOnTouchListener(drag)
        // 把手本身没有子 View，事件会冒到 dragZone；标题那次 tap 也会被当成拖动起点（位移为 0，无害）

        if (!host.hasMediaPermission()) {
            host.requestMediaPermission {
                dlg.dismiss()
                show()
            }
        }
    }

    // ------------------------------------------------------------------ 最近照片

    /**
     * 分批加载：先取列表 → 每批 8 张解码后立刻追加到网格。
     * 以前是「60 张全解完才一次性上屏」，打开面板要等一两秒才出图。
     */
    private fun loadThumbs(grid: LinearLayout, cell: Int, gap: Int) {
        val px = (cell * 1.6f).toInt().coerceAtLeast(320)
        io.execute {
            val photos = loadRecentPhotos(host, 120)
            if (photos.isEmpty()) {
                host.runOnUiThread {
                    if (dialog?.isShowing != true) return@runOnUiThread
                    grid.removeAllViews()
                    grid.addView(emptyHint())
                }
                return@execute
            }
            var first = true
            var index = 0
            while (index < photos.size) {
                if (dialog?.isShowing != true) return@execute
                val end = min(index + CHUNK, photos.size)
                val chunk = photos.subList(index, end)
                val bitmaps = chunk.map { uri -> cachedThumb(uri, px) }
                val from = index
                host.runOnUiThread {
                    if (dialog?.isShowing != true) return@runOnUiThread
                    if (from == 0) grid.removeAllViews()
                    chunk.forEachIndexed { i, uri -> addTile(grid, uri, bitmaps[i], cell, gap) }
                }
                if (first) {
                    first = false
                    // 第一批先让出来，画面出来之后再继续解码剩下的
                    Thread.sleep(16)
                }
                index = end
            }
        }
    }

    private fun cachedThumb(uri: Uri, px: Int): Bitmap? {
        val key = uri.toString() + "@" + px
        thumbCache.get(key)?.let { return it }
        val bmp = runCatching { host.thumbFor(uri, px) }.getOrNull() ?: return null
        thumbCache.put(key, bmp)
        return bmp
    }

    private fun addTile(grid: LinearLayout, uri: Uri, bmp: Bitmap?, cell: Int, gap: Int) {
        val box = FrameLayout(host).apply {
            layoutParams = LinearLayout.LayoutParams(cell, cell).also { it.rightMargin = gap }
        }
        box.addView(ImageView(host).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(tileColor)
                cornerRadius = radius(10f)
            }
            clipToOutline = true
            if (bmp != null) setImageBitmap(bmp)
        })
        // 选中：蓝色描边 + 右上角序号角标
        val ring = View(host).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), accent)
                cornerRadius = radius(10f)
            }
            visibility = View.GONE
        }
        box.addView(ring)
        val badge = TextView(host).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
                setStroke(dp(2), bgColor)
            }
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22)).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.setMargins(0, dp(4), dp(4), 0)
            }
            visibility = View.GONE
        }
        box.addView(badge)
        box.setOnClickListener { toggle(uri) }
        if (inRow == 0) {
            currentRow = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, gap)
            }
            grid.addView(currentRow)
            inRow = 0
        }
        currentRow?.addView(box)
        inRow = (inRow + 1) % columns
        tiles[uri] = Tile(badge, ring)
        if (selected.contains(uri)) paintTile(uri)
    }

    private fun toggle(uri: Uri) {
        if (selected.contains(uri)) {
            selected.remove(uri)
        } else {
            if (selected.size >= MAX_SELECT) {
                host.toastShort(host.getString(R.string.sheet_max, MAX_SELECT))
                return
            }
            selected.add(uri)
        }
        repaintTiles()
        updateSelection()
    }

    /** 序号会随删除变化，所以整片重刷（选中的最多 9 个，开销可忽略）。 */
    private fun repaintTiles() {
        for ((uri, t) in tiles) paintTile(uri)
    }

    private fun paintTile(uri: Uri) {
        val t = tiles[uri] ?: return
        val order = selected.indexOf(uri)
        if (order < 0) {
            t.ring.visibility = View.GONE
            t.badge.visibility = View.GONE
        } else {
            t.ring.visibility = View.VISIBLE
            t.badge.visibility = View.VISIBLE
            t.badge.text = (order + 1).toString()
        }
    }

    private fun updateSelection() {
        val n = selected.size
        countLabel?.text = if (n == 0) "" else host.getString(R.string.sheet_selected, n)
        sendButton?.let { b ->
            b.text = if (n == 0) host.getString(R.string.sheet_send) else host.getString(R.string.sheet_send_n, n)
            b.isEnabled = n > 0
            b.alpha = if (n == 0) 0.35f else 1f
        }
    }

    private fun send() {
        if (selected.isEmpty()) {
            dismiss()
            return
        }
        val list = ArrayList(selected)
        dismiss()
        onPhotos(list)
    }

    private fun emptyHint(): View = TextView(host).apply {
        text = host.getString(R.string.sheet_recent_empty)
        setTextColor(subColor)
        textSize = 13f
        setPadding(dp(2), dp(4), 0, dp(8))
    }

    // ------------------------------------------------------------------ 布局小件

    private fun applyHeight() {
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, sheetHeight)
    }

    fun dismiss() {
        try {
            dialog?.dismiss()
        } catch (_: Exception) {
        }
        dialog = null
    }

    private fun action(ctx: Context, icon: Int, label: String, click: () -> Unit): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { click() }
            addView(ImageView(ctx).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                background = GradientDrawable().apply {
                    setColor(tileColor)
                    cornerRadius = radius(22f)
                }
                setPadding(dp(11), dp(11), dp(11), dp(11))
            })
            addView(TextView(ctx).apply {
                text = label
                setTextColor(fgColor)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun tileParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun radius(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, host.resources.displayMetrics
    )

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, host.resources.displayMetrics
    ).toInt()

    companion object {
        private const val CHUNK = 8
        private const val MAX_SELECT = 9

        /** 缩略图内存缓存：换页/重开面板都不用重新解码（约 16 MB 上限）。 */
        private val thumbCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        /** 最近照片（按添加时间倒序）。需要读图权限；没权限返回空表。 */
        fun loadRecentPhotos(ctx: Context, limit: Int): List<Uri> {
            val out = ArrayList<Uri>()
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val args = android.os.Bundle().apply {
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
                )
            }
            try {
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (c.moveToNext() && out.size < limit) {
                        out.add(
                            android.content.ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DeepSeekChat", "recent photos query failed", e)
            }
            return out
        }

        fun thumbOf(ctx: Context, uri: Uri, px: Int = 512): Bitmap? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
            } else {
                val id = android.content.ContentUris.parseId(uri)
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    ctx.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
