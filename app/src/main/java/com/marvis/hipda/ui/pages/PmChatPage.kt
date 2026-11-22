package com.marvis.hipda.ui.pages

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.marvis.hipda.model.PmMessage
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.net.CfChallengeException
import com.marvis.hipda.net.HiApi
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.net.NotLoggedInException
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async
import org.jsoup.Jsoup

/**
 * 单个短消息会话（原生单列）。
 * 站点会话页是「头像 + 引用 + 内容 + 转发链接」的横向网页行，手机上同样要横向拖；
 * 这里改成对话气泡式的单列：自己发的靠右、对方发的靠左，时间单独一行小字。
 */
class PmChatPage(
    host: MainActivity,
    private val uid: String,
    private val peerTitle: String
) : ScrollPage(host) {

    override val title: String get() = if (peerTitle.isNotBlank()) peerTitle else "短消息"

    private var loaded = false
    private var errorText: String? = null
    private var needLogin = false
    private var messages: List<PmMessage> = emptyList()
    private var emptyText = ""
    /** 会话页 form#pmform 的防伪串，回复提交必须带；为空说明页面不是会话页或已过期 */
    private var formhash = ""
    /** 会话页回复表单的隐藏域，提交时原样带上 */
    private var replyFields: Map<String, String> = emptyMap()
    private var sending = false
    private var statusText = ""

    private val replyInput: EditText by lazy { buildReplyInput() }

    init {
        load()
    }

    private fun load() {
        clearBody()
        body.addView(loadingView())
        chrome()
        val url = HiUrls.pmChat(uid)
        loadHtml(
            url = url,
            onHtml = { html ->
                Async.run(
                    work = { HiApi.pmChatFromHtml(html, url, uid) },
                    onSuccess = { result ->
                        loaded = true
                        errorText = null
                        messages = result.messages
                        emptyText = result.empty.ifBlank { "这个会话里还没有消息。" }
                        formhash = result.formhash
                        replyFields = result.replyFields
                        render()
                    },
                    onError = { t -> fail(t) }
                )
            },
            onFail = { t -> fail(t) }
        )
    }

    private fun fail(t: Throwable) {
        needLogin = t is NotLoggedInException
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
        if (err != null) {
            body.addView(noticeView(err, if (needLogin) "去登录" else "重新载入") {
                if (needLogin) host.push(LoginPage(host)) else load()
            })
            chrome()
            return
        }
        if (messages.isEmpty()) {
            body.addView(noticeView(emptyText, "重新载入") { load() })
            // 空会话也留回复入口，避免「列表里点进来却发不出第一条」
            body.addView(bottomActions())
            chrome()
            return
        }

        body.addView(
            Ui.text(host, "与 " + title + " 的会话　·　共 " + messages.size + " 条", Theme.smallSize - 1, Theme.muted)
                .apply { setPadding(dpD(2f), 0, 0, dpD(8f)) }
        )

        for (m in messages) body.addView(messageView(m))

        body.addView(bottomActions())
        chrome()
    }

    private fun messageView(m: PmMessage): View {
        val wrap = Ui.column(host)
        wrap.setPadding(0, 0, 0, dpD(9f))

        val head = Ui.row(host)
        val who = Ui.text(host, if (m.self) "我" else m.name.ifBlank { "对方" }, Theme.smallSize - 1, Theme.muted)
        Ui.singleLine(who)
        head.addView(who, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (m.time.isNotBlank()) head.addView(Ui.text(host, m.time, Theme.smallSize - 1, Theme.muted))
        wrap.addView(head)

        val bubble = Ui.text(host, m.text, Theme.bodySize, Theme.body)
        bubble.setPadding(dpD(10f), dpD(8f), dpD(10f), dpD(8f))
        bubble.background = Ui.card(host, stroke = true, fill = m.self)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dpD(4f)
        wrap.addView(bubble, lp)
        return wrap
    }

    private fun buildReplyInput(): EditText {
        val et = EditText(host)
        et.hint = "输入回复内容"
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Theme.bodySize.toFloat())
        et.setTextColor(Theme.body)
        et.setHintTextColor(Theme.muted)
        et.background = Ui.card(host, stroke = true, fill = false)
        et.setPadding(dpD(10f), dpD(8f), dpD(10f), dpD(8f))
        et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.gravity = Gravity.TOP or Gravity.START
        et.minLines = 3
        et.maxLines = 8
        et.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return et
    }

    /**
     * 底部回复区：原生输入框 + 发送。
     *
     * 旧实现把回复丢给内嵌浏览器，而且 WebViewPage 的参数顺序写反，URL 位被传成了标题
     * 「回复短消息」，WebView 拼出一个 http:// 明文地址，安卓 9+ 直接报
     * net::ERR_CLEARTEXT_NOT_PERMITTED —— 也就是「网页无法打开」的来源。
     * 现在按站点会话页的真实表单（form#pmform）直接 POST：
     * pm.php?action=send&uid=..&pmsubmit=yes&infloat=yes，
     * 字段 formhash / handlekey=pmreply / lastdaterange / message。
     */
    private fun bottomActions(): View {
        val col = Ui.column(host)
        col.setPadding(0, dpD(8f), 0, 0)

        col.addView(
            Ui.text(host, "回复", Theme.smallSize + 1, Theme.ink, bold = true)
                .apply { setPadding(dpD(2f), 0, 0, dpD(6f)) }
        )
        // 每次 render 都会建新的底部容器，输入框是复用的单例：先摘掉旧父视图，
        // 否则 addView 会因为「child already has a parent」直接崩
        (replyInput.parent as? ViewGroup)?.removeView(replyInput)
        col.addView(replyInput)

        val send = Ui.text(
            host,
            if (sending) "发送中…" else "发送",
            Theme.bodySize, Theme.paper, bold = true, gravity = Gravity.CENTER
        )
        send.setPadding(0, dpD(11f), 0, dpD(11f))
        send.background = Ui.solid(host, Theme.ink, 4f)
        send.isClickable = !sending
        send.setOnClickListener { sendReply() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dpD(8f)
        col.addView(send, lp)

        if (statusText.isNotBlank()) {
            col.addView(
                Ui.text(host, statusText, Theme.smallSize, if (sending) Theme.muted else Theme.secondary)
                    .apply { setPadding(dpD(2f), dpD(8f), dpD(2f), 0) }
            )
        }

        val browser = Ui.text(host, "仍打不开时：在浏览器里打开这个会话", Theme.smallSize, Theme.muted)
        browser.setPadding(dpD(2f), dpD(10f), dpD(2f), dpD(2f))
        browser.isClickable = true
        browser.setOnClickListener {
            host.push(WebViewPage(host, title, HiUrls.pmChat(uid)))
        }
        col.addView(browser)
        return col
    }

    private fun sendReply() {
        if (sending) return
        if (!Prefs.isLoggedIn()) {
            host.push(LoginPage(host))
            return
        }
        val text = replyInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            statusText = "内容不能为空。"
            render()
            return
        }
        if (formhash.isBlank()) {
            statusText = "会话页的校验串没取到，已重新载入，请再发一次。"
            invalidatePageCache()
            load()
            return
        }
        sending = true
        statusText = "正在发送…"
        render()
        val token = formhash
        val hidden = replyFields
        Async.run(
            work = { HiApi.sendPm(uid, token, text, hidden) },
            onSuccess = { result ->
                sending = false
                if (result.ok) {
                    statusText = "已发送。"
                    replyInput.setText("")
                    invalidatePageCache()
                    load()
                } else {
                    statusText = result.message
                    render()
                }
            },
            onError = { t ->
                sending = false
                statusText = "发送失败：" + (t.message ?: "网络异常")
                render()
            }
        )
    }

    override fun refresh() {
        invalidatePageCache()
        load()
    }
}
