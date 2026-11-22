package com.marvis.hipda.ui.pages

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.marvis.hipda.data.MessageCenter
import com.marvis.hipda.model.PmItem
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
 * 短消息列表（原生单列）。
 * 站点 pm.php 是 ul.pm_list 的会话列表，这里改成一条一会话的卡片：
 * 第一行对方昵称 + 时间，第二行最近一条内容，未读左侧加粗竖条标记。
 */
class PmListPage(host: MainActivity, private val startPage: Int = 1) : ScrollPage(host) {

    override val title: String = "短消息"

    private var page = startPage.coerceAtLeast(1)
    private var lastPage = 1
    private var loading = false
    private var errorText: String? = null
    private var needLogin = false
    private var needVerify = false
    private val items = ArrayList<PmItem>()

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
        val url = HiUrls.sms(target)
        loadHtml(
            url = url,
            onHtml = { html ->
                Async.run(
                    work = { HiApi.pmFromHtml(html, url) },
                    onSuccess = { result ->
                        loading = false
                        page = result.page.coerceAtLeast(1)
                        lastPage = result.lastPage.coerceAtLeast(1)
                        if (!append) items.clear()
                        val seen = items.map { it.uid }.toHashSet()
                        for (it in result.items) if (seen.add(it.uid)) items.add(it)
                        render()
                        // 打开私信列表后站点会把这批消息记为已读：先清本地短消息未读（顶栏信封立即消失），
                        // 再用站点真实未读校准一次角标
                        if (!append && page <= 1) {
                            MessageCenter.clearUnread(0)
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
        needLogin = t is NotLoggedInException
        needVerify = t is CfChallengeException
        errorText = when (t) {
            is NotLoggedInException -> "短消息需要登录后查看。"
            is CfChallengeException -> "站点要求 Cloudflare 验证，验证一次即可。"
            is java.net.UnknownHostException -> "无法连接站点，请检查网络。"
            else -> "载入失败：" + (t.message ?: t.javaClass.simpleName)
        }
        render()
    }

    private fun render() {
        clearBody()
        val err = errorText
        if (err != null && items.isEmpty()) {
            body.addView(noticeView(err, if (needLogin) "去登录" else "重新载入") {
                if (needLogin) host.push(LoginPage(host)) else load(page, append = false)
            })
            chrome()
            return
        }
        if (items.isEmpty()) {
            body.addView(noticeView("没有短消息。", "重新载入") { load(page, append = false) })
            chrome()
            return
        }

        val unread = items.count { it.unread }
        body.addView(
            Ui.text(
                host,
                "共 " + items.size + " 个会话" + (if (unread > 0) "　·　" + unread + " 条未读" else ""),
                Theme.smallSize - 1,
                Theme.muted
            ).apply { setPadding(dpD(2f), 0, 0, dpD(8f)) }
        )

        for (item in items) body.addView(pmCard(item))

        val tail = if (page < lastPage) "继续下翻载入下一页（" + page + "/" + lastPage + "）" else "已经到底了"
        val tailView = Ui.text(host, tail, Theme.smallSize - 1, Theme.muted, gravity = Gravity.CENTER)
        tailView.setPadding(0, dpD(10f), 0, dpD(4f))
        if (page < lastPage) {
            tailView.isClickable = true
            tailView.setOnClickListener { if (!loading) load((page + 1).coerceAtMost(lastPage), true) }
        }
        body.addView(tailView)
        chrome()
    }

    private fun pmCard(item: PmItem): View {
        val row = Ui.row(host)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpD(8f) }
        row.background = Ui.card(host, stroke = true, fill = false)
        row.setPadding(dpD(12f), dpD(9f), dpD(12f), dpD(9f))

        if (item.unread) {
            val bar = View(host)
            bar.setBackgroundColor(Theme.ink)
            row.addView(bar, LinearLayout.LayoutParams(dpD(3f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                rightMargin = dpD(9f)
            })
        }

        val col = Ui.column(host)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val head = Ui.row(host)
        val name = Ui.text(host, item.name.ifBlank { "未知" }, Theme.bodySize, Theme.body, bold = item.unread)
        Ui.singleLine(name)
        head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (item.time.isNotBlank()) {
            head.addView(Ui.text(host, item.time, Theme.smallSize - 1, Theme.muted))
        }
        col.addView(head)

        if (item.summary.isNotBlank()) {
            val summary = Ui.text(host, item.summary, Theme.smallSize, Theme.secondary)
            summary.maxLines = 2
            summary.ellipsize = android.text.TextUtils.TruncateAt.END
            summary.setPadding(0, dpD(4f), 0, 0)
            col.addView(summary)
        }

        row.isClickable = true
        row.setOnClickListener {
            host.push(PmChatPage(host, item.uid, item.name))
        }
        return row
    }

    override fun hasNextChunk(): Boolean = page < lastPage
    override fun onReachedEnd() {
        if (page < lastPage && !loading) load(page + 1, append = true)
    }

    override fun refresh() {
        invalidatePageCache()
        items.clear()
        load(1, append = false)
    }
}
