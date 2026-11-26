package com.marvis.hipda.net

import com.marvis.hipda.data.Prefs

/**
 * 论坛接口地址，结构与参考项目 net.jejer.hipda.utils.HiUtils 保持一致。
 */
object HiUrls {

    val baseUrl: String get() = Prefs.server + "/forum/"
    val imageBaseUrl: String get() = Prefs.imageHost + "/forum/"
    val cookieDomain: String get() = hostOf(Prefs.server)

    val loginFormHash: String get() = baseUrl + "logging.php?action=login"
    val loginSubmit: String get() = baseUrl + "logging.php?action=login&loginsubmit=yes&inajax=1"

    fun threadList(fid: String, page: Int): String =
        baseUrl + "forumdisplay.php?fid=" + fid + "&page=" + page

    fun threadDetail(tid: String, page: Int): String =
        baseUrl + "viewthread.php?tid=" + tid + "&page=" + page

    /**
     * 定位到某一楼。
     * viewthread.php 带 pid 时站点会直接返回含该楼的那一页（并按 pid 高亮该楼），
     * 这样不必自己算页码，翻到哪儿都能落到正确位置。
     */
    fun findPost(pid: String, tid: String): String {
        val tail = if (tid.isBlank()) "" else "&tid=" + tid
        return baseUrl + "viewthread.php?pid=" + pid + tail
    }

    fun lastPage(tid: String): String =
        baseUrl + "redirect.php?goto=lastpost&from=fastpost&tid=" + tid

    val myThreadsUrl: String get() = baseUrl + "my.php?item=threads"
    val myRepliesUrl: String get() = baseUrl + "my.php?item=posts"
    val myFavoritesUrl: String get() = baseUrl + "my.php?item=favorites&type=thread"
    val myThreads: String get() = myThreadsUrl
    val myPosts: String get() = myThreadsUrl
    val myReplies: String get() = myRepliesUrl
    val myFavorites: String get() = myFavoritesUrl
    val sms: String get() = baseUrl + "pm.php?filter=privatepm"
    val notices: String get() = baseUrl + "notice.php"

    /** 「我的」系列列表的分页地址（Discuz 统一 page=N） */
    fun page(url: String, page: Int): String {
        if (page <= 1) return url
        return url + (if (url.contains("?")) "&" else "?") + "page=" + page
    }

    fun myThreads(page: Int): String = page(myThreads, page)
    fun myReplies(page: Int): String = page(myReplies, page)
    fun myFavorites(page: Int): String = page(myFavorites, page)

    /**
     * 收藏本帖：本站是 Discuz! 7.2，「我的」模块是 my.php（没有 X2/X3 的 home.php spacecp，
     * 请求 home.php 直接 404）。收藏动作挂在 my.php?item=favorites 上：
     * action=add 收藏、action=delete 取消，tid 指帖子，formhash 防跨站。
     */
    fun favoriteAdd(tid: String, formhash: String): String =
        baseUrl + "my.php?item=favorites&action=add&tid=" + tid + "&formhash=" + formhash

    fun favoriteDelete(tid: String, formhash: String): String =
        baseUrl + "my.php?item=favorites&action=delete&tid=" + tid + "&formhash=" + formhash
    fun sms(page: Int): String = page(sms, page)

    /**
     * 短消息对话页。
     * 必须带 daterange=5（查看全部时间范围），否则站点只返回「没有新短消息」的空会话。
     */
    fun pmChat(uid: String): String =
        baseUrl + "pm.php?uid=" + uid + "&filter=privatepm&daterange=5"

    /**
     * 回复短消息的提交地址。
     * 站点会话页 form#pmform 的 action 就是它：收件人由 uid 承载，短消息没有主题字段；
     * infloat=yes 会让服务端返回 JS 片段（appendpmnode / pm_send_succeed）而不是跳转页。
     */
    fun pmReplySubmit(uid: String): String =
        baseUrl + "pm.php?action=send&uid=" + uid + "&pmsubmit=yes&infloat=yes"

