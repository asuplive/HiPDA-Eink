package com.marvis.hipda.ui.pages

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.model.PostItem
import com.marvis.hipda.model.ReportSpec
import com.marvis.hipda.net.HiApi
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async

/**
 * 报告（举报）楼层 —— 原生表单，不跳网页。
 *
 * 站点表单是 misc.php?action=report 上的 form#reportform：
 * 理由下拉（select[name=reason]）+ 补充说明（textarea[name=message]）+ formhash。
 * 提交要求把表单里所有隐藏域原样带回，漏一个就被判来路不明。
 */
class ReportPage(
    host: MainActivity,
    private val fid: String,
    private val tid: String,
    private val floor: Int,
    private val pid: String,
    private val author: String
) : ScrollPage(host) {

    override val title: String = "报告"

    private var spec: ReportSpec? = null
    private var submitting = false
    private var statusText: String = "正在读取报告表单…"
    private var reasonId: String = ""
    private val reasonRow: LinearLayout by lazy { Ui.row(host) }
    private val messageInput: EditText by lazy { input() }
    private val statusView: android.widget.TextView by lazy {
        Ui.text(host, statusText, Theme.smallSize, Theme.secondary)
    }

    init {
        if (!Prefs.isLoggedIn()) {
            host.push(LoginPage(host))
        }
        render()
        loadSpec()
    }

    private fun loadSpec() {
        val url = HiUrls.report(fid, tid, pid)
        loadHtml(
            url = url,
            onHtml = { html ->
                Async.run(
                    work = { HiApi.reportSpecFromHtml(html, url) },
                    onSuccess = { s ->
                        spec = s
                        if (s.error.isNotBlank()) {
                            statusText = s.error
                        } else {
                            statusText = if (s.reasons.isEmpty()) "站点没返回可选项，可直接写意见提交。" else ""
                            if (reasonId.isBlank()) {
                                // 站点默认勾选「推荐」，这里沿用它的 checked 项
                                reasonId = s.defaultReason.ifBlank {
                                    s.reasons.firstOrNull()?.first.orEmpty()
                                }
                            }
                            if (messageInput.text.isBlank() && s.messageHint.isNotBlank()) {
                                messageInput.setText(s.messageHint)
                            }
                        }
                        render()
                    },
                    onError = { t ->
                        statusText = "读取报告表单失败：" + (t.message ?: "网络异常")
                        render()
                    }
                )
            },
            onFail = { t ->
                statusText = "读取报告表单失败：" + (t.message ?: "网络异常")
                render()
            }
        )
    }

    private fun render() {
        clearBody()
        val s = spec

        val head = Ui.text(
            host,
            "报告第 " + floor + " 楼" + (if (author.isNotBlank()) "（" + author + "）" else ""),
            Theme.bodySize, Theme.ink, bold = true
        )
        head.setPadding(0, dpD(2f), 0, dpD(4f))
        body.addView(head)
        body.addView(
            Ui.text(host, "报告会提交给版主处理，被报告的人看不到是谁提交的。", Theme.smallSize - 1, Theme.muted)
                .apply { setPadding(0, 0, 0, dpD(6f)) }
        )

        if (s != null && s.reasons.isNotEmpty()) {
            body.addView(label("报告类型"))
            reasonRow.removeAllViews()
            for ((id, name) in s.reasons) {
                val chip = Ui.text(
                    host, name, Theme.smallSize + 1,
                    if (id == reasonId) Theme.paper else Theme.body,
                    bold = id == reasonId, gravity = Gravity.CENTER
                )
                chip.setPadding(dpD(10f), dpD(6f), dpD(10f), dpD(6f))
                chip.background = if (id == reasonId) Ui.solid(host, Theme.ink, 4f)
                else Ui.card(host, stroke = true, fill = false)
                chip.isClickable = true
                chip.setOnClickListener {
                    reasonId = if (reasonId == id) "" else id
                    render()
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.rightMargin = dpD(8f)
                lp.topMargin = dpD(4f)
                reasonRow.addView(chip, lp)
            }
            body.addView(reasonRow)
        }

        body.addView(label(s?.messageLabel ?: "我的意见"))
        body.addView(messageInput)

        statusView.text = statusText
        statusView.setPadding(0, dpD(10f), 0, 0)
        body.addView(statusView)

        val submit = Ui.text(
            host, if (submitting) "正在提交…" else "提交",
            Theme.bodySize, Theme.paper, bold = true, gravity = Gravity.CENTER
        )
        submit.setPadding(0, dpD(12f), 0, dpD(12f))
        submit.background = Ui.solid(host, if (submitting) Theme.muted else Theme.ink, 4f)
        submit.isClickable = true
        submit.setOnClickListener { submit() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dpD(12f)
        body.addView(submit, lp)
        chrome()
    }

    private fun submit() {
        if (submitting) return
        val s = spec ?: run {
            statusText = "报告表单还没取到，请稍后重试。"
            render()
            return
        }
        if (s.error.isNotBlank()) {
            statusText = s.error
            render()
            return
        }
        if (s.reasons.isNotEmpty() && reasonId.isBlank()) {
            statusText = "请先选「推荐」或「举报」。"
            render()
            return
        }
        if (reasonId.isBlank() && messageInput.text.isBlank()) {
            statusText = "请先选报告类型或写一句意见。"
            render()
            return
        }
        submitting = true
        statusText = "正在提交…"
        render()
        Async.run(
            work = { HiApi.submitReport(s, reasonId, messageInput.text.toString().trim()) },
            onSuccess = { r ->
                submitting = false
                statusText = r.message
                render()
                if (r.ok) {
                    messageInput.setText("")
                    showReportDone(r.message)
                }
            },
            onError = { t ->
                submitting = false
                statusText = "提交失败：" + (t.message ?: "网络异常")
                render()
            }
        )
    }

    /** 提交成功后应用内提示；点「确定」直接退回原帖，不再抛网页式的「点此跳转」 */
    private fun showReportDone(message: String) {
        if (host.isFinishing) return
        AlertDialog.Builder(host)
            .setTitle("报告已提交")
            .setMessage(message.ifBlank { "报告已提交。" })
            .setCancelable(false)
            .setPositiveButton("确定") { _, _ -> host.pop() }
            .show()
    }

    private fun label(text: String): android.widget.TextView {
        val tv = Ui.text(host, text, Theme.smallSize, Theme.muted, bold = true)
        tv.setPadding(0, dpD(10f), 0, dpD(4f))
        return tv
    }

    private fun input(): EditText {
        val et = EditText(host)
        et.hint = "写一句意见（选填）"
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Theme.bodySize.toFloat())
        et.setTextColor(Theme.body)
        et.setHintTextColor(Theme.muted)
        et.setBackgroundColor(Theme.paper)
        et.setPadding(dpD(10f), dpD(8f), dpD(10f), dpD(8f))
        et.background = Ui.card(host, stroke = true, fill = false)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.gravity = Gravity.TOP or Gravity.START
        et.minLines = 3
        et.maxLines = 10
        et.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return et
    }

    override fun onNext() {}
    override fun onPrev() {}
}
