package com.marvis.hipda.net

import com.marvis.hipda.data.Prefs
import com.marvis.hipda.model.LoginResult
import com.marvis.hipda.model.PostPage
import com.marvis.hipda.model.ThreadPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest

/** 登录态快照：由页面 HTML 直接判定，不依赖本地标记 */
data class LoginState(val loggedIn: Boolean, val uid: String, val name: String)

/** 论坛业务接口 */
object HiApi {

    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun loadDocument(url: String, referer: String? = HiUrls.baseUrl): Document {
        val result = HiHttp.get(url, referer)
        return Jsoup.parse(result.body, result.url)
    }

    /** 页面里是否夹带 Cloudflare 挑战脚本 */
    fun isChallengeHtml(html: String): Boolean =
        html.contains("cf-chl-widget") ||
            html.contains("_cf_chl_opt") ||
            html.contains("challenge-platform") ||
            html.contains("Just a moment", true) ||
            html.contains("Attention Required", true)

    fun fetchThreadList(fid: String, page: Int): ThreadPage {
        val result = HiHttp.get(HiUrls.threadList(fid, page), HiUrls.baseUrl)
        return threadListFromHtml(result.body, result.url)
    }

    /** 解析板块帖子列表：OkHttp 与 WebView 两条通道共用同一套解析 */
    fun threadListFromHtml(html: String, url: String): ThreadPage {
        val doc = Jsoup.parse(html, url)
        val uid = HiParser.parseUid(doc)
        if (uid.isNotBlank() && uid != Prefs.uid) Prefs.uid = uid
        if (HiParser.isNoAuth(doc)) {
            throw NotLoggedInException("该版块需要登录后才能浏览")
        }
        return HiParser.parseThreadList(doc)
    }

    fun fetchThreadDetail(tid: String, page: Int): PostPage {
        val result = HiHttp.get(HiUrls.threadDetail(tid, page), HiUrls.baseUrl)
        return threadDetailFromHtml(result.body, result.url, tid)
    }

    fun threadDetailFromHtml(html: String, url: String, tid: String): PostPage {
        val doc = Jsoup.parse(html, url)
        val uid = HiParser.parseUid(doc)
        if (uid.isNotBlank() && uid != Prefs.uid) Prefs.uid = uid
        return HiParser.parseThreadDetail(doc, tid)
            ?: PostPage(tid, "", emptyList(), 1, 1, "")
    }

    /**
     * 从任意页面 HTML 判定登录态 —— 这是"登录后仍显示未登录"的核心修复点。
     * 之前的实现只认 OkHttp 通道的结果，手机网络下 OkHttp 常被 Cloudflare 挡住，
     * 于是浏览器里明明登录成功，软件却报未登录。现在 WebView 拿到的同一份 HTML
     * 也能判定，两条通道任一成功即视为已登录。
     */
    fun loginStateFromHtml(html: String, url: String): LoginState {
        if (isChallengeHtml(html)) return LoginState(false, "", "")
        val doc = Jsoup.parse(html, url)
        if (HiParser.isNoAuth(doc)) return LoginState(false, "", "")
        // Discuz 桌面模板：已登录才有 #umenu（cite a 为用户名）与「退出」链接
        val name = doc.select("#umenu cite a").text().trim()
            .ifBlank { doc.select(".postinfo a[href*=uid]").firstOrNull()?.text()?.trim().orEmpty() }
        val uid = HiParser.parseUid(doc)
        val hasLogout = doc.select("a[href*=action=logout]").isNotEmpty()
        val logged = name.isNotBlank() || uid.isNotBlank() || hasLogout
        if (logged) {
            if (uid.isNotBlank()) Prefs.uid = uid
            if (name.isNotBlank()) Prefs.username = name
            Prefs.webSession = true
        }
        return LoginState(logged, uid, name)
    }

    /** OkHttp 通道快速探测登录态；网络异常 / 被挑战时抛异常，由调用方降级到 WebView */
    fun probeLogin(): Boolean {
        val result = HiHttp.get(HiUrls.myThreads, HiUrls.baseUrl)
        if (isChallengeHtml(result.body)) throw CfChallengeException("Cloudflare 挑战")
        return loginStateFromHtml(result.body, result.url).loggedIn
    }