    /** 发新帖表单 */
    fun newThreadForm(fid: String): String = baseUrl + "post.php?action=newthread&fid=" + fid

    /** 回帖表单（fid 未知时只带 tid，站点同样能返回表单） */
    fun replyForm(fid: String, tid: String): String {
        val tail = if (fid.isBlank()) "" else "&fid=" + fid
        return baseUrl + "post.php?action=reply&tid=" + tid + tail
    }

    /** 回帖提交 */
    fun replySubmit(fid: String, tid: String): String =
        baseUrl + "post.php?action=reply&fid=" + fid + "&tid=" + tid + "&replysubmit=yes"

    /**
     * 引用回复表单：repquote=yes + pid 让站点把被引用楼层的内容预填进 textarea[name=message]，
     * 与论坛网页上点「引用」进入的是同一张表单（隐藏域也随之带上，提交不会丢引用关系）。
     */
    fun quoteReplyForm(fid: String, tid: String, pid: String): String {
        val tail = if (fid.isBlank()) "" else "&fid=" + fid
        return baseUrl + "post.php?action=reply&tid=" + tid + tail + "&repquote=yes&pid=" + pid
    }

    /** 举报（报告）页：站点自带的报告表单，必需参数是 tid + pid */
    fun report(fid: String, tid: String, pid: String): String {
        val tail = if (fid.isBlank()) "" else "&fid=" + fid
        return baseUrl + "misc.php?action=report" + tail + "&tid=" + tid + "&pid=" + pid
    }

    /** 发新帖提交 */
    fun newThreadSubmit(fid: String): String =
        baseUrl + "post.php?action=newthread&fid=" + fid + "&topicsubmit=yes"

    fun search(keyword: String): String =
        baseUrl + "search.php?srchtype=title&srchtxt=" + java.net.URLEncoder.encode(keyword, "GBK") +
            "&searchsubmit=true&st=on&srchuname=&srchfilter=all&srchfrom=0&before=&orderby=lastpost&ascdesc=desc"

    fun userInfo(uid: String): String = baseUrl + "space.php?uid=" + uid

    fun avatarUrl(uid: String): String {
        if (uid.isBlank() || !uid.all { it.isDigit() }) return ""
        val full = uid.padStart(9, '0')
        return imageBaseUrl + "uc_server/data/avatar/" +
            full.substring(0, 3) + "/" + full.substring(3, 5) + "/" +
            full.substring(5, 7) + "/" + full.substring(7, 9) + "_avatar_middle.jpg"
    }

    /** 首页地址，Cloudflare 验证时加载 */
    val home: String get() = baseUrl + "index.php"

    /**
     * 板块清单来源：站点首页。
     * 加 styleid=1 是必要的 —— 裸 index.php 常被 Cloudflare 的缓存条目直接返回 403，
     * 带参数才会进入站点动态页面，板块结构才能取到。
     */
    val forumIndex: String get() = baseUrl + "index.php?styleid=1"

    /** 备用入口（forumIndex 失败时尝试） */
    val forumIndexPlain: String get() = baseUrl + "index.php"

    /**
     * 板块清单的第二来源：任意一个板块页。
     * Discuz 的「跳转到」下拉菜单 #fjump_menu 会渲染出当前账号可见的全部板块，
     * 而 forumdisplay.php 不参与 Cloudflare 的首页缓存规则（首页 index.php 常被直接 403），
     * 所以这条通道比首页更稳。
     */
    val forumJumpProbe: String get() = baseUrl + "forumdisplay.php?fid=7"

    fun fidOf(href: String?): String {
        if (href == null) return ""
        val m = Regex("[?&]fid=(\\d+)").find(href) ?: return ""
        return m.groupValues[1]
    }

    fun tidOf(href: String?): String {
        if (href == null) return ""
        Regex("[?&]tid=(\\d+)").find(href)?.let { return it.groupValues[1] }
        Regex("thread-(\\d+)-").find(href)?.let { return it.groupValues[1] }
        return ""
    }

    fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: "4d4y.com"
    } catch (e: Exception) {
        "4d4y.com"
    }
}
