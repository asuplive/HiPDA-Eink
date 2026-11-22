package com.marvis.hipda.ui.pages

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.net.HiHttp
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.PageKeys
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD

/** 设置页：E-Ink 模式开关即在这里 */
class SettingsPage(host: MainActivity) : ScrollPage(host) {

    override val title: String = "设置"

    private val fontSizes = listOf(14, 16, 18, 20, 22)
    private val refreshOptions = listOf(0, 4, 8, 12, 16)

    init {
        render()
    }

    private fun render() {
        clearBody()

        body.addView(sectionLabel("显示"))
        body.addView(row("E-Ink 模式", if (Prefs.einkMode) "开" else "关") {
            Prefs.einkMode = !Prefs.einkMode
            // 整屏翻页栏与圆角都随之变化，重建界面
            host.applyThemeChange()
        })
        body.addView(hint("开启后使用纯灰阶配色、整屏翻页、底部翻页栏，视觉与墨水屏一致。"))
        body.addView(row("夜间模式", if (Prefs.darkMode) "开" else "关") {
            Prefs.darkMode = !Prefs.darkMode
            host.applyThemeChange()
        })
        body.addView(row("字号", Prefs.fontSize.toString() + " sp") {
            cycleFontSize()
        })
        body.addView(row("字体", if (Prefs.serifFont) "宋体" else "黑体") {
            Prefs.serifFont = !Prefs.serifFont
            host.applyThemeChange()
        })
        body.addView(row("帖子图片", Prefs.imageModeLabel()) {
            Prefs.imageMode = (Prefs.imageMode + 1) % 3
            render()
        })
        body.addView(
            hint(
                "自动下载：进帖即加载全部图片（流量大、翻页稍慢）。\n" +
                    "点击下载：图片位置显示占位，点一下才下载（推荐，省流量、开帖更快）。\n" +
                    "不显示：只保留一行图注，完全不下载图片。"
            )
        )

        body.addView(sectionLabel("翻页"))
        body.addView(row("按键翻页", if (Prefs.keyTurn) "开" else "关") {
            Prefs.keyTurn = !Prefs.keyTurn
            render()
            chrome()
        })
        body.addView(
            hint(
                "默认开启：音量+ / 音量-、方向键 ↑ ↓ ← →、PageUp / PageDown 均为翻屏键。\n" +
                    "关闭后按键不再翻页，音量键恢复正常调节音量。"
            )
        )
        body.addView(row("上翻页键", PageKeys.label(Prefs.keyPrevCode)) { chooseKey(true) })
        body.addView(row("下翻页键", PageKeys.label(Prefs.keyNextCode)) { chooseKey(false) })
        body.addView(
            hint(
                "点一下可改绑：从常用键里选，或选「按下按键捕获」后按设备上的任意键。\n" +
                    "自定义键优先于默认键位，例如把上翻页键设为方向↓，按 ↓ 就是上翻。"
            )
        )
        body.addView(row("智能全刷", if (Prefs.smartRefresh) "每 ${Prefs.fullRefreshPages} 屏" else "关") {
            cycleRefresh()
        })
        body.addView(hint("模拟墨水屏残影治理：连续翻屏达到设定次数后插入一次整屏刷新。设为关则不闪。"))

        body.addView(sectionLabel("网络"))
        body.addView(row("Cloudflare 验证", if (HiHttp.cookies.hasCookie("cf_clearance")) "已通过" else "未验证") {
            host.startCloudflareCheck()
        })
        body.addView(hint("站点启用 Cloudflare 防护时，先在验证页过一次人机校验，凭据会保存下来供后台请求复用。"))

        body.addView(sectionLabel("账号"))
        body.addView(row("登录状态", if (Prefs.isLoggedIn()) Prefs.username else "未登录") {
            if (Prefs.isLoggedIn()) {
                host.push(LoginPage(host))
            } else {
                host.push(LoginPage(host))
            }
        })
        body.addView(row("清除 Cookie", "执行") { confirmClear() })

        body.addView(sectionLabel("关于"))
        body.addView(row("版本", com.marvis.hipda.BuildConfig.VERSION_NAME) {})
        body.addView(hint("界面参考 HiPDA·E-INK 设计稿，内容解析参考 GreenSkinMonster/hipda 开源项目。"))
    }