    /**
     * 当前会话是否仍是登录态。
     * 用「我的主题」这类必须登录的页面判定：站点首页常年被 Cloudflare 拦在 403，
     * 而且游客访问时不带 umenu，只能靠 NOAUTH 提示页反推。
     */
    fun checkLogin(): Boolean {
        val doc = loadDocument(HiUrls.myThreads)
        if (HiParser.isNoAuth(doc)) {
            Prefs.uid = ""
            return false
        }
        val uid = HiParser.parseUid(doc)
        val name = doc.select("#umenu cite a").firstOrNull()?.text()?.trim().orEmpty()
        if (uid.isBlank() && !HiHttp.cookies.hasCookie("cdb_auth")) return false
        if (uid.isNotBlank()) Prefs.uid = uid
        if (name.isNotBlank() && Prefs.username.isBlank()) Prefs.username = name
        return true
    }

    /**
     * 内嵌浏览器返回后调用：把 WebView 里的 Cookie 同步进 OkHttp，再验证一次登录态。
     * 这里的"完成"必须真正校验，否则会出现"浏览器里登了、软件里还是未登录"。
     */
    fun adoptWebSession(currentUrl: String? = null): Boolean {
        HiHttp.cookies.syncFromWebView(currentUrl ?: HiUrls.baseUrl)
        HiHttp.cookies.flushCookieManager()
        val doc = runCatching { loadDocument(HiUrls.myThreads) }.getOrNull() ?: return false
        if (HiParser.isNoAuth(doc)) return false
        val uid = HiParser.parseUid(doc)
        val name = doc.select("#umenu cite a").firstOrNull()?.text()?.trim().orEmpty()
        if (uid.isBlank() && !HiHttp.cookies.hasCookie("cdb_auth")) return false
        if (uid.isNotBlank()) Prefs.uid = uid
        if (name.isNotBlank()) Prefs.username = name
        Prefs.webSession = true
        return true
    }

    /**
     * Discuz 登录：先取 formhash，再提交 MD5 后的密码。
     * 注意站点被 Cloudflare 拦截时，logging.php 会直接返回挑战页，取不到 formhash，
     * 此时明确提示改走内嵌浏览器，不再含糊报"未获取到登录参数"。
     */
    fun login(username: String, rawPassword: String): LoginResult {
        val loginPage = try {
            HiHttp.get(HiUrls.loginFormHash, HiUrls.baseUrl)
        } catch (e: CfChallengeException) {
            return LoginResult(false, "站点要求 Cloudflare 验证，请改用「用内嵌浏览器登录」")
        }
        if (isChallengeHtml(loginPage.body)) {
            return LoginResult(false, "登录页被 Cloudflare 拦截，请点下方「用内嵌浏览器登录」")
        }
        val doc = Jsoup.parse(loginPage.body, loginPage.url)
        val formhash = doc.select("input[name=formhash]").attr("value").ifBlank {
            doc.select("input[name=m_formhash]").attr("value")
        }
        if (formhash.isBlank()) {
            return LoginResult(false, "登录页结构异常，未取到 formhash，请改用「用内嵌浏览器登录」")
        }
        val needSecCode = doc.select("input[name=seccodeverify]").size > 0 ||
            doc.select("img[src*=seccode]").size > 0
        if (needSecCode) {
            return LoginResult(false, "该站登录需要图形验证码，请改用「用内嵌浏览器登录」")
        }
        val sid = doc.select("input[name=sid]").attr("value")

        val fields = mutableMapOf(
            "formhash" to formhash,
            "referer" to HiUrls.baseUrl,
            "loginfield" to "username",
            "username" to username,
            "password" to md5(rawPassword),
            "questionid" to "0",
            "answer" to "",
            "cookietime" to "2592000"
        )
        if (sid.isNotBlank()) fields["sid"] = sid
        val resp = HiHttp.post(HiUrls.loginSubmit, fields, HiUrls.loginFormHash)
        val body = resp.body
        val ok = body.contains("login_success") ||
            body.contains("欢迎您回来") ||
            body.contains("现在将转入登录前页面")
        if (!ok) {
            val msg = when {
                body.contains("密码错误") || body.contains("用户名或密码") -> "用户名或密码不正确"
                body.contains("验证码") || body.contains("seccode") -> "需要验证码，请改用内嵌浏览器登录"
                body.contains("尝试次数") -> "登录失败次数过多，请稍后再试或改用内嵌浏览器"
                isChallengeHtml(body) -> "登录请求被 Cloudflare 拦截，请改用内嵌浏览器登录"
                else -> "登录未成功，建议改用「用内嵌浏览器登录」"
            }
            return LoginResult(false, msg)
        }
        Prefs.username = username
        Prefs.password = md5(rawPassword)
        // 让 WebView 也带上刚拿到的会话，避免两边状态不一致
        HiHttp.cookies.pushToWebView(HiUrls.baseUrl)
        val uid = runCatching { HiParser.parseUid(loadDocument(HiUrls.myThreads)) }.getOrDefault("")
        if (uid.isNotBlank()) Prefs.uid = uid
        Prefs.webSession = false
        return LoginResult(true, "登录成功")
    }


