package com.marvis.hipda.net

import android.content.Context
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * OkHttp 与 WebView 共用的 Cookie 仓库。
 * 关键用途：Cloudflare 验证通过后拿到的 cf_clearance 必须让后台请求也带上，
 * 否则下一次 HTTP 请求仍然会被挑战。
 */
class PersistentCookieStore(context: Context) : CookieJar {

    private val sp = context.applicationContext.getSharedPreferences("hipda_cookies", Context.MODE_PRIVATE)
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val lock = Any()

    init {
        load()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val list = store.getOrPut(url.host) { mutableListOf() }
        for (c in cookies) {
            list.removeAll { it.name == c.name }
            list.add(c)
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<Cookie>()
        for (cookies in store.values) {
            for (c in cookies) {
                if (c.expiresAt > now && c.matches(url)) out.add(c)
            }
        }
        return out
    }

    @Synchronized
    fun put(cookie: Cookie) {
        val list = store.getOrPut(cookie.domain) { mutableListOf() }
        list.removeAll { it.name == cookie.name }
        list.add(cookie)
        persist()
    }

    /** 从 WebView 的 Cookie 池同步到 OkHttp（Cloudflare 验证后调用） */
    fun syncFromWebView(url: String) {
        val manager = CookieManager.getInstance()
        val raw = manager.getCookie(url) ?: return
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val baseDomain = HiUrls.cookieDomain
        val host = httpUrl.host
        val targetDomain = if (host.endsWith(baseDomain)) baseDomain else host
        for (pair in raw.split(";")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            if (name.isEmpty()) continue
            try {
                val c = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(targetDomain)
                    .path("/")
                    .expiresAt(System.currentTimeMillis() + 30L * 24 * 3600 * 1000)
                    .build()
                put(c)
            } catch (ignored: Exception) {
            }
        }
    }

    /** 把 OkHttp 的 Cookie 推给 WebView，保证验证页处于已登录状态 */
    fun pushToWebView(url: String) {
        val manager = CookieManager.getInstance()
        val httpUrl = url.toHttpUrlOrNull() ?: return
        for (cookies in store.values) {
            for (c in cookies) {
                if (c.matches(httpUrl)) {
                    manager.setCookie(url, "${c.name}=${c.value}; path=/")
                }
            }
        }
        manager.flush()
    }

    /** 确保 WebView 侧的 Cookie 立即落盘（验证页退出时调用） */
    fun flushCookieManager() {
        runCatching {
            CookieManager.getInstance().flush()
        }
    }

    /** 取某个 Cookie 的原值（用于直接从 cdb_auth 判断登录态，无需联网） */
    fun cookieValue(name: String): String? {
        val now = System.currentTimeMillis()
        for (list in store.values) {
            for (c in list) {
                if (c.name == name && c.expiresAt > now) return c.value
            }
        }
        return null
    }

    fun hasCookie(name: String): Boolean {
        val now = System.currentTimeMillis()
        return store.values.any { list -> list.any { it.name == name && it.expiresAt > now } }
    }

    @Synchronized
    fun clear() {
        store.clear()
        sp.edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            for ((host, cookies) in store) {
                for (c in cookies) {
                    val o = JSONObject()
                    o.put("host", host)
                    o.put("raw", c.toString())
                    arr.put(o)
                }
            }
            sp.edit().putString("data", arr.toString()).apply()
        } catch (ignored: Exception) {
        }
    }

    private fun load() {
        val data = sp.getString("data", null) ?: return
        try {
            val arr = JSONArray(data)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val host = o.optString("host")
                val raw = o.optString("raw")
                val httpUrl = "https://$host/".toHttpUrlOrNull() ?: continue
                val cookie = Cookie.parse(httpUrl, raw) ?: continue
                store.getOrPut(cookie.domain) { mutableListOf() }.add(cookie)
            }
        } catch (ignored: Exception) {
        }
    }
}
