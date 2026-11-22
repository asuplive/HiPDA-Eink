package com.marvis.hipda.ui

import android.view.KeyEvent

/**
 * 翻页按键映射。
 *
 * 内置一套开箱即用的键位（不需要任何设置）：
 *   上屏：音量+ / 方向↑ / 方向← / PageUp
 *   下屏：音量- / 方向↓ / 方向→ / PageDown
 *
 * 用户可在设置页为「上翻页键 / 下翻页键」各改绑一个键，自定义键优先于内置键位，
 * 例如把上翻页键设成方向↓，则按 ↓ 变成上翻。
 */
object PageKeys {

    /** 未指定（该方向只保留内置键位） */
    const val NONE = 0

    /** 翻页语义 */
    const val PREV = -1
    const val NEXT = 1

    private val builtinPrev = intArrayOf(
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_PAGE_UP
    )

    private val builtinNext = intArrayOf(
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_PAGE_DOWN
    )

    /** 不可被绑定为翻页键的按键：绑了会破坏返回 / 回桌面 */
    private val blocked = intArrayOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_MENU
    )

    /** 设置页下拉可选的内置键 */
    val choices: List<Int> = listOf(
        NONE,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN
    )

    fun isBlocked(code: Int): Boolean = blocked.contains(code)

    /** 音量键：翻不动时要把按键交还系统，保留调音量的能力 */
    fun isVolume(code: Int): Boolean =
        code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN

    fun label(code: Int): String = when (code) {
        NONE -> "未指定"
        KeyEvent.KEYCODE_VOLUME_UP -> "音量+"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "音量-"
        KeyEvent.KEYCODE_DPAD_UP -> "方向↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "方向↓"
        KeyEvent.KEYCODE_DPAD_LEFT -> "方向←"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "方向→"
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    }

    /**
     * 判定某个键往哪翻：返回 PREV / NEXT / 0（不处理）。
     * 自定义键优先，其次内置键位。
     */
    fun resolve(code: Int, customPrev: Int, customNext: Int): Int {
        if (code == NONE) return 0
        if (customPrev != NONE && code == customPrev) return PREV
        if (customNext != NONE && code == customNext) return NEXT
        if (builtinPrev.contains(code)) return PREV
        if (builtinNext.contains(code)) return NEXT
        return 0
    }
}