    // ────────────────────────────────────────────────────────────────
    // 登录态判定：本地 Cookie 优先，联网复核兜底
    // 「内嵌浏览器里登录成功、回到软件还显示未登录」的根因是只认 OkHttp 联网结果，
    // 而手机网络下 OkHttp 常被 Cloudflare 挡回挑战页。现在改为先看 Cookie。
    // ────────────────────────────────────────────────────────────────

    /** Discuz 的 cdb_uid Cookie 值形如 "12345" 或 "12345%09username" */
    fun uidFromCookie(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val head = raw.split("%09", "\t").firstOrNull().orEmpty()
        return Regex("(\\d{1,9})").find(head)?.groupValues?.get(1).orEmpty()
    }

    fun nameFromCookie(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val parts = raw.split("%09", "\t")
        return if (parts.size > 1) parts[1].trim() else ""
    }

    /** 纯本地判定：只看 Cookie，不联网，不会因为 Cloudflare 而误判 */
    fun localLoginState(url: String? = null): LoginState {
        val target = url ?: HiUrls.baseUrl
        HiHttp.cookies.syncFromWebView(target)
        HiHttp.cookies.flushCookieManager()

        val auth = HiHttp.cookies.cookieValue("cdb_auth")
        val uidCookie = HiHttp.cookies.cookieValue("cdb_uid")
        val uid = uidFromCookie(uidCookie).ifBlank { Prefs.uid }
        val name = nameFromCookie(uidCookie).ifBlank { Prefs.username }

        if (auth.isNullOrBlank()) return LoginState(false, "", "")
        if (uid.isNotBlank()) Prefs.uid = uid
        if (name.isNotBlank()) Prefs.username = name
        Prefs.webSession = true
        return LoginState(true, uid, name)
    }

    /**
     * 内嵌浏览器「完成」后的综合判定：
     * 1) 本地 Cookie 判定（离线也能成立）；
     * 2) Still 不成立就联网复核（能过 Cloudflare 时）；
     * 3) 仍不成立时由调用方再走 WebView 抓一次页面复核。
     */
    fun resolveLoginState(url: String? = null): LoginState {
        val local = localLoginState(url)
        if (local.loggedIn) return local
        return runCatching {
            val r = HiHttp.get(HiUrls.myThreads, HiUrls.baseUrl)
            loginStateFromHtml(r.body, r.url)
        }.getOrDefault(LoginState(false, "", ""))
    }

    /** 直接把一段页面 HTML 当作登录态依据（WebView 通道复核定用） */
    fun applyLoginHtml(html: String, url: String): LoginState {
        return loginStateFromHtml(html, url)
    }

    /** 站点首页 → 板块清单 */
    fun forumIndexFromHtml(html: String, url: String): List<Pair<String, List<com.marvis.hipda.model.Forum>>> {
        return HiParser.parseForumIndex(Jsoup.parse(html, url))
    }

    /**
     * 联网取当前账号昵称并落库。
     * 内嵌浏览器登录时只能拿到 Cookie，cdb_uid 里不一定带昵称（站点会做编码），
     * 所以菜单页打开时用 my.php 复核一次，把真实账号名写进 Prefs。
     */
    fun refreshAccountName(): String {
        if (!Prefs.isLoggedIn() && !hasSessionCookie()) return Prefs.username
        val html = runCatching {
            val r = HiHttp.get(HiUrls.myThreads, HiUrls.baseUrl)
            r.body
        }.getOrNull() ?: return Prefs.username
        val (name, uid) = HiParser.parseAccountName(Jsoup.parse(html))
        if (uid.isNotBlank()) Prefs.uid = uid
        if (name.isNotBlank()) Prefs.username = name
        return Prefs.username
    }

