package com.marvis.hipda.ui.pages

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.marvis.hipda.BuildConfig
import com.marvis.hipda.data.Forums
import com.marvis.hipda.data.MessageCenter
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.net.HiApi
import com.marvis.hipda.net.HiHttp
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async
import com.marvis.hipda.util.MsgPoll

/** Hi 菜单 */
class MenuPage(host: MainActivity) : ScrollPage(host) {

    override val title: String = "菜单"

    private var accountName: String = ""
    private var probing = false

    private var lastBadge = -1

    /** 两行消息入口的角标 / 行尾文字引用：更新时只改这两个小组件，绝不重建整页 */
    private var pmBadge: TextView? = null
    private var pmBadgeGap: View? = null
    private var pmTrailing: TextView? = null
    private var pmTrailingGap: View? = null
    private var noticeBadge: TextView? = null
    private var noticeBadgeGap: View? = null
    private var noticeTrailing: TextView? = null
    private var noticeTrailingGap: View? = null

    /**
     * 未读数变化时只做局部更新：改角标文字与可见性、改行尾文案，
     * 让墨水屏只重绘这两行，不整页刷新（整页刷新在墨水屏上就是一次全屏闪）。
     */
    private val msgListener: (MessageCenter.State) -> Unit = { st ->
        if (sigOf(st) != lastBadge && isAttachedToWindow) {
            updateBadges()
            chrome()
        }
    }

    /** 角标只关心短消息与三类提醒的条数，用两者的组合当指纹，顶栏信封的有效未读变化不影响这里 */
    private fun sigOf(st: MessageCenter.State): Int = st.pm * 1000 + st.notice

    init {
        accountName = if (Prefs.isLoggedIn()) Prefs.username else ""
        render()
        probeAccount()
        MessageCenter.refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MessageCenter.addListener(msgListener)
        // 脱离窗口期间收到过变化（比如后台轮询）时补一次刷新，否则角标是旧值
        if (sigOf(MessageCenter.state) != lastBadge) {
            if (pmBadge == null) {
                render()
                chrome()
            } else {
                updateBadges()
                chrome()
            }
        }
    }

    override fun onDetachedFromWindow() {
        MessageCenter.removeListener(msgListener)
        super.onDetachedFromWindow()
    }

    /**
     * 已登录时后台取一次真实昵称。
     * 内嵌浏览器登录只留下 Cookie，本地存的可能是登录名（asup），
     * 所以即使已有值也要刷一次，取到后即时替换显示。
     */
    private fun probeAccount() {
        if (probing || !Prefs.isLoggedIn()) return
        probing = true
        Async.run(
            work = { HiApi.refreshAccountName() },
            onSuccess = { name ->
                probing = false
                if (name.isNotBlank() && name != accountName) {
                    accountName = name
                    render()
                    chrome()
                }
            },
            onError = { probing = false }
        )
    }

    private fun render() {
        clearBody()

        // 站点区块置顶：最常用的入口放在最上面
        body.addView(sectionLabel("站点"))
        body.addView(item("版块首页", "") { host.resetTo(ForumPage(host)) })
        body.addView(item("刷新当前页", "") {
            host.top?.refresh()
            host.flashScreen()
        })
        body.addView(item("Cloudflare 验证", if (HiHttp.cookies.hasCookie("cf_clearance")) "已通过" else "未验证") {
            host.startCloudflareCheck()
        })

        body.addView(sectionLabel("账号"))
        if (Prefs.isLoggedIn()) {
            val shown = accountName.ifBlank { Prefs.username }
            body.addView(
                accountCard(
                    name = shown.ifBlank { "已登录" },
                    sub = if (shown.isBlank()) "正在读取账号名称…" else "已登录 · Discuz 会话有效",
                    trailing = "退出"
                ) { confirmLogout() }
            )
        } else {
            body.addView(accountCard("未登录", "登录后可发帖、回帖、看短消息", "登录") {
                host.push(LoginPage(host))
            })
        }

        body.addView(sectionLabel("我的"))
        val st = MessageCenter.state
        lastBadge = sigOf(st)
        val pmRow = MsgRow()
        val ntRow = MsgRow()
        body.addView(
            item(
                "短消息",
                if (st.pm > 0) st.pm.toString() + " 条未读" else "",
                badge = st.pm,
                holders = pmRow
            ) { host.push(PmListPage(host)) }
        )
        body.addView(
            item(
                "提醒",
                if (st.notice > 0) "有新提醒" else "",
                badge = st.notice,
                holders = ntRow
            ) { host.push(NoticePage(host)) }
        )
        pmBadge = pmRow.badge
        pmBadgeGap = pmRow.badgeGap
        pmTrailing = pmRow.trailing
        pmTrailingGap = pmRow.trailingGap
        noticeBadge = ntRow.badge
        noticeBadgeGap = ntRow.badgeGap
        noticeTrailing = ntRow.trailing
        noticeTrailingGap = ntRow.trailingGap
        body.addView(item("我的帖子", "") { host.push(FeedListPage.myThreads(host)) })
        body.addView(item("我的回复", "") { host.push(FeedListPage.myReplies(host)) })
        body.addView(item("我的收藏", "") { host.push(FeedListPage.myFavorites(host)) })
        body.addView(item("搜索", "") { askSearch() })

        body.addView(sectionLabel("发帖"))
        body.addView(item("发表新帖", "") { chooseBoard() })
        body.addView(item("回帖", "在帖子页") {
            if (host.top is ThreadDetailPage) {
                (host.top as ThreadDetailPage).startReply()
            } else {
                toastTip("先打开一个帖子，帖子页底部就有「回复」入口。")
            }
        })

        body.addView(sectionLabel("外观"))
        body.addView(item("E-Ink 模式", if (Prefs.einkMode) "开" else "关") {
            Prefs.einkMode = !Prefs.einkMode
            host.applyThemeChange()
        })
        body.addView(item("夜间模式", if (Prefs.darkMode) "开" else "关") {
            Prefs.darkMode = !Prefs.darkMode
            host.applyThemeChange()
        })
        body.addView(item("设置", "") { host.push(SettingsPage(host)) })
        body.addView(item("消息提醒", pollLabel()) { askMsgPoll() })

        body.addView(sectionLabel("关于"))
        body.addView(item("关于 HiPDA.Eink", "v" + BuildConfig.VERSION_NAME) {
            host.push(AboutPage(host))
        })
        body.addView(
            Ui.text(
                host,
                "非官方客户端 · v" + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）",
                Theme.smallSize - 1,
                Theme.muted
            ).apply { setPadding(dpD(4f), dpD(10f), dpD(4f), 0) }
        )
    }

