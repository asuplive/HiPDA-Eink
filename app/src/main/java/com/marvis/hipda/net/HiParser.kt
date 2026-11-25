package com.marvis.hipda.net

import com.marvis.hipda.model.Block
import com.marvis.hipda.model.FeedItem
import com.marvis.hipda.model.FeedPage
import com.marvis.hipda.model.FormSpec
import com.marvis.hipda.model.Forum
import com.marvis.hipda.model.PmItem
import com.marvis.hipda.model.PmPage
import com.marvis.hipda.model.PostItem
import com.marvis.hipda.model.PostPage
import com.marvis.hipda.model.ThreadItem
import com.marvis.hipda.model.ThreadPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Discuz 7.x 页面解析，选择器沿用参考项目 HiParserThreadList / HiParserThreadDetail。
 */
object HiParser {

    private fun middle(source: String?, prefix: String, suffix: String): String {
        if (source == null) return ""
        val i = source.indexOf(prefix)
        if (i < 0) return ""
        val start = i + prefix.length
        val j = source.indexOf(suffix, start)
        return if (j < 0) source.substring(start) else source.substring(start, j)
    }

    fun parseUid(doc: Document): String {
        val cite = doc.select("#umenu cite a").firstOrNull() ?: return ""
        return middle(cite.attr("href"), "space.php?uid=", "&")
    }

    /**
     * 站点对游客关闭了部分版块，此时 forumdisplay.php 返回 Discuz 的「提示信息」页：
     * 「对不起，您还未登录，无权访问该版块。」——必须与"真的没有帖子"区分开。
     */
    fun isNoAuth(doc: Document): Boolean {
        val text = doc.select("div.alert_error, div.alert_info, div.fcontent, div.postbox").text()
        if (text.contains("还未登录") || text.contains("无权访问")) return true
        val all = doc.text()
        return all.contains("您还未登录") ||
            (all.contains("无权访问该版块") && doc.select("tbody[id^=normalthread_]").isEmpty())
    }

    /** 页面是否被渲染成了 WAP 模板（站点按移动 UA 输出，结构完全不同） */
    fun isWapTemplate(doc: Document): Boolean =
        doc.select("link[href*=templates/wap]").isNotEmpty() ||
            doc.select("div.message, div.postlist").isNotEmpty() && doc.select("tbody[id^=normalthread_]").isEmpty()

    fun parsePageInfo(doc: Document): Pair<Int, Int> {
        val holder = doc.select("div#wrap div.forumcontrol div.pages").firstOrNull()
            ?: doc.select("div.pages").firstOrNull()
            ?: return 1 to 1
        var current = 1
        var last = 1
        for (n in holder.childNodes()) {
            val text = when (n) {
                is Element -> n.text()
                is TextNode -> n.text()
                else -> ""
            }
            val num = text.trim().toIntOrNull() ?: continue
            if (num > last) last = num
            if (n is Element && n.tagName() == "strong") current = num
        }
        if (last < current) last = current
        return current to last
    }

    /** 从 href 取 tid：兼容 viewthread.php?tid=123 与伪静态 thread-123-1-1.html */
    private fun tidOf(href: String?): String = HiUrls.tidOf(href)

    private fun uidOf(href: String?): String {
        if (href == null) return ""
        return Regex("[?&]uid=(\\d+)").find(href)?.groupValues?.get(1) ?: ""
    }

