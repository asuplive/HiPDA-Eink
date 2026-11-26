package com.marvis.hipda.net

import com.marvis.hipda.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 版本更新查询：只做一件事 —— 读 GitHub Release 的 latest，拿回版本号、更新说明与 apk 下载地址。
 *
 * 设计取舍：
 * 1) 独立 OkHttpClient：GitHub API 与论坛站点无关，不该带上论坛的 Cookie，也不想被论坛的
 *    Cloudflare 挑战状态牵连，因此复用 OkHttp 引擎但单独一个 client，超时给得比论坛短。
 * 2) 全部是同步方法，调用方（页面）负责放到后台线程执行，绝不在主线程发起。
 * 3) 解析只依赖 Android 自带的 org.json，不额外引依赖。
 */
object UpdateApi {

    /** 项目主页 */
    const val GITHUB_URL = "https://github.com/asuplive/HiPDA-Eink"

    /** latest release 的网页地址（查不到 api 里的 html_url 时的兜底） */
    const val GITHUB_RELEASES_URL = "https://github.com/asuplive/HiPDA-Eink/releases/latest"

    /** GitHub Release 查询接口 */
    const val API_LATEST = "https://api.github.com/repos/asuplive/HiPDA-Eink/releases/latest"

    /**
     * 蓝奏云下载地址（备用下载入口）。
     *
     * 用「文件夹级固定分享」而不是单文件分享：文件夹地址长期不变，以后只往文件夹里传新包，
     * 应用里这个入口就永远有效，不必每次发版都改代码。
     * 若某次 Release 说明里写了蓝奏链接，则优先用说明里的那个（见 resolveLanzou）。
     */
    const val LANZOU_URL = "https://wwana.lanzoue.com/b01i6194kb"
    const val LANZOU_CODE = "6ma8"

    /** 一次检查的结果 */
    data class UpdateInfo(
        val tag: String,
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val pageUrl: String,
        /** 实际使用的蓝奏云地址：优先取自 release 说明，否则回退到常量 */
        val lanzouUrl: String = LANZOU_URL,
        /** 实际使用的提取码 */
        val lanzouCode: String = LANZOU_CODE
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectionPool(okhttp3.ConnectionPool(2, 3, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 同步查询最新 Release。网络失败 / 状态码异常 / 内容不是 JSON 一律抛 IOException，
     * 由页面转成一句人话提示，不把异常抛给用户看。
     */
    fun fetchLatest(): UpdateInfo {
        val request = Request.Builder()
            .url(API_LATEST)
            .header("User-Agent", HiHttp.UA)
            .header("Accept", "application/vnd.github+json")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        403, 429 -> "查询过于频繁，请稍后再试"
                        404 -> "还没有发布版本"
                        else -> "HTTP " + response.code
                    }
                )
            }
            if (body.isBlank()) throw IOException("返回内容为空")

            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IOException("返回内容无法解析")
            }

            val tag = json.optString("tag_name").trim().ifBlank {
                json.optString("name").trim()
            }
            val version = normalizeVersion(tag)
            if (version.isBlank()) throw IOException("版本号缺失")

            val htmlUrl = json.optString("html_url").trim().ifBlank { GITHUB_RELEASES_URL }
            val apkUrl = pickApkUrl(json)
            val notes = json.optString("body").orEmpty()

            // 蓝奏入口：说明里写了就用说明里的，没写就用文件夹常量兜底
            val lanzouUrl = resolveLanzouUrl(notes) ?: LANZOU_URL
            // 提取码优先在链接后面那一小段里找，找不到再看全文，最后才用常量
            val lanzouCode = resolveLanzouCode(notes, fromUrlOnly = true)
                ?: resolveLanzouCode(notes)
                ?: LANZOU_CODE

            return UpdateInfo(tag, version, notes, apkUrl, htmlUrl, lanzouUrl, lanzouCode)
        }
    }

    /** 说明文本里第一个蓝奏云链接（lanzoue / lanzoui / lanzou 等域名都认） */
    private val LANZOU_URL_RE = Regex(
        """https?://[A-Za-z0-9._\-]*lanzou[a-z]*\.[A-Za-z0-9.\-]{2,20}/[A-Za-z0-9_\-]+""",
        RegexOption.IGNORE_CASE
    )

    /** 「提取码 xxxx」「密码：xxxx」「访问码 xxxx」 */
    private val LANZOU_CODE_RE = Regex(
        """(?:提取码|密码|访问码)\s*[:：]?\s*([A-Za-z0-9]{3,12})""",
        RegexOption.IGNORE_CASE
    )

    fun resolveLanzouUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = LANZOU_URL_RE.find(text) ?: return null
        return m.value.trimEnd('.', ',', '。', '，', ')', '）')
    }

    /**
     * 从说明里取提取码。
     * @param fromUrlOnly 为 true 时只在链接后面那一小段里找，避免把正文里无关的「密码」认成提取码。
     */
    fun resolveLanzouCode(text: String?, fromUrlOnly: Boolean = false): String? {
        if (text.isNullOrBlank()) return null
        val scope = if (fromUrlOnly) {
            val url = LANZOU_URL_RE.find(text) ?: return null
            text.substring(url.range.last + 1).take(80)
        } else {
            text
        }
        return LANZOU_CODE_RE.find(scope)?.groupValues?.get(1)
    }

    /** assets 里挑第一个 .apk 的下载地址；没有就返回 null，页面改用 release 网页 */
    private fun pickApkUrl(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val item = assets.optJSONObject(i) ?: continue
            val name = item.optString("name").lowercase()
            if (!name.endsWith(".apk")) continue
            val url = item.optString("browser_download_url").trim()
            if (url.isNotEmpty()) return url
        }
        return null
    }

    /** "v1.6.7" / "V1.6.7" / "1.6.7-beta" → "1.6.7-beta" */
    fun normalizeVersion(tag: String): String {
        var t = tag.trim()
        while (t.startsWith("v", ignoreCase = true)) t = t.substring(1).trim()
        return t
    }

    /** 远端版本是否比当前安装版本更新（按数字段逐段比较，1.6.10 > 1.6.7） */
    fun isNewer(remote: String, current: String): Boolean {
        val a = numbers(remote)
        val b = numbers(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = if (i < a.size) a[i] else 0
            val y = if (i < b.size) b[i] else 0
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        v.split('.', '-', '_', '+', ' ')
            .mapNotNull { it.toIntOrNull() }

    /** 便于页面做「本地版本」判断，避免各处硬写 BuildConfig 字段 */
    fun currentVersion(): String = BuildConfig.VERSION_NAME

    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE
}
