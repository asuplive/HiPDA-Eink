package com.marvis.hipda.ui.pages

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.marvis.hipda.data.FeedCache
import com.marvis.hipda.data.OfflineStore
import com.marvis.hipda.model.FeedItem
import com.marvis.hipda.net.CfChallengeException
import com.marvis.hipda.net.HiApi
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.net.NotLoggedInException
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async
import com.marvis.hipda.util.OfflineFetcher

/**
 * 「我的帖子 / 我的回复 / 我的收藏 / 搜索结果」通用原生单列列表。
 *
 * <p>这些页面在站点上是 table.datatable 网页表格，手机上横向拥挤、必须左右拖动；
 * 这里统一改成一条一卡片、整行可点、信息分两行的单列版式，
 * 并把 Discuz 的 page=N 分页接到整屏翻页上：翻到最后一屏继续下翻即载入下一页。</p>
 */
class FeedListPage(
    host: MainActivity,
    private val pageTitle: String,
    private val urlOf: (Int) -> String,
    private val tidFromPid: Boolean = false,
    private val startPage: Int = 1,
    private val emptyHint: String = "这里还没有内容。",
    /** 采集页 / 搜索结果里额外带 uid 时才展示作者 */
    private val showAuthor: Boolean = true,
    /**
     * 传入缓存键（目前只有「我的收藏」用 FeedCache.FAVORITES）即开启本地缓存：
     * 进页面先秒出上次结果，同时后台刷新；刷新失败则继续显示缓存并标注时间。
     */
    private val cacheKey: String? = null
) : ScrollPage(host) {

    private var page = startPage.coerceAtLeast(1)
    private var lastPage = 1
    private var loading = false
    private var errorText: String? = null
    private var needLogin = false
    private var needVerify = false
    private val items = ArrayList<FeedItem>()

    /** 当前列表来自本地缓存时的写入时间（0 表示内容来自刚刚的实时请求） */
    private var cacheAt = 0L

    /** 顶部「离线缓存」按钮：缓存进行中要实时改文字，所以留一个引用 */
    private var offlineBtn: TextView? = null

    override val title: String get() = pageTitle

    init {
        if (cacheKey != null) {
            // 离线缓存任务跨页面存活：回到列表页时把当前进度接上
            OfflineFetcher.watch { st -> onOfflineState(st) }
        }
        val snap = if (cacheKey != null) FeedCache.load(cacheKey) else null
        if (snap != null && snap.items.isNotEmpty()) {
            items.addAll(snap.items)
            page = snap.page.coerceAtLeast(1)
            lastPage = snap.lastPage.coerceAtLeast(page)
            cacheAt = snap.at
            render()
            // 缓存先顶上，紧接着静默刷新一次；失败时保留缓存内容
            load(page, append = false, keepContent = true)
        } else {
            load(page, append = false)
        }
    }

    private fun load(target: Int, append: Boolean, keepContent: Boolean = false) {
        if (loading) return
        loading = true
        errorText = null
        needLogin = false
        needVerify = false
        if (!append && !keepContent) {
            clearBody()
            body.addView(loadingView())
            chrome()
        }
        val url = urlOf(target)
        loadHtml(
            url = url,
            onHtml = { html ->
                Async.run(
                    work = { HiApi.feedFromHtml(html, url, tidFromPid) },
                    onSuccess = { result ->
                        loading = false
                        page = result.page.coerceAtLeast(1)
                        lastPage = result.lastPage.coerceAtLeast(1)
                        if (!append) items.clear()
                        // 逐页追加时去重，避免翻页边界重复条目
                        val seen = items.map { it.tid + "/" + it.pid }.toHashSet()
                        for (it in result.items) {
                            if (seen.add(it.tid + "/" + it.pid)) items.add(it)
                        }
                        // 实时数据到手：回写缓存（只缓存第一页整页结果，翻页不覆盖），并取消缓存标记
                        if (cacheKey != null) {
                            if (!append && page <= 1) FeedCache.save(cacheKey, items, page, lastPage)
                            cacheAt = 0L
                        }
                        render()
                    },
                    onError = { t -> fail(t, append) }
                )
            },
            onFail = { t -> fail(t, append) }
        )
    }

    private fun fail(t: Throwable, append: Boolean) {
        loading = false
        needLogin = t is NotLoggedInException
        needVerify = t is CfChallengeException
        errorText = when (t) {
            is NotLoggedInException -> "这个列表需要登录后才能看。"
            is CfChallengeException -> "站点要求 Cloudflare 验证，验证一次即可。"
            is java.net.UnknownHostException -> "无法连接站点，请检查网络。"
            is java.net.SocketTimeoutException -> "连接超时，请稍后重试。"
            else -> "载入失败：" + (t.message ?: t.javaClass.simpleName)
        }
        render()
    }

    private fun render() {
        clearBody()
        val err = errorText
        if (err != null && items.isEmpty()) {
            body.addView(noticeView(err, actionLabel()) {
                when {
                    needLogin -> host.push(LoginPage(host))
                    needVerify -> host.startCloudflareCheck(urlOf(page))
                    else -> load(page, append = false)
                }
            })
            chrome()
            return
        }
        if (items.isEmpty()) {
            body.addView(noticeView(emptyHint, "重新载入") { load(page, append = false) })
            chrome()
            return
        }

        val off = if (cacheKey != null) OfflineStore.stats() else null
        val metaLine = "共 " + items.size + " 条　·　第 " + page + " / " + lastPage + " 页" +
            (if (cacheAt > 0L) "　·　缓存 " + FeedCache.timeText(cacheAt) else "") +
            (if (off != null && off.pages > 0) "　·　离线包 " + off.threads + " 篇 " + OfflineStore.sizeText(off.bytes) else "")
        if (cacheKey != null) {
            // 有缓存的列表多给一个显式「离线缓存」按钮：
            // 点一下把列表、全部收藏帖子的正文与图片都存到本地，出门前做完即可完全离线浏览
            val bar = Ui.row(host)
            bar.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpD(8f) }
            bar.addView(
                Ui.text(host, metaLine, Theme.smallSize - 1, Theme.muted),
                Ui.weightParams(1f)
            )
            val st = OfflineFetcher.state
            val cacheBtn = Ui.text(
                host,
                if (st.running) offlineRunningText(st) else "离线缓存",
                Theme.smallSize - 1,
                Theme.body,
                gravity = Gravity.CENTER
            )
            cacheBtn.setPadding(dpD(10f), dpD(4f), dpD(10f), dpD(4f))
            cacheBtn.background = Ui.card(host, stroke = true, fill = false)
            cacheBtn.isClickable = true
            cacheBtn.setOnClickListener { onOfflineButton() }
            offlineBtn = cacheBtn
            bar.addView(cacheBtn)
            body.addView(bar)
        } else {
            body.addView(
                Ui.text(
                    host,
                    metaLine,
                    Theme.smallSize - 1,
                    Theme.muted
                ).apply { setPadding(dpD(2f), 0, 0, dpD(8f)) }
            )
        }

        for (item in items) {
            body.addView(feedCard(item))
        }

        val tail = when {
            err != null && cacheAt > 0L -> "离线缓存 · 刷新失败：" + err + "　·　点这里重试"
            err != null -> "载入下一页失败：" + err + "　·　点这里重试"
            page < lastPage -> "继续下翻载入下一页（" + page + "/" + lastPage + "）"
            else -> "已经到底了"
        }
        val tailView = Ui.text(host, tail, Theme.smallSize - 1, Theme.muted, gravity = Gravity.CENTER)
        tailView.setPadding(0, dpD(10f), 0, dpD(4f))
        if (err != null || page < lastPage) {
            tailView.isClickable = true
            tailView.setOnClickListener {
                if (loading) return@setOnClickListener
                when {
                    // 缓存内容 + 刷新失败：重试的是刷新本身，而不是下一页
                    err != null && cacheAt > 0L -> load(1, append = false, keepContent = true)
                    err != null && page < lastPage -> load((page + 1).coerceAtMost(lastPage), true)
                    err != null -> load(page, append = false, keepContent = items.isNotEmpty())
                    else -> load((page + 1).coerceAtMost(lastPage), true)
                }
            }
        }
        body.addView(tailView)
        chrome()
    }

    /**
     * 「离线缓存」按钮：弹出缓存范围，让用户选「只存列表」还是「连正文和图片一起存」。
     * 任务在 [OfflineFetcher] 里独立跑，切页面不中断；进行中再点就是取消。
     */
    private fun onOfflineButton() {
        val st = OfflineFetcher.state
        if (st.running) {
            AlertDialog.Builder(host)
                .setTitle("正在缓存离线包")
                .setMessage(progressText(st) + "\n\n完成后「我的收藏」里的帖子无需网络即可看正文与图片。")
                .setPositiveButton("取消缓存") { _, _ ->
                    OfflineFetcher.cancel()
                    Toast.makeText(host, "正在停止…", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("继续后台缓存", null)
                .show()
            return
        }
        val choices = arrayOf(
            "只缓存列表条目（最快）",
            "缓存正文与图片 · 每帖第一页",
            "缓存正文与图片 · 全部页（最彻底）"
        )
        AlertDialog.Builder(host)
            .setTitle("离线缓存")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> cacheNow()
                    1 -> startOffline(allPages = false)
                    2 -> startOffline(allPages = true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 先存列表本身（离线至少能翻标题），再逐帖抓正文与图片 */
    private fun startOffline(allPages: Boolean) {
        cacheNow()
        if (OfflineFetcher.isRunning()) return
        OfflineFetcher.start(allPages) { st ->
            Toast.makeText(host, st.message, Toast.LENGTH_LONG).show()
            render()
        }
        Toast.makeText(host, "开始缓存正文与图片，可切到其他页面等待", Toast.LENGTH_SHORT).show()
    }

    private fun offlineRunningText(st: OfflineFetcher.State): String =
        "缓存中 " + st.done + "/" + st.total

    private fun progressText(st: OfflineFetcher.State): String = buildString {
        append("进度 ").append(st.done).append("/").append(st.total).append(" 篇")
        if (st.pages > 0) append("　·　").append(st.pages).append(" 页")
        if (st.images > 0) append("　·　").append(st.images).append(" 张图")
        if (st.current.isNotBlank()) {
            append("\n正在处理：").append(st.current)
        }
    }

    /** 抓取任务的进度回调：只改按钮文字，不整页重绘，E-Ink 上不会闪 */
    private fun onOfflineState(st: OfflineFetcher.State) {
        val btn = offlineBtn ?: return
        val text = if (st.running) offlineRunningText(st) else "离线缓存"
        if (btn.text.toString() != text) btn.text = text
        if (!st.running && st.message.isNotBlank()) render()
    }

    /**
     * 只缓存列表条目：把当前已加载的全部条目（含已翻过的页）写入本地，
     * 之后无论断网还是被验证拦住，进页面都能直接看到这份列表。
     */
    private fun cacheNow() {
        val key = cacheKey ?: return
        if (items.isEmpty()) return
        FeedCache.save(key, items, 1, lastPage)
        cacheAt = FeedCache.load(key)?.at ?: System.currentTimeMillis()
        Toast.makeText(host, "已缓存 " + items.size + " 条 · " + FeedCache.timeText(cacheAt), Toast.LENGTH_SHORT).show()
        render()
    }

    private fun actionLabel(): String = when {
        needLogin -> "去登录"
        needVerify -> "去完成验证"
        else -> "重新载入"
    }

    /** 一条一卡片：标题占满整行、最多两行，元信息压到第二行 */
    private fun feedCard(item: FeedItem): View {
        val card = Ui.column(host)
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpD(8f) }
        card.background = Ui.card(host, stroke = true, fill = false)
        card.setPadding(dpD(12f), dpD(9f), dpD(12f), dpD(9f))

        val titleView = Ui.text(
            host,
            item.title.ifBlank { "（无标题）" },
            Theme.bodySize + 0.5f,
            Theme.body,
            bold = true
        )
        titleView.maxLines = 2
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        card.addView(titleView)

        val metaText = buildString {
            if (item.forumName.isNotBlank()) append(item.forumName)
            if (showAuthor && item.author.isNotBlank()) {
                if (isNotEmpty()) append("  ·  ")
                append(item.author)
            }
            if (item.time.isNotBlank()) {
                if (isNotEmpty()) append("  ·  ")
                append(item.time)
            }
            if (item.info.isNotBlank()) {
                if (isNotEmpty()) append("  ·  ")
                append(item.info)
            }
        }
        if (metaText.isNotBlank()) {
            val meta = Ui.text(host, metaText, Theme.smallSize - 1, Theme.muted)
            meta.maxLines = 2
            meta.ellipsize = android.text.TextUtils.TruncateAt.END
            meta.setPadding(0, dpD(5f), 0, 0)
            card.addView(meta)
        }

        if (item.tid.isNotBlank()) {
            card.isClickable = true
            card.setOnClickListener {
                // 同上：带上已知的作者名，供详情页翻页兜底判定「楼主」
                host.push(ThreadDetailPage(host, item.tid, item.title, "", item.author, ""))
            }
        }
        return card
    }

    override fun hasNextChunk(): Boolean = page < lastPage
    override fun hasPrevChunk(): Boolean = false
    override fun onReachedEnd() {
        if (page < lastPage && !loading) load(page + 1, append = true)
    }

    override fun refresh() {
        invalidatePageCache()
        if (cacheKey == null) items.clear()
        // 有缓存时不先清空：刷新失败仍能看到上次的列表，只把提示改成「离线缓存」
        load(1, append = false, keepContent = cacheKey != null && items.isNotEmpty())
    }

    companion object {
        fun myThreads(host: MainActivity) = FeedListPage(
            host, "我的帖子", { p -> HiUrls.page(HiUrls.myThreadsUrl, p) },
            emptyHint = "你还没有发过帖子。"
        )

        fun myReplies(host: MainActivity) = FeedListPage(
            host, "我的回复", { p -> HiUrls.page(HiUrls.myRepliesUrl, p) },
            tidFromPid = true,
            emptyHint = "你还没有回复过帖子。"
        )

        fun myFavorites(host: MainActivity) = FeedListPage(
            host, "我的收藏", { p -> HiUrls.page(HiUrls.myFavoritesUrl, p) },
            emptyHint = "收藏夹是空的。",
            cacheKey = FeedCache.FAVORITES
        )

        fun search(host: MainActivity, keyword: String) = FeedListPage(
            host,
            "搜索：" + keyword,
            { p -> HiUrls.page(HiUrls.search(keyword), p) },
            tidFromPid = true,
            emptyHint = "没有搜到「" + keyword + "」相关的帖子。"
        )
    }
}
