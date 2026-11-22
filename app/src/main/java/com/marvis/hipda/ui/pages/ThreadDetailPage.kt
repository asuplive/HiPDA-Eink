package com.marvis.hipda.ui.pages

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.model.Block
import com.marvis.hipda.model.PostItem
import com.marvis.hipda.model.PostPage
import com.marvis.hipda.net.CfChallengeException
import com.marvis.hipda.net.HiApi
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async
import com.marvis.hipda.util.ImageLoader

/** 帖子正文 */
class ThreadDetailPage(
    host: MainActivity,
    private val tid: String,
    private val fallbackTitle: String
) : ScrollPage(host) {

    private var page = 1
    private var lastPage = 1
    private var data: PostPage? = null
    private var loading = false
    private var errorText: String? = null
    private var needVerify = false
    /** 分批渲染代次：重新载入后旧批次不能再往 body 加视图 */
    private var renderToken = 0

    override val title: String
        get() = data?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle

    init {
        load(1)
    }

    private fun load(target: Int, force: Boolean = false) {
        if (loading) return
        loading = true
        errorText = null
        needVerify = false
        // 新一次载入：作废上一轮还在排队的分批渲染
        renderToken++
        clearBody()
        body.addView(loadingView())
        chrome()

        val url = HiUrls.threadDetail(tid, target)
        loadHtml(
            url = url,
            forceRefresh = force,
            onHtml = { html ->
                Async.run(
                    work = { HiApi.threadDetailFromHtml(html, url, tid) },
                    onSuccess = { result ->
                        loading = false
                        data = result
                        page = result.page.coerceAtLeast(1)
                        lastPage = result.lastPage.coerceAtLeast(1)
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
        errorText = when (t) {
            is CfChallengeException -> "站点要求 Cloudflare 验证，验证一次即可正常浏览。"
            is java.net.UnknownHostException -> "无法连接站点，请检查网络。"
            is java.net.SocketTimeoutException -> "连接超时，请稍后重试。"
            else -> "载入失败：" + (t.message ?: t.javaClass.simpleName)
        }
        clearBody()
        body.addView(
            noticeView(errorText!!, if (needVerify) "去完成验证" else "重新载入") {
                if (needVerify) host.startCloudflareCheck(HiUrls.threadDetail(tid, page)) else load(page)
            }
        )
        chrome()
    }

    private fun render() {
        clearBody()
        val current = data ?: return
        if (current.posts.isEmpty()) {
            body.addView(
                noticeView(
                    "没有取到帖子内容：可能需要登录，或该帖已被限制。",
                    if (Prefs.isLoggedIn()) "重新载入" else "去登录"
                ) {
                    if (Prefs.isLoggedIn()) load(page) else host.push(LoginPage(host))
                }
            )
            chrome()
            return
        }
        // 首屏先上两楼，其余分批补：一页 20 楼时一次性建出所有卡片要几十到上百毫秒，
        // 期间屏幕是空白的。先渲染 2 楼能立刻看到内容，剩余按 3 楼一批补齐。
        val token = ++renderToken
        for (post in current.posts.take(2)) {
            body.addView(postView(post))
        }
        chrome()
        appendRest(token, current.posts.drop(2), 0)
    }

    /** 分批补齐剩余楼层；代次变化（重新载入 / 离开本页）后自动停止 */
    private fun appendRest(token: Int, rest: List<PostItem>, from: Int) {
        if (token != renderToken) return
        if (from >= rest.size) {
            appendFooter()
            return
        }
        val end = (from + 3).coerceAtMost(rest.size)
        for (i in from until end) body.addView(postView(rest[i]))
        chrome()
        if (end >= rest.size) appendFooter() else body.postDelayed({ appendRest(token, rest, end) }, 16L)
    }

    /** 楼层末尾：回帖按钮 + 页码 */
    private fun appendFooter() {
        val replyTip = if (page >= lastPage) "回复本帖" else "回复本帖（会跳到最后一页）"
        val replyBtn = Ui.text(host, replyTip, Theme.bodySize, Theme.paper, bold = true, gravity = Gravity.CENTER)
        replyBtn.setPadding(0, dpD(12f), 0, dpD(12f))
        replyBtn.background = Ui.solid(host, Theme.ink, 4f)
        replyBtn.isClickable = true
        replyBtn.setOnClickListener { startReply() }
        val btnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        btnLp.topMargin = dpD(10f)
        body.addView(replyBtn, btnLp)

        body.addView(
            Ui.text(
                host,
                "第 " + page + " / " + lastPage + " 页" + (if (page < lastPage) "　·　继续下翻载入下一页" else ""),
                Theme.smallSize - 1,
                Theme.muted,
                gravity = Gravity.CENTER
            ).apply { setPadding(0, dpD(6f), 0, dpD(2f)) }
        )
        chrome()
    }

    /** 打开回帖编辑器（菜单页的「回帖」也走这里） */
    fun startReply() {
        if (!Prefs.isLoggedIn()) {
            host.push(LoginPage(host))
            return
        }
        val subject = data?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        host.push(
            PostEditorPage(
                host = host,
                fid = "",
                tid = tid,
                newThread = false,
                replySubject = subject,
                onPosted = {
                    host.pop()
                    load(lastPage, force = true)
                }
            )
        )
    }

    private fun postView(post: PostItem): View {
        val wrapper = Ui.column(host)
        wrapper.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpD(10f) }

        val card = Ui.column(host)
        card.background = Ui.card(host, stroke = true, fill = false)

        val head = Ui.row(host)
        head.setPadding(dpD(12f), dpD(9f), dpD(12f), dpD(7f))
        val author = Ui.text(host, post.author.ifBlank { "匿名" }, Theme.smallSize + 1, Theme.ink, bold = true)
        head.addView(author)
        if (post.op) {
            val gap = View(host)
            head.addView(gap, LinearLayout.LayoutParams(dpD(6f), 1))
            head.addView(Ui.badge(host, "楼主"))
        }
        head.addView(Ui.spacer(host))
        val meta = Ui.text(
            host,
            buildString {
                append("第 ").append(post.floor).append(" 楼")
                if (post.time.isNotBlank()) append("   ").append(post.time)
            },
            Theme.smallSize - 1,
            Theme.muted,
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        )
        head.addView(meta)
        card.addView(head)

        card.addView(Ui.vsep(host, Theme.line, 1f))

        val contentCol = Ui.column(host)
        contentCol.setPadding(dpD(12f), dpD(9f), dpD(12f), dpD(10f))
        if (post.notice.isNotBlank()) {
            contentCol.addView(Ui.text(host, post.notice, Theme.bodySize - 1, Theme.muted))
        }
        for (block in post.blocks) {
            val bv = blockView(block) ?: continue
            contentCol.addView(bv)
        }
        card.addView(contentCol)

        wrapper.addView(card)
        return wrapper
    }

    private fun blockView(block: Block): View? {
        return when (block) {
            is Block.Text -> {
                val tv = Ui.text(host, block.text, Theme.bodySize, Theme.body)
                tv.setPadding(0, dpD(2f), 0, dpD(4f))
                tv.setTextIsSelectable(true)
                tv
            }
            is Block.Quote -> {
                val box = Ui.row(host)
                box.setPadding(0, dpD(4f), 0, dpD(6f))
                box.addView(Ui.vline(host, 3f, Theme.muted))
                val tv = Ui.text(host, block.text, Theme.bodySize - 1, Theme.secondary)
                tv.setPadding(dpD(10f), dpD(4f), 0, dpD(4f))
                box.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                box
            }
            is Block.Link -> {
                val tv = Ui.text(host, block.text, Theme.bodySize - 1, Theme.link)
                tv.paintFlags = tv.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                tv.setPadding(0, dpD(3f), 0, dpD(3f))
                tv.setOnClickListener { host.push(WebViewPage(host, "链接", block.url)) }
                tv
            }
            is Block.Image -> imageView(block.url)
        }
    }

    /**
     * 帖子图片，按设置三态处理：
     * 自动下载 = 进帖即加载；点击下载 = 先显示占位，点一下才下载（省流量、翻页更快）；
     * 不显示 = 留一行图注，整块图片不渲染。
     *
     * 顺带修掉一个「帖子永远没有图片」的老问题：旧代码在回调里判断 iv.isAttachedToWindow，
     * 而缓存命中/网络很快时回调往往早于 View 挂载，判断为 false 就丢弃了位图。
     * View 未挂载也能先 setImageBitmap，挂载后自然可见。
     */
    private fun imageView(url: String): View? {
        val name = url.substringAfterLast("/").substringBefore("?")
        when (Prefs.imageMode) {
            Prefs.IMAGE_NONE -> {
                val tv = Ui.text(host, "[图片] " + name, Theme.smallSize - 1, Theme.muted)
                tv.setPadding(0, dpD(3f), 0, dpD(3f))
                return tv
            }
            Prefs.IMAGE_TAP -> {
                val holder = Ui.column(host)
                val tip = Ui.text(host, "[图片] " + name + "　·　点击加载", Theme.smallSize, Theme.muted)
                tip.setPadding(0, dpD(5f), 0, dpD(5f))
                tip.isClickable = true
                tip.setOnClickListener {
                    tip.text = "[图片] " + name + "　·　加载中…"
                    ImageLoader.load(url) { bitmap: Bitmap? ->
                        if (bitmap != null) {
                            holder.removeAllViews()
                            val iv = buildImageView(url)
                            applyBitmap(iv, bitmap)
                            holder.addView(iv)
                        } else {
                            tip.text = "[图片] " + name + "　·　加载失败，点击重试"
                        }
                    }
                }
                tip.setOnLongClickListener {
                    host.push(WebViewPage(host, "查看图片", url))
                    true
                }
                holder.addView(tip)
                return holder
            }
            else -> {
                // 自动下载：先放一个容器，取到图就换成图片，取不到就退化成可点击的文字占位
                // （防盗链拦截 / 附件需登录 / 数据损坏时，至少不会给用户留一片空白）
                val holder = Ui.column(host)
                val iv = buildImageView(url)
                holder.addView(iv)
                ImageLoader.load(url) { bitmap: Bitmap? ->
                    if (bitmap != null) {
                        applyBitmap(iv, bitmap)
                    } else {
                        holder.removeAllViews()
                        val tip = Ui.text(host, "[图片] " + name + "　·　加载失败，长按打开", Theme.smallSize, Theme.muted)
                        tip.setPadding(0, dpD(4f), 0, dpD(4f))
                        tip.setOnClickListener { host.push(WebViewPage(host, "查看图片", url)) }
                        tip.setOnLongClickListener {
                            host.push(WebViewPage(host, "查看图片", url))
                            true
                        }
                        holder.addView(tip)
                    }
                }
                return holder
            }
        }
    }

    private fun buildImageView(url: String): ImageView {
        val iv = ImageView(host)
        iv.adjustViewBounds = true
        iv.setPadding(0, dpD(6f), 0, dpD(6f))
        iv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.visibility = View.GONE
        iv.setOnClickListener { host.push(WebViewPage(host, "查看图片", url)) }
        return iv
    }

    /**
     * 图片尺寸自适应：宽度不再一律 MATCH_PARENT。
     *
     * 旧实现固定 width=MATCH_PARENT + adjustViewBounds，会把一张 270px 宽的小图
     * 硬拉到整屏宽再插值放大，于是「帖子里的图片全是模糊的」。现在按位图真实宽度决定：
     * 够大的图铺满整宽，小图按原像素宽度居中显示，不做放大插值。
     */
    private fun applyBitmap(iv: ImageView, bmp: Bitmap) {
        val dm = host.resources.displayMetrics
        val bmpW = bmp.width
        iv.setImageBitmap(bmp)
        val lp = iv.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        if (bmpW in 1 until (dm.widthPixels * 9 / 10)) {
            val wdp = (bmpW / dm.density).toInt().coerceAtLeast(60)
            lp.width = dpD(wdp.toFloat())
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.CENTER_HORIZONTAL
        iv.layoutParams = lp
        iv.visibility = View.VISIBLE
    }

    override fun hasNextChunk(): Boolean = page < lastPage
    override fun hasPrevChunk(): Boolean = page > 1
    override fun onReachedEnd() = load((page + 1).coerceAtMost(lastPage))
    override fun onReachedStart() = load((page - 1).coerceAtLeast(1))

    override fun refresh() {
        load(page, force = true)
    }
}