    fun accountNameFromHtml(html: String, url: String): String {
        val (name, uid) = HiParser.parseAccountName(Jsoup.parse(html, url))
        if (uid.isNotBlank()) Prefs.uid = uid
        if (name.isNotBlank()) Prefs.username = name
        return Prefs.username
    }

    /** 用已保存的凭据尝试静默恢复登录态（内嵌浏览器登录没有保存密码，只能靠 Cookie） */
    fun ensureLogin(): Boolean {
        if (!Prefs.isLoggedIn()) return false
        // 注意：Prefs.password 里存的是 MD5，不能再喂给 login()（会二次加密导致必然失败）。
        // 登录态只靠 Cookie 判断，Cookie 失效时引导用户重新登录。
        if (hasSessionCookie()) return true
        return runCatching { checkLogin() }.getOrDefault(false)
    }

    /** 本地是否持有论坛会话 Cookie */
    fun hasSessionCookie(): Boolean = !HiHttp.cookies.cookieValue("cdb_auth").isNullOrBlank()

    // ────────────────────────────────────────────────────────────────
    // 「我的」系列 / 搜索 / 短消息：都是站点网页表格，解析后交给单列界面渲染
    // ────────────────────────────────────────────────────────────────

    fun feed(url: String, tidFromPid: Boolean = false): com.marvis.hipda.model.FeedPage {
        val r = HiHttp.get(url, HiUrls.baseUrl)
        return feedFromHtml(r.body, r.url, tidFromPid)
    }

    fun feedFromHtml(html: String, url: String, tidFromPid: Boolean = false) =
        HiParser.parseFeedList(Jsoup.parse(html, url), tidFromPid)

    fun pmList(page: Int): com.marvis.hipda.model.PmPage {
        val r = HiHttp.get(HiUrls.sms(page), HiUrls.baseUrl)
        return pmFromHtml(r.body, r.url)
    }

    fun pmFromHtml(html: String, url: String) =
        HiParser.parsePmList(Jsoup.parse(html, url))

    fun pmChat(uid: String): com.marvis.hipda.model.PmChat {
        val r = HiHttp.get(HiUrls.pmChat(uid), HiUrls.baseUrl)
        return pmChatFromHtml(r.body, r.url, uid)
    }

    fun pmChatFromHtml(html: String, url: String, uid: String) =
        HiParser.parsePmChat(Jsoup.parse(html, url), uid)

    /** 抓发帖/回帖表单页，取出 formhash 与 posttime */
    fun formSpec(url: String): com.marvis.hipda.model.FormSpec {
        val r = HiHttp.get(url, HiUrls.baseUrl)
        return HiParser.parseFormSpec(Jsoup.parse(r.body, r.url), r.url)
    }

    fun formSpecFromHtml(html: String, url: String) =
        HiParser.parseFormSpec(Jsoup.parse(html, url), url)

