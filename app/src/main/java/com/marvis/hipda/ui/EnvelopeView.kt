package com.marvis.hipda.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * 顶栏右侧的红色信封。纯 Canvas 自绘，不依赖任何图片资源。
 *
 * 形态约定（与左侧 "Hi" 方块同一视觉高度）：
 * 1) 只有一种形态：有未读 → 实心红信封；没有未读 → 整个视图 INVISIBLE，右上角什么都不画；
 * 2) 没有任何动效（墨水屏上跳动需要反复刷屏，且看着烦）；
 * 3) 视图本身是 26dp × 28dp 的点击区，画出来的信封是扁宽的（宽 : 高 ≈ 1.4 : 1），
 *    纵向居中，与左侧 34dp × 28dp 的 Hi 方块齐平，不会显得竖长突兀。
 */
class EnvelopeView(context: Context) : View(context) {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flapPath = Path()
    private val rect = RectF()

    private var count = 0

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "消息提醒"
        visibility = View.INVISIBLE
        bodyPaint.style = Paint.Style.FILL
        flapPaint.style = Paint.Style.STROKE
        flapPaint.strokeCap = Paint.Cap.ROUND
    }

    /** 未读总数；>0 才显示红色信封，否则整只图标不出现 */
    fun setCount(value: Int) {
        val next = value.coerceAtLeast(0)
        val show = next > 0
        val changed = next != count
        count = next
        isClickable = show
        val vis = if (show) View.VISIBLE else View.INVISIBLE
        if (visibility != vis) visibility = vis
        if (changed || show) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (count <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 信封外框：横向只留 1dp 呼吸位；纵向按 0.72 的扁宽比居中（宽 : 高 ≈ 1.4 : 1，
        // 与常见信封比例一致），高度只有 28dp 点击区的六成左右，不会顶着 Hi 方块显竖长
        val side = dpD(1f).toFloat()
        val bodyW = w - side * 2f
        var bodyH = bodyW * 0.72f
        val maxH = h - dpD(2f)
        if (bodyH > maxH) bodyH = maxH
        val top = (h - bodyH) / 2f
        rect.set(side, top, w - side, top + bodyH)

        bodyPaint.style = Paint.Style.FILL
        bodyPaint.color = Theme.alert
        val radius = dpD(2f).toFloat()
        canvas.drawRoundRect(rect, radius, radius, bodyPaint)

        // 信封盖：左上角 → 底边中心偏上 → 右上角，用纸色描边把折线刻出来
        flapPaint.color = Theme.paper
        flapPaint.strokeWidth = dpD(1.5f).toFloat()
        flapPath.reset()
        flapPath.moveTo(rect.left, rect.top)
        flapPath.lineTo(rect.centerX(), rect.top + rect.height() * 0.52f)
        flapPath.lineTo(rect.right, rect.top)
        canvas.drawPath(flapPath, flapPaint)
    }
}
