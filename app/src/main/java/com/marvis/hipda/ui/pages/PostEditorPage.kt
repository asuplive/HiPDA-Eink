package com.marvis.hipda.ui.pages

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.marvis.hipda.model.FormSpec
import com.marvis.hipda.net.HiApi
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async

/**
 * 发新帖 / 回帖编辑器（原生）。
 *
 * 站点防伪是 formhash + posttime，二者每次提交都要重新取，所以进入页面先抓一次表单页；
 * 提交时把表单里的全部隐藏域原样带回（漏一个就会被判「无权发帖」）。
 */
class PostEditorPage(
    host: MainActivity,
    private val fid: String,
    private val tid: String,
    private val newThread: Boolean,
    private val replySubject: String = "",
    /** 提交成功后回到哪个页面（一般是帖子详情页） */
    private val onPosted: (() -> Unit)? = null
) : ScrollPage(host) {

    override val title: String get() = if (newThread) "发新帖" else "回复"

    private var spec: FormSpec? = null
    private var submitting = false
    private var statusText: String = "正在读取发帖参数…"

    private val subjectInput: EditText by lazy { input("标题", singleLine = true) }
    private val messageInput: EditText by lazy { input("内容", singleLine = false) }
    private val categoryRow: LinearLayout by lazy { Ui.row(host) }
    private var categoryId: String = ""
    private val statusView: android.widget.TextView by lazy {
        Ui.text(host, statusText, Theme.smallSize, Theme.secondary)
    }

    init {
        render()
        loadSpec()
    }

    private fun formUrl(): String =
        if (newThread) HiUrls.newThreadForm(fid) else HiUrls.replyForm(fid, tid)

    private fun loadSpec() {
        val url = formUrl()
        loadHtml(
            url = url,
            onHtml = { html ->
                Async.run(
                    work = { HiApi.formSpecFromHtml(html, url) },
                    onSuccess = { s ->
                        spec = s
                        statusText = if (s.error.isNotBlank()) s.error else ""
                        if (newThread && subjectInput.text.isBlank()) {
                            subjectInput.setText(replySubject)
                        }
                        render()
                    },
                    onError = { t ->
                        statusText = "读取发帖参数失败：" + (t.message ?: "网络异常")
                        render()
                    }
                )
            },
            onFail = { t ->
                statusText = "读取发帖参数失败：" + (t.message ?: "网络异常")
                render()
            }
        )
    }

    private fun render() {
        clearBody()
        val s = spec

        if (newThread) {
            body.addView(label("标题"))
            body.addView(subjectInput)
        } else if (replySubject.isNotBlank()) {
            val sub = Ui.text(host, "回复：" + replySubject, Theme.smallSize, Theme.muted)
            sub.maxLines = 2
            sub.setPadding(0, 0, 0, dpD(8f))
            body.addView(sub)
        }

        if (s != null && s.categories.isNotEmpty()) {
            body.addView(label("分类"))
            categoryRow.removeAllViews()
            for ((id, name) in s.categories) {
                val chip = Ui.text(
                    host, name, Theme.smallSize + 1,
                    if (id == categoryId) Theme.paper else Theme.body,
                    bold = id == categoryId, gravity = Gravity.CENTER
                )
                chip.setPadding(dpD(10f), dpD(6f), dpD(10f), dpD(6f))
                chip.background = if (id == categoryId) Ui.solid(host, Theme.ink, 4f)
                else Ui.card(host, stroke = true, fill = false)
                chip.isClickable = true
                chip.setOnClickListener {
                    categoryId = if (categoryId == id) "" else id
                    render()
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.rightMargin = dpD(8f)
                lp.topMargin = dpD(4f)
                categoryRow.addView(chip, lp)
            }
            body.addView(categoryRow)
        }

        body.addView(label("内容"))
        body.addView(messageInput)

        statusView.text = statusText
        statusView.setPadding(0, dpD(10f), 0, 0)
        body.addView(statusView)

        val submit = Ui.text(
            host, if (newThread) "发表新帖" else "发表回复",
            Theme.bodySize, Theme.paper, bold = true, gravity = Gravity.CENTER
        )
        submit.setPadding(0, dpD(12f), 0, dpD(12f))
        submit.background = Ui.solid(host, Theme.ink, 4f)
        submit.isClickable = true
        submit.setOnClickListener { submit() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dpD(12f)
        body.addView(submit, lp)

        val tip = Ui.text(
            host,
            "支持站点原有 Discuz 代码（如图片 [img] 标签），纯文本即可。",
            Theme.smallSize - 1, Theme.muted
        )
        tip.setPadding(0, dpD(8f), 0, 0)
        body.addView(tip)
        chrome()
    }

    private fun submit() {
        if (submitting) return
        val s = spec ?: run {
            statusText = "发帖参数还没取到，请稍后重试。"
            render()
            return
        }
        if (s.error.isNotBlank()) {
            statusText = s.error
            render()
            return
        }
        val subject = if (newThread) subjectInput.text.toString().trim() else replySubject
        val message = messageInput.text.toString().trim()
        if (newThread && subject.isBlank()) {
            statusText = "标题不能为空。"
            render()
            return
        }
        if (message.isBlank()) {
            statusText = "内容不能为空。"
            render()
            return
        }
        // 部分版区（如 Discovery）强制选分类，空着提交会被站点退回，先在这里拦住
        if (newThread && s.categories.isNotEmpty() && categoryId.isBlank()) {
            statusText = "这个版区要求选一个分类，请先点上面的分类。"
            render()
            return
        }
        submitting = true
        statusText = "正在提交…"
        render()
        Async.run(
            work = { HiApi.submitPost(s, subject, message, categoryId) },
            onSuccess = { result ->
                submitting = false
                statusText = result.message
                if (result.ok) {
                    messageInput.setText("")
                    val back = onPosted
                    if (back != null) back() else {
                        // 发新帖成功后没有现成详情页可回，退回到上一页让用户自己刷新
                        host.pop()
                    }
                } else {
                    render()
                }
            },
            onError = { t ->
                submitting = false
                statusText = "提交失败：" + (t.message ?: "网络异常")
                render()
            }
        )
    }

    private fun label(text: String): android.widget.TextView {
        val tv = Ui.text(host, text, Theme.smallSize, Theme.muted, bold = true)
        tv.setPadding(0, dpD(10f), 0, dpD(4f))
        return tv
    }

    private fun input(hint: String, singleLine: Boolean): EditText {
        val et = EditText(host)
        et.hint = hint
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Theme.bodySize.toFloat())
        et.setTextColor(Theme.body)
        et.setHintTextColor(Theme.muted)
        et.setBackgroundColor(Theme.paper)
        et.setPadding(dpD(10f), dpD(8f), dpD(10f), dpD(8f))
        et.background = Ui.card(host, stroke = true, fill = false)
        if (singleLine) {
            et.inputType = InputType.TYPE_CLASS_TEXT
            et.maxLines = 1
        } else {
            et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            et.gravity = Gravity.TOP or Gravity.START
            et.minLines = 8
            et.maxLines = 24
        }
        et.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return et
    }

    override fun onBack(): Boolean {
        // 输入过内容就二次确认，避免误触返回丢草稿
        val typed = messageInput.text?.toString().orEmpty().isNotBlank() ||
            (newThread && subjectInput.text?.toString().orEmpty().isNotBlank())
        if (!typed) return false
        confirmDiscard()
        return true
    }

    private fun confirmDiscard() {
        val view = Ui.column(host)
        view.setPadding(dpD(4f), dpD(10f), dpD(4f), 0)
        view.addView(Ui.text(host, "已输入的内容会丢失，确认离开？", Theme.smallSize + 1, Theme.secondary))
        val row = Ui.row(host)
        val yes = Ui.text(host, "离开", Theme.smallSize + 1, Theme.ink, bold = true)
        yes.setPadding(0, dpD(10f), 0, 0)
        yes.isClickable = true
        yes.setOnClickListener { host.pop() }
        val no = Ui.text(host, "继续编辑", Theme.smallSize + 1, Theme.muted)
        no.setPadding(dpD(20f), dpD(10f), 0, 0)
        no.isClickable = true
        no.setOnClickListener { clearBody(); render() }
        row.addView(yes)
        row.addView(Ui.spacer(host, 1f), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(no)
        view.addView(row)
        clearBody()
        body.addView(view)
        chrome()
    }

    override fun onNext() {}
    override fun onPrev() {}
}
