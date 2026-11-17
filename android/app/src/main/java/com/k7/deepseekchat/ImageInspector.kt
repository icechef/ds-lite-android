package com.k7.deepseekchat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 附件图片查看 + 裁剪，形态照官方 DeepSeek App（已对官方 App 的真机界面截图取证）：
 *
 * 预览态：全屏黑底，双指缩放 / 双击放大 / 拖动；右下角深灰胶囊「裁剪」。
 * 裁剪态：左上「还原」、右上「旋转」；白色裁剪框 + 四角 L 形手柄 + 四边中点白条（都可拖）；
 *        图片可双指捏合放大、单指拖动平移（框内框外都算拖图）；
 *        左下圆形 ×（放弃本次编辑、回预览）、右下蓝色长胶囊「完成」（提交并替换页面上的附件）。
 */
class ImageInspector(
    private val host: MainActivity,
    private var bitmap: Bitmap,
    private val name: String,
    private val onCropped: (Bitmap) -> Unit
) {

    companion object {
        /** 进入裁剪态时整张图缩到视图的多少倍（官方：图整体收一点，裁剪框不贴屏幕边） */
        private const val INSET = 0.86f

        /** 裁剪态上下两条 UI 带（dp）：图至少要缩到让开「还原/旋转」和左下×/右下完成。 */
        private const val SAFE_TOP_DP = 96
        private const val SAFE_BOTTOM_DP = 108
        private const val SAFE_SIDE_DP = 18
    }

    private var dialog: Dialog? = null
    private lateinit var zoomView: ZoomView
    private lateinit var overlay: CropOverlay
    private lateinit var previewRoot: FrameLayout
    private lateinit var cropRoot: FrameLayout
    private var inCrop = false

    fun show() {
        val ctx = host
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            // 打开图片：整体从略大处收进来（官方也是这个入场感）
            alpha = 0f
            scaleX = 1.08f
            scaleY = 1.08f
        }

        zoomView = ZoomView(ctx).apply {
            setBitmap(bitmap)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(zoomView)

        overlay = CropOverlay(ctx).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        overlay.zoomer = zoomView
        root.addView(overlay)

        // ---------------- 预览态：官方预览页只有右下「裁剪」；退出走系统返回键
        previewRoot = FrameLayout(ctx)
        previewRoot.addView(
            pill(ctx, host.getString(R.string.viewer_crop), 0xFF3A3A3C.toInt(), R.drawable.ic_crop, 0) { enterCrop() },
            bottomEndParams()
        )
        root.addView(previewRoot, matchAll())

        // ---------------- 裁剪态
        cropRoot = FrameLayout(ctx).apply { visibility = View.GONE }
        cropRoot.addView(
            textAction(ctx, host.getString(R.string.viewer_restore)) { restore() },
            topStartParams()
        )
        cropRoot.addView(
            topRightAction(ctx, host.getString(R.string.viewer_rotate), R.drawable.ic_rotate) { rotate() },
            topEndParams()
        )
        cropRoot.addView(circleClose(ctx) { exitCrop() }, circleBottomStartParams(54))
        cropRoot.addView(
            pill(ctx, host.getString(R.string.viewer_done), 0xFF4D6BFE.toInt(), null, 126) { finishCrop() },
            bottomEndParams(dp(126))
        )
        root.addView(cropRoot, matchAll())

        dialog = Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            setContentView(root)
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 1f }
            }
            show()
        }
        dialog?.setOnDismissListener { dialog = null }
        // 返回键：裁剪态先退回预览态，预览态才关闭（官方就是这个层级）
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP &&
                cropRoot.visibility == View.VISIBLE
            ) {
                exitCrop()
                true
            } else {
                false
            }
        }
        root.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    fun dismiss() {
        try {
            dialog?.dismiss()
        } catch (_: Exception) {
        }
        dialog = null
    }

    /** 进裁剪态：先让整张图缩进去（官方有这一下），缩完再落裁剪框。 */
    private fun enterCrop() {
        inCrop = true
        previewRoot.visibility = View.GONE
        cropRoot.visibility = View.VISIBLE
        overlay.visibility = View.GONE
        zoomView.setCover(null)
        zoomView.resetToFit(1f)
        zoomView.animateFrame(cropInset(), 240) {
            if (!inCrop) return@animateFrame
            overlay.bind(zoomView.imageRect(), bitmap.width, bitmap.height, host)
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            overlay.animate().alpha(1f).setDuration(140).start()
        }
    }

    /**
     * 裁剪态该把图缩到多少：取「0.86」和「刚好让开上下 UI 带 + 左右留白」的较小值。
     * 固定 0.86 只对 4:3 这类图够用；图越接近竖屏（和手机屏幕一样长/更细长）时，
     * fit 之后的图片高度就等于屏高，乘 0.86 后上下只让出约 7% 屏高，裁剪框会压到
     * 「还原/旋转」和「完成」这些按钮上。
     */
    private fun cropInset(): Float {
        val vw = zoomView.width.toFloat()
        val vh = zoomView.height.toFloat()
        if (vw <= 1f || vh <= 1f || bitmap.width <= 0 || bitmap.height <= 0) return INSET
        val fit = min(vw / bitmap.width, vh / bitmap.height)
        val sw = bitmap.width * fit
        val sh = bitmap.height * fit
        if (sw <= 0f || sh <= 0f) return INSET
        val availW = vw - 2 * dp(SAFE_SIDE_DP.toFloat())
        val availH = vh - dp(SAFE_TOP_DP.toFloat()) - dp(SAFE_BOTTOM_DP.toFloat())
        if (availW <= 0f || availH <= 0f) return INSET
        val dynamic = min(availW / sw, availH / sh)
        return min(INSET, dynamic.coerceIn(0.15f, 1f))
    }

    private fun exitCrop() {
        inCrop = false
        overlay.visibility = View.GONE
        zoomView.setCover(null)
        cropRoot.visibility = View.GONE
        previewRoot.visibility = View.VISIBLE
        zoomView.animateFrame(1f, 200)
    }

    /** 左上「还原」：裁剪框恢复成整张图，缩放也回位。 */
    private fun restore() {
        zoomView.resetToFit(cropInset())
        overlay.bind(zoomView.imageRect(), bitmap.width, bitmap.height, host)
    }

    /** 官方那颗「旋转」：每次逆时针 90°。 */
    private fun rotate() {
        val m = Matrix()
        m.postRotate(-90f)
        val rotated = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } catch (e: Exception) {
            host.toastShort(host.getString(R.string.viewer_crop_failed))
            return
        }
        bitmap = rotated
        zoomView.setBitmap(rotated)
        zoomView.resetToFit(cropInset())
        zoomView.post {
            overlay.bind(zoomView.imageRect(), bitmap.width, bitmap.height, host)
        }
    }

    private fun finishCrop() {
        val rect = zoomView.toBitmapRect(overlay.cropRect())
        if (rect == null || rect.width() < 16 || rect.height() < 16) {
            host.toastShort(host.getString(R.string.viewer_crop_too_small))
            return
        }
        val cropped = try {
            Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        } catch (e: Exception) {
            host.toastShort(host.getString(R.string.viewer_crop_failed))
            return
        }
        dismiss()
        host.suppressPreviewFor(700)   // 「完成」那一下的 touch-up 别穿透回页面又打开查看器
        onCropped(cropped)
    }

    // ------------------------------------------------------------------ 控件

    private fun pill(
        ctx: Context,
        label: String,
        bg: Int,
        iconRes: Int?,
        widthDp: Int,
        onClick: () -> Unit
    ): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(15), dp(24), dp(15))
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(28f)
            }
            isClickable = true
            setOnClickListener { onClick() }
            if (iconRes != null) {
                addView(ImageView(ctx).apply {
                    setImageResource(iconRes)
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                })
            }
            addView(TextView(ctx).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 17f
                setPadding(if (iconRes != null) dp(9) else 0, 0, 0, 0)
            })
            if (widthDp > 0) minimumWidth = dp(widthDp)
        }

    private fun textAction(ctx: Context, label: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(dp(10), dp(12), dp(10), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun topRightAction(ctx: Context, label: String, iconRes: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
            addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            })
            addView(TextView(ctx).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 17f
                setPadding(dp(8), 0, 0, 0)
            })
        }

    /** 圆形垫底的 ×。尺寸用固定的 LayoutParams 传进 addView，否则会被 WRAP_CONTENT 拉成椭圆。 */
    private fun circleClose(ctx: Context, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = "✕"
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF3A3A3C.toInt())
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun matchAll() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun topStartParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).also {
        it.gravity = Gravity.TOP or Gravity.START
        it.setMargins(dp(14), dp(22), 0, 0)
    }

    private fun topEndParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).also {
        it.gravity = Gravity.TOP or Gravity.END
        it.setMargins(0, dp(22), dp(10), 0)
    }

    private fun bottomEndParams(widthPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.BOTTOM or Gravity.END
            it.setMargins(0, 0, dp(20), dp(30))
        }

    /** 固定方形的 params：椭圆问题就是这里被 WRAP_CONTENT 覆盖造成的。 */
    private fun circleBottomStartParams(sizeDp: Int) =
        FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).also {
            it.gravity = Gravity.BOTTOM or Gravity.START
            it.setMargins(dp(20), 0, 0, dp(30))
        }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), host.resources.displayMetrics
    ).toInt()

    private fun dp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, host.resources.displayMetrics
    )

    // ------------------------------------------------------------------ 缩放/平移

    class ZoomView(ctx: Context) : View(ctx) {
        private var bmp: Bitmap? = null
        private val matrix = Matrix()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private var baseScale = 1f      // 铺满视图的基准缩放
        private var zoom = 1f           // 手捏/双击缩放倍数，>=1
        private var frame = 1f          // 裁剪态整体收一点（<=1）
        private var tx = 0f
        private var ty = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var downDist = 0f
        private var pinchBase = 1f
        private var lastTapAt = 0L
        private var fitted = false
        private var cover: RectF? = null
        private var frameAnimator: ValueAnimator? = null

        fun setBitmap(b: Bitmap) {
            bmp = b
            fitted = false
            invalidate()
        }

        /** 回到铺满状态（zoom=1），frame 自定义：预览态 1f，裁剪态 INSET。 */
        fun resetToFit(frameValue: Float = frame) {
            frame = frameValue
            zoom = 1f
            fitted = false
            ensureFit()
            invalidate()
        }

        /** 裁剪态：要求图片至少盖住这块区域（选区），拖动/缩放都不能把它露空。 */
        fun setCover(r: RectF?) {
            cover = r?.let { RectF(it) }
            clamp()
            recompute()
            invalidate()
        }

        fun zoomValue(): Float = zoom

        /** 双指捏合：以 (focalX, focalY) 为不动点缩放。 */
        fun pinchTo(newZoom: Float, focalX: Float, focalY: Float) {
            val b = bmp ?: return
            ensureFit()
            val s0 = baseScale * zoom * frame
            if (s0 <= 0f) return
            val bx = (focalX - tx) / s0
            val by = (focalY - ty) / s0
            zoom = newZoom.coerceIn(1f, 8f)
            val s1 = baseScale * zoom * frame
            tx = focalX - bx * s1
            ty = focalY - by * s1
            clamp()
            recompute()
            invalidate()
        }

        fun panBy(dx: Float, dy: Float) {
            bmp ?: return
            ensureFit()
            tx += dx
            ty += dy
            clamp()
            recompute()
            invalidate()
        }

        /** frame 动画：图整体收进去 / 弹回来，中心保持不动。 */
        fun animateFrame(target: Float, duration: Long = 220, onEnd: (() -> Unit)? = null) {
            val b = bmp ?: return
            if (width == 0 || height == 0) {
                frame = target
                fitted = false
                onEnd?.invoke()
                return
            }
            ensureFit()
            frameAnimator?.cancel()
            val from = frame
            if (abs(from - target) < 0.001f) {
                onEnd?.invoke()
                return
            }
            val cx = tx + b.width * baseScale * zoom * frame / 2f
            val cy = ty + b.height * baseScale * zoom * frame / 2f
            frameAnimator = ValueAnimator.ofFloat(from, target).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    frame = anim.animatedValue as Float
                    val s = baseScale * zoom * frame
                    tx = cx - b.width * s / 2f
                    ty = cy - b.height * s / 2f
                    clamp()
                    recompute()
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) onEnd?.invoke()
                    }
                })
                start()
            }
        }

        /** 当前图片在屏幕上的位置。 */
        fun imageRect(): RectF {
            val b = bmp ?: return RectF()
            ensureFit()
            val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
            matrix.mapRect(r)
            return r
        }

        /** 屏幕矩形 → 原图像素矩形（裁剪用）。 */
        fun toBitmapRect(r: RectF): Rect? {
            val b = bmp ?: return null
            ensureFit()
            val inv = Matrix()
            if (!matrix.invert(inv)) return null
            val pts = floatArrayOf(r.left, r.top, r.right, r.bottom)
            inv.mapPoints(pts)
            val l = pts[0].roundToInt().coerceIn(0, b.width - 1)
            val t = pts[1].roundToInt().coerceIn(0, b.height - 1)
            val rr = pts[2].roundToInt().coerceIn(l + 1, b.width)
            val bb = pts[3].roundToInt().coerceIn(t + 1, b.height)
            return Rect(l, t, rr, bb)
        }

        private fun ensureFit() {
            val b = bmp ?: return
            if (width == 0 || height == 0) return
            if (!fitted) {
                baseScale = min(width.toFloat() / b.width, height.toFloat() / b.height)
                val s = baseScale * zoom * frame
                tx = (width - b.width * s) / 2f
                ty = (height - b.height * s) / 2f
                fitted = true
            }
            recompute()
        }

        private fun recompute() {
            val b = bmp ?: return
            val s = baseScale * zoom * frame
            matrix.reset()
            matrix.postScale(s, s)
            matrix.postTranslate(tx, ty)
        }

        private fun clamp() {
            val b = bmp ?: return
            val s = baseScale * zoom * frame
            val w = b.width * s
            val h = b.height * s
            val c = cover
            if (c == null || c.width() <= 1f || c.height() <= 1f) {
                tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
                ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
            } else {
                tx = if (w >= c.width()) tx.coerceIn(c.right - w, c.left) else c.centerX() - w / 2f
                ty = if (h >= c.height()) ty.coerceIn(c.bottom - h, c.top) else c.centerY() - h / 2f
            }
        }

        override fun onDraw(canvas: Canvas) {
            val b = bmp ?: return
            ensureFit()
            canvas.drawBitmap(b, matrix, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            bmp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x
                    lastY = e.y
                    val now = System.currentTimeMillis()
                    if (now - lastTapAt < 280) {
                        zoom = if (zoom > 1.05f) 1f else 2.5f
                        clamp()
                        invalidate()
                    }
                    lastTapAt = now
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount >= 2) {
                        downDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        pinchBase = zoom
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2 && downDist > 0f) {
                        val dist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        zoom = (pinchBase * (dist / downDist)).coerceIn(1f, 8f)
                    } else {
                        tx += e.x - lastX
                        ty += e.y - lastY
                        lastX = e.x
                        lastY = e.y
                    }
                    clamp()
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount <= 2) downDist = 0f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> downDist = 0f
            }
            return true
        }
    }

    // ------------------------------------------------------------------ 裁剪框

    class CropOverlay(ctx: Context) : View(ctx) {
        var zoomer: ZoomView? = null

        private val crop = RectF()
        private val startRect = RectF()
        private val startImage = RectF()
        private var mode = 0            // 0 无, 2..5 四角, 6..9 四边中点, 10 单指拖图, 11 双指缩放
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var density = 1f
        private var downDist = 0f
        private var pinchBaseZoom = 1f
        private var lastFocalX = 0f
        private var lastFocalY = 0f
        private var ready = false
        private val dimPaint = Paint().apply { color = 0x66000000 }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        fun bind(image: RectF, widthPx: Int, heightPx: Int, host: MainActivity) {
            density = host.resources.displayMetrics.density
            // 官方：裁剪框默认铺满整张图
            crop.set(image)
            ready = image.width() > 1f
            zoomer?.setCover(crop)
            invalidate()
        }

        fun cropRect(): RectF = RectF(crop)

        override fun onDraw(canvas: Canvas) {
            if (!ready || crop.width() <= 1f) return
            canvas.drawRect(0f, 0f, width.toFloat(), crop.top, dimPaint)
            canvas.drawRect(0f, crop.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, crop.top, crop.left, crop.bottom, dimPaint)
            canvas.drawRect(crop.right, crop.top, width.toFloat(), crop.bottom, dimPaint)

            canvas.drawRect(crop, borderPaint)

            // 官方样式：四角 L + 每条边中点一段白条；用实心矩形画，所以角落两条能严丝合缝接上
            val thick = 4f * density
            val cornerLen = 24f * density
            val tickLen = 18f * density
            val white = Paint().apply { color = Color.WHITE }

            fun hBar(x0: Float, x1: Float, y: Float) {
                canvas.drawRect(min(x0, x1), y - thick / 2f, max(x0, x1), y + thick / 2f, white)
            }

            fun vBar(x: Float, y0: Float, y1: Float) {
                canvas.drawRect(x - thick / 2f, min(y0, y1), x + thick / 2f, max(y0, y1), white)
            }

            // 四角 L
            hBar(crop.left, crop.left + cornerLen, crop.top)
            vBar(crop.left, crop.top, crop.top + cornerLen)
            hBar(crop.right - cornerLen, crop.right, crop.top)
            vBar(crop.right, crop.top, crop.top + cornerLen)
            hBar(crop.left, crop.left + cornerLen, crop.bottom)
            vBar(crop.left, crop.bottom - cornerLen, crop.bottom)
            hBar(crop.right - cornerLen, crop.right, crop.bottom)
            vBar(crop.right, crop.bottom - cornerLen, crop.bottom)

            // 四边中点（可拖）
            val cx = crop.centerX()
            val cy = crop.centerY()
            hBar(cx - tickLen / 2f, cx + tickLen / 2f, crop.top)
            hBar(cx - tickLen / 2f, cx + tickLen / 2f, crop.bottom)
            vBar(crop.left, cy - tickLen / 2f, cy + tickLen / 2f)
            vBar(crop.right, cy - tickLen / 2f, cy + tickLen / 2f)
        }

        private fun corners(): List<Pair<Float, Float>> = listOf(
            crop.left to crop.top,
            crop.right to crop.top,
            crop.left to crop.bottom,
            crop.right to crop.bottom
        )

        private fun edgeMids(): List<Pair<Float, Float>> = listOf(
            crop.left to crop.centerY(),
            crop.centerX() to crop.top,
            crop.right to crop.centerY(),
            crop.centerX() to crop.bottom
        )

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!ready) return false
            val z = zoomer ?: return false
            val touchR = 34f * density
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    lastX = e.x
                    lastY = e.y
                    startRect.set(crop)
                    startImage.set(z.imageRect())
                    mode = 0
                    var best = touchR
                    corners().forEachIndexed { i, p ->
                        val d = hypot(e.x - p.first, e.y - p.second)
                        if (d < best) {
                            best = d
                            mode = 2 + i
                        }
                    }
                    if (mode == 0) {
                        var bestEdge = touchR
                        edgeMids().forEachIndexed { i, p ->
                            val d = hypot(e.x - p.first, e.y - p.second)
                            if (d < bestEdge) {
                                bestEdge = d
                                mode = 6 + i
                            }
                        }
                    }
                    // 框内、框外单指拖动都是拖图（官方也是一样：选区只能靠四角/四边改大小）
                    if (mode == 0) mode = 10
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount >= 2) {
                        if (mode in 1..9) crop.set(startRect)    // 第二根手指落下：撤掉单指的误触
                        mode = 11
                        downDist = hypot(
                            e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)
                        ).coerceAtLeast(1f)
                        pinchBaseZoom = z.zoomValue()
                        lastFocalX = (e.getX(0) + e.getX(1)) / 2f
                        lastFocalY = (e.getY(0) + e.getY(1)) / 2f
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 11) {
                        if (e.pointerCount >= 2) {
                            val dist = hypot(
                                e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)
                            ).coerceAtLeast(1f)
                            val fx = (e.getX(0) + e.getX(1)) / 2f
                            val fy = (e.getY(0) + e.getY(1)) / 2f
                            z.pinchTo(pinchBaseZoom * (dist / downDist), fx, fy)
                            z.panBy(fx - lastFocalX, fy - lastFocalY)
                            lastFocalX = fx
                            lastFocalY = fy
                            invalidate()
                        }
                        return true
                    }
                    if (mode == 10) {
                        z.panBy(e.x - lastX, e.y - lastY)
                        lastX = e.x
                        lastY = e.y
                        invalidate()
                        return true
                    }
                    if (mode == 0) return false
                    val dx = e.x - downX
                    val dy = e.y - downY
                    val minSide = 64f * density
                    when (mode) {
                        2 -> {   // 左上
                            crop.left = (startRect.left + dx).coerceIn(startImage.left, startRect.right - minSide)
                            crop.top = (startRect.top + dy).coerceIn(startImage.top, startRect.bottom - minSide)
                            crop.right = startRect.right
                            crop.bottom = startRect.bottom
                        }
                        3 -> {   // 右上
                            crop.right = (startRect.right + dx).coerceIn(startRect.left + minSide, startImage.right)
                            crop.top = (startRect.top + dy).coerceIn(startImage.top, startRect.bottom - minSide)
                            crop.left = startRect.left
                            crop.bottom = startRect.bottom
                        }
                        4 -> {   // 左下
                            crop.left = (startRect.left + dx).coerceIn(startImage.left, startRect.right - minSide)
                            crop.bottom = (startRect.bottom + dy).coerceIn(startRect.top + minSide, startImage.bottom)
                            crop.right = startRect.right
                            crop.top = startRect.top
                        }
                        5 -> {   // 右下
                            crop.right = (startRect.right + dx).coerceIn(startRect.left + minSide, startImage.right)
                            crop.bottom = (startRect.bottom + dy).coerceIn(startRect.top + minSide, startImage.bottom)
                            crop.left = startRect.left
                            crop.top = startRect.top
                        }
                        6 -> {   // 左边中点
                            crop.left = (startRect.left + dx).coerceIn(startImage.left, startRect.right - minSide)
                            crop.top = startRect.top
                            crop.right = startRect.right
                            crop.bottom = startRect.bottom
                        }
                        7 -> {   // 上边中点
                            crop.top = (startRect.top + dy).coerceIn(startImage.top, startRect.bottom - minSide)
                            crop.left = startRect.left
                            crop.right = startRect.right
                            crop.bottom = startRect.bottom
                        }
                        8 -> {   // 右边中点
                            crop.right = (startRect.right + dx).coerceIn(startRect.left + minSide, startImage.right)
                            crop.left = startRect.left
                            crop.top = startRect.top
                            crop.bottom = startRect.bottom
                        }
                        9 -> {   // 下边中点
                            crop.bottom = (startRect.bottom + dy)
                                .coerceIn(startRect.top + minSide, startImage.bottom)
                            crop.left = startRect.left
                            crop.right = startRect.right
                            crop.top = startRect.top
                        }
                    }
                    // 框变了就得同步给图片的「不许露空」范围，否则拖图会被旧的整图矩形锁死
                    z.setCover(crop)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (e.pointerCount <= 2) mode = 0
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mode = 0
                    return true
                }
            }
            return true
        }
    }
}
