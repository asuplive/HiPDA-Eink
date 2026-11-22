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
    private val PID_RE = Regex("[?&]pid=(\\d+)")
    private val UID_RE = Regex("[?&]uid=(\\d+)")
    private val SPACE_RE = Regex("\\s+")
    private val TIME_RE = Regex("\\d{1,2}[-/月]|\\d{1,2}:\\d{2}|昨天|今天|前天|\\d{4}")

    /** 提醒列表常见容器：命中就只在这些容器里找条目，防止把导航栏的 li 也当提醒 */
    private val PRECISE_SELECTORS = arrayOf(
        "#notice_list li",
        "ul.mylist li",
        "div.nfl li",
        "ul.ntf_list li",
        "div.ntf_list li",
        "div.notice_list li",
        "li.n_li",
        "table.datatable tbody tr"
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

            val list = ArrayList(out.values)
            list.sortByDescending { it.id }
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
        var pid = ""
        var titleAnchor: Element? = null
        var hasTarget = false
        var hasPost = false

        for (a in anchors) {
            val href = a.attr("href")
            val t = TID_RE.find(href)?.groupValues?.get(1) ?: ""
            val p = PID_RE.find(href)?.groupValues?.get(1) ?: ""
            val u = UID_RE.find(href)?.groupValues?.get(1) ?: ""
            if (t.isNotEmpty() || p.isNotEmpty() || u.isNotEmpty()) hasTarget = true
            if (t.isNotEmpty() || p.isNotEmpty()) hasPost = true
            if (t.isNotEmpty() && tid.isEmpty()) {
                tid = t
                titleAnchor = a
            }
            if (p.isNotEmpty() && pid.isEmpty()) pid = p
        }
        if (!hasTarget) return null
        // 全页扫描模式下只认主题/回复链接：纯 uid 行（「我的」「退出」这类导航）一律丢弃
        if (postOnly && !hasPost) return null

        var title = titleAnchor?.text()?.trim()?.replace(SPACE_RE, " ") ?: ""
        if (title.isBlank()) {
            title = el.text().trim().replace(SPACE_RE, " ").take(60)
        }
        if (title.isBlank()) title = "（无标题）"

        var from = ""
        var uid = ""
        for (a in anchors) {
            val href = a.attr("href")
            if (href.contains("space.php") || href.contains("uid=")) {
                val name = a.text().trim()
                if (name.isNotEmpty()) {
                    from = name
                    uid = UID_RE.find(href)?.groupValues?.get(1) ?: ""
                    break
                }
            }
        }

        var time = ""
        for (node in el.select("span, em, cite, font[color]")) {
            val t = node.text().trim()
            if (t.isNotEmpty() && TIME_RE.containsMatchIn(t)) time = t
        }

        val fallback = abs((from + title + time).hashCode()).toLong()
        val id = when {
            pid.isNotEmpty() -> pid.toLongOrNull() ?: fallback
            tid.isNotEmpty() -> 0x40000000L + abs(tid.toLongOrNull() ?: 0L)
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
            href = titleAnchor?.attr("href")?.trim() ?: ""
        )
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
