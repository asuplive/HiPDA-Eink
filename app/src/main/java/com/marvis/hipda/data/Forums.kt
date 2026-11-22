package com.marvis.hipda.data

import com.marvis.hipda.model.Forum
import org.json.JSONArray
import org.json.JSONObject

/**
 * 板块表与分类。
 *
 * 数据来源：站点「跳转到」菜单 #fjump_menu（forumdisplay.php 页内置，登录态下渲染
 * 当前账号可见的全部板块）与首页 index.php 的 category_ 表。两处合并后按下面的规则重排：
 *
 *   1) Discovery 置顶，单独一组；
 *   2) Buy & Sell 交易服务区排第二；
 *   3) 其余分成技术版区 / 生活版区 / 其他三类。
 *
 * 以前只抓首页，而首页只渲染 14 个 tbody、且 tbody id 形如 forum7（不带下划线），
 * 老选择器 tbody[id^=forum_] 一个都匹配不到，结果只同步出少数几个板块 —— 现在
 * 以 #fjump_menu 为主来源，再用 CATALOG 兜住解析失败时的完整清单。
 */
object Forums {

    const val G_DISCOVERY = "Discovery"
    const val G_TRADE = "交易服务区"
    const val G_TECH = "技术版区"
    const val G_LIFE = "生活与其他"
    const val G_OTHER = "生活与其他"

    /**
     * 分组显示顺序：共 4 组 —— Discovery 置顶，Buy & Sell 交易服务区第二，
     * 其余按技术版区 / 生活与其他两类收敛（站点上新出现的板块也会落进后两类，不会再丢）。
     */
    val GROUP_ORDER = listOf(G_DISCOVERY, G_TRADE, G_TECH, G_LIFE)

    /**
     * 站点当前可见的完整板块（2026-11 登录态实测 19 个）。
     * 仅作为「在线解析拿不到时的兜底」与「分组/排序依据」，在线结果永远优先。
     */
    val CATALOG: List<Forum> = listOf(
        Forum("2", "Discovery", "站点主版，闲聊与杂谈", G_DISCOVERY),
        Forum("6", "Buy & Sell 交易服务区", "数码产品交易区", G_TRADE),
        Forum("63", "已完成交易", "交易存档", G_TRADE),
        Forum("7", "Geek Talks · 奇客怪谈", "业界、Bluetooth、WiFi、嵌入Linux", G_TECH),
        Forum("62", "Joggler", "Joggler 子版", G_TECH),
        Forum("9", "Smartphone", "智能手机综合", G_TECH),
        Forum("56", "iPhone, iPod Touch，iPad", "iOS 设备", G_TECH),
        Forum("60", "Android, Chrome, & Google", "安卓与谷歌", G_TECH),
        Forum("12", "PalmOS ，Treo", "Palm 与 Treo", G_TECH),
        Forum("40", "Palm芝麻宝典", "Palm 资料子版", G_TECH),
        Forum("22", "麦客爱苹果", "Mac 相关", G_TECH),
        Forum("14", "Windows Mobile，PocketPC，HPC", "WinMo / PPC / HPC", G_TECH),
        Forum("50", "DC,NB,MP3,Gadgets...", "数码装备", G_TECH),
        Forum("70", "俄乌战争", "时事子版", G_LIFE),
        Forum("64", "只讨论2.0", "时事子版", G_LIFE),
        Forum("24", "意欲蔓延", "电影、音乐、读书、美术、摄影", G_LIFE),
        Forum("23", "随笔与个人文集", "文字随笔", G_LIFE),
        Forum("25", "吃喝玩乐", "美食、旅行", G_LIFE),
        Forum("51", "La Femme", "生活子版", G_LIFE)
    )

    private val ORDER: Map<String, Int> =
        CATALOG.mapIndexed { index, forum -> forum.fid to index }.toMap()

    /** 兜底表按分组排好序 */
    val FALLBACK: List<Forum> = CATALOG

    @Volatile
    var current: List<Pair<String, List<Forum>>> = emptyList()

    val ALL: List<Forum>
        get() {
            val list = current.flatMap { it.second }
            return if (list.isEmpty()) CATALOG else list
        }

    fun grouped(): List<Pair<String, List<Forum>>> =
        if (current.isNotEmpty()) current else organize(listOf(G_OTHER to CATALOG))