    /**
     * 解析板块帖子列表。
     * 站点模板里 th 的 class 有 subject / subject common / subject new 三种，
     * 主题链接也同时存在 viewthread.php?tid= 与伪静态 thread-1-1-1.html 两种写法，
     * 老实现只认 th.subject + span a + tid= 三件套，任一变化就会把整页帖子全部丢掉，
     * 表现为「点进板块看不到帖子」。这里改成逐级兜底，任何一项缺失都不至于丢掉整行。
     */
    fun parseThreadList(doc: Document): ThreadPage {
        val items = mutableListOf<ThreadItem>()
        val preferred = doc.select("tbody[id^=normalthread_], tbody[id^=stickthread_]")
        val rows = if (preferred.isNotEmpty()) preferred else doc.select("tbody[id]")
        for (tbody in rows) {
            val titleLink = tbody.select("th.subject span a").firstOrNull()
                ?: tbody.select("th[class*=subject] a[href]").firstOrNull()
                ?: tbody.select("th a[href*=tid=]").firstOrNull()
                ?: tbody.select("th a[href*=thread-]").firstOrNull()
                ?: tbody.select("a[href*=viewthread]").firstOrNull()
                ?: tbody.select("a[href*=thread-]").firstOrNull()
                ?: continue
            val tid = tidOf(titleLink.attr("href"))
            if (tid.isBlank()) continue

            val folderTd = tbody.select("td.folder").firstOrNull()
            val folderImg = folderTd?.select("img")?.firstOrNull()?.attr("src").orEmpty()
            val sticky = tbody.id().startsWith("stickthread") || folderImg.contains("pin_")

            val authorTd = tbody.select("td.author").firstOrNull()
            val authorEl = authorTd?.select("cite")?.firstOrNull() ?: authorTd?.select("a")?.firstOrNull()
            val author = authorEl?.text()?.trim().orEmpty()
            val uid = uidOf(authorEl?.attr("href").orEmpty())
            val createTime = authorTd?.select("em")?.firstOrNull()?.text().orEmpty()

            val nums = tbody.select("td.nums").firstOrNull()
            val replies = nums?.select("strong")?.firstOrNull()?.text().orEmpty()
            val views = nums?.select("em")?.firstOrNull()?.text().orEmpty()

            val lastTd = tbody.select("td.lastpost").firstOrNull()
            val lastUser = lastTd?.select("cite")?.firstOrNull()?.text().orEmpty()
            val lastTime = lastTd?.select("em")?.firstOrNull()?.text().orEmpty()

            var maxPage = 1
            for (a in tbody.select("span.threadpages a, a[href*=page=]")) {
                val p = Regex("[?&]page=(\\d+)").find(a.attr("href"))
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (p != null && p > maxPage) maxPage = p
            }

            val type = tbody.select("th em a").text().trim()
            val isNew = folderImg.contains("new")

            // 特殊状态标注（与论坛列表里的一致）：置顶 / 精华 / 高亮 / 热。
            // 站点没有独立的「状态字段」，置顶看 tbody id 与图钉图标，精华看 digest 图标，
            // 高亮看标题被套了颜色（inline style 或 <font color>），热帖看 folder 图标。
            val statuses = mutableListOf<String>()
            if (sticky) statuses.add("置顶")
            val iconText = (folderImg + " " + tbody.select("img").joinToString(" ") { it.attr("src") })
                .lowercase()
            if (iconText.contains("digest")) statuses.add("精华")
            if (iconText.contains("hot")) statuses.add("热")
            val titleStyle = (titleLink.attr("style") + " " +
                (titleLink.parent()?.attr("style") ?: "")).lowercase()
            if (titleStyle.contains("color") || titleLink.select("font[color]").isNotEmpty()) {
                statuses.add("高亮")
            }
            val title = titleLink.text().trim().ifBlank { titleLink.attr("title").trim() }

            items.add(
                ThreadItem(
                    tid = tid,
                    title = title,
                    author = author,
                    authorId = uid,
                    type = type,
                    lastPost = lastUser.trim(),
                    lastPostTime = lastTime,
                    createTime = createTime,
                    replyCount = replies,
                    viewCount = views,
                    sticky = sticky,
                    isNew = isNew,
                    maxPage = maxPage,
                    statuses = statuses
                )
            )
        }
        val (page, last) = parsePageInfo(doc)
        val title = doc.select("div#nav").firstOrNull()?.let { nav ->
            val copy = nav.clone()
            copy.select("a").remove()
            copy.text().replace("»", "").trim()
        }.orEmpty()
        return ThreadPage(normalizeOrder(items), page, last, title)
    }

    /**
     * 解析站点首页的板块清单 —— 板块表不再写死，完全跟随站点当前展示的板块。
     * Discuz 7.2 首页结构：table#category_N（分类）> tbody#forum_N（板块行，td.forum 内 h2>a 为板块名、p 为简介），
     * 子版块是 td.forum 内的 em>a。取不到的板块站点本来就不展示，正好符合"网站上不显示的就不要"。
     */
    /**
     * 解析 Discuz「跳转到」下拉菜单 #fjump_menu —— 站点当前账号可见的板块全集。
     * 结构：div#fjump_menu > dl（分类）> dt>a 分类名 / dd>ul>li>a 板块。
     * 分类下 ul 为空说明该分类里没有对当前账号开放的板块，整组丢弃。
     */
    fun parseForumJump(doc: Document): List<Pair<String, List<Forum>>> {
        val out = mutableListOf<Pair<String, List<Forum>>>()
        val seen = HashSet<String>()
        for (dl in doc.select("#fjump_menu dl")) {
            val groupName = dl.select("dt a").firstOrNull()?.text()?.trim()
                ?: dl.select("dt").firstOrNull()?.text()?.trim().orEmpty()
            val list = mutableListOf<Forum>()
            for (a in dl.select("dd ul li a[href]")) {
                val fid = HiUrls.fidOf(a.attr("href"))
                val name = a.text().trim()
                if (fid.isBlank() || name.isBlank()) continue
                if (!seen.add(fid)) continue
                list.add(Forum(fid = fid, name = name, desc = "", group = groupName))
            }
            if (list.isNotEmpty()) out.add((groupName.ifBlank { "板块" }) to list)
        }
        return out
    }

