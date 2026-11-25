package com.marvis.hipda.ui.pages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.content.Context
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
    private val fallbackTitle: String,
    /** 从提醒进来时要定位的楼层 pid；空串表示普通打开 */
    private val focusPid: String = "",
    /** 跳转来源（列表 / 收藏 / 我的回复等）已知的楼主名与 uid：翻页兜底判定「楼主」用 */
    listAuthor: String = "",
    listUid: String = ""
) : ScrollPage(host) {

    private var page = 1
    private var lastPage = 1
    private var data: PostPage? = null
    private var loading = false
    private var errorText: String? = null
    private var needVerify = false
    /** 分批渲染代次：重新载入后旧批次不能再往 body 加视图 */
    private var renderToken = 0

    /**
     * 本帖楼主身份：先取跳转来源带过来的（列表 / 收藏里的作者名与 id），
     * 首页解析成功后以 1 楼为准覆盖，翻到第 2 页及以后回传给解析器，
     * 保证楼主自己在后面的回帖也带「楼主」标签。只在取到非空值时更新，刷新/重载不会丢。
     */
    private var opAuthor: String = listAuthor
    private var opUid: String = listUid

    /** 待定位的楼层：载入到含该楼的那一页后清空，后续翻页不再跳 */
    private var pendingFocus = focusPid
    private var focusView: View? = null
    private var focusScrolled = false
    /** 站点也没给出含该楼的那页时，退回普通第一页，只退一次 */
    private var focusMissed = false

    /** 顶栏标题：显示帖子标题，字数超出时顶栏自己收成「…」，点一下可下拉展开全文 */
    override val title: String
        get() = data?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle

    /** 顶栏下拉展开用的完整标题：与顶栏一致，点标题时整段铺开、不截断 */
    override val expandTitle: String
        get() = title

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

        // 有 pid 时走「定位」地址：站点直接返回含该楼的那一页，省去自己算页码
        val jump = pendingFocus.isNotBlank()
        val url = if (jump) HiUrls.findPost(pendingFocus, tid) else HiUrls.threadDetail(tid, target)
        loadHtml(
            url = url,
            forceRefresh = force,
            onHtml = { html ->
                Async.run(
                    work = { HiApi.threadDetailFromHtml(html, url, tid, opAuthor, opUid) },
                    onSuccess = { result ->
                        loading = false
                        data = result
                        // 记住楼主身份：本页若含 1 楼就以它为准，否则保留已有值，供后续翻页继续打「楼主」标签
                        if (result.opUid.isNotBlank()) opUid = result.opUid
                        if (result.opAuthor.isNotBlank()) opAuthor = result.opAuthor
                        page = result.page.coerceAtLeast(1)
                        lastPage = result.lastPage.coerceAtLeast(1)
                        if (jump) {
                            val hit = result.posts.any { it.postId == pendingFocus }
                            if (hit) pendingFocus = ""
                        }
                        focusView = null
                        focusScrolled = false
                        render()
                        // 定位失败（站点没返回含该楼的页）：退回普通打开，别把用户留在半截页面
                        if (jump && pendingFocus.isNotBlank() && !focusMissed) {
                            focusMissed = true
                            pendingFocus = ""
                            load(1, force = true)
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
        if (focusPid.isNotBlank() && current.posts.any { it.postId == focusPid }) {
            body.addView(
                Ui.text(host, "已定位到提醒里的那一楼", Theme.smallSize, Theme.muted)
                    .apply { setPadding(0, 0, 0, dpD(8f)) }
            )
        }
        for (post in current.posts.take(2)) {
            body.addView(postView(post))
        }
        chrome()
        maybeFocusScroll()
        appendRest(token, current.posts.drop(2), 0)
    }

    /** 定位楼层渲染出来后滚到屏首；只在首次生效，之后用户自己翻页 */
    private fun maybeFocusScroll() {
        if (focusScrolled) return
        val v = focusView ?: return
        focusScrolled = true
        scrollToView(v)
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
        maybeFocusScroll()
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
    fun startReply(post: PostItem? = null) {
        if (!Prefs.isLoggedIn()) {
            host.push(LoginPage(host))
            return
        }
        val subject = data?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        // 针对某楼回复时，正文框首行标出楼号与作者，格式对齐站点里常见的
        // 「回复 第 1 楼（ roc.wang ）」——括号内两侧留空格、行尾不跟冒号
        val prefill = if (post == null) "" else (
            "回复 第 " + post.floor + " 楼" +
                (if (post.author.isNotBlank()) "（ " + post.author + " ）" else "") + "\n"
            )
        host.push(
            PostEditorPage(
                host = host,
                fid = "",
                tid = tid,
                newThread = false,
                replySubject = subject,
                initialMessage = prefill,
                onPosted = {
                    host.pop()
                    load(lastPage, force = true)
                }
            )
        )
    }

    /** 长按楼层的菜单：报告 / 回复 / 引用（顺序与站点楼层里的功能按钮一致） */
    private fun showPostMenu(post: PostItem) {
        val items = arrayOf("报告", "回复", "引用")
        android.app.AlertDialog.Builder(host)
            .setTitle("第 " + post.floor + " 楼")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openReport(post)
                    1 -> startReply(post)
                    else -> startQuote(post)
                }
            }
            .show()
    }

    /** 报告：站点自带的举报表单页（misc.php?action=report），在网页里选理由并提交 */
    private fun openReport(post: PostItem) {
        if (!Prefs.isLoggedIn()) {
            host.push(LoginPage(host))
            return
        }
        host.push(
            ReportPage(
                host = host,
                fid = data?.fid.orEmpty(),
                tid = tid,
                floor = post.floor,
                pid = post.postId,
                author = post.author
            )
        )
    }

    /**
     * 引用：本地拼好 [quote] 块（楼层编号 + 原文）直接塞进回复框。
     * 不再依赖站点 repquote 参数预填（实测取不到，站内被 Cloudflare 拦下时会拿空表单）。
     */
    private fun startQuote(post: PostItem) {
        if (!Prefs.isLoggedIn()) {
            host.push(LoginPage(host))
            return
        }
        val subject = data?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        host.push(
            PostEditorPage(
                host = host,
                fid = data?.fid.orEmpty(),
                tid = tid,
                newThread = false,
                replySubject = subject,
                initialMessage = quoteText(post),
                onPosted = {
                    host.pop()
                    load(lastPage, force = true)
                }
            )
        )
    }

    /** 楼层原文（纯文本拼装）：图片位置留一行 [图片] 提示，避免引用块里出现破图标签 */
    private fun postText(post: PostItem): String {
        val sb = StringBuilder()
        for (b in post.blocks) {
            when (b) {
                is Block.Text -> sb.append(b.text.trim()).append("\n")
                is Block.Quote -> sb.append("[quote]").append(b.text.trim()).append("[/quote]\n")
                is Block.Link -> sb.append(b.text.trim()).append("\n")
                is Block.Image -> sb.append("[图片]")
            }
        }
        return sb.toString().trim()
    }

    /**
     * 引用块对齐论坛网页版：原帖由 xxx 于 时间 发表 [指向该楼的链接，显示为「第 N 楼」]，
     * 下面接原文。楼层编号与内容都落在回复框里，可再编辑。
     */
    private fun quoteText(post: PostItem): String {
        val link = HiUrls.baseUrl + "viewthread.php?tid=" + tid + "&pid=" + post.postId + "#pid" + post.postId
        val who = post.author.ifBlank { "匿名" }
        val whenText = post.time.trim()
        val head = "原帖由 " + who + (if (whenText.isNotBlank()) " 于 " + whenText else "") +
            " 发表 [url=" + link + "]第 " + post.floor + " 楼[/url]"
        return "[quote]" + head + "\n" + postText(post) + "\n[/quote]\n\n"
    }

    private fun postView(post: PostItem): View {
        val wrapper = Ui.column(host)
        wrapper.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpD(10f) }

        val focus = focusPid.isNotBlank() && post.postId == focusPid
        if (focus) focusView = wrapper

        val card = Ui.column(host)
        card.background = Ui.card(host, stroke = true, fill = false)

        val head = Ui.row(host)
        head.setPadding(dpD(12f), dpD(9f), dpD(12f), dpD(7f))
        // 头像：与列表同一开关（帖子设置 → 头像显示），默认关
        if (Prefs.showAvatar && post.uid.isNotBlank()) {
            val avatar = ImageView(host)
            avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            val alp = LinearLayout.LayoutParams(dpD(24f), dpD(24f))
            alp.rightMargin = dpD(8f)
            head.addView(avatar, alp)
            val url = HiUrls.avatarUrl(post.uid)
            if (url.isNotBlank()) {
                ImageLoader.load(url) { bmp -> if (bmp != null) avatar.setImageBitmap(bmp) }
            }
        }
        val author = Ui.text(host, post.author.ifBlank { "匿名" }, Theme.smallSize + 1, Theme.ink, bold = true)
        head.addView(author)
        // 楼主回帖时的「楼主」标签：受帖子设置里的开关控制，关闭后标签与前面那段间隔一起不留
        if (post.op && Prefs.showOpBadge) {
            val gap = View(host)
            head.addView(gap, LinearLayout.LayoutParams(dpD(6f), 1))
            head.addView(Ui.badge(host, "楼主"))
        }
        if (focus) {
            val gap = View(host)
            head.addView(gap, LinearLayout.LayoutParams(dpD(6f), 1))
            head.addView(Ui.badge(host, "提醒楼层"))
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

        // 作者资料：UID / 帖子数 / 积分 / 注册时间，四项各自独立开关，默认全不显示
        val profileText = buildString {
            if (Prefs.showUid && post.uid.isNotBlank()) {
                if (isNotEmpty()) append("  ·  ")
                append("UID ").append(post.uid)
            }
            if (Prefs.showPostCount && post.postCount.isNotBlank()) {
                if (isNotEmpty()) append("  ·  ")
                append("帖子 ").append(post.postCount)
            }
            if (Prefs.showCredits && post.credits.isNotBlank()) {
                if (isNotEmpty()) append("  ·  ")
                append("积分 ").append(post.credits)
            }
            if (Prefs.showRegTime && post.regTime.isNotBlank()) {
                if (isNotEmpty()) append("  ·  ")
                append("注册 ").append(post.regTime)
            }
        }
        if (profileText.isNotBlank()) {
            val pv = Ui.text(host, profileText, Theme.smallSize - 1, Theme.muted)
            pv.setPadding(dpD(12f), 0, dpD(12f), dpD(6f))
            card.addView(pv)
        }

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
        // 个性签名：站点放在正文之后的 div.signatures 里，默认不显示
        if (Prefs.showSignature && post.signature.isNotBlank()) {
            val sep = Ui.vsep(host, Theme.line, 1f)
            val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpD(1f)
            )
            slp.topMargin = dpD(8f)
            contentCol.addView(sep, slp)
            val sv = Ui.text(host, "签名：" + post.signature, Theme.smallSize - 1, Theme.muted)
            sv.setPadding(0, dpD(6f), 0, 0)
            contentCol.addView(sv)
        }
        card.addView(contentCol)

        wrapper.addView(card)
        // 长按整张贴文卡片弹出「报告 / 回复 / 引用」，与论坛网页上的楼层功能一致
        wrapper.isLongClickable = true
        wrapper.setOnLongClickListener {
            showPostMenu(post)
            true
        }
        return wrapper
    }

    private fun blockView(block: Block): View? {
        return when (block) {
            is Block.Text -> {
                val tv = Ui.text(host, block.text, Theme.bodySize, Theme.body)
                tv.setPadding(0, dpD(2f), 0, dpD(4f))
                // 不设 setTextIsSelectable：可选择的 TextView 会把长按吞掉去做选区，
                // 长按菜单就永远弹不出来。需要复制的内容走「引用」进编辑器即可。
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
                holder.addView(downloadCard(url, name, holder))
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

    /**
     * 「点击下载」模式的占位卡片：
     * 一个带边框的长方形块（比原来一行灰字大得多，触摸热区也更大），
     * 左侧一个下载图示，右侧大字「点击下载图片」，下面小字附文件名。
     * 点一下才真正下载，成功后整块换成图片；失败则在卡内提示可重试，长按可直接用浏览器打开。
     */
    private fun downloadCard(url: String, name: String, holder: LinearLayout): View {
        val card = Ui.row(host)
        card.background = Ui.card(host, stroke = true, fill = true)
        card.setPadding(dpD(14f), dpD(10f), dpD(14f), dpD(10f))
        // 用最小高度而非死值：字号调大时卡片跟着长高，正文不会被裁切
        card.minimumHeight = dpD(68f)
        card.isClickable = true

        card.addView(
            DownloadGlyph(host),
            LinearLayout.LayoutParams(dpD(26f), dpD(26f)).apply { rightMargin = dpD(14f) }
        )

        val texts = Ui.column(host)
        val label = Ui.text(host, "点击下载图片", Theme.bodySize, Theme.ink, bold = true)
        val sub = Ui.text(host, name, Theme.smallSize - 2, Theme.muted)
        Ui.singleLine(sub)
        texts.addView(label)
        texts.addView(sub)
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dpD(5f)
            bottomMargin = dpD(7f)
        }

        card.setOnClickListener {
            label.text = "正在下载…"
            card.isClickable = false
            ImageLoader.load(url) { bitmap: Bitmap? ->
                if (bitmap != null) {
                    holder.removeAllViews()
                    val iv = buildImageView(url)
                    applyBitmap(iv, bitmap)
                    holder.addView(iv)
                } else {
                    label.text = "下载失败，点击重试"
                    card.isClickable = true
                }
            }
        }
        card.setOnLongClickListener {
            host.push(WebViewPage(host, "查看图片", url))
            true
        }
        return card
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

/**
 * 「点击下载图片」占位卡左侧的下载图示：竖箭杆 + 箭头 + 底部托盘，
 * 直接用 Canvas 画，不走 emoji/字体，颜色跟随主题（E-Ink 下纯黑/纯白，边缘锐利）。
 */
private class DownloadGlyph(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.color = Theme.ink
        paint.strokeWidth = (w * 0.055f).coerceAtLeast(1f)

        val cx = w / 2f
        val shaftBottom = h * 0.56f
        canvas.drawLine(cx, h * 0.12f, cx, shaftBottom, paint)
        val head = w * 0.17f
        canvas.drawLine(cx - head, shaftBottom - head, cx, shaftBottom, paint)
        canvas.drawLine(cx + head, shaftBottom - head, cx, shaftBottom, paint)

        val trayTop = h * 0.66f
        val bottom = h * 0.92f
        val left = w * 0.13f
        val right = w * 0.87f
        canvas.drawLine(left, trayTop, left, bottom, paint)
        canvas.drawLine(left, bottom, right, bottom, paint)
        canvas.drawLine(right, bottom, right, trayTop, paint)
    }
}