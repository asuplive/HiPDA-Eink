package com.marvis.hipda.ui.pages

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import com.marvis.hipda.util.ImageLoader
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

    /** 顶部下拉刷新中（与整页 load 分开：刷新时保留屏幕上的旧列表） */
    private var refreshing = false
    /** 列表顶部那一行纯灰阶状态文字：正在刷新… / 已刷新 / 刷新失败，下拉重试 */
    private var pullState: String? = null
    private var statusView: View? = null

    private val rowHeight = dpD(66f)

    override val title: String get() = forumName

    init {
        load(startPage)
    }

    private fun load(target: Int, force: Boolean = false) {
        if (loading) return
        loading = true
        refreshing = false
        pullState = null
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
        refreshing = false
        pullState = null
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
        addPullStatus()
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
        val pullHint = if (page == 1) "　·　已在顶部时下滑刷新" else ""
        val pageHint = if (page < lastPage) {
            "第 " + page + " / " + lastPage + " 页　·　继续下滑载入下一页" + pullHint
        } else {
            "第 " + page + " / " + lastPage + " 页　·　已到最后一页" + pullHint
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
        // 刷新手势（列表最顶部下滑）走独立通道，见 onPullDownRefresh；
        // 这个按钮保留成随时可用的显式入口，不必非得把列表拖回顶部。
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
     * 列表最上面那行纯灰阶状态文字。
     * 只在「顶部下拉刷新」期间出现，不占位时不渲染（null 就整行不加），
     * 用纯文字表达状态，没有转圈、没有下拉回弹、没有动画。
     */
    private fun addPullStatus() {
        val state = pullState
        if (state == null) {
            statusView = null
            return
        }
        val line = Ui.text(
            host, state, Theme.smallSize + 1, Theme.ink,
            bold = true, gravity = android.view.Gravity.CENTER
        )
        line.setPadding(0, dpD(3f), 0, dpD(9f))
        statusView = line
        body.addView(line)
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
        // 头像：仅在「帖子设置 → 头像显示」开启时出现，列表与帖子详情保持一致
        if (Prefs.showAvatar && item.authorId.isNotBlank()) {
            val avatar = ImageView(host)
            avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            val lp = LinearLayout.LayoutParams(dpD(20f), dpD(20f))
            lp.rightMargin = dpD(6f)
            titleRow.addView(avatar, lp)
            val url = HiUrls.avatarUrl(item.authorId)
            if (url.isNotBlank()) {
                ImageLoader.load(url) { bmp -> if (bmp != null) avatar.setImageBitmap(bmp) }
            }
        }
        // 特殊状态：加粗 + [状态] 前缀（置顶 / 精华 / 高亮 / 热），可在设置里关掉
        val statusPrefix = if (Prefs.showStatus && item.statuses.isNotEmpty()) {
            item.statuses.joinToString("") { "[" + it + "]" }
        } else {
            ""
        }
        val titleView = Ui.text(
            host,
            statusPrefix + item.title,
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
            // 把列表里的作者名 / uid 带进详情页：翻页时用来兜底判定「楼主」
            host.push(ThreadDetailPage(host, item.tid, item.title, "", item.author, item.authorId))
        }
        return row
    }

    override fun hasNextChunk(): Boolean = page < lastPage

    /** 顶部的「下滑」始终可用：第 1 页顶部下滑即刷新，后面的页下滑回上一页 */
    override fun hasPrevChunk(): Boolean = true
    override fun onReachedEnd() = load((page + 1).coerceAtMost(lastPage))

    /**
     * 滑到列表开头（第 2 页及以后）：回上一页，既有的分页手感原样保留。
     * 第 1 页的顶部下滑由 onPullDownRefresh 接成「刷新列表」，
     * 所以这里不掺刷新逻辑，两者互不干扰。
     */
    override fun onReachedStart() {
        if (page > 1) load(page - 1)
    }

    /**
     * 列表已滚到最顶端、手指继续下滑超过 60dp 时被 EinkPager / 普通 ScrollView 回调到这里。
     *
     * - 第 1 页：刷新当前板块列表（重拉第 1 页、回到顶部），返回 true 消费手势；
     * - 第 2 页及以后：返回 false，让原逻辑继续「回上一页」，不破坏分页加载；
     * - 正在刷新或正在载入：吞掉手势但不重复发请求（防抖）。
     */
    override fun onPullDownRefresh(distancePx: Int): Boolean {
        if (page > 1) return false
        if (refreshing || loading) return true
        // 列表空的 / 正停在错误提示上：走整页重载（会显示「载入中…」）
        if (items.isEmpty() || errorText != null) {
            refreshList()
            return true
        }
        pullRefresh()
        return true
    }

    /**
     * 顶部下拉刷新：只重拉当前板块第 1 页。
     * 屏幕上已有的列表保持不动，顶部插一行灰阶状态文字，刷新成功后回到列表最顶部；
     * 排序 / 过滤（置顶、头像、状态标记等）沿用原有 render() 逻辑，不做任何改动。
     */
    private fun pullRefresh() {
        refreshing = true
        errorText = null
        pullState = "正在刷新…"
        render()
        invalidatePageCache()
        val url = HiUrls.threadList(fid, 1)
        loadHtml(
            url = url,
            forceRefresh = true,
            onHtml = { html ->
                Async.run(
                    work = { HiApi.threadListFromHtml(html, url) },
                    onSuccess = { result ->
                        refreshing = false
                        items = result.items
                        lastPage = result.lastPage.coerceAtLeast(1)
                        page = result.page.coerceAtLeast(1)
                        pullState = if (result.items.isEmpty()) "已刷新（这个版块暂时没有新帖）" else "已刷新"
                        render()
                        scroller.scrollTo(0, 0)
                        chrome()
                        scheduleClearStatus()
                    },
                    onError = { t -> pullRefreshFail(t) }
                )
            },
            onFail = { t -> pullRefreshFail(t) }
        )
    }

    /** 刷新失败：旧列表保留在屏幕上，只在顶部留一行可重试提示 */
    private fun pullRefreshFail(t: Throwable) {
        refreshing = false
        if (items.isEmpty()) {
            fail(t)
            return
        }
        val reason = when (t) {
            is java.net.UnknownHostException -> "（没有网络）"
            is java.net.SocketTimeoutException -> "（连接超时）"
            is CfChallengeException -> "（需要先过 Cloudflare 验证）"
            is NotLoggedInException -> "（登录已失效）"
            else -> ""
        }
        pullState = "刷新失败，下拉重试" + reason
        render()
        chrome()
    }

    /** 「已刷新」只留 1.6 秒：直接移除那一行，无动画、无渐变 */
    private fun scheduleClearStatus() {
        val v = statusView ?: return
        v.postDelayed({
            val state = pullState
            if (state != null && state != "正在刷新…" && v.isAttachedToWindow) {
                pullState = null
                (v.parent as? ViewGroup)?.removeView(v)
                if (statusView === v) statusView = null
                chrome()
            }
        }, 1600L)
    }

    override fun refresh() = refreshList()

    private fun refreshList() {
        if (loading) return
        invalidatePageCache()
        load(1, force = true)
    }
}