    fun parseForumIndex(doc: Document): List<Pair<String, List<Forum>>> {
        val groups = LinkedHashMap<String, MutableList<Forum>>()
        val seen = HashSet<String>()

        fun push(group: String, forum: Forum) {
            if (forum.fid.isBlank() || forum.name.isBlank()) return
            if (!seen.add(forum.fid)) return
            groups.getOrPut(group) { mutableListOf() }.add(forum)
        }

        for (table in doc.select("table[id^=category_]")) {
            val group = table.select("thead h2 a, thead h2, thead").firstOrNull()?.text()
                ?.replace("»", "")?.trim().orEmpty().ifBlank { "版块" }
            // 站点首页的 tbody id 是 forum7 / forum9 这种（forum + fid，没有下划线），
            // 老选择器 tbody[id^=forum_] 全部落空，导致首页只同步出两三个板块。
            val bodies = table.select("tbody[id^=forum]")
            val scan = if (bodies.isNotEmpty()) bodies else listOf(table)
            for (tb in scan) {
                // 首页板块标题在 <th><div class="left"><h2><a>；旧模板在 td.forum 里，两种都认。
                val link = tb.select("th h2 a[href*=forumdisplay]").firstOrNull()
                    ?: tb.select("h2 a[href*=forumdisplay]").firstOrNull()
                    ?: tb.select("td.forum h2 a").firstOrNull()
                    ?: tb.select("a[href*=forumdisplay]").firstOrNull()
                    ?: continue
                val fid = HiUrls.fidOf(link.attr("href"))
                if (fid.isBlank()) continue
                val name = link.text().trim().ifBlank { link.attr("title").trim() }
                val desc = tb.select("div.left p").firstOrNull()?.text()?.trim()
                    ?: tb.select("td.forum p").firstOrNull()?.text()?.trim()
                    ?: tb.select("p").firstOrNull()?.text()?.trim().orEmpty()
                push(group, Forum(fid, name, desc, group))

                for (sub in tb.select("td.forum em a, em a[href*=forumdisplay]")) {
                    val sfid = HiUrls.fidOf(sub.attr("href"))
                    if (sfid.isBlank()) continue
                    val subName = sub.text().trim()
                    if (subName.isBlank()) continue
                    val subGroup = "子版块 · " + name
                    push(subGroup, Forum(sfid, subName, "", subGroup))
                }
            }
        }

        if (groups.isEmpty()) {
            for (a in doc.select("a[href*=forumdisplay.php]")) {
                val fid = HiUrls.fidOf(a.attr("href"))
                val name = a.text().trim()
                if (fid.isBlank() || name.isBlank() || name.length > 24) continue
                push("版块", Forum(fid, name, "", "版块"))
            }
        }
        return groups.map { it.key to it.value.toList() }
    }

    private fun normalizeOrder(items: List<ThreadItem>): List<ThreadItem> =
        items.sortedByDescending { it.sticky }

    /**
     * 「我的帖子 / 我的回复 / 我的收藏 / 搜索结果」统一解析。
     *
     * 站点这几页都是 table.datatable 的 7 列网页表格：
     *   td.folder(图标) | td.icon | th(标题) | td.forum(版块) | td.nums(回复/查看) | td.lastpost(作者/时间)
     * 手机屏放不下横向表格，这里拍平成一条条记录，界面上再排成一列。
     */
    fun parseFeedList(doc: Document, tidFromPid: Boolean = false): FeedPage {
        val (page, last) = parsePageInfo(doc)
        val table = doc.select("table.datatable").firstOrNull()
        val items = mutableListOf<FeedItem>()
        if (table != null) {
            for (tr in table.select("tr")) {
                val titleLink = tr.select("th a[href]").firstOrNull()
                    ?: tr.select("a[href*=viewthread]").firstOrNull()
                    ?: tr.select("a[href*=findpost]").firstOrNull()
                    ?: continue
                val href = titleLink.attr("href")
                val tid = if (tidFromPid && HiUrls.tidOf(href).isBlank()) {
                    Regex("[?&]ptid=(\\d+)").find(href)?.groupValues?.get(1).orEmpty()
                } else {
                    HiUrls.tidOf(href)
                }
                if (tid.isBlank()) continue
                val title = titleLink.text().trim()
                if (title.isBlank()) continue
                val forum = tr.select("td.forum a").firstOrNull()?.text()?.trim().orEmpty()
                val author = tr.select("td.lastpost cite a").firstOrNull()?.text()?.trim().orEmpty()
                val time = tr.select("td.lastpost em").firstOrNull()?.text()?.trim()
                    ?: tr.select("td.lastpost").text()?.trim().orEmpty()
                val info = tr.select("td.nums").firstOrNull()?.text()?.trim().orEmpty()
                val pid = Regex("[?&]pid=(\\d+)").find(href)?.groupValues?.get(1).orEmpty()
                items.add(FeedItem(tid = tid, pid = pid, title = title, forumName = forum, author = author, time = time, info = info))
            }
        }
        val emptyHint = if (items.isEmpty()) {
            val text = doc.select("table.datatable th").text()
            if (text.contains("没有找到")) "没有找到匹配结果" else "暂无内容"
        } else ""
        return FeedPage(items = items, page = page, lastPage = last, empty = emptyHint)
    }

    /** 短消息列表：ul.pm_list > li，含对方 uid、昵称、时间、未读标记 */
    fun parsePmList(doc: Document): PmPage {
        val (page, last) = parsePageInfo(doc)
        val items = mutableListOf<PmItem>()
        for (li in doc.select("ul.pm_list li")) {
            val viewHref = li.select("p.more a, a.to").firstOrNull()?.attr("href").orEmpty()
            val uid = Regex("[?&]uid=(\\d+)").find(viewHref)?.groupValues?.get(1)
                ?: li.attr("id").removePrefix("pm_").takeIf { it.all { c -> c.isDigit() } }
                ?: ""
            val nameLink = li.select("cite a").firstOrNull()
                ?: li.select("a[href*=space.php]").firstOrNull()
            val name = nameLink?.text()?.trim().orEmpty()
            if (name.isBlank()) continue
            val cite = li.select("p.cite").firstOrNull()
            val time = cite?.clone()?.let { c ->
                c.select("cite").remove()
                c.text().trim()
            }.orEmpty()
            val summary = li.select("div.summary").firstOrNull()?.text()?.trim().orEmpty()
            val unread = li.text().contains("新") || li.select("img[src*=new]").isNotEmpty()
            items.add(PmItem(uid = uid, name = name, time = time, summary = summary, unread = unread))
        }
        return PmPage(items = items, page = page, lastPage = last)
    }

