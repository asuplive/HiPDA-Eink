package com.marvis.hipda.net

import com.marvis.hipda.model.NoticeItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.math.abs

/** 站点头部「未读汇总」块的五个计数 */
data class PromptCounts(
    val pm: Int = 0,
    val thread: Int = 0,
    val system: Int = 0,
    val friend: Int = 0,
    val announce: Int = 0,
    val loggedIn: Boolean = true
) {
    /** 提醒类合计：帖子消息 + 系统消息 + 好友消息（与提醒页三个频道一一对应） */
    val notice: Int get() = thread + system + friend

    /** 顶栏信封要看的总数：私人消息 + 三类提醒 */
    val total: Int get() = pm + notice
}

/**
 * 消息提醒的取数与解析。
 *
 * 站点是 Discuz! 7.2，未读数只在页面头部的 #myprompt_menu 里以「私人消息 (3)」这种文本给出，
 * 而 notice.php 的列表页结构在登录态下没法稳定验证，所以这里全部走
 * 「多选择器 + 正则兜底」的容错解析：任何一步异常都退化成空列表 / 0，
 * 绝不把异常抛给上层界面。
 */
object NoticeApi {

    const val KIND_THREAD = 0
    const val KIND_SYSTEM = 1
    const val KIND_FRIEND = 2
    /** 短消息（pm.php），只有 markAllRead 用得到 */
    const val KIND_PM = 3

    private val NUM_RE = Regex("[(（]\\s*(\\d+)\\s*[)）]")
    private val TID_RE = Regex("[?&]tid=(\\d+)")
    private val PTID_RE = Regex("[?&]ptid=(\\d+)")
    private val PID_RE = Regex("[?&]pid=(\\d+)")
    private val UID_RE = Regex("[?&]uid=(\\d+)")
    private val SPACE_RE = Regex("\\s+")
    private val TIME_RE = Regex("\\d{1,2}[-/月]|\\d{1,2}:\\d{2}|昨天|今天|前天|\\d{4}")
    /** 评分明细：「威望 +1」「金钱 -2」这种，系统提醒里最有信息量的一段 */
    private val RATING_RE = Regex("(威望|金钱|积分|贡献|好评度)\\s*([+-]\\s*\\d+)")

    /** 管理类动作词：出现在正文里就当作管理提醒 */
    private val MANAGE_WORDS = arrayOf(
        "高亮", "置顶", "加精", "精华", "推荐", "提升", "下沉", "锁定", "关闭", "移动", "警告", "禁言", "删除"
    )

    /** 兜底时要从正文里剥掉的「主语残留」 */
    private val SUBJECT_WORDS = arrayOf(
        "您发表的主题", "您发表的主题帖", "您发表的文章", "您发表的帖子", "您关注的主题",
        "您的主题", "您的帖子", "您的文章", "您的话题", "您的回复", "主题", "帖子", "文章"
    )

    /** 提醒列表常见容器：命中就只在这些容器里找条目，防止把导航栏的 li 也当提醒 */
    private val PRECISE_SELECTORS = arrayOf(
        // Discuz! 7.2 notice.php 真实结构：<ul class="feed"> 每个提示一个 <li>
        "ul.feed > li",
        "ul.feed li",
        "#notice_list li",
        "ul.mylist li",
        "div.nfl li",
        "ul.ntf_list li",
        "div.ntf_list li",
        "div.notice_list li",
        "li.n_li",
        "table.datatable tbody tr"
    )

    /** 条目里的按钮类链接文字，不能当作主题标题 */
    private val BUTTON_WORDS = setOf(
        "查看", "回复", "忽略", "删除", "取消提醒", "打印", "更多", "详情", "全部忽略", "全部已读"
    )

    /** 全局导航/页头页脚：退到通用 li 扫描时一律跳过这些区域里的行 */
    private val CHROME_IDS = setOf("menu", "umenu", "nav", "navmenu", "header", "footer", "ft", "hd")

    fun filterOf(kind: Int): String = when (kind) {
        KIND_SYSTEM -> "systempm"
        KIND_FRIEND -> "friend"
        else -> "threads"
    }

    // ────────────────────────────────────────────────────────────────
    // 未读汇总
    // ────────────────────────────────────────────────────────────────

