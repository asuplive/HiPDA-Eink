package com.marvis.hipda.ui.pages

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
    private val showAuthor: Boolean = true
) : ScrollPage(host) {

    private var page = startPage.coerceAtLeast(1)
    private var lastPage = 1
    private var loading = false
    private var errorText: String? = null
    private var needLogin = false
    private var needVerify = false
    private val items = ArrayList<FeedItem>()

    override val title: String get() = pageTitle

    init {
        load(page, append = false)
    }

    private fun load(target: Int, append: Boolean) {
        if (loading) return
        loading = true
        errorText = null
        needLogin = false
        needVerify = false
        if (!append) {
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

        body.addView(
            Ui.text(
                host,
                "共 " + items.size + " 条　·　第 " + page + " / " + lastPage + " 页",
                Theme.smallSize - 1,
                Theme.muted
            ).apply { setPadding(dpD(2f), 0, 0, dpD(8f)) }
        )

        for (item in items) {
            body.addView(feedCard(item))
        }

        val tail = when {
            err != null -> "载入下一页失败：" + err + "　·　点这里重试"
            page < lastPage -> "继续下翻载入下一页（" + page + "/" + lastPage + "）"
            else -> "已经到底了"
        }
        val tailView = Ui.text(host, tail, Theme.smallSize - 1, Theme.muted, gravity = Gravity.CENTER)
        tailView.setPadding(0, dpD(10f), 0, dpD(4f))
        if (err != null || page < lastPage) {
            tailView.isClickable = true
            tailView.setOnClickListener { if (!loading) load((page + 1).coerceAtMost(lastPage), true) }
        }
        body.addView(tailView)
        chrome()
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
        items.clear()
        load(1, append = false)
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
            emptyHint = "收藏夹是空的。"
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
