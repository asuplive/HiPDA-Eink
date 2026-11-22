package com.marvis.hipda.ui.pages

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.marvis.hipda.data.MessageCenter
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.model.NoticeItem
import com.marvis.hipda.net.CfChallengeException
import com.marvis.hipda.net.HiHttp
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.net.NotLoggedInException
import com.marvis.hipda.net.NoticeApi
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async
import kotlin.math.abs

/**
 * 提醒页：帖子回复 / @、系统消息、好友消息三个频道。
 *
 * 墨水屏版式：一条一卡片，未读条目在卡片左侧有一条 3dp 竖线，
 * 整行可点；点开后按 tid 进帖子详情，没有 tid 的（系统消息）走 WebViewPage。
 */
class NoticePage(
    host: MainActivity,
    private val startKind: Int = NoticeApi.KIND_THREAD
) : ScrollPage(host) {

    override val title: String = "提醒"

    private var kind = startKind.coerceIn(NoticeApi.KIND_THREAD, NoticeApi.KIND_FRIEND)
    private var loading = false
    private var errorText: String? = null
    private var needLogin = false
    private var needVerify = false
    private var hydrated = false
    private val items = ArrayList<NoticeItem>()

    init {
        load()
    }

    private fun urlOf(): String = HiUrls.notices + "?filter=" + NoticeApi.filterOf(kind)

    private fun load() {
        if (loading) return
        loading = true
        errorText = null
        needLogin = false
        needVerify = false
        clearBody()
        body.addView(header())
        body.addView(loadingView())
        chrome()

        val url = urlOf()
        loadHtml(
            url = url,
            onHtml = { html ->
                Async.run(
                    work = { NoticeApi.listFromHtml(html, url, kind) },
                    onSuccess = { list ->
                        loading = false
                        items.clear()
                        items.addAll(list)
                        render()
                        if (!hydrated) {
                            hydrated = true
                            // 进来看过提醒了：先把本地角标清掉（墨水屏上不必等网络回来才消号），
                            // 再用站点的真实未读数校准一次，避免角标与站点长期不一致
                            MessageCenter.clearUnread(1)
                            MessageCenter.refresh(true)
                        }
                    },
                    onError = { t -> fail(t) }
                )
            },
            onFail = { t -> fail(t) }
        )
    }

    private fun fail(t: Throwable) {
        loading = false
        items.clear()
        needLogin = t is NotLoggedInException
        needVerify = t is CfChallengeException
        errorText = when (t) {
            is NotLoggedInException -> "提醒需要登录后才能查看。"
            is CfChallengeException -> "站点要求 Cloudflare 验证，验证一次即可。"
            is java.net.UnknownHostException -> "无法连接站点，请检查网络。"
            is java.net.SocketTimeoutException -> "连接超时，请稍后重试。"
            else -> "载入失败：" + (t.message ?: t.javaClass.simpleName)
        }
        render()
    }

    private fun render() {
        clearBody()
        body.addView(header())

        val err = errorText
        if (err != null) {
            body.addView(noticeView(err, actionLabel()) {
                when {
                    needLogin -> host.push(LoginPage(host))
                    needVerify -> host.startCloudflareCheck(urlOf())
                    else -> load()
                }
            })
            chrome()
            return
        }

        if (items.isEmpty()) {
            body.addView(noticeView("这里暂时没有提醒。", "重新载入") { load() })
            chrome()
            return
        }

        val seen = MessageCenter.seenIds()
        val maxId = runCatching { Prefs.msgMaxId }.getOrDefault(0L)
        for (item in items) {
            body.addView(itemCard(item, item.id !in seen && abs(item.id) > abs(maxId)))
        }

        val tail = Ui.text(host, "共 " + items.size + " 条", Theme.smallSize - 1, Theme.muted, gravity = Gravity.CENTER)
        tail.setPadding(0, dpD(10f), 0, dpD(4f))
        body.addView(tail)
        chrome()
    }

    private fun actionLabel(): String = when {
        needLogin -> "去登录"
        needVerify -> "去完成验证"
        else -> "重新载入"
    }

    /** 顶部入口行：短消息入口 + 三个频道切换 + 全部标为已读 */
    private fun header(): View {
        val col = Ui.column(host)
        col.setPadding(0, 0, 0, dpD(10f))
        val st = MessageCenter.state

        val row1 = Ui.row(host)
        val pm = Ui.text(host, "短消息 " + st.pm, Theme.bodySize, Theme.ink, bold = true)
        pm.setPadding(0, dpD(6f), 0, dpD(6f))
        pm.isClickable = true
        pm.setOnClickListener { host.push(PmListPage(host)) }
        row1.addView(pm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val readAll = Ui.text(host, "全部标为已读", Theme.smallSize + 1, Theme.muted, gravity = Gravity.END)
        readAll.setPadding(dpD(8f), dpD(6f), 0, dpD(6f))
        readAll.isClickable = true
        readAll.setOnClickListener { markAllRead() }
        row1.addView(readAll)
        col.addView(row1)

        val row2 = Ui.row(host)
        for (k in NoticeApi.KIND_THREAD..NoticeApi.KIND_FRIEND) {
            val label = when (k) {
                NoticeApi.KIND_THREAD -> "帖子提醒"
                NoticeApi.KIND_SYSTEM -> "系统提醒"
                else -> "好友提醒"
            }
            val tab = Ui.text(
                host,
                label,
                Theme.smallSize + 1,
                if (k == kind) Theme.ink else Theme.muted,
                bold = k == kind
            )
            tab.setPadding(dpD(9f), dpD(7f), dpD(9f), dpD(7f))
            if (k == kind) {
                tab.background = Ui.solid(host, Theme.subtle, 3f)
            }
            tab.isClickable = true
            tab.setOnClickListener {
                if (k != kind) {
                    kind = k
                    items.clear()
                    load()
                }
            }
            row2.addView(tab)
        }
        col.addView(row2)

        val hint = Ui.text(host, hintOf(kind), Theme.smallSize - 1, Theme.muted)
        hint.setPadding(0, dpD(6f), 0, 0)
        col.addView(hint)
        return col
    }

    private fun hintOf(kind: Int): String = when (kind) {
        NoticeApi.KIND_THREAD -> "有人回复你的帖子或 @ 你时会出现在这里。"
        NoticeApi.KIND_SYSTEM -> "系统通知与公告会出现在这里。"
        else -> "好友动态会出现在这里。"
    }

    /** 一条一卡片：未读左侧竖线 + 标题两行 + 来源/时间 */
    private fun itemCard(item: NoticeItem, unread: Boolean): View {
        val row = Ui.row(host)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpD(8f) }
        row.background = Ui.card(host, stroke = true, fill = false)
        row.setPadding(dpD(11f), dpD(9f), dpD(11f), dpD(9f))

        if (unread) {
            val bar = View(host)
            bar.setBackgroundColor(Theme.alert)
            row.addView(
                bar,
                LinearLayout.LayoutParams(dpD(3f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dpD(9f)
                }
            )
        }

        val col = Ui.column(host)
        val titleView = Ui.text(
            host,
            item.title.ifBlank { "（无标题）" },
            Theme.bodySize + 0.5f,
            Theme.body,
            bold = true
        )
        titleView.maxLines = 2
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(titleView)

        val meta = buildString {
            if (item.from.isNotBlank()) append("来自 ").append(item.from)
            if (item.time.isNotBlank()) {
                if (isNotEmpty()) append("　·　")
                append(item.time)
            }
            if (isEmpty()) append(if (unread) "未读" else "已读")
        }
        val metaView = Ui.text(host, meta, Theme.smallSize - 1, Theme.muted)
        metaView.setPadding(0, dpD(4f), 0, 0)
        col.addView(metaView)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.isClickable = true
        row.setOnClickListener { open(item) }
        return row
    }

    private fun open(item: NoticeItem) {
        try {
            MessageCenter.markSeen(listOf(item.id))
        } catch (t: Throwable) {
        }
        // 先把这条的未读竖线去掉，再压栈；返回时看到的就是已读态
        runCatching { render() }
        val fallback = item.title.ifBlank { "提醒" }
        if (item.tid.isNotBlank()) {
            host.push(ThreadDetailPage(host, item.tid, fallback))
            return
        }
        if (item.href.isNotBlank()) {
            host.push(WebViewPage(host, fallback, HiHttp.absolute(item.href)))
            return
        }
        render()
    }

    private fun markAllRead() {
        val target = kind
        Async.run(
            work = { NoticeApi.markAllRead(target) },
            onSuccess = {
                MessageCenter.clearUnread(2)
                load()
            },
            onError = {
                MessageCenter.clearUnread(2)
                load()
            }
        )
    }

    override fun refresh() {
        invalidatePageCache()
        load()
    }
}
