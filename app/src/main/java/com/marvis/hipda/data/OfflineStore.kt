package com.marvis.hipda.data

import android.content.Context
import com.marvis.hipda.model.Block
import com.marvis.hipda.model.PostItem
import com.marvis.hipda.model.PostPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 帖子的离线正文仓库：把「我的收藏」里每个帖子的解析结果（含楼层文字与图片）落到应用私有目录，
 * 断网 / 被 Cloudflare 拦住 / 站点抽风时，点开帖子仍然能看到正文，而不是一句「载入失败」。
 *
 * <p>为什么不用 ImageLoader 的磁盘缓存：那份缓存在 cacheDir，系统存储不足时会被清掉，
 * 而离线包的语义是「用户主动出门前存好的东西」，所以图片另存一份在 filesDir 下。</p>
 *
 * <p>结构：</p>
 * <pre>
 * files/offline/posts/&lt;tid&gt;_&lt;page&gt;.json   一页正文（解析后的结构化数据，不是原始 HTML）
 * files/offline/img/&lt;md5(url)&gt;            正文图片原图字节
 * </pre>
 */
object OfflineStore {

    private var root: File? = null
    private var postDir: File? = null
    private var imgDir: File? = null

    /**
     * 正文页文件名：<tid>_<page>.json 是「浏览时顺手缓存」，<tid>_<page>_m.json 是
     * 用户在收藏里主动点的「离线缓存」。两类分开存，关掉浏览缓存时才清得干净、
     * 又不会误删用户主动存好的离线包。group(1)=tid，group(2)=页码，group(3)=_m 或空。
     */
    private val nameRe = Regex("^(\\w+)_(\\d+)(_m)?\\.json$")

    fun init(ctx: Context) {
        if (root != null) return
        val r = File(ctx.filesDir, "offline")
        postDir = File(r, "posts").apply { if (!exists()) mkdirs() }
        imgDir = File(r, "img").apply { if (!exists()) mkdirs() }
        root = r
    }

    fun ready(): Boolean = root != null

    // ---------------------------------------------------------------- 图片

    fun imageKey(url: String): String = MessageDigest.getInstance("MD5")
        .digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** 已离线存好的图片文件；没有则返回 null（调用方据此决定联网） */
    fun imageFile(url: String): File? {
        if (url.isBlank()) return null
        val dir = imgDir ?: return null
        val f = File(dir, imageKey(url))
        return if (f.exists() && f.length() > 0L) f else null
    }

