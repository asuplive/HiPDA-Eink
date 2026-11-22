package com.marvis.hipda.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dpD(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** View 内部（页面/自定义控件）同样可以直接用 dpD */
fun View.dpD(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** 通用 UI 构造工具：页面全部用代码搭视图，保证主题切换后立即可用 */
object Ui {

    fun text(
        context: Context,
        content: CharSequence = "",
        sizeSp: Float = Theme.bodySize,
        color: Int = Theme.body,
        bold: Boolean = false,
        gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL
    ): TextView {
        val tv = TextView(context)
        tv.text = content
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tv.setTextColor(color)
        tv.gravity = gravity
        tv.typeface = if (bold) Theme.titleTypeface() else Theme.bodyTypeface()
        tv.includeFontPadding = false
        tv.setLineSpacing(context.dpD(3f).toFloat(), 1f)
        return tv
    }

    fun vsep(context: Context, color: Int = Theme.line, heightDp: Float = 1f): View {
        val v = View(context)
        v.setBackgroundColor(color)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpD(heightDp))
        return v
    }

    fun hsep(context: Context, color: Int = Theme.line, widthDp: Float = 1f): View {
        val v = View(context)
        v.setBackgroundColor(color)
        v.layoutParams = LinearLayout.LayoutParams(context.dpD(widthDp), ViewGroup.LayoutParams.MATCH_PARENT)
        return v
    }

    fun vline(context: Context, widthDp: Float = 3f, color: Int = Theme.ink): View {
        val v = View(context)
        v.setBackgroundColor(color)
        v.layoutParams = LinearLayout.LayoutParams(
            context.dpD(widthDp), ViewGroup.LayoutParams.MATCH_PARENT
        )
        return v
    }

    fun spacer(context: Context, weight: Float = 1f): View {
        val v = View(context)
        v.layoutParams = LinearLayout.LayoutParams(0, 0, weight)
        return v
    }

    fun card(context: Context, stroke: Boolean = true, fill: Boolean = false): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = context.dpD(Theme.radius(10f)).toFloat()
        d.setColor(if (fill) Theme.subtle else Theme.paper)
        if (stroke) d.setStroke(context.dpD(1f).coerceAtLeast(1), Theme.line)
        return d
    }

    fun solid(context: Context, color: Int, radiusDp: Float = 4f): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = context.dpD(Theme.radius(radiusDp)).toFloat()
        d.setColor(color)
        return d
    }

    /** 小方标：置顶 / 图 / 新 */
    fun badge(context: Context, label: String): TextView {
        val tv = text(
            context, label, Theme.smallSize - 1, Theme.badgeFg, bold = true,
            gravity = Gravity.CENTER
        )
        val pad = context.dpD(4f)
        tv.setPadding(pad, context.dpD(1f), pad, context.dpD(1f))
        tv.background = solid(context, Theme.badgeBg, 3f)
        return tv
    }

    fun row(context: Context): LinearLayout {
        val ll = LinearLayout(context)
        ll.orientation = LinearLayout.HORIZONTAL
        ll.gravity = Gravity.CENTER_VERTICAL
        return ll
    }

    fun column(context: Context): LinearLayout {
        val ll = LinearLayout(context)
        ll.orientation = LinearLayout.VERTICAL
        return ll
    }

    fun weightParams(weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

    fun singleLine(tv: TextView) {
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
    }

    fun twoLines(tv: TextView) {
        tv.maxLines = 2
        tv.ellipsize = TextUtils.TruncateAt.END
    }
}