    fun apply(parsed: List<Pair<String, List<Forum>>>) {
        if (parsed.isEmpty()) return
        val parsedCount = parsed.sumOf { it.second.size }
        // 在线解析常常只拿到十几个（首页 tbody 不全、跳转菜单偶发只渲染部分），
        // 少得明显时用已知板块表补齐，避免「同步完反而少了几个板块」。
        val source = if (parsedCount < CATALOG.size) parsed + listOf(G_OTHER to CATALOG) else parsed
        val organized = organize(source)
        if (organized.isEmpty()) return
        current = organized
        saveCache(organized)
    }

    /** fid 属于哪一组 */
    fun classify(fid: String): String = when (fid) {
        "2" -> G_DISCOVERY
        "6", "63" -> G_TRADE
        "7", "62", "9", "56", "60", "12", "40", "22", "14", "50" -> G_TECH
        "70", "64", "24", "23", "25", "51" -> G_LIFE
        else -> G_OTHER
    }

    /**
     * 把任意来源的板块表整理成固定分组与顺序：
     * 按 fid 去重（先出现的描述优先保留），组内按 CATALOG 顺序，CATALOG 之外的新板块排在组尾。
     */
    fun organize(raw: List<Pair<String, List<Forum>>>): List<Pair<String, List<Forum>>> {
        val merged = LinkedHashMap<String, Forum>()
        for ((_, list) in raw) {
            for (forum in list) {
                if (forum.fid.isBlank() || forum.name.isBlank()) continue
                val old = merged[forum.fid]
                if (old == null) {
                    merged[forum.fid] = forum
                } else if (old.desc.isBlank() && forum.desc.isNotBlank()) {
                    merged[forum.fid] = old.copy(desc = forum.desc)
                }
            }
        }
        if (merged.isEmpty()) return emptyList()

        val buckets = LinkedHashMap<String, MutableList<Forum>>()
        for (group in GROUP_ORDER) buckets[group] = mutableListOf()
        for (forum in merged.values) {
            val group = classify(forum.fid)
            val desc = forum.desc.ifBlank { CATALOG.firstOrNull { it.fid == forum.fid }?.desc.orEmpty() }
            buckets.getOrPut(group) { mutableListOf() }.add(forum.copy(group = group, desc = desc))
        }

        val out = mutableListOf<Pair<String, List<Forum>>>()
        for (group in GROUP_ORDER) {
            val list = buckets[group] ?: continue
            if (list.isEmpty()) continue
            val sorted = list.sortedWith(
                compareBy<Forum> { ORDER[it.fid] ?: Int.MAX_VALUE }
                    .thenBy { it.name }
            )
            out.add(group to sorted)
        }
        return out
    }

    fun loadCache(): List<Pair<String, List<Forum>>> {
        val raw = Prefs.forumIndexJson
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val parsed = mutableListOf<Pair<String, List<Forum>>>()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val name = g.optString("group")
                val items = g.optJSONArray("forums") ?: JSONArray()
                val list = mutableListOf<Forum>()
                for (j in 0 until items.length()) {
                    val o: JSONObject = items.getJSONObject(j)
                    val fid = o.optString("fid")
                    val fname = o.optString("name")
                    if (fid.isBlank() || fname.isBlank()) continue
                    list.add(Forum(fid, fname, o.optString("desc"), name))
                }
                if (list.isNotEmpty()) parsed.add(name to list)
            }
            organize(parsed)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCache(data: List<Pair<String, List<Forum>>>) {
        try {
            val arr = JSONArray()
            for ((group, list) in data) {
                val g = JSONObject()
                g.put("group", group)
                val items = JSONArray()
                for (f in list) {
                    val o = JSONObject()
                    o.put("fid", f.fid)
                    o.put("name", f.name)
                    o.put("desc", f.desc)
                    items.put(o)
                }
                g.put("forums", items)
                arr.put(g)
            }
            Prefs.forumIndexJson = arr.toString()
            Prefs.forumIndexAt = System.currentTimeMillis()
        } catch (e: Exception) {
            // 缓存失败不影响使用
        }
    }

    fun nameOf(fid: String): String = ALL.firstOrNull { it.fid == fid }?.name ?: "板块 $fid"

    fun find(fid: String): Forum? = ALL.firstOrNull { it.fid == fid }

    fun needLogin(fid: String): Boolean = false
}