    /** 从任意已登录页面 HTML 里取未读汇总；取不到就当作未登录 / 全 0 */
    fun countsFromHtml(html: String): PromptCounts {
        try {
            if (html.isBlank()) return PromptCounts(loggedIn = false)
            val doc = Jsoup.parse(html)
            var links = doc.select("#myprompt_menu a")
            if (links.isEmpty()) links = doc.select("a[id^=prompt_]")
            if (links.isEmpty()) return PromptCounts(loggedIn = false)
            val head = html.take(6000)
            if (head.contains("discuz_uid = 0") || head.contains("discuz_uid=0") ||
                head.contains("discuz_uid = '0'") || html.contains("您还未登录")
            ) {
                return PromptCounts(loggedIn = false)
            }
            var pm = 0
            var thread = 0
            var system = 0
            var friend = 0
            var announce = 0
            for (a in links) {
                val n = numOf(a.text())
                when (a.id()) {
                    "prompt_pm" -> pm = n
                    "prompt_threads" -> thread = n
                    "prompt_systempm" -> system = n
                    "prompt_friend" -> friend = n
                    "prompt_announcepm" -> announce = n
                }
            }
            return PromptCounts(pm, thread, system, friend, announce, loggedIn = true)
        } catch (t: Throwable) {
            return PromptCounts(loggedIn = false)
        }
    }

    /** 请求首页拿未读汇总；未登录时抛 NotLoggedInException（调用方自行吞掉） */
    fun fetchCounts(): PromptCounts {
        val result = HiHttp.get(HiUrls.home, HiUrls.baseUrl)
        val counts = countsFromHtml(result.body)
        if (!counts.loggedIn) throw NotLoggedInException("消息提醒需要登录后查看")
        return counts
    }

    /** 「私人消息 (12)」→ 12；取不到按 0 */
    private fun numOf(text: String): Int =
        NUM_RE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    // ────────────────────────────────────────────────────────────────
    // 提醒列表
    // ────────────────────────────────────────────────────────────────

    /** 联网取提醒列表；失败返回空列表，不抛异常 */
    fun noticeList(kind: Int = KIND_THREAD): List<NoticeItem> {
        try {
            val url = HiUrls.notices + "?filter=" + filterOf(kind)
            val html = HiHttp.get(url, HiUrls.baseUrl).body
            val list = listFromHtml(html, url, kind)
            if (list.isNotEmpty() || kind != KIND_THREAD) return list

            // 帖子消息在不同 Discuz 版本里可能叫 post，空列表时再试一次
            val alt = HiUrls.notices + "?filter=post"
            val html2 = try {
                HiHttp.get(alt, HiUrls.baseUrl).body
            } catch (t: Throwable) {
                return list
            }
            return listFromHtml(html2, alt, kind)
        } catch (t: Throwable) {
            return emptyList()
        }
    }

    /**
     * 从 notice.php 的 HTML 里解析提醒条目。
     * 站点结构不可靠，按「候选容器 → 含 tid/pid/uid 的链接」两级筛，
     * 筛不出东西就当这页没有提醒。
     */
    fun listFromHtml(html: String, url: String, kind: Int): List<NoticeItem> {
        try {
            if (html.isBlank()) return emptyList()
            val doc = Jsoup.parse(html, url)

            val containers = ArrayList<Element>()
            val seenContainer = HashSet<Element>()
            for (selector in PRECISE_SELECTORS) {
                for (el in doc.select(selector)) {
                    if (seenContainer.add(el)) containers.add(el)
                }
            }
            // 精准容器一个都没命中时才退回全页 li，并且只认带主题/回复链接的行
            val loose = containers.isEmpty()
            if (loose) {
                for (el in doc.select("li")) {
                    if (inChrome(el)) continue
                    containers.add(el)
                }
            }

            val out = LinkedHashMap<Long, NoticeItem>()
            for (el in containers) {
                val item = parseOne(el, kind, postOnly = loose) ?: continue
                if (!out.containsKey(item.id)) out[item.id] = item
            }
            if (out.isEmpty()) return emptyList()

            // 保持站点给出的顺序（feed 本身已是最新在前）；按 ID 排序会把合成 ID 的系统提醒全顶到最前
            val list = ArrayList(out.values)
            return if (list.size > 100) ArrayList(list.subList(0, 100)) else list
        } catch (t: Throwable) {
            return emptyList()
        }
    }

    /** 行是否属于全局导航/页头页脚区域 */
    private fun inChrome(el: Element): Boolean {
        var p: Element? = el
        var guard = 0
        while (p != null && guard < 8) {
            val id = p.id()
            if (id.isNotEmpty() && CHROME_IDS.contains(id.lowercase())) return true
            val cls = p.className().lowercase()
            if (cls.contains("menu") || cls.contains("nav") || cls.contains("footer")) return true
            p = p.parent()
            guard++
        }
        return false
    }