    fun saveImage(url: String, bytes: ByteArray): Boolean {
        if (url.isBlank() || bytes.isEmpty()) return false
        val dir = imgDir ?: return false
        return runCatching {
            val f = File(dir, imageKey(url))
            f.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 正文

    private fun pageFile(tid: String, page: Int, manual: Boolean = false): File? {
        val dir = postDir ?: return null
        return File(dir, tid + "_" + page + (if (manual) "_m" else "") + ".json")
    }

    /**
     * 正文落盘。manual=true 表示用户在「我的收藏」里主动缓存的离线包：
     * 它与浏览缓存分开存，不受「浏览过的帖子自动缓存」开关影响。
     */
    fun savePage(
        p: PostPage,
        at: Long = System.currentTimeMillis(),
        manual: Boolean = false
    ): Boolean {
        val f = pageFile(p.tid, p.page, manual) ?: return false
        return runCatching {
            f.writeText(encode(p, at), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /**
     * 读离线正文。手动离线包总是优先；浏览缓存只在设置里开着
     * 「浏览过的帖子自动缓存」时才算数——否则关掉开关还能读到旧内容，等于开关没生效。
     */
    fun loadPage(tid: String, page: Int): PostPage? {
        readFile(tid, page, manual = true)?.let { return it }
        if (!Prefs.cacheVisitedThreads) return null
        return readFile(tid, page, manual = false)
    }

    private fun readFile(tid: String, page: Int, manual: Boolean): PostPage? {
        val f = pageFile(tid, page, manual) ?: return null
        if (!f.exists() || f.length() == 0L) return null
        return runCatching { decode(tid, f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** 这一页离线内容的写入时间；没有则 0（与 [loadPage] 同源：读到哪份就报哪份的时间） */
    fun pageAt(tid: String, page: Int): Long {
        val manual = pageFile(tid, page, manual = true)
        if (manual != null && manual.exists() && manual.length() > 0L) return manual.lastModified()
        if (!Prefs.cacheVisitedThreads) return 0L
        val read = pageFile(tid, page, manual = false) ?: return 0L
        return if (read.exists() && read.length() > 0L) read.lastModified() else 0L
    }

    /** 该帖已存下的最大页码；没存过返回 0 */
    fun cachedPages(tid: String): Int {
        val dir = postDir ?: return 0
        val files = dir.listFiles() ?: return 0
        var max = 0
        for (f in files) {
            val m = nameRe.find(f.name) ?: continue
            if (m.groupValues[1] == tid) {
                val p = m.groupValues[2].toIntOrNull() ?: 0
                if (p > max) max = p
            }
        }
        return max
    }

    fun hasThread(tid: String): Boolean = cachedPages(tid) > 0

    data class Stats(val threads: Int, val pages: Int, val images: Int, val bytes: Long)

    /**
     * 手动离线包（收藏里主动缓存）的占用：篇 / 页 / 图片 / 字节。
     * 浏览时顺手存的正文不算在内，那是开关可关的临时内容，另有 [readDesc]。
     */
    fun stats(): Stats {
        val r = root ?: return Stats(0, 0, 0, 0L)
        if (!r.exists()) return Stats(0, 0, 0, 0L)
        var pages = 0
        var bytes = 0L
        val tids = HashSet<String>()
        (postDir?.listFiles() ?: emptyArray()).forEach { f ->
            val m = nameRe.find(f.name) ?: return@forEach
            if (m.groupValues[3].isEmpty()) return@forEach
            tids.add(m.groupValues[1])
            pages++
            bytes += f.length()
        }
        var images = 0
        (imgDir?.listFiles() ?: emptyArray()).forEach { f ->
            images++
            bytes += f.length()
        }
        return Stats(tids.size, pages, images, bytes)
    }

    /** 设置页里展示的一行说明：「12 篇 · 34 页 · 8.6 MB」 */
    fun desc(): String {
        val s = stats()
        if (s.pages == 0) return "未缓存"
        return s.threads.toString() + " 篇 · " + s.pages + " 页 · " + sizeText(s.bytes)
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> bytes.toString() + " B"
    }

    fun clear() {
        runCatching { postDir?.listFiles()?.forEach { it.delete() } }
        runCatching { imgDir?.listFiles()?.forEach { it.delete() } }
    }

    /**
     * 浏览缓存的占用说明（只算点开帖子顺手存的正文，不含手动离线包与图片字节）：
     * 空则返回「无」，供设置页那行直接显示。
     */
    fun readDesc(): String {
        var pages = 0
        var bytes = 0L
        val tids = HashSet<String>()
        (postDir?.listFiles() ?: emptyArray()).forEach { f ->
            val m = nameRe.find(f.name) ?: return@forEach
            if (m.groupValues[3].isNotEmpty()) return@forEach
            tids.add(m.groupValues[1])
            pages++
            bytes += f.length()
        }
        if (pages == 0) return "无"
        return tids.size.toString() + " 篇 · " + pages + " 页 · " + sizeText(bytes)
    }

    /**
     * 只清浏览缓存，保留用户主动缓存的离线包；
     * 图片目录不动——手动离线包与浏览缓存共用 img/，删了会把离线包的图一起弄没。
     */
    fun clearRead() {
        runCatching {
            (postDir?.listFiles() ?: emptyArray()).forEach { f ->
                val m = nameRe.find(f.name) ?: return@forEach
                if (m.groupValues[3].isEmpty()) f.delete()
            }
        }
    }

    /** 删掉某个帖子的离线正文（收藏里撤掉帖子后由离线任务顺手清理时可复用） */
    fun removeThread(tid: String) {
        val dir = postDir ?: return
        (dir.listFiles() ?: emptyArray()).forEach { f ->
            val m = nameRe.find(f.name) ?: return@forEach
            if (m.groupValues[1] == tid) runCatching { f.delete() }
        }
    }

    fun timeText(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(at))

    // ---------------------------------------------------------------- 序列化

    private fun encode(p: PostPage, at: Long): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("at", at)
        o.put("tid", p.tid)
        o.put("title", p.title)
        o.put("page", p.page)
        o.put("last", p.lastPage)
        o.put("fid", p.fid)
        o.put("opAuthor", p.opAuthor)
        o.put("opUid", p.opUid)
        val posts = JSONArray()
        for (post in p.posts) {
            val po = JSONObject()
            po.put("pid", post.postId)
            po.put("floor", post.floor)
            po.put("author", post.author)
            po.put("uid", post.uid)
            po.put("time", post.time)
            po.put("op", post.op)
            po.put("notice", post.notice)
            po.put("postCount", post.postCount)
            po.put("credits", post.credits)
            po.put("regTime", post.regTime)
            po.put("signature", post.signature)
            val blocks = JSONArray()
            for (b in post.blocks) {
                val bo = JSONObject()
                when (b) {
                    is Block.Text -> { bo.put("t", "text"); bo.put("v", b.text) }
                    is Block.Quote -> { bo.put("t", "quote"); bo.put("v", b.text) }
                    is Block.Link -> { bo.put("t", "link"); bo.put("v", b.text); bo.put("u", b.url) }
                    is Block.Image -> { bo.put("t", "image"); bo.put("v", b.url) }
                }
                blocks.put(bo)
            }
            po.put("blocks", blocks)
            posts.put(po)
        }
        o.put("posts", posts)
        return o.toString()
    }

    private fun decode(tid: String, json: String): PostPage {
        val o = JSONObject(json)
        val posts = ArrayList<PostItem>()
        val arr = o.optJSONArray("posts") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val po = arr.optJSONObject(i) ?: continue
            val blocks = ArrayList<Block>()
            val ba = po.optJSONArray("blocks") ?: JSONArray()
            for (j in 0 until ba.length()) {
                val bo = ba.optJSONObject(j) ?: continue
                when (bo.optString("t")) {
                    "text" -> blocks.add(Block.Text(bo.optString("v")))
                    "quote" -> blocks.add(Block.Quote(bo.optString("v")))
                    "link" -> blocks.add(Block.Link(bo.optString("v"), bo.optString("u")))
                    "image" -> blocks.add(Block.Image(bo.optString("v")))
                }
            }
            posts.add(
                PostItem(
                    postId = po.optString("pid"),
                    floor = po.optInt("floor"),
                    author = po.optString("author"),
                    uid = po.optString("uid"),
                    time = po.optString("time"),
                    blocks = blocks,
                    op = po.optBoolean("op"),
                    notice = po.optString("notice"),
                    postCount = po.optString("postCount"),
                    credits = po.optString("credits"),
                    regTime = po.optString("regTime"),
                    signature = po.optString("signature")
                )
            )
        }
        return PostPage(
            tid = o.optString("tid").ifBlank { tid },
            title = o.optString("title"),
            posts = posts,
            page = o.optInt("page", 1).coerceAtLeast(1),
            lastPage = o.optInt("last", 1).coerceAtLeast(1),
            fid = o.optString("fid"),
            opAuthor = o.optString("opAuthor"),
            opUid = o.optString("opUid")
        )
    }
}
