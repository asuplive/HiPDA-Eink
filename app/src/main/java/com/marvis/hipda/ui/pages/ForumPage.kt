package com.marvis.hipda.ui.pages

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.marvis.hipda.data.Forums
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.model.Forum
import com.marvis.hipda.net.HiParser
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async
import org.jsoup.Jsoup

/**
 * 首页：板块列表。
 *
 * 板块不再写死：打开时用站点首页当前展示的板块渲染（在线解析 → 缓存 → 兜底表），
 * 站点上不显示的板块天然不会出现在这里。
 */
class ForumPage(host: MainActivity) : ScrollPage(host) {

    private val rowHeight = dpD(62f)
    private var syncing = false
    private var syncTip = ""

    override val title: String = "HiPDA"

    init {
        if (Forums.current.isEmpty()) {
            val cached = Forums.loadCache()
            if (cached.isNotEmpty()) Forums.current = cached
        }
        render()
        syncForums(force = false)
    }

    /** 从站点取当前板块清单：先首页，首页被拦或解析为空时改为从任意板块页的「跳转到」菜单取 */
    private fun syncForums(force: Boolean) {
        if (syncing) return
        val fresh = System.currentTimeMillis() - Prefs.forumIndexAt < 6L * 3600_000L
        if (!force && fresh && Forums.current.isNotEmpty()) return
        syncing = true
        syncTip = "正在同步站点板块…"
        render()
        fetchForums(HiUrls.forumIndex, allowJumpFallback = true)
    }

    private fun fetchForums(url: String, allowJumpFallback: Boolean) {
        loadHtml(
            url = url,
            onHtml = { html ->
                Async.run(
                    work = {
                        val doc = Jsoup.parse(html, url)
                        // 两处来源合并取并集：首页分类表 + 「跳转到」菜单。
                        // 只取其一都会漏板块（首页 tbody 不全、跳转菜单偶发缺项）。
                        val byIndex = HiParser.parseForumIndex(doc)
                        val byJump = HiParser.parseForumJump(doc)
                        if (byIndex.isEmpty() && byJump.isEmpty()) emptyList()
                        else byIndex + byJump
                    },
                    onSuccess = { groups ->
                        when {
                            groups.isNotEmpty() -> {
                                Forums.apply(groups)
                                syncing = false
                                syncTip = ""
                                render()
                                chrome()
                            }
                            allowJumpFallback -> {
                                syncTip = "首页没有取到板块，改用板块页…"
                                render()
                                fetchForums(HiUrls.forumJumpProbe, allowJumpFallback = false)
                            }
                            else -> {
                                syncing = false
                                syncTip = "站点板块解析为空，暂时使用本地板块表。"
                                render()
                                chrome()
                            }
                        }
                    },
                    onError = {
                        if (allowJumpFallback) {
                            syncTip = "首页没有取到板块，改用板块页…"
                            render()
                            fetchForums(HiUrls.forumJumpProbe, allowJumpFallback = false)
                        } else {
                            syncing = false
                            syncTip = "同步板块失败，暂时使用本地板块表。"
                            render()
                            chrome()
                        }
                    }
                )
            },
            onFail = { t ->
                if (allowJumpFallback) {
                    syncTip = "首页需要 Cloudflare 验证，改用板块页…"
                    render()
                    fetchForums(HiUrls.forumJumpProbe, allowJumpFallback = false)
                } else {
                    syncing = false
                    syncTip = if (t is com.marvis.hipda.net.CfChallengeException)
                        "同步板块需要先完成 Cloudflare 验证（设置页可打开验证页）。"
                    else "同步板块失败，暂时使用本地板块表。"
                    render()
                    chrome()
                }
            }
        )
    }

    private fun render() {
        clearBody()
        body.addView(headerBar())
        for ((name, list) in Forums.grouped()) {
            body.addView(groupHeader(name))
            for (forum in list) {
                body.addView(forumRow(forum))
            }
        }
        if (syncTip.isNotBlank()) {
            body.addView(
                Ui.text(host, syncTip, Theme.smallSize - 1, Theme.muted)
                    .apply { setPadding(dpD(2f), dpD(10f), 0, 0) }
            )
        }
    }

    /** 顶部：说明 + 手动同步入口 */
    private fun headerBar(): View {
        val row = Ui.row(host)
        row.setPadding(dpD(2f), dpD(2f), dpD(2f), dpD(6f))
        val tip = Ui.text(host, "板块与站点一致", Theme.smallSize - 1, Theme.muted)
        row.addView(tip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btn = Ui.text(host, "同步板块", Theme.smallSize - 1, Theme.ink, bold = true)
        btn.setPadding(dpD(8f), dpD(4f), dpD(2f), dpD(4f))
        btn.isClickable = true
        btn.setOnClickListener { syncForums(force = true) }
        row.addView(btn)
        return row
    }

    private fun groupHeader(text: String): View {
        val tv = Ui.text(host, text, Theme.smallSize, Theme.muted, bold = true)
        tv.setPadding(dpD(4f), dpD(12f), 0, dpD(8f))
        return tv
    }

    private fun forumRow(forum: Forum): View {
        val row = Ui.row(host)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
        ).apply { bottomMargin = dpD(8f) }
        row.background = Ui.card(host, stroke = true, fill = false)

        row.addView(Ui.vline(host, 3f, Theme.ink))

        val inner = Ui.row(host)
        inner.setPadding(dpD(12f), 0, dpD(10f), 0)

        val textCol = Ui.column(host)
        val name = Ui.text(host, forum.name, Theme.titleSize, Theme.ink, bold = true)
        Ui.singleLine(name)
        textCol.addView(name)

        if (forum.desc.isNotBlank()) {
            val sub = Ui.text(host, forum.desc, Theme.smallSize - 1, Theme.muted)
            Ui.singleLine(sub)
            sub.setPadding(0, dpD(3f), 0, 0)
            textCol.addView(sub)
        }

        inner.addView(
            textCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        inner.addView(Ui.text(host, "›", Theme.titleSize + 4, Theme.muted, gravity = android.view.Gravity.CENTER))

        row.addView(
            inner,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        row.isClickable = true
        row.setOnClickListener {
            host.push(ThreadListPage(host, forum.fid, forum.name))
        }
        return row
    }

    override fun refresh() {
        invalidatePageCache()
        render()
        chrome()
        syncForums(force = true)
    }
}