    private fun parseOne(el: Element, kind: Int, postOnly: Boolean = false): NoticeItem? {
        val anchors = el.select("a[href]")
        if (anchors.isEmpty()) return null

        var tid = ""
        var ptid = ""
        var pid = ""
        var titleAnchor: Element? = null
        var hasTarget = false
        var hasPost = false

        for (a in anchors) {
            val href = a.attr("href")
            val t = TID_RE.find(href)?.groupValues?.get(1) ?: ""
            val pt = PTID_RE.find(href)?.groupValues?.get(1) ?: ""
            val p = PID_RE.find(href)?.groupValues?.get(1) ?: ""
            val u = UID_RE.find(href)?.groupValues?.get(1) ?: ""
            if (t.isNotEmpty() || pt.isNotEmpty() || p.isNotEmpty() || u.isNotEmpty()) hasTarget = true
            if (t.isNotEmpty() || pt.isNotEmpty() || p.isNotEmpty()) hasPost = true
            if (t.isNotEmpty() && tid.isEmpty()) tid = t
            if (pt.isNotEmpty() && ptid.isEmpty()) ptid = pt
            if (p.isNotEmpty() && pid.isEmpty()) pid = p
            // 主题标题链接：带 tid/ptid、且文字不是「查看」「回复」这类按钮词
            if (titleAnchor == null && (t.isNotEmpty() || pt.isNotEmpty())) {
                val label = a.text().trim()
                if (label.length > 1 && !BUTTON_WORDS.contains(label)) titleAnchor = a
            }
        }
        if (!hasTarget) return null
        // 全页扫描模式下只认主题/回复链接：纯 uid 行（「我的」「退出」这类导航）一律丢弃
        if (postOnly && !hasPost) return null

        // 帖子消息的链接常写成 viewthread.php?ptid=123，tid 与 ptid 同义，缺 tid 时用它兜底
        if (tid.isEmpty()) tid = ptid

        // 条目正文块：真实结构是 <li class="f_thread"><div class="f_thread_box">…</div></li>，
        // 取不到就退回整个 li
        val box = el.children().firstOrNull { it.tagName() == "div" } ?: el
        val cls = (el.className() + " " + box.className()).lowercase()

        var time = ""
        for (node in el.select("span, em, cite, font[color]")) {
            val t = node.text().trim()
            if (t.isNotEmpty() && TIME_RE.containsMatchIn(t)) time = t
        }

        // 人名：条目里所有 space.php / uid= 的链接文字（有人在、谁高亮、谁评分都靠它）
        val names = ArrayList<String>()
        var uid = ""
        for (a in anchors) {
            if (a === titleAnchor) continue
            val href = a.attr("href")
            if (href.contains("space.php") || href.contains("uid=")) {
                val name = a.text().trim()
                if (name.isNotEmpty() && !names.contains(name)) names.add(name)
                if (uid.isEmpty()) uid = UID_RE.find(href)?.groupValues?.get(1) ?: ""
            }
        }
        val from = names.firstOrNull() ?: ""

        var title = titleAnchor?.text()?.trim()?.replace(SPACE_RE, " ") ?: ""
        if (title.isBlank()) {
            // 没有标题链接：把正文里的人名、时间、动作词抠掉，剩下的当标题
            var t = box.text().replace(SPACE_RE, " ").trim()
            for (n in names) t = t.replace(n, " ")
            if (time.isNotEmpty()) t = t.replace(time, " ")
            title = t.replace(SPACE_RE, " ").trim().take(60)
        }
        if (title.isBlank()) title = "（无标题）"

        val reason = reasonOf(cls, box.text().replace(SPACE_RE, " ").trim(), names, time)

        // 摘要：<dl class="summary"><dd>主题</dd><dd>回复原文</dd></dl>，取最后一格当预览
        var excerpt = ""
        for (dd in el.select("dl dd")) {
            val tx = dd.text().replace(SPACE_RE, " ").trim()
            if (tx.isNotEmpty() && tx != title && tx != reason) excerpt = tx
        }

        val fallback = abs((from + title + time).hashCode()).toLong()
        val id = when {
            pid.isNotEmpty() -> pid.toLongOrNull() ?: fallback
            // 系统提醒没有 pid，同一主题会被高亮、被评分、被提升多次：用「主题+原因+时间」合成，
            // 免得几条不同原因的提醒挤成一条
            tid.isNotEmpty() -> 0x40000000L + abs((tid + "|" + reason + "|" + time).hashCode() % 0x3F000000)
            else -> fallback
        }

        return NoticeItem(
            id = if (id == 0L) fallback else id,
            kind = kind,
            title = title,
            from = from,
            uid = uid,
            time = time,
            tid = tid,
            pid = pid,
            href = titleAnchor?.attr("href")?.trim() ?: "",
            reason = reason,
            excerpt = excerpt.take(70)
        )
    }

