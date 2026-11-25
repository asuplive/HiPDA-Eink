package com.marvis.hipda.ui.pages

import android.view.View
import android.widget.LinearLayout
import com.marvis.hipda.BuildConfig
import com.marvis.hipda.net.UpdateApi
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async

/**
 * 关于：简介 + 版本信息 + 项目地址 + 下载/更新入口。
 *
 * 全部用代码搭视图，纯灰阶、无动画、无弹层：
 * 点「检查更新」在后台线程请求 GitHub Release，结果就在本页原地回显一行行文字。
 */
class AboutPage(host: MainActivity) : ScrollPage(host) {

    override val title: String = "关于"

    /** 检查更新的结果区域：每次检查前清空重建，页面高度随之变化 */
    private lateinit var updateBox: LinearLayout
    private var checking = false

    init {
        render()
    }

    private fun render() {
        clearBody()

        val name = Ui.text(host, "HiPDA.Eink", Theme.titleSize + 6, Theme.ink, bold = true)
        body.addView(name)

        body.addView(
            Ui.text(host, "版本 " + BuildConfig.VERSION_NAME + "（build " + BuildConfig.VERSION_CODE + "）",
                Theme.smallSize, Theme.muted)
                .apply { setPadding(0, dpD(4f), 0, dpD(14f)) }
        )

        body.addView(para(
            "HiPDA.Eink 是一个为 4d4y 论坛（HiPDA）写的第三方安卓客户端，" +
                "目标只有一个：把网页版的论坛变成手机上顺手翻的样子。"
        ))
        body.addView(para(
            "版块、帖子列表、帖子详情、我的帖子与回复、收藏、短消息、搜索，全部走原生排版，" +
                "不再把网页表格硬塞进手机屏：一条一条竖着排，整行可点，长标题自动折行，元信息压到第二行。"
        ))
        body.addView(para(
            "翻页按墨水屏的习惯做成一屏一屏地翻，不做跟手滚动；配合 E-Ink 模式一起看，" +
                "残影少、翻页稳。夜里可以切夜间模式，正文用黑底白字。"
        ))
        body.addView(para(
            "支持登录后发新帖与回帖，支持短消息会话阅读，支持 Cloudflare 验证保持在应用内完成。"
        ))
        body.addView(para(
            "数据全部来自 4d4y.com 站点本身，本程序不收集、不上传任何账号信息，登录凭据只保存在本机。"
        ))

        // ── 项目地址 ───────────────────────────────────────────────
        body.addView(sectionTitle("项目地址"))
        body.addView(
            linkRow("源码仓库", UpdateApi.GITHUB_URL) {
                openLink("项目地址", UpdateApi.GITHUB_URL)
            }
        )

        // ── 下载 / 更新 ────────────────────────────────────────────
        body.addView(sectionTitle("下载 / 更新"))
        body.addView(
            linkRow("蓝奏云下载（提取码 " + UpdateApi.LANZOU_CODE + "）", UpdateApi.LANZOU_URL) {
                openLink("蓝奏云下载", UpdateApi.LANZOU_URL)
            }
        )

        val check = Ui.text(host, "检查更新", Theme.bodySize, Theme.ink, bold = true)
        check.setPadding(0, dpD(10f), 0, dpD(6f))
        check.isClickable = true
        check.setOnClickListener { checkUpdate() }
        body.addView(check)

        updateBox = Ui.column(host)
        body.addView(updateBox)
        showIdle()

        body.addView(
            Ui.text(host, "非官方客户端，与站点管理方无关。", Theme.smallSize - 1, Theme.muted)
                .apply { setPadding(0, dpD(12f), 0, 0) }
        )

        val refreshTip = Ui.text(host, "返回上一页", Theme.smallSize + 1, Theme.ink, bold = true)
        refreshTip.setPadding(0, dpD(18f), 0, 0)
        refreshTip.isClickable = true
        refreshTip.setOnClickListener { host.pop() }
        body.addView(refreshTip)
        chrome()
    }

    // ────────────────────────────────────────────────────────────────
    // 检查更新
    // ────────────────────────────────────────────────────────────────

    private fun showIdle() {
        updateBox.removeAllViews()
        updateBox.addView(note("向 GitHub 查询最新发布版本，蓝奏云作为备用下载入口。", Theme.muted))
    }