    /**
     * 短消息会话内容：pm.php?uid=xxx&daterange=5 的 ul.pm_list 里，
     * li.pm_date 是日期分隔行，li.s_clear[.self] 才是消息行（.self 表示自己发的）。
     */
    fun parsePmChat(doc: Document, uid: String): com.marvis.hipda.model.PmChat {
        val messages = mutableListOf<com.marvis.hipda.model.PmMessage>()
        var dateLabel = ""
        var peer = ""
        for (li in doc.select("ul.pm_list li")) {
            if (li.hasClass("pm_date")) {
                dateLabel = li.text().trim()
                continue
            }
            val body = li.select("div.summary").firstOrNull()?.let { HiParser.textOf(it) }.orEmpty()
            if (body.isBlank()) continue
            val cite = li.select("p.cite").firstOrNull()
            val name = (cite?.select("cite a, cite")?.firstOrNull()?.text()?.trim().orEmpty())
                .ifBlank { cite?.select("cite")?.text()?.trim().orEmpty() }
            val timeText = cite?.clone()?.let { c -> c.select("cite").remove(); c.text().trim() }.orEmpty()
            val self = li.hasClass("self")
            if (peer.isBlank() && !self && name.isNotBlank()) peer = name
            messages.add(
                com.marvis.hipda.model.PmMessage(
                    name = name,
                    time = timeText.ifBlank { dateLabel },
                    text = body,
                    self = self
                )
            )
        }
        if (peer.isBlank()) {
            peer = doc.select("div.pm_talker cite a, #umenu cite a").text().trim()
        }
        return com.marvis.hipda.model.PmChat(
            uid = uid,
            peer = peer,
            messages = messages,
            empty = if (messages.isEmpty()) doc.select("ul.pm_list li").text().trim() else "",
            formhash = doc.select("form#pmform input[name=formhash]").attr("value")
                .ifBlank { doc.select("input[name=formhash]").attr("value") },
            replyFields = doc.select("form#pmform input[type=hidden]").associate {
                it.attr("name") to it.attr("value")
            }.filterKeys { it.isNotBlank() }
        )
    }

