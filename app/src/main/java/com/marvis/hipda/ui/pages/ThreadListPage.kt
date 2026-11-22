package com.marvis.hipda.ui.pages

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.model.ThreadItem
import com.marvis.hipda.net.CfChallengeException
import com.marvis.hipda.net.HiApi
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.net.NotLoggedInException
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async
import com.marvis.hipda.util.Prefetch

/** 版块帖子列表 */
class ThreadListPage(
    host: MainActivity,
    private val fid: String,
    private val forumName: String,
    private val startPage: Int = 1
) : ScrollPage(host) {

    private var page = 1
    private var lastPage = 1
    private var items: List<ThreadItem> = emptyList()
    private var loading = false
    private var errorText: String? = null
    private var needVerify = false
    private var needLogin = false

    private val rowHeight = dpD(66f)

    override val title: String get() = forumName

    init {
        load(startPage)
    }

    private fun load(target: Int, force: Boolean = false) {
        if (loading) return
        loading = true
        errorText = null
        needVerify = false
        needLogin = false
        clearBody()
        body.addView(loadingView())
        chrome()

        val url = HiUrls.threadList(fid, target)
        loadHtml(
            url = url,
            forceRefresh = force,
            onHtml = { html ->
                Async.run(
                    work = { HiApi.threadListFromHtml(html, url) },
                    onSuccess = { result ->
                        loading = false
                        items = result.items
                        lastPage = result.lastPage.coerceAtLeast(1)
                        page = result.page.coerceAtLeast(1)
                        render()
                    },
                    onError = { t -> fail(t) }
                )
            },
            onFail = { t -> fail(t) }
        )
    }

    private fun fail(t: Throwable) {
        loading = false
        needVerify = t is CfChallengeException
        needLogin = t is NotLoggedInException
        errorText = when (t) {
            is NotLoggedInException ->
                "该版块需要登录后查看。当前会话未登录或登录已失效，登录后即可正常浏览。"
            is CfChallengeException -> "站点要求 Cloudflare 验证（会返回验证页而不是帖子列表），验证一次即可正常浏览。"
            is java.net.UnknownHostException -> "无法连接站点，请检查网络。"
            is java.net.SocketTimeoutException -> "连接超时，请稍后重试。"
            else -> "载入失败：" + (t.message ?: t.javaClass.simpleName)
        }
        render()
    }

    private fun render() {
        clearBody()
        body.addView(headerBar())
        val err = errorText
        if (err != null) {
            val btn = when {
                needLogin -> "去登录"
                needVerify -> "去完成验证"
                else -> "重新载入"
            }
            body.addView(noticeView(err, btn) {
                when {
                    needLogin -> host.push(LoginPage(host))
                    needVerify -> host.startCloudflareCheck(HiUrls.threadList(fid, page))
                    else -> load(page)
                }
            })
            chrome()
            return
        }
        if (items.isEmpty()) {
            val tip = if (Prefs.isLoggedIn()) {
                "没有取到帖子。站点全站开着 Cloudflare 校验，会话过期时会返回验证页而不是帖子列表，" +
                    "点下面「去完成验证」过一次即可。"
            } else {
                "没有取到帖子。这个版块可能要求登录后才显示，也可能是 Cloudflare 校验未过。"
            }
            val box = noticeView(tip, if (Prefs.isLoggedIn()) "去完成验证" else "去登录") {
                if (Prefs.isLoggedIn()) host.startCloudflareCheck(HiUrls.threadList(fid, page))
                else host.push(LoginPage(host))
            }
            val reload = Ui.text(host, "重新载入", Theme.smallSize + 1, Theme.ink, bold = true)
            reload.setPadding(dpD(12f), dpD(10f), 0, dpD(2f))
            reload.isClickable = true
            reload.setOnClickListener { load(page) }
            (box.getChildAt(0) as? LinearLayout)?.addView(reload)
            body.addView(box)
            chrome()
            return
        }
        for (item in items) {
            body.addView(threadRow(item))
        }
        val pageHint = if (page < lastPage) {
            "第 " + page + " / " + lastPage + " 页　·　继续下滑载入下一页"
        } else {
            "第 " + page + " / " + lastPage + " 页　·　已到最后一页"
        }
        body.addView(
            Ui.text(
                host,
                pageHint,
                Theme.smallSize - 1,
                Theme.muted,
                gravity = android.view.Gravity.CENTER
            ).apply { setPadding(0, dpD(6f), 0, 0) }
        )
        // 刷新做成明确的按钮：下滑/上滑只负责翻页，避免「一滑就刷新、视口跳回第一条」
        val reload = Ui.text(
            host, "↻ 刷新列表（回到第 1 页）", Theme.smallSize + 1, Theme.ink,
            bold = true, gravity = android.view.Gravity.CENTER
        )
        reload.setPadding(0, dpD(10f), 0, dpD(4f))
        reload.isClickable = true
        reload.setOnClickListener { refreshList() }
        body.addView(reload)
        chrome()
        prefetchTopThreads()
    }

    /**
     * 预取列表前几个帖子的第 1 页 HTML。
     * 用户在列表上停留的这几秒里，帖子正文已经悄悄拉进页面短缓存，
     * 点进去时直接命中、不再等一个完整往返 —— 这是「打开帖子慢」最省的解法。
     */
    private fun prefetchTopThreads() {
        val tops = items.asSequence()
            .filter { it.tid.isNotBlank() }
            .filter { !it.sticky }
            .take(3)
            .map { it.tid }
            .toList()
        if (tops.isEmpty()) return
        Prefetch.submit {
            for (tid in tops) HiApi.prefetchHtml(HiUrls.threadDetail(tid, 1))
        }
    }

    /** 顶部条：板块名 + 发帖入口 */
    private fun headerBar(): View {
        val row = Ui.row(host)
        row.setPadding(dpD(2f), dpD(2f), dpD(2f), dpD(6f))
        val label = Ui.text(host, forumName, Theme.smallSize - 1, Theme.muted)
        Ui.singleLine(label)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val post = Ui.text(host, "＋ 发新帖", Theme.smallSize + 1, Theme.ink, bold = true)
        post.setPadding(dpD(8f), dpD(4f), dpD(2f), dpD(4f))
        post.isClickable = true
        post.setOnClickListener {
            if (!Prefs.isLoggedIn()) {
                host.push(LoginPage(host))
            } else {
                host.push(
                    PostEditorPage(host, fid = fid, tid = "", newThread = true) {
                        load(1)
                    }
                )
            }
        }
        row.addView(post)
        return row
    }

    private fun threadRow(item: ThreadItem): View {
        val row = Ui.column(host)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
        ).apply { bottomMargin = dpD(8f) }
        row.background = Ui.card(host, stroke = true, fill = false)
        row.setPadding(dpD(12f), dpD(8f), dpD(12f), dpD(8f))

        val titleRow = Ui.row(host)
        if (item.sticky) {
            titleRow.addView(Ui.badge(host, "置顶"))
            val g = View(host)
            titleRow.addView(g, LinearLayout.LayoutParams(dpD(6f), 1))
        }
        val titleView = Ui.text(
            host,
            item.title,
            Theme.bodySize + 0.5f,
            if (item.sticky) Theme.ink else Theme.body,
            bold = true
        )
        Ui.singleLine(titleView)
        titleRow.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(titleRow)

        val metaText = buildString {
            append(item.author.ifBlank { "匿名" })
            if (item.replyCount.isNotBlank()) append("  ·  ").append(item.replyCount).append(" 回复")
            if (item.viewCount.isNotBlank()) append("  ·  ").append(item.viewCount).append(" 查看")
            if (item.lastPostTime.isNotBlank()) append("  ·  ").append(item.lastPostTime)
        }
        val meta = Ui.text(host, metaText, Theme.smallSize - 1, Theme.muted)
        Ui.singleLine(meta)
        meta.setPadding(0, dpD(5f), 0, 0)
        row.addView(meta)

        row.isClickable = true
        row.setOnClickListener {
            host.push(ThreadDetailPage(host, item.tid, item.title))
        }
        return row
    }

    override fun hasNextChunk(): Boolean = page < lastPage

    /** 顶部的「下滑」始终可用：下滑要的就是刷新列表，不跟随页码 */
    override fun hasPrevChunk(): Boolean = true
    override fun onReachedEnd() = load((page + 1).coerceAtMost(lastPage))

    /**
     * 滑到列表开头：还在后面的页就回上一页；已在第 1 页就什么都不做。
     *
     * 这里绝不能接「刷新」——翻看列表时会反复上滑回顶，一接刷新就变成
     * 「一滑就重新加载、视口跳回第一条」，也正是之前反馈的「下滑就是刷新帖子列表」。
     * 刷新只走工具栏的刷新按钮 / 页面 refresh()。
     */
    override fun onReachedStart() {
        if (page > 1) load(page - 1)
    }

    override fun refresh() = refreshList()

    private fun refreshList() {
        if (loading) return
        invalidatePageCache()
        load(1, force = true)
    }
}