    private fun checkUpdate() {
        if (checking) return
        checking = true
        updateBox.removeAllViews()
        updateBox.addView(note("正在查询…", Theme.muted))
        chrome()

        Async.run(
            work = { UpdateApi.fetchLatest() },
            onSuccess = { info ->
                checking = false
                showResult(info)
            },
            onError = {
                checking = false
                showFailure()
            }
        )
    }

    private fun showResult(info: UpdateApi.UpdateInfo) {
        val current = UpdateApi.currentVersion()
        updateBox.removeAllViews()

        if (UpdateApi.isNewer(info.version, current)) {
            updateBox.addView(
                note("发现新版本 " + info.version + "（当前 " + current + "）", Theme.ink, bold = true)
            )

            val notes = info.notes.trim()
            if (notes.isNotEmpty()) {
                val tv = Ui.text(host, shorten(notes, 400), Theme.smallSize + 1, Theme.secondary)
                tv.setLineSpacing(dpD(4f).toFloat(), 1f)
                tv.setPadding(0, dpD(6f), 0, dpD(6f))
                updateBox.addView(tv)
            }

            val target = info.apkUrl ?: info.pageUrl
            val label = if (info.tag.isNotBlank()) "GitHub 下载（" + info.tag + "）" else "GitHub 下载"
            updateBox.addView(linkRow(label, target) { openLink("更新下载", target) })
        } else {
            updateBox.addView(
                note("已是最新版本 " + current, Theme.ink, bold = true)
            )
        }

        // 无论有没有新版本，蓝奏云入口都留在结果区，方便手动取包。
        // 地址优先用 release 说明里写的那条（发版时在说明里贴蓝奏链接即可自动跟随），否则用固定文件夹。
        addLanzouRow(info.lanzouUrl, info.lanzouCode)
        chrome()
    }

    private fun showFailure() {
        updateBox.removeAllViews()
        updateBox.addView(note("检查失败，可手动访问项目地址。", Theme.secondary))
        addLanzouRow(UpdateApi.LANZOU_URL, UpdateApi.LANZOU_CODE)
        chrome()
    }

    private fun addLanzouRow(url: String, code: String) {
        val label = if (code.isBlank()) "蓝奏云下载" else "蓝奏云下载（提取码 " + code + "）"
        updateBox.addView(
            linkRow(label, url) { openLink("蓝奏云下载", url) }
        )
    }

    // ────────────────────────────────────────────────────────────────
    // 视图小工具
    // ────────────────────────────────────────────────────────────────

    private fun openLink(pageTitle: String, url: String) {
        host.push(WebViewPage(host, pageTitle, url))
    }

    private fun shorten(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max).trimEnd() + "…"

    private fun sectionTitle(text: String): View {
        val tv = Ui.text(host, text, Theme.smallSize + 1, Theme.ink, bold = true)
        tv.setPadding(0, dpD(16f), 0, dpD(2f))
        return tv
    }

    /** 一行可点入口：上小字标签 + 下加粗可点内容 */
    private fun linkRow(label: String, value: String, action: () -> Unit): View {
        val col = Ui.column(host)
        col.setPadding(0, dpD(5f), 0, dpD(5f))
        col.addView(Ui.text(host, label, Theme.smallSize, Theme.muted))

        val v = Ui.text(host, value, Theme.bodySize, Theme.link, bold = true)
        v.setPadding(0, dpD(4f), 0, dpD(4f))
        v.isClickable = true
        v.setOnClickListener { action() }
        col.addView(v)
        return col
    }

    private fun note(text: String, color: Int, bold: Boolean = false): View {
        val tv = Ui.text(host, text, Theme.smallSize + 1, color, bold = bold)
        tv.setPadding(0, dpD(6f), 0, dpD(4f))
        return tv
    }

    private fun para(text: String): View {
        val tv = Ui.text(host, text, Theme.bodySize, Theme.body)
        tv.setLineSpacing(dpD(4f).toFloat(), 1f)
        tv.setPadding(0, 0, 0, dpD(11f))
        return tv
    }

    override fun pageText(): String = ""

    override fun canNext(): Boolean {
        val content = body.height
        if (content <= 0) return false
        return content - scroller.height - scroller.scrollY > dpD(4f)
    }
}