    private fun cycleFontSize() {
        val current = Prefs.fontSize
        val index = fontSizes.indexOf(current)
        val next = fontSizes[(index + 1).mod(fontSizes.size)]
        Prefs.fontSize = next
        host.applyThemeChange()
    }

    private fun cycleRefresh() {
        if (!Prefs.smartRefresh) {
            Prefs.smartRefresh = true
            Prefs.fullRefreshPages = 4
        } else {
            val index = refreshOptions.indexOf(Prefs.fullRefreshPages)
            val next = refreshOptions[(index + 1).mod(refreshOptions.size)]
            if (next == 0) {
                Prefs.smartRefresh = false
            } else {
                Prefs.fullRefreshPages = next
            }
        }
        render()
        chrome()
    }

    private fun confirmClear() {
        AlertDialog.Builder(host)
            .setTitle("清除 Cookie")
            .setMessage("将清除本站所有登录态与 Cloudflare 验证凭据。")
            .setPositiveButton("清除") { _, _ ->
                HiHttp.cookies.clear()
                Prefs.clearLogin()
                render()
                chrome()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ────────────────────────────────────────────────────────────────
    // 翻页按键设置
    // ────────────────────────────────────────────────────────────────

    private fun chooseKey(isPrev: Boolean) {
        val labels = PageKeys.choices.map { PageKeys.label(it) }.toMutableList()
        labels.add("按下按键捕获…")
        AlertDialog.Builder(host)
            .setTitle(if (isPrev) "上翻页键" else "下翻页键")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= PageKeys.choices.size) {
                    captureKey(isPrev)
                } else {
                    applyKey(isPrev, PageKeys.choices[which])
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 直接按设备上的实体键绑定，适配各种墨水屏的自定义侧键 */
    private fun captureKey(isPrev: Boolean) {
        val dialog = AlertDialog.Builder(host)
            .setTitle(if (isPrev) "设置上翻页键" else "设置下翻页键")
            .setMessage("请按下设备上要绑定的按键…\n（返回键不可绑定）")
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            if (PageKeys.isBlocked(keyCode)) {
                Toast.makeText(host, PageKeys.label(keyCode) + " 不能用作翻页键", Toast.LENGTH_SHORT).show()
                return@setOnKeyListener true
            }
            dialog.dismiss()
            applyKey(isPrev, keyCode)
            Toast.makeText(host, "已绑定：" + PageKeys.label(keyCode), Toast.LENGTH_SHORT).show()
            true
        }
        dialog.show()
    }

    private fun applyKey(isPrev: Boolean, code: Int) {
        if (isPrev) {
            Prefs.keyPrevCode = code
            if (code != PageKeys.NONE && Prefs.keyNextCode == code) Prefs.keyNextCode = PageKeys.NONE
        } else {
            Prefs.keyNextCode = code
            if (code != PageKeys.NONE && Prefs.keyPrevCode == code) Prefs.keyPrevCode = PageKeys.NONE
        }
        render()
        chrome()
    }

    private fun sectionLabel(text: String): View {
        val tv = Ui.text(host, text, Theme.smallSize - 1, Theme.muted, bold = true)
        tv.setPadding(dpD(2f), dpD(14f), 0, dpD(6f))
        return tv
    }

    private fun hint(text: String): View {
        val tv = Ui.text(host, text, Theme.smallSize - 1, Theme.muted)
        tv.setPadding(dpD(2f), dpD(8f), dpD(2f), dpD(2f))
        return tv
    }

    private fun row(label: String, value: String, action: () -> Unit): View {
        val holder = Ui.row(host)
        holder.background = Ui.card(host, stroke = true, fill = false)
        holder.setPadding(dpD(14f), 0, dpD(12f), 0)

        val name = Ui.text(host, label, Theme.bodySize, Theme.body)
        Ui.singleLine(name)
        holder.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val tv = Ui.text(host, value, Theme.smallSize + 0.5f, Theme.muted, gravity = Gravity.END or Gravity.CENTER_VERTICAL)
        holder.addView(tv)

        val wrapper = LinearLayout(host)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpD(7f) }
        wrapper.addView(
            holder,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpD(50f))
        )
        holder.isClickable = true
        holder.setOnClickListener { action() }
        return wrapper
    }

    override fun pageText(): String = ""

    override fun canNext(): Boolean {
        val content = body.height
        if (content <= 0) return false
        return content - scroller.height - scroller.scrollY > dpD(4f)
    }
}
