package com.marvis.hipda.net

import android.content.Context
import android.util.LruCache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** Cloudflare 挑战：需要用户去 WebView 过验证 */
class CfChallengeException(val failedUrl: String) : IOException("需要 Cloudflare 验证")

/** 该功能/版块要求登录后访问（游客访问返回「还未登录，无权访问该版块」） */
class NotLoggedInException(message: String = "该版块需要登录后访问") : IOException(message)

class HttpResult(val code: Int, val body: String, val url: String)

object HiHttp {

    /**
     * 关键：必须使用「桌面浏览器 UA」。
     * 4D4Y（Discuz! 7.2）检测到移动 UA 会输出 WAP 模板（templates/wap），
     * 该模板没有 tbody#normalthread / th.subject / input[name=formhash]，
     * 会导致"板块里看不到帖子"和"未获取到登录参数"。
     * WebView 必须使用同一个 UA，cf_clearance 才能与后台请求共用。
     */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    lateinit var cookies: PersistentCookieStore
        private set

    private var client: OkHttpClient? = null

    fun init(context: Context) {
        cookies = PersistentCookieStore(context)
        // 提速相关的三件事：
        // 1) 连接池显式放大并保活 5 分钟：连续点开帖子时省掉 TCP + TLS 握手；
        // 2) retryOnConnectionFailure 打开，偶发的连接复用失败直接换连接重试，不再报错给用户；
        // 3) 连接超时从 20s 收到 12s：站点被 Cloudflare 拦或网络不通时能更快落到 WebView 兜底。
        client = OkHttpClient.Builder()
            .cookieJar(cookies)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private fun requireClient(): OkHttpClient = client ?: throw IllegalStateException("HiHttp 未初始化")

    /**
     * @param quiet 预取等「后台顺带做的事」用 true：万一撞上 Cloudflare 挑战只当没拿到，
     *              不写挑战时间戳，免得把用户正在等的页面也一起推到慢速 WebView 通道。
     */
    fun get(url: String, referer: String? = null, quiet: Boolean = false): HttpResult {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (referer != null) builder.header("Referer", referer)
        return execute(builder.build(), url, quiet)
    }

    fun post(url: String, form: Map<String, String>, referer: String? = null): HttpResult {
        val body = formBody(form)
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .post(body)
        if (referer != null) builder.header("Referer", referer)
        return execute(builder.build(), url)
    }

    /**
     * 表单编码必须按「站点字符集」做 percent-encoding。
     *
     * 乱码根因：站点（Discuz! 7.2）只有 GBK，而 OkHttp 的 FormBody 固定用 UTF-8 编码，
     * 提交的中文被服务器当成 GBK 字节解读，于是「自己打的字」在服务器上被存成了
     * 「å›žå¤」这一类乱码串，回读自然全是乱码。实测同一关键词按 UTF-8 提交搜索结果为 0 条、
     * 按 GBK 提交正常返回 5 条，可直接确认服务器只认 GBK 字节。
     * 因此这里手工编码：先取 GB18030 字节，再逐字节 percent-encode，
     * Content-Type 也如实声明 charset=GBK。
     */
    private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=GBK".toMediaType()
    private val HEX = "0123456789ABCDEF".toCharArray()

    private fun formEncode(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (b in value.toByteArray(Charset.forName("GB18030"))) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                    ch == '-' || ch == '_' || ch == '.' || ch == '*' -> sb.append(ch)
                ch == ' ' -> sb.append('+')
                else -> sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    fun formBody(form: Map<String, String>): RequestBody {
        val sb = StringBuilder()
        for ((k, v) in form) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(formEncode(k)).append('=').append(formEncode(v))
        }
        return sb.toString().toRequestBody(FORM_MEDIA)
    }

    // ────────────────────────────────────────────────────────────────
    // 页面短缓存 + 挑战标记：把「打开帖子慢」的两个浪费点消掉
    // 1) 从列表进帖、返回后再进、翻回上一页，都会重复取同一份页面；
    // 2) 站点开着 Cloudflare，之前只要没有 cf_clearance 就强制走隐藏 WebView，
    //    哪怕 OkHttp 其实能直接取到页面，白白多花几秒。
    // ────────────────────────────────────────────────────────────────

    private val pageCache = LruCache<String, Pair<Long, String>>(48)

    /**
     * 页面短缓存有效期。60 秒时「返回列表再点同一个帖子」经常刚好过期、又得重跑一次网络，
     * 延长到 150 秒：正常浏览节奏下同一个帖子只取一次；需要最新内容时（下滑刷新、发回帖后）
     * 走 forceRefresh / clearPageCache 绕开缓存，不会看到旧页。
     */
    const val PAGE_TTL_MS = 150_000L

    /** 取缓存页面，超过 ttl 视为失效 */
    fun cachedPage(url: String, ttlMs: Long = PAGE_TTL_MS): String? {
        val hit = pageCache.get(url) ?: return null
        if (System.currentTimeMillis() - hit.first > ttlMs) {
            pageCache.remove(url)
            return null
        }
        return hit.second
    }

    fun putPage(url: String, html: String) {
        if (html.length > 600) pageCache.put(url, System.currentTimeMillis() to html)
    }

    fun clearPageCache() = pageCache.evictAll()

    /** 最近一次被 Cloudflare 挑战的时间戳：短时间内的后续请求直接走 WebView，不再空跑一次 OkHttp */
    @Volatile
    var lastChallengeAt: Long = 0L
        private set

    internal fun markChallenged() {
        lastChallengeAt = System.currentTimeMillis()
    }

    /** 最近 3 分钟内是否刚被拦过 */
    fun justChallenged(): Boolean = System.currentTimeMillis() - lastChallengeAt < 180_000L

    /** 下载二进制（图片等），同样复用 Cookie，站点开 Cloudflare 时也能取到 */
    fun bytes(url: String, referer: String? = null): ByteArray? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "image/*,*/*;q=0.8")
        if (referer != null) builder.header("Referer", referer)
        requireClient().newCall(builder.build()).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.bytes()
        }
    }

    private fun execute(request: Request, url: String, quiet: Boolean = false): HttpResult {
        val response = requireClient().newCall(request).execute()
        response.use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val charset = resolveCharset(r.header("Content-Type"), bytes)
            val text = String(bytes, charset)
            val mitigated = r.header("cf-mitigated")
            if (isChallenge(r.code, text) || mitigated != null) {
                if (!quiet) markChallenged()
                throw CfChallengeException(url)
            }
            return HttpResult(r.code, text, url)
        }
    }

    private fun resolveCharset(contentType: String?, bytes: ByteArray): Charset {
        if (contentType != null) {
            val m = Regex("charset=([\\w\\-]+)", RegexOption.IGNORE_CASE).find(contentType)
            val name = m?.groupValues?.get(1)?.lowercase()
            if (name != null) {
                return when {
                    name.contains("gb") -> Charset.forName("GB18030")
                    name.contains("utf") -> Charsets.UTF_8
                    else -> runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
                }
            }
        }
        // 无声明时先按 UTF-8 试探，出现替换字符则判定为 GB 系编码（Discuz 中文站常见）
        val guess = String(bytes, Charsets.UTF_8)
        val bad = guess.count { it == '\uFFFD' }
        if (bad > 0) return Charset.forName("GB18030")
        val head = guess.take(3000).lowercase()
        val meta = Regex("charset=[\"']?([\\w\\-]+)").find(head)?.groupValues?.get(1)
        if (meta != null && meta.contains("gb")) return Charset.forName("GB18030")
        return Charsets.UTF_8
    }

    private fun isChallenge(code: Int, body: String): Boolean {
        if (body.contains("cf-browser-verification") ||
            body.contains("_cf_chl_opt") ||
            body.contains("cf_chl_prog") ||
            body.contains("challenge-platform/h/b") ||
            body.contains("challenges.cloudflare.com") ||
            body.contains("cf-chl-widget")
        ) return true
        if (body.contains("Just a moment", ignoreCase = true) &&
            body.contains("cloudflare", ignoreCase = true)
        ) return true
        if ((code == 403 || code == 503) && body.contains("Cloudflare", ignoreCase = true)) return true
        if (code == 403 && body.contains("Attention Required", ignoreCase = true)) return true
        return false
    }

    /** 是否已持有 Cloudflare 通行 cookie */
    fun hasCfClearance(): Boolean = cookies.hasCookie("cf_clearance")

    fun absolute(url: String): String {
        if (url.startsWith("http")) return url
        val base = HiUrls.baseUrl
        return if (url.startsWith("/")) HiUrls.hostOf(base).let { "https://$it$url" } else base + url
    }

    fun isValidHostUrl(url: String): Boolean = url.toHttpUrlOrNull() != null
}
