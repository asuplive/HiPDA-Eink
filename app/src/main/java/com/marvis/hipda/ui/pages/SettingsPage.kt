package com.marvis.hipda.ui.pages

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
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
        body.addView(row("字号", Prefs.fontSize.toString() + " sp") { pickFontSize() })
        body.addView(row("框间距", Prefs.gapScale.toString() + "%") { pickGapScale() })
        body.addView(row("行间距", Prefs.lineSpacing.toString() + "%") { pickLineSpacing() })
        body.addView(
            hint(
                "字号可 10~30 sp 任意取整；框间距按 50%~200% 缩放卡片与列表行的留白；" +
                    "行间距按 60%~250% 缩放多行文字之间的行距，100% 为各自默认值。\n" +
                    "三项互不影响：字号只改文字大小，框间距只改框内外留白，行间距只改文字行与行的距离。"
            )
        )
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

        body.addView(sectionLabel("帖子设置"))
        body.addView(row("头像显示", if (Prefs.showAvatar) "开" else "关") {
            Prefs.showAvatar = !Prefs.showAvatar
            render()
            chrome()
        })
        body.addView(
            hint(
                "默认关闭。开启后板块列表与帖子详情的作者区都显示头像；" +
                    "关闭时不占地方，列表一屏能多看几行。"
            )
        )
        body.addView(row("帖子状态标注", if (Prefs.showStatus) "开" else "关") {
            Prefs.showStatus = !Prefs.showStatus
            render()
            chrome()
        })
        body.addView(
            hint(
                "默认开启。带特殊状态的帖子在列表里加粗，并在标题前用 [置顶] [精华] [高亮] [热] 标注，" +
                    "与论坛网页上的标记一致。关闭后列表只按普通样式显示。"
            )
        )
        body.addView(row("显示 UID", if (Prefs.showUid) "开" else "关") {
            Prefs.showUid = !Prefs.showUid
            render()
            chrome()
        })
        body.addView(row("显示帖子数量", if (Prefs.showPostCount) "开" else "关") {
            Prefs.showPostCount = !Prefs.showPostCount
            render()
            chrome()
        })
        body.addView(row("显示积分", if (Prefs.showCredits) "开" else "关") {
            Prefs.showCredits = !Prefs.showCredits
            render()
            chrome()
        })
        body.addView(row("显示注册时间", if (Prefs.showRegTime) "开" else "关") {
            Prefs.showRegTime = !Prefs.showRegTime
            render()
            chrome()
        })
        body.addView(row("显示个性签名", if (Prefs.showSignature) "开" else "关") {
            Prefs.showSignature = !Prefs.showSignature
            render()
            chrome()
        })
        body.addView(
            hint(
                "以上五项默认全部关闭，开启后在每层楼的作者名下方显示 UID / 帖子数 / 积分 / 注册时间，" +
                    "个性签名显示在正文末尾。"
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

        body.addView(sectionLabel("底部按钮"))
        body.addView(row("上屏按钮", if (Prefs.navPrevVisible) "显示" else "隐藏") {
            Prefs.navPrevVisible = !Prefs.navPrevVisible
            host.refreshBottomBar()
            render()
            chrome()
        })
        body.addView(row("下屏按钮", if (Prefs.navNextVisible) "显示" else "隐藏") {
            Prefs.navNextVisible = !Prefs.navNextVisible
            host.refreshBottomBar()
            render()
            chrome()
        })
        body.addView(row("返回按钮", if (Prefs.navBackVisible) "显示" else "隐藏") {
            Prefs.navBackVisible = !Prefs.navBackVisible
            host.refreshBottomBar()
            render()
            chrome()
        })
        body.addView(row("左右手习惯", if (Prefs.leftHanded) "左手" else "右手") {
            Prefs.leftHanded = !Prefs.leftHanded
            host.refreshBottomBar()
            render()
            chrome()
        })
        body.addView(
            hint(
                "上屏 / 下屏 / 返回三个按钮可各自隐藏或显示，隐藏后空出的位置自动分给剩下的按钮。\n" +
                    "符号统一为小三角：上屏用 ◂，下屏用 ▸，返回用 ▴，与墨水屏极简黑白风格一致。\n" +
                    "右手（默认）顺序：上屏、下屏、返回；左手顺序整排镜像：返回、下屏、上屏。"
            )
        )

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

    /** 字号：滑杆 10~30 sp 任意取整 */
    private fun pickFontSize() {
        pickValue("字号", 10, 30, 1, Prefs.fontSize, { v -> v.toString() + " sp" }) { v ->
            Prefs.fontSize = v
            render()
            chrome()
        }
    }

    /** 框间距：滑杆 50%~200%，步长 5% */
    private fun pickGapScale() {
        pickValue("框间距", 50, 200, 5, Prefs.gapScale, { v -> v.toString() + "%" }) { v ->
            Prefs.gapScale = v
            render()
            chrome()
        }
    }

    /** 行间距：滑杆 60%~250%，步长 5%；确定后立即刷新已建页面，无需重进 */
    private fun pickLineSpacing() {
        pickValue("行间距", 60, 250, 5, Prefs.lineSpacing, { v -> v.toString() + "%" }) { v ->
            Prefs.lineSpacing = v
            host.refreshLineSpacingNow()
            render()
            chrome()
        }
    }

    /** 通用数值滑杆对话框：实时显示当前值，确定后回调 */
    private fun pickValue(
        title: String,
        min: Int,
        max: Int,
        step: Int,
        current: Int,
        label: (Int) -> String,
        onPick: (Int) -> Unit
    ) {
        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        val padH = dpD(16f)
        box.setPadding(padH, dpD(10f), padH, dpD(2f))

        val valueView = Ui.text(
            host, label(current), Theme.bodySize + 4f, Theme.ink,
            bold = true, gravity = Gravity.CENTER
        )
        box.addView(valueView)

        val bar = SeekBar(host)
        bar.max = (max - min) / step
        bar.progress = ((current - min) / step).coerceIn(0, bar.max)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                valueView.text = label(min + progress * step)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        box.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpD(6f) }
        )

        AlertDialog.Builder(host)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("确定") { _, _ -> onPick(min + bar.progress * step) }
            .setNegativeButton("取消", null)
            .show()
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
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.rowHeight(host))
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
