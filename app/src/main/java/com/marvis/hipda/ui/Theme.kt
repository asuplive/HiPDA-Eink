package com.marvis.hipda.ui

import android.graphics.Typeface
import com.marvis.hipda.data.Prefs

/**
 * 全站配色与排版。
 * E-Ink 模式下所有颜色退化为纯灰阶（黑/白/灰），并禁用圆角、阴影与动画。
 */
object Theme {

    val eink: Boolean get() = Prefs.einkMode
    val dark: Boolean get() = Prefs.darkMode

    val paper: Int get() = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    val ink: Int get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val body: Int get() = if (dark) 0xFFE6E6E6.toInt() else 0xFF1F1F1F.toInt()
    val secondary: Int get() = if (dark) 0xFFAFAFAF.toInt() else 0xFF555555.toInt()
    val muted: Int get() = if (dark) 0xFF8A8A8A.toInt() else 0xFF6B6B6B.toInt()
    val line: Int get() = if (dark) 0xFF3C3C3C.toInt() else 0xFFCDCDCD.toInt()
    val subtle: Int get() = if (dark) 0xFF171717.toInt() else 0xFFF3F3F3.toInt()
    val inverse: Int get() = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    val link: Int get() = if (eink) ink else 0xFF1A63A8.toInt()
    val badgeBg: Int get() = if (eink) ink else 0xFFE8EEF5.toInt()
    val badgeFg: Int get() = if (eink) paper else 0xFF1A63A8.toInt()

    /**
     * 提醒红：信封 / 未读角标。
     * E-Ink 黑白屏也照样画红（灰度渲染成深灰，靠加粗描边与实心块保证可见），
     * 不再按模式切换，避免同一元素在两种主题下位置/尺寸跳动。
     */
    val alert: Int get() = if (dark) 0xFFE05555.toInt() else 0xFFC40000.toInt()
    /** 红底上的文字色 */
    val alertFg: Int get() = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    /** 框间距缩放系数：设置里的「框间距」百分比 / 100，作用到全站 dp 留白 */
    val gapScale: Float get() = Prefs.gapScale / 100f

    /** 行间距缩放系数：设置里的「行间距」百分比 / 100，单独作用到多行文字的行间留白 */
    val lineScale: Float get() = Prefs.lineSpacing / 100f

    val bodySize: Float get() = Prefs.fontSize.toFloat()
    val smallSize: Float get() = (Prefs.fontSize - 2).coerceAtLeast(10).toFloat()
    val titleSize: Float get() = (Prefs.fontSize + 1).toFloat()

    val serif: Boolean get() = Prefs.serifFont

    fun bodyTypeface(): Typeface = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
    fun titleTypeface(): Typeface = if (serif) Typeface.create(Typeface.SERIF, Typeface.BOLD)
    else Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    fun radius(dp: Float): Float = if (eink) 0f else dp
}
