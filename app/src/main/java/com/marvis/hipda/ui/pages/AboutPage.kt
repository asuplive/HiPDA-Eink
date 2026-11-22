package com.marvis.hipda.ui.pages

import android.view.View
import com.marvis.hipda.BuildConfig
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD

/** 关于：一段自写简介 + 版本信息 */
class AboutPage(host: MainActivity) : ScrollPage(host) {

    override val title: String = "关于"

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