    /** 账号卡：昵称单独一行大字，避免和操作项混在一起看不清 */
    private fun accountCard(name: String, sub: String, trailing: String, action: () -> Unit): View {
        val card = Ui.column(host)
        card.background = Ui.card(host, stroke = true, fill = false)
        card.setPadding(dpD(14f), dpD(10f), dpD(12f), dpD(10f))
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpD(7f) }

        val row = Ui.row(host)
        val col = Ui.column(host)
        val nameView = Ui.text(host, name, Theme.titleSize, Theme.ink, bold = true)
        Ui.singleLine(nameView)
        col.addView(nameView)
        if (sub.isNotBlank()) {
            val subView = Ui.text(host, sub, Theme.smallSize - 1, Theme.muted)
            Ui.singleLine(subView)
            subView.setPadding(0, dpD(3f), 0, 0)
            col.addView(subView)
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(host, trailing, Theme.smallSize + 1, Theme.ink, bold = true, gravity = Gravity.CENTER_VERTICAL))
        card.addView(row)
        card.isClickable = true
        card.setOnClickListener { action() }
        return card
    }

    /** 发新帖要先选板块；板块清单来自同步结果，不用写死 */
    private fun chooseBoard() {
        val boards = Forums.ALL
        if (boards.isEmpty()) {
            toastTip("板块清单还没同步好，请先回到板块首页同步一次。")
            return
        }
        val names = boards.map { it.name }.toTypedArray()
        AlertDialog.Builder(host)
            .setTitle("选择发帖板块")
            .setItems(names) { _, index ->
                val board = boards[index]
                host.push(PostEditorPage(host, fid = board.fid, tid = "", newThread = true))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sectionLabel(text: String): View {
        val tv = Ui.text(host, text, Theme.smallSize - 1, Theme.muted, bold = true)
        tv.setPadding(dpD(2f), dpD(14f), 0, dpD(6f))
        return tv
    }

    /**
     * 菜单行。holders 不为空时把「角标 / 行尾文字」两个子视图的引用回传出去，
     * 未读数变化时就能只改这两个 TextView，而不用重建整页（墨水屏上重建即全屏闪）。
     */
    private fun item(
        label: String,
        trailing: String,
        badge: Int = 0,
        holders: MsgRow? = null,
        action: () -> Unit
    ): View {
        val row = Ui.row(host)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpD(50f)
        )
        row.background = Ui.card(host, stroke = true, fill = false)
        row.setPadding(dpD(14f), 0, dpD(12f), 0)

        val name = Ui.text(host, label, Theme.bodySize, Theme.body)
        Ui.singleLine(name)
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val trailingView = Ui.text(
            host, trailing, Theme.smallSize, Theme.muted,
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        )
        trailingView.visibility = if (trailing.isNotBlank()) View.VISIBLE else View.GONE
        row.addView(trailingView)
        val trailingGap = View(host)
        trailingGap.visibility = trailingView.visibility
        row.addView(trailingGap, LinearLayout.LayoutParams(dpD(6f), 1))

        // 角标始终建出来，靠可见性切换；这样未读数从 0 变正数时不用重建整行
        val badgeView = redBadge(badge)
        badgeView.visibility = if (badge > 0) View.VISIBLE else View.GONE
        row.addView(badgeView)
        val badgeGap = View(host)
        badgeGap.visibility = badgeView.visibility
        row.addView(badgeGap, LinearLayout.LayoutParams(dpD(6f), 1))

        row.addView(Ui.text(host, "›", Theme.titleSize + 2, Theme.muted, gravity = Gravity.CENTER))

        holders?.let {
            it.badge = badgeView
            it.badgeGap = badgeGap
            it.trailing = trailingView
            it.trailingGap = trailingGap
        }

        val wrapper = LinearLayout(host)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpD(7f) }
        wrapper.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.rowHeight(host))
        )
        row.isClickable = true
        row.setOnClickListener { action() }
        return wrapper
    }

    /** 局部刷新两行消息入口：只动角标与行尾文字，其余视图保持不动 */
    private fun updateBadges() {
        val st = MessageCenter.state
        lastBadge = sigOf(st)
        updateTrailing(pmTrailing, pmTrailingGap, if (st.pm > 0) st.pm.toString() + " 条未读" else "")
        updateBadge(pmBadge, pmBadgeGap, st.pm)
        val notice = st.notice
        updateTrailing(noticeTrailing, noticeTrailingGap, if (notice > 0) "有新提醒" else "")
        updateBadge(noticeBadge, noticeBadgeGap, notice)
    }

    private fun updateTrailing(tv: TextView?, gap: View?, text: String) {
        if (tv == null) return
        if (tv.text.toString() != text) tv.text = text
        val vis = if (text.isNotBlank()) View.VISIBLE else View.GONE
        if (tv.visibility != vis) {
            tv.visibility = vis
            gap?.visibility = vis
        }
    }

    private fun updateBadge(bv: TextView?, gap: View?, count: Int) {
        if (bv == null) return
        val label = if (count > 99) "99+" else count.toString()
        if (bv.text.toString() != label) bv.text = label
        val vis = if (count > 0) View.VISIBLE else View.GONE
        if (bv.visibility != vis) {
            bv.visibility = vis
            gap?.visibility = vis
        }
    }

    private class MsgRow {
        var badge: TextView? = null
        var badgeGap: View? = null
        var trailing: TextView? = null
        var trailingGap: View? = null
    }

    /** 未读角标：红底白字小方标；编号超过 99 显示 99+，避免撑破行高 */
    private fun redBadge(count: Int): TextView {
        val badge = Ui.text(
            host,
            if (count > 99) "99+" else count.toString(),
            Theme.smallSize - 1,
            Theme.alertFg,
            bold = true,
            gravity = Gravity.CENTER
        )
        badge.setPadding(dpD(5f), dpD(1f), dpD(5f), dpD(1f))
        badge.background = Ui.solid(host, Theme.alert, 3f)
        return badge
    }

    private fun pollLabel(): String {
        val minutes = Prefs.msgPollMinutes
        if (minutes <= 0) return "只在打开时刷新"
        val span = if (minutes < 60) minutes.toString() + " 分钟" else (minutes / 60).toString() + " 小时"
        return if (Prefs.msgNotify) "每 " + span + " · 通知开" else "每 " + span + " · 通知关"
    }

    private fun askMsgPoll() {
        val items = arrayOf(
            "关闭后台轮询（只在打开 App 时刷新）",
            "每 15 分钟检查一次",
            "每 30 分钟检查一次",
            "每 60 分钟检查一次",
            "每 2 小时检查一次",
            if (Prefs.msgNotify) "系统通知：关" else "系统通知：开"
        )
        val minutesOf = intArrayOf(0, 15, 30, 60, 120)
        AlertDialog.Builder(host)
            .setTitle("消息提醒")
            .setItems(items) { _, index ->
                if (index < minutesOf.size) {
                    Prefs.msgPollMinutes = minutesOf[index]
                } else {
                    Prefs.msgNotify = !Prefs.msgNotify
                    if (Prefs.msgNotify) {
                        MsgPoll.ensureChannel(host)
                        host.askNotificationPermission()
                        val tip = "已开启系统通知。若系统未弹出，请到系统设置里允许本应用发送通知。"
                        toastTip(tip)
                    }
                }
                MsgPoll.schedule(host)
                render()
                chrome()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toastTip(text: String) {
        android.widget.Toast.makeText(host, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun askSearch() {
        val input = EditText(host)
        input.hint = "关键词"
        input.setTextColor(Theme.body)
        input.setHintTextColor(Theme.muted)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setSingleLine(true)
        val pad = dpD(16f)
        val holder = LinearLayout(host)
        holder.setPadding(pad, dpD(8f), pad, 0)
        holder.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        AlertDialog.Builder(host)
            .setTitle("搜索帖子")
            .setView(holder)
            .setPositiveButton("搜索") { _, _ ->
                val keyword = input.text.toString().trim()
                if (keyword.isNotEmpty()) {
                    host.push(FeedListPage.search(host, keyword))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(host)
            .setTitle("退出登录")
            .setMessage("将清除本站 Cookie，下次浏览需要重新登录。")
            .setPositiveButton("退出") { _, _ ->
                HiHttp.cookies.clear()
                Prefs.clearLogin()
                accountName = ""
                render()
                chrome()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun pageText(): String = ""

    override fun canNext(): Boolean {
        val content = body.height
        if (content <= 0) return false
        return content - scroller.height - scroller.scrollY > dpD(4f)
    }
}