    /**
     * 提交发帖 / 回帖。
     * 站点要求：formhash、posttime、message 必填；subject 仅发新帖需要；typeid 可空。
     */
    fun submitPost(
        spec: com.marvis.hipda.model.FormSpec,
        subject: String,
        message: String,
        typeId: String = ""
    ): com.marvis.hipda.model.PostResult {
        if (spec.formhash.isBlank()) return com.marvis.hipda.model.PostResult(false, "没取到 formhash，请重试")
        if (message.isBlank()) return com.marvis.hipda.model.PostResult(false, "内容不能为空")
        // 先原样带回站点表单的全部隐藏域（formhash/posttime/wysiwyg/notice* 等），再覆盖正文
        val fields = LinkedHashMap<String, String>(spec.hidden)
        fields["formhash"] = spec.formhash
        if (spec.posttime.isNotBlank()) fields["posttime"] = spec.posttime
        fields["wysiwyg"] = "0"
        fields["message"] = message
        fields["usesig"] = "1"
        if (spec.isNewThread) {
            fields["subject"] = subject.ifBlank { "无标题" }
            if (typeId.isNotBlank()) fields["typeid"] = typeId
            fields["topicsubmit"] = "发新话题"
        } else {
            fields["replysubmit"] = "回复帖子"
        }
        val action = spec.action.ifBlank {
            if (spec.isNewThread) HiUrls.newThreadSubmit(spec.fid) else HiUrls.replySubmit(spec.fid, spec.tid)
        }
        return try {
            val resp = HiHttp.post(action, fields, HiUrls.baseUrl)
            // 刚发的内容必须让缓存失效，否则回到列表/帖子页还会看到旧页面
            HiHttp.clearPageCache()
            HiParser.parsePostResult(resp.body, message)
        } catch (e: Exception) {
            com.marvis.hipda.model.PostResult(false, "提交失败：" + (e.message ?: "网络异常"))
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 短消息回复 + 帖子预取
    // ────────────────────────────────────────────────────────────────

    /**
     * 回复短消息。
     * 站点链路（已实测会话页 form#pmform）：POST 到
     * pm.php?action=send&uid=<对方uid>&pmsubmit=yes&infloat=yes，
     * 必带 formhash / handlekey=pmreply / lastdaterange / message，收件人由 uid 承载。
     * infloat=yes 时服务端返回 JS 片段，成功标志是 pm_send_succeed 与 appendpmnode。
     */
    fun sendPm(
        uid: String,
        formhash: String,
        message: String,
        hidden: Map<String, String> = emptyMap()
    ): com.marvis.hipda.model.PostResult {
        if (uid.isBlank()) return com.marvis.hipda.model.PostResult(false, "收件人 uid 缺失")
        if (message.isBlank()) return com.marvis.hipda.model.PostResult(false, "内容不能为空")
        if (formhash.isBlank()) return com.marvis.hipda.model.PostResult(false, "没取到 formhash，请重试")
        // 原表单隐藏域先铺底，再盖上必须项 —— 站点要求的字段一个都不落
        val fields = LinkedHashMap<String, String>(hidden)
        fields["formhash"] = formhash
        fields["handlekey"] = "pmreply"
        if (!fields.containsKey("lastdaterange")) fields["lastdaterange"] = ""
        fields["message"] = message
        return try {
            // Referer 用会话页本身：与浏览器里点「发送」时发出的请求一致
            val resp = HiHttp.post(HiUrls.pmReplySubmit(uid), fields, HiUrls.pmChat(uid))
            val body = resp.body
            val failed = Regex("id=\"ajaxerror\"[^>]*>(.*?)</", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.trim()
            when {
                !failed.isNullOrBlank() -> com.marvis.hipda.model.PostResult(false, failed)
                body.contains("pm_send_succeed") || body.contains("appendpmnode") ||
                    body.contains("succeedhandle_pm") -> {
                    HiHttp.clearPageCache()
                    com.marvis.hipda.model.PostResult(true, "已发送")
                }
                body.contains("两次发送短消息太快") || body.contains("太快") ->
                    com.marvis.hipda.model.PostResult(false, "两次发送间隔太短，请稍后再试。")
                else -> com.marvis.hipda.model.PostResult(false, "站点没有返回成功标志，请稍后重试或改用浏览器打开。")
            }
        } catch (e: CfChallengeException) {
            com.marvis.hipda.model.PostResult(false, "站点要求 Cloudflare 验证，请先完成验证。")
        } catch (e: Exception) {
            com.marvis.hipda.model.PostResult(false, "发送失败：" + (e.message ?: "网络异常"))
        }
    }

    /**
     * 预取帖子第 1 页 HTML 到页面短缓存：列表上停留的这几秒把前几个帖子先拉下来，
     * 用户点进去时 loadHtml 直接命中缓存，省掉一次完整往返。
     * 只下载不解析，失败（含 Cloudflare 挑战）静默放弃，不影响当前页面。
     */
    fun prefetchHtml(url: String) {
        if (HiHttp.cachedPage(url) != null) return
        if (HiHttp.justChallenged() && !HiHttp.hasCfClearance()) return
        try {
            // quiet：预取撞到挑战就静默放弃，不影响主流程的 OkHttp / WebView 选择
            val r = HiHttp.get(url, HiUrls.baseUrl, quiet = true)
            if (r.code == 200 && r.body.length > 600 && !isChallengeHtml(r.body)) {
                HiHttp.putPage(url, r.body)
            }
        } catch (ignored: Throwable) {
        }
    }
}
