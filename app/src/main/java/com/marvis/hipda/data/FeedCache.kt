package com.marvis.hipda.data

import android.content.Context
import android.content.SharedPreferences
import com.marvis.hipda.App
import com.marvis.hipda.model.FeedItem
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「我的收藏」列表的本地缓存。
 *
 * <p>目的很单纯：进收藏页先秒出上次的结果，再后台刷新一次覆盖；
 * 断网、超时或被 Cloudflare 拦的时候，列表里还有东西可看，
 * 而不是只剩一句「载入失败」。</p>
 *
 * <p>只存列表本身（标题、作者、时间等文本），不存网页 HTML，
 * 所以体积很小，也不会因为站点改版把陈旧页面长期留在本地。</p>
 */
object FeedCache {

    /** 收藏列表的缓存键 */
    const val FAVORITES = "favorites"

    private const val PREF = "feed_cache"

    /** 最多缓存多少条，防止收藏特别多时把 SP 撑大（够覆盖前几页浏览即可） */
    private const val MAX_ITEMS = 200

    data class Snapshot(
        val items: List<FeedItem>,
        val page: Int,
        val lastPage: Int,
        val at: Long
    )

    private val sp: SharedPreferences
        get() = App.instance.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun save(key: String, items: List<FeedItem>, page: Int, lastPage: Int) {
        val arr = JSONArray()
        for (it in items.take(MAX_ITEMS)) {
            arr.put(
                JSONObject()
                    .put("tid", it.tid)
                    .put("pid", it.pid)
                    .put("title", it.title)
                    .put("forum", it.forumName)
                    .put("author", it.author)
                    .put("time", it.time)
                    .put("info", it.info)
            )
        }
        val root = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("page", page)
            .put("lastPage", lastPage)
            .put("items", arr)
        runCatching { sp.edit().putString(key, root.toString()).apply() }
    }

    fun load(key: String): Snapshot? {
        val raw = runCatching { sp.getString(key, null) }.getOrNull() ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("items") ?: JSONArray()
            val list = ArrayList<FeedItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    FeedItem(
                        tid = o.optString("tid"),
                        pid = o.optString("pid"),
                        title = o.optString("title"),
                        forumName = o.optString("forum"),
                        author = o.optString("author"),
                        time = o.optString("time"),
                        info = o.optString("info")
                    )
                )
            }
            Snapshot(
                items = list,
                page = root.optInt("page", 1),
                lastPage = root.optInt("lastPage", 1),
                at = root.optLong("at", 0L)
            )
        }.getOrNull()
    }

    fun clear(key: String) {
        runCatching { sp.edit().remove(key).apply() }
    }

    /** 设置页展示用：「32 条 · 11-26 20:40」，没有缓存返回「无」 */
    fun desc(key: String): String {
        val s = load(key) ?: return "无"
        if (s.items.isEmpty() || s.at <= 0L) return "无"
        return s.items.size.toString() + " 条 · " + timeText(s.at)
    }

    fun timeText(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(at))
}
