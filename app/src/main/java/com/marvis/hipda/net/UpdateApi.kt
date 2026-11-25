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
     * 后续换包只需改这两处常量，页面与检查更新里都引用它们。
     */
    const val LANZOU_URL = "https://wwana.lanzoue.com/iAmPE4h53cob"
    const val LANZOU_CODE = "3tkz"

    /** 一次检查的结果 */
    data class UpdateInfo(
        val tag: String,
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val pageUrl: String
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionPool(okhttp3.ConnectionPool(2, 3, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

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

            return UpdateInfo(tag, version, notes, apkUrl, htmlUrl)
        }
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