    /**
     * 提醒原因：把站点条目正文压成一句「谁做了什么」。
     *
     * 站点给的 div 类名（f_reply / f_rate / f_manage / f_thread …）不可靠，各版本还不一样，
     * 所以先按类名定类型，类名认不出时按正文关键词判类型（高亮 / 置顶 / 评分 / 威望 +1 / 回复 / 引用），
     * 最后才退化成「剥掉人名后剩下的那句」。目标是列表上永远有一句能看懂的原因。
     */
    private fun reasonOf(cls: String, text: String, names: List<String>, time: String): String {
        var t = text
        if (time.isNotEmpty()) t = t.replace(time, " ")
        t = t.replace(SPACE_RE, " ").trim()

        val who = when {
            names.isEmpty() -> ""
            names.size == 1 -> names[0]
            else -> names[0] + " 等 " + names.size + " 人"
        }
        val lead = if (who.isEmpty()) "" else who + " "

        // 1) 评分 / 威望 / 金钱这类加减分：直接写出加了多少，这是系统提醒里最要紧的信息
        val rating = RATING_RE.find(t)
        if (cls.contains("f_rate") || t.contains("评分") || t.contains("威望") ||
            t.contains("金钱") || t.contains("积分")
        ) {
            if (!t.contains("不足")) {
                val detail = rating?.let { it.groupValues[1] + " " + it.groupValues[2].replace(" ", "") }
                return when {
                    detail != null && who.isNotEmpty() -> lead + "给你的帖子评分：" + detail
                    detail != null -> "你的帖子被评分：" + detail
                    who.isNotEmpty() -> lead + "给你的帖子评分"
                    else -> "你的帖子被评分"
                }
            }
        }

        // 2) 管理动作：高亮 / 置顶 / 加精 / 推荐 / 移动 / 删除 等
        val manage = MANAGE_WORDS.firstOrNull { t.contains(it) }
        if (cls.contains("f_manage") || manage != null) {
            val act = manage ?: "管理操作"
            return if (who.isNotEmpty()) "你的主题被 " + who + " " + act + "了" else "你的主题被" + act + "了"
        }

        // 3) 回复 / 引用 / 评论 / 关注的主题 / 好友 / 短消息
        if (t.contains("引用")) return lead + "引用了你的帖子"
        if (cls.contains("f_comment") || t.contains("评论")) return lead + "评论了你的帖子"
        if (t.contains("关注的主题") || cls.contains("f_thread")) {
            return (if (who.isEmpty()) "有人" else who) + " 回复了你关注的主题"
        }
        if (t.contains("回复")) return lead + "回复了你的帖子"
        if (cls.contains("f_buddy") || t.contains("好友")) return lead + "加你为好友"
        if (cls.contains("f_pm") || t.contains("短消息")) return lead + "给你发了短消息"
        if (t.contains("提到") || t.contains("@")) return lead + "@ 了你"

        // 4) 兜底：把主谓部分剥掉，剩下的就是动作本身
        var s = t
        for (n in names) s = s.replace(n, " ")
        for (w in SUBJECT_WORDS) s = s.replace(w, " ")
        s = s.replace(SPACE_RE, " ").trim().trim('，', ',', '。', '：', ':', '的')
        return s.take(40)
    }

    // ────────────────────────────────────────────────────────────────
    // 已读
    // ────────────────────────────────────────────────────────────────

    /**
     * 尽力而为地把提醒标为已读。
     * 站点没有稳定的「全部已读」接口，只能找页面里的「标记已读 / 忽略」链接并跟一次；
     * 找不到或失败就直接 return，不做任何 UI 提示。
     */
    fun markAllRead(kind: Int) {
        try {
            if (kind >= KIND_PM) {
                try {
                    HiHttp.get(HiUrls.baseUrl + "pm.php?action=markread&filter=newpm", HiUrls.baseUrl)
                } catch (t: Throwable) {
                }
                return
            }
            val url = HiUrls.notices + "?filter=" + filterOf(kind)
            val html = HiHttp.get(url, HiUrls.baseUrl).body
            val doc = Jsoup.parse(html, url)
            // 只点「全部已读 / 全部忽略」这类按钮；单个条目的「忽略」不能碰，否则会误删用户没看过的提醒
            val link = doc.select("a[href]").firstOrNull { a ->
                val href = a.attr("href")
                val text = a.text()
                val acts = href.contains("markread") || href.contains("action=notice") || href.contains("ignore")
                val all = text.contains("全部") || text.contains("所有")
                acts && all
            } ?: return
            val target = HiHttp.absolute(link.attr("href"))
            HiHttp.get(target, url)
        } catch (t: Throwable) {
        }
    }
}