    /** 把节点转成纯文本，<br> 与段落边界补换行，避免正文被拼成一片 */
    fun textOf(el: org.jsoup.nodes.Element): String {
        val copy = el.clone()
        for (br in copy.select("br")) br.replaceWith(org.jsoup.nodes.TextNode("\n"))
        for (p in copy.select("p, div.blockcode, blockquote")) p.appendText("\n")
        for (img in copy.select("img")) {
            val src = img.attr("file").ifBlank { img.attr("src") }
            if (src.contains("smilies")) img.replaceWith(org.jsoup.nodes.TextNode(""))
            else img.replaceWith(org.jsoup.nodes.TextNode("\n[图片]"))
        }
        return copy.text()
            .replace("\u00a0", " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * 发帖 / 回帖表单规格。Discuz 用 formhash + posttime 做防伪，并且每次发帖都要重新取一次，
     * 所以提交前必须先抓表单页。
     */
    fun parseFormSpec(doc: Document, url: String): FormSpec {
        val form = doc.select("form#postform").firstOrNull()
            ?: doc.select("form[name=postform]").firstOrNull()
        if (form == null) {
            val message = doc.select("div.alert_error, div.alert_info, div.fcontent").text().trim()
            return FormSpec(action = "", formhash = "", posttime = "", error = message.ifBlank { "没能打开发帖页" })
        }
        val rawAction = form.attr("action").ifBlank { url }
        // 站点给的是相对地址（post.php?action=reply&...），提交前必须补成绝对地址
        val action = if (rawAction.startsWith("http")) rawAction else HiHttp.absolute(rawAction)
        val formhash = form.select("input[name=formhash]").attr("value")
        val posttime = form.select("input[name=posttime]").attr("value")
        val fid = HiUrls.fidOf(action).ifBlank { HiUrls.fidOf(url) }
        val tid = HiUrls.tidOf(action).ifBlank { HiUrls.tidOf(url) }
        val subject = form.select("input[name=subject]").attr("value")
        val categories = form.select("select[name=typeid] option")
            .map { it.attr("value") to it.text().trim() }
            .filter { it.first.isNotBlank() && it.first != "0" && it.second.isNotBlank() }
        // 站点表单带一堆隐藏域（wysiwyg / notice* / smileyoff ...），漏一个就可能被拒；
        // 全部原样带回，提交时只覆盖 message / subject / typeid。
        val hidden = LinkedHashMap<String, String>()
        for (input in form.select("input[type=hidden]")) {
            val name = input.attr("name")
            if (name.isBlank()) continue
            hidden[name] = input.attr("value")
        }
        hidden["wysiwyg"] = "0"
        // 引用回复时站点把被引用楼层的内容预填在 textarea 里，带出来才能原样显示
        val messageText = form.select("textarea[name=message]").firstOrNull()?.text()?.trim().orEmpty()
        return FormSpec(
            action = action,
            formhash = formhash,
            posttime = posttime,
            fid = fid,
            tid = tid,
            subject = subject,
            categories = categories,
            hidden = hidden,
            messageText = messageText
        ).apply { hint = url }
    }

    /**
     * 报告（举报）表单规格。
     *
     * 站点实况（misc.php?action=report，已实测）：
     *   form#postform → action=misc.php?action=report&reportsubmit=yes&infloat=yes
     *   hidden：formhash / fid / tid / pid / handlekey=report
     *   理由：radio 组 name=type（1=推荐 默认勾选、0=举报）
     *   意见：textarea name=reason，上方 h4「我的意见」
     *   reason 不是理由下拉，message 根本不存在 —— 按旧假设提交必然失败。
     * 兼容分支：个别风格用 select[name=reason] 作理由下拉，一并支持。
     */
    fun parseReportSpec(doc: Document, url: String): com.marvis.hipda.model.ReportSpec {
        val form = doc.select("form#postform").firstOrNull { it.attr("action").contains("report") }
            ?: doc.select("form#reportform").firstOrNull()
            ?: doc.select("form").firstOrNull { it.attr("action").contains("report", true) }
            ?: doc.select("form").firstOrNull { it.select("input[name=formhash]").isNotEmpty() }
        if (form == null) {
            val message = doc.select("div.alert_error, div.alert_info, div.fcontent").text().trim()
            return com.marvis.hipda.model.ReportSpec(
                action = "", formhash = "", error = message.ifBlank { "没能打开报告页" }
            )
        }
        val rawAction = form.attr("action").ifBlank { url }
        val action = if (rawAction.startsWith("http")) rawAction else HiHttp.absolute(rawAction)

        val hidden = LinkedHashMap<String, String>()
        for (input in form.select("input[type=hidden]")) {
            val name = input.attr("name")
            if (name.isBlank()) continue
            hidden[name] = input.attr("value")
        }
        val formhash = form.select("input[name=formhash]").attr("value").ifBlank {
            hidden["formhash"].orEmpty()
        }

        // 理由控件：单选组优先（本站 name=type），没有单选再退回下拉
        var reasonField = "type"
        var defaultReason = ""
        val reasons = mutableListOf<Pair<String, String>>()
        val radioGroups = form.select("input[type=radio]").toList()
            .filter { it.attr("name").isNotBlank() }
            .groupBy { it.attr("name") }
        if (radioGroups.isNotEmpty()) {
            val picked = radioGroups.maxByOrNull { it.value.size }!!
            reasonField = picked.key
            for (r in picked.value) {
                val v = r.attr("value").trim()
                if (v.isBlank()) continue
                val label = r.parent()?.text()?.trim().orEmpty()
                    .ifBlank { r.attr("title").trim() }
                    .ifBlank { v }
                if (r.hasAttr("checked")) defaultReason = v
                if (reasons.none { it.first == v }) reasons.add(v to label)
            }
        } else {
            val select = form.select("select[name=reason]").firstOrNull()
            if (select != null) {
                reasonField = select.attr("name").ifBlank { "reason" }
                for (opt in select.select("option")) {
                    val v = opt.attr("value").trim()
                    val t = opt.text().trim()
                    if (t.isNotBlank()) {
                        if (opt.hasAttr("selected")) defaultReason = v.ifBlank { t }
                        reasons.add(v.ifBlank { t } to t)
                    }
                }
            }
        }

        // 「我的意见」多行框：name=reason；找不到就取第一个 textarea
        val area = form.select("textarea[name=reason]").firstOrNull() ?: form.select("textarea").firstOrNull()
        val messageField = area?.attr("name")?.ifBlank { "reason" } ?: "reason"
        val messageHint = area?.text()?.trim().orEmpty()
        // 标题取 form 内 textarea 上方的 h4（本站写作「我的意见」）
        val messageLabel = form.select("h4").text().trim()
            .ifBlank { "我的意见" }
            .substringBefore(" ")

        return com.marvis.hipda.model.ReportSpec(
            action = action,
            formhash = formhash,
            reasonField = reasonField,
            messageField = messageField,
            reasons = reasons,
            hidden = hidden,
            messageHint = messageHint,
            defaultReason = defaultReason,
            messageLabel = messageLabel
        )
    }

    /**
     * 从页面里取当前账号的昵称与 uid。
     * Discuz 登录后页头是 div#umenu：`<a href="space.php?uid=N">昵称</a>`；
     * 登录成功页还会有一句「欢迎您回来 xxx」。两处都取，取不到返回空串。
     */
    fun parseAccountName(doc: Document): Pair<String, String> {
        val umenu = doc.select("#umenu").firstOrNull()
        val link = umenu?.select("a[href*=space.php]")?.firstOrNull()
            ?: doc.select("a[href*=space.php]").firstOrNull()
        var name = link?.text()?.trim().orEmpty()
        var uid = link?.let { Regex("[?&]uid=(\\d+)").find(it.attr("href"))?.groupValues?.get(1) }.orEmpty()
        if (name.isBlank()) {
            val m = Regex("欢迎您回来[，,]?\\s*([^\\s<，,]{1,20})").find(doc.text())
            if (m != null) name = m.groupValues[1].trim()
        }
        if (uid.isBlank()) {
            uid = doc.select("a[href*=pm.php]").firstOrNull()
                ?.let { Regex("[?&]uid=(\\d+)").find(it.attr("href"))?.groupValues?.get(1) }.orEmpty()
        }
        return name to uid
    }

    /** 提交后判断成败：Discuz 成功页含 *_succeed，失败页含 alert_error 与提示语 */
    /**
     * 判定提交结果。实测站点行为：成功 = 302 回 viewthread.php?...pid=xxx，
     * 拿到的就是帖子页；失败 = 停在 post.php 并带 alert_error。
     * 所以不能对全文做「无权」这类关键字匹配 —— 帖子正文里本来就可能有这些词。
     */
    fun parsePostResult(html: String, message: String = ""): com.marvis.hipda.model.PostResult {
        if (html.contains("post_newthread_succeed") || html.contains("post_reply_succeed")) {
            return com.marvis.hipda.model.PostResult(true, "已提交")
        }
        val doc = Jsoup.parse(html)
        val alertText = doc.select("div.alert_error, div#messagetext, div.alert_info, div.alert, div.posterror")
            .joinToString(" ") { it.text() }.trim()
        if (alertText.isNotBlank()) {
            val failHints = listOf(
                "两次发表间隔", "长度不符合", "没有输入内容", "包含", "需要审核", "两次",
                "无权", "验证码", "抱歉", "不允许", "已关闭", "禁止"
            )
            if (failHints.any { alertText.contains(it) }) {
                return com.marvis.hipda.model.PostResult(false, alertText.take(300))
            }
        }
        val probe = message.trim().take(12)
        if (probe.isNotBlank() && doc.text().contains(probe)) {
            return com.marvis.hipda.model.PostResult(true, "已提交")
        }
        // 停在发帖页（有 textarea 表单）＝没成功；落到帖子页＝成功
        val stillForm = doc.select("form#postform textarea[name=message]").isNotEmpty()
        if (stillForm) {
            val tip = doc.select("div.alert_error, div.alert_info, div#messagetext")
                .joinToString(" ") { it.text() }.trim()
            return com.marvis.hipda.model.PostResult(
                false, tip.ifBlank { "提交未成功，站点没有返回成功提示，请稍后重试" }
            )
        }
        if (doc.select("div#postlist").isNotEmpty() || doc.text().contains("查看新帖")) {
            return com.marvis.hipda.model.PostResult(true, "已提交")
        }
        return com.marvis.hipda.model.PostResult(false, "提交后未确认成功，请到帖子中确认")
    }

    /**
     * 解析帖子正文页。
     *
     * 「楼主」判定不再是「只有 1 楼算楼主」：真实场景里楼主会在自己帖子里回帖，那些楼也要打标。
     * 楼主基准取值优先级：
     *   1. 本页 floor == 1 楼层（首页）的 uid / 作者名；
     *   2. 本页没有 1 楼时（翻到第 2 页及以后），用 [knownOpUid] / [knownOpAuthor] 兜底，
     *      由 ThreadDetailPage 在首次加载成功后记住、翻页时回传；
     *   3. 再没有时用列表页带过来的作者名 / id（[knownOpAuthor] / [knownOpUid] 同一条通路）。
     * 比对时 uid 优先（避免同名不同人误判），uid 缺失时才退回比作者名。
     */
    fun parseThreadDetail(
        doc: Document,
        tid: String,
        knownOpAuthor: String = "",
        knownOpUid: String = ""
    ): PostPage? {
        val (page, last) = parsePageInfo(doc)
        val root = doc.select("div#wrap div#postlist").firstOrNull()
            ?: doc.select("div#postlist").firstOrNull()
            ?: return null

        var title = ""
        doc.select("div#nav").firstOrNull()?.let { nav ->
            val copy = nav.clone()
            copy.select("a").remove()
            title = copy.text().replace("»", "").trim()
        }
        if (title.isBlank()) {
            title = doc.select("div#threadtitle").firstOrNull()?.let {
                val copy = it.clone()
                copy.select("a").remove()
                copy.text().trim()
            }.orEmpty()
        }

        var fid = ""
        doc.select("div#nav a[href*=fid=]").firstOrNull()?.let {
            fid = middle(it.attr("href"), "fid=", "&").ifBlank { middle(it.attr("href"), "fid=", "") }
        }

        val posts = mutableListOf<PostItem>()
        for (postEl in root.children()) {
            val rawId = postEl.attr("id")
            if (!rawId.startsWith("post_")) continue
            val postId = rawId.substring("post_".length)

            val authorA = postEl.select("td.postauthor div.postinfo a").firstOrNull() ?: continue
            val uid = middle(authorA.attr("href"), "uid=", "&").ifBlank {
                middle(authorA.attr("href"), "uid=", "")
            }
            val author = authorA.text().trim()
            val time = postEl.select("td.postcontent div.postinfo div.posterinfo em").text().trim()
            val floorText = postEl.select("td.postcontent div.postinfo strong a em").text().trim()
            val floor = floorText.toIntOrNull() ?: 1

            // 实测：真实正文结构为 <div.postmessage> > table > tr > td.t_msgfont[id=postmessage_x]，
            // 既无 tbody 也不是 div.t_msgfont，旧选择器会整体落空导致楼层正文空白，改为多级兜底。
            val contentEl = postEl.select("td.postcontent td.t_msgfont[id^=postmessage_]").firstOrNull()
                ?: postEl.select("td.postcontent div.postmessage td.t_msgfont").firstOrNull()
                ?: postEl.select("td.postcontent div.defaultpost div.postmessage table tr td.t_msgfont").firstOrNull()
                ?: postEl.select("td.postcontent div.postmessage div.t_msgfont").firstOrNull()

            var notice = ""
            val blocks: List<Block>
            if (contentEl == null) {
                val locked = postEl.select("td.postcontent div.defaultpost div.postmessage div.locked").firstOrNull()
                notice = locked?.text()?.trim() ?: "找不到帖子内容，可能已被屏蔽"
                blocks = emptyList()
            } else {
                blocks = parseBlocks(contentEl)
            }

            // 楼层左侧作者区（td.postauthor）：UID / 帖子数 / 积分 / 注册时间的排版在各模板里
            // 有 dl-dt-dd、ul-li、纯文本行三种写法，这里统一按「标签+数字」取，不依赖具体标签。
            val leftText = postEl.select("td.postauthor").firstOrNull()?.text().orEmpty()
            val postCount = pickByLabel(leftText, "帖子", "(\\d{1,7})")
            val credits = pickByLabel(leftText, "积分", "(\\d{1,9})")
            val regTime = pickByLabel(leftText, "注册", "(\\d{4}-\\d{1,2}-\\d{1,2})")
            val realUid = uid.ifBlank { pickByLabel(leftText, "UID", "(\\d{1,9})") }
            // 个性签名：Discuz 7.2 放在正文之后 div.signatures 里
            val signature = postEl.select("td.postcontent div.signatures, td.postcontent div.signature")
                .firstOrNull()?.text()?.trim().orEmpty()

            posts.add(
                PostItem(
                    postId = postId,
                    floor = floor,
                    author = author,
                    uid = realUid,
                    time = time,
                    blocks = blocks,
                    op = floor == 1,
                    notice = notice,
                    postCount = postCount,
                    credits = credits,
                    regTime = regTime,
                    signature = signature
                )
            )
        }

        // ── 楼主身份：本页 1 楼的 uid / 作者名优先，其次用调用方记住的（翻页 / 列表页带过来）兜底
        val firstPost = posts.firstOrNull { it.floor == 1 }
        val opUid = firstPost?.uid?.trim()?.takeIf { it.isNotBlank() }
            ?: knownOpUid.trim().takeIf { it.isNotBlank() }.orEmpty()
        val opAuthor = firstPost?.author?.trim()?.takeIf { it.isNotBlank() }
            ?: knownOpAuthor.trim().takeIf { it.isNotBlank() }.orEmpty()

        // uid 能对上就认 uid（同名不同人不误伤）；楼主 uid 缺失时才退回比作者名
        val postsWithOp = if (opUid.isBlank() && opAuthor.isBlank()) {
            posts
        } else {
            posts.map { p ->
                val isOp = p.floor == 1 ||
                    (opUid.isNotBlank() && p.uid.isNotBlank() && p.uid == opUid) ||
                    (opUid.isBlank() && opAuthor.isNotBlank() && p.author.trim() == opAuthor)
                if (isOp == p.op) p else p.copy(op = isOp)
            }
        }

        return PostPage(
            tid = tid,
            title = title,
            posts = postsWithOp,
            page = page,
            lastPage = last,
            fid = fid,
            opAuthor = opAuthor,
            opUid = opUid
        )
    }

    private fun parseBlocks(root: Element): List<Block> {
        val out = mutableListOf<Block>()
        val sb = StringBuilder()

        fun flush() {
            val text = sb.toString().trim('\n', ' ', '\u00a0')
            if (text.isNotBlank()) out.add(Block.Text(text))
            sb.setLength(0)
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> {
                    sb.append(node.text().replace('\u00a0', ' '))
                }
                is Element -> {
                    val tag = node.tagName().lowercase()
                    when {
                        tag == "br" -> sb.append('\n')
                        tag == "img" -> {
                            val src = imageSrc(node)
                            if (src != null) {
                                flush()
                                out.add(Block.Image(src))
                            }
                        }
                        node.hasClass("quote") || tag == "blockquote" -> {
                            flush()
                            val text = node.text().trim()
                            if (text.isNotBlank()) out.add(Block.Quote(text))
                        }
                        node.hasClass("attach") || node.hasClass("attachments") -> {
                            // 附件的真实结构（Discuz 7.2）：
                            //   <a class="attach" href="attachment.php?aid=xxxx"> <img src="images/default/attachimg.gif"> <img src="xxx.jpg.thumb.jpg"> <img src="xxx.jpg"> 文件名 (1.15 MB, 下载次数: N) </a>
                            // 旧代码在这一分支直接输出 "[附件] 文本" 且不递归子节点，正文里的图片被整块丢掉，
                            // 用户只能看到一张被拉伸的 attachimg.gif（20x20 图标铺满整宽 → 模糊）。收图优先。
                            val imgs = node.select("img").mapNotNull { imageSrc(it) }.distinct()
                            flush()
                            if (imgs.isNotEmpty()) {
                                for (u in imgs) out.add(Block.Image(u))
                            } else {
                                val href = node.attr("href")
                                val pic = attachmentImageUrl(href) ?: attachmentImageUrl(node.select("a[href]").firstOrNull()?.attr("href") ?: "")
                                if (pic != null) {
                                    out.add(Block.Image(pic))
                                } else if (href.contains("attachment") ||
                                    href.contains(".jpg") || href.contains(".png") || href.contains(".gif")
                                ) {
                                    out.add(Block.Text("[附件] " + node.text().trim().take(120)))
                                }
                            }
                        }
                        tag == "a" -> {
                            // 站点把图片附件包在 <a class="zoom" href="attachment.php?aid=..."><img src="*.thumb.jpg"></a> 里，
                            // 旧实现在 a 上直接取文本、不递归子节点，图片被整块吞掉。含 img 的链接先走子节点收图。
                            if (node.select("img").isNotEmpty()) {
                                for (child in node.childNodes()) walk(child)
                            } else {
                                val href = node.attr("href")
                                val text = node.text().trim()
                                if (href.startsWith("http") && text.startsWith("http")) {
                                    flush()
                                    out.add(Block.Link(text, href))
                                } else if (text.isNotBlank()) {
                                    sb.append(text)
                                }
                            }
                        }
                        else -> {
                            val blockLevel = tag in BLOCK_TAGS
                            if (blockLevel) sb.append('\n')
                            for (child in node.childNodes()) walk(child)
                            if (blockLevel) sb.append('\n')
                        }
                    }
                }
            }
        }

        for (n in root.childNodes()) walk(n)
        flush()
        return out
    }

    private val BLOCK_TAGS = setOf(
        "div", "p", "li", "ul", "ol", "table", "tbody", "tr", "td", "h1", "h2", "h3", "h4", "h5", "hr", "center"
    )

    /**
     * 帖子图片地址解析。旧实现只认 src，会掉两个坑：
     *
     * 1) 附件图片的 src 是 **缩略图** `xxx.jpg.thumb.jpg`（实测 270x600，原图 1200x2670），
     *    贴进 MATCH_PARENT 的 ImageView 后被拉伸到满屏宽 → 全帖图片一律「模糊」。
     *    这里把 `.thumb.jpg / .thumb.png` 还原成原图地址（实测原图可直接匿名取到）。
     * 2) `images/default/attachimg.gif`（20x20 附件图标）、`images/common/none.gif`（1x1）等
     *    站点静态图没被过滤，会被当成正文图片渲染成一张铺满宽度的模糊小图 —— 正是
     *    「所有图片都是同一张模糊图片」的直接来源。凡是站点自身 /images/ 下的装饰图一律丢弃。
     */
    /**
     * 从一段纯文本里按标签取值：如「帖子: 1234」「帖子 1234」「帖子数 1234」都能取到 1234。
     * 标签与数字之间只允许少量非数字字符，避免把相邻字段的数字也吃进来。
     */
    private fun pickByLabel(text: String, label: String, pattern: String): String {
        if (text.isBlank()) return ""
        val m = Regex(label + "[^0-9\\n]{0,6}" + pattern).find(text) ?: return ""
        return m.groupValues[1].trim()
    }

    private fun imageSrc(el: Element): String? {
        // zoomfile / file / data-original 通常就是原图，src 往往是降级缩略图，故按此顺序取第一个可用值
        for (attr in listOf("zoomfile", "file", "data-original", "src")) {
            val url = normalizeImageUrl(el.attr(attr)) ?: continue
            return url
        }
        return null
    }

    /** 把页面里的图片地址规范成「可直接下载的原图绝对地址」，装饰图和缩略图都排除掉 */
    private fun normalizeImageUrl(raw: String): String? {
        var src = raw.trim()
        if (src.isBlank()) return null
        if (src.startsWith("data:")) return null
        val lower = src.lowercase()

        // 站点静态装饰图 / 表情 / 图标：不是正文内容
        if (lower.contains("images/smilies") || lower.contains("/smilies/")) return null
        if (lower.contains("images/common/")) return null
        if (lower.contains("images/default/")) return null
        if (lower.contains("images/attachicons/")) return null
        if (lower.contains("static/image")) return null
        val tail = lower.substringAfterLast("/")
        if (tail == "attachimg.gif" || tail == "none.gif" || tail == "back.gif" ||
            tail == "blank.gif" || tail == "loading.gif" || tail == "attachimg.png"
        ) return null

        // 缩略图 → 原图：261015...jpg.thumb.jpg → 261015...jpg
        val thumb = src.indexOf(".thumb.", ignoreCase = true)
        if (thumb > 0) {
            val query = src.indexOf('?')
            val end = if (query > thumb) query else src.length
            src = src.substring(0, thumb) + src.substring(end)
        }
        // 少数模板给的是 xxx.jpg?thumb=1 或 thumbnail.php?aid=xxx&size=thumb 之类，去掉缩略参数
        if (src.contains("&size=thumb") || src.contains("?thumb=")) {
            src = src.replace("&size=thumb", "").replace("?thumb=1", "").replace("?thumb=yes", "")
        }

        val plain = src.lowercase().substringBefore("?")
        val isImageExt = plain.endsWith(".jpg") || plain.endsWith(".jpeg") || plain.endsWith(".png") ||
            plain.endsWith(".gif") || plain.endsWith(".webp") || plain.endsWith(".bmp")
        val isAttachment = lower.contains("attachment.php") || lower.contains("attachments/")
        if (!isImageExt && !isAttachment) return null
        return HiHttp.absolute(src)
    }

    /** 附件下载链接若能确定是图片，就返回它的图片地址，交给正文当图渲染 */
    private fun attachmentImageUrl(href: String): String? {
        if (href.isBlank()) return null
        val lower = href.lowercase()
        if (!lower.contains("attachment.php") && !lower.contains("attachments/")) return null
        val name = href.substringAfterLast("/").substringBefore("?").lowercase()
        if (!name.contains("attachment.php")) {
            val pic = name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp")
            if (!pic) return null
        }
        return HiHttp.absolute(href)
    }
}
