package com.marvis.hipda.util

import android.os.Handler
import android.os.Looper
import com.marvis.hipda.data.OfflineStore
import com.marvis.hipda.model.Block
import com.marvis.hipda.net.HiApi
import com.marvis.hipda.net.HiHttp
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.ui.pages.Page
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「我的收藏」离线缓存的抓取任务：把收藏里所有帖子的正文（含图片）逐页存进 [OfflineStore]。
 *
 * <p>跑在独立单线程上，页面来回切换不会打断；同一时刻只允许一个任务，重复点击直接忽略。
 * 进度通过 [State] 同步给列表页，让用户看到「缓存中 12/48」而不是长时间没反应。</p>
 *
 * <p>为什么图片要单独下：正文只存了图片地址，真正断网时地址取不到内容，
 * 所以离线必须把图片字节也落盘，再由 ImageLoader 优先读本地文件。</p>
 */
object OfflineFetcher {

    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val pages: Int = 0,
        val images: Int = 0,
        val failed: Int = 0,
        /** 正在处理的帖子标题，用于进度提示 */
        val current: String = "",
        /** 结束后的结果说明 */
        val message: String = ""
    )

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val imgPool = Executors.newFixedThreadPool(3)

    @Volatile
    var state = State()
        private set

    @Volatile
    private var running = false

    @Volatile
    private var cancelled = false

    /** 只保留最新页面的回调，避免旧页面长期被引用 */
    private var watcher: ((State) -> Unit)? = null

    fun watch(cb: (State) -> Unit) {
        watcher = cb
        cb(state)
    }

    fun isRunning(): Boolean = running

    fun cancel() {
        if (running) cancelled = true
    }

    /**
     * 开始缓存：先抓收藏列表的全部分页拿到所有帖子，再逐帖抓正文。
     *
     * @param allPages true 时把每个帖子的所有页都存下来；false 只存第一页（更快，播放量少）
     * @param onDone 结束回调（主线程），带最终统计
     */
    fun start(allPages: Boolean, onDone: ((State) -> Unit)? = null) {
        if (running) return
        running = true
        cancelled = false
        publish(State(running = true, current = "读取收藏列表…"))
        work.execute {
            val result = try {
                job(allPages)
            } catch (t: Throwable) {
                state.copy(message = "缓存中断：" + (t.message ?: t.javaClass.simpleName))
            }
            running = false
            val final = result.copy(running = false)
            publish(final)
            main.post { onDone?.invoke(final) }
        }
    }

    private fun publish(s: State) {
        state = s
        main.post { watcher?.invoke(s) }
    }

    /** 后台线程跑：抓全部收藏 → 逐帖正文 → 图片 */
    private fun job(allPages: Boolean): State {
        // 1) 收藏列表分页拿全量 tid+标题
        val seen = LinkedHashMap<String, String>()
        var last = 1
        var p = 1
        while (p <= last && !cancelled) {
            val url = HiUrls.page(HiUrls.myFavoritesUrl, p)
            val html = fetch(url) ?: break
            val page = runCatching { HiApi.feedFromHtml(html, url, false) }.getOrNull() ?: break
            last = page.lastPage.coerceAtLeast(1)
            for (item in page.items) {
                if (item.tid.isNotBlank() && !seen.containsKey(item.tid)) {
                    seen[item.tid] = item.title
                }
            }
            p++
        }
        if (seen.isEmpty()) {
            return state.copy(
                message = if (cancelled) "已取消缓存" else "没取到收藏列表，请检查网络或登录状态"
            )
        }

        val threads = seen.entries.toList()
        var done = 0
        var pages = 0
        var images = 0
        var failed = 0
        publish(state.copy(running = true, total = threads.size, current = ""))

        for ((tid, title) in threads) {
            if (cancelled) break
            publish(state.copy(done = done, total = threads.size, current = title, pages = pages, images = images, failed = failed))
            var pageNo = 1
            var threadFailed = false
            while (!cancelled) {
                val url = HiUrls.threadDetail(tid, pageNo)
                val html = fetch(url)
                val parsed = if (html == null) null else
                    runCatching { HiApi.threadDetailFromHtml(html, url, tid, "", "") }.getOrNull()
                if (parsed == null || parsed.posts.isEmpty()) {
                    threadFailed = true
                    break
                }
                // 用户主动点的「离线缓存」：标记为手动离线包，不受「浏览过的帖子自动缓存」开关影响
                if (OfflineStore.savePage(parsed, manual = true)) pages++
                images += downloadImages(parsed.posts.flatMap { post ->
                    post.blocks.filterIsInstance<Block.Image>().map { it.url }
                })
                if (!allPages || pageNo >= parsed.lastPage.coerceAtLeast(1)) break
                pageNo++
            }
            if (threadFailed) failed++
            done++
            publish(state.copy(done = done, total = threads.size, pages = pages, images = images, failed = failed))
        }

        val msg = if (cancelled) {
            "已取消 · 已存 " + done + "/" + threads.size + " 篇"
        } else {
            "已缓存 " + (threads.size - failed) + " 篇 · " + pages + " 页 · " + images + " 张图" +
                (if (failed > 0) " · " + failed + " 篇失败" else "")
        }
        return State(
            running = false,
            done = done,
            total = threads.size,
            pages = pages,
            images = images,
            failed = failed,
            current = "",
            message = msg
        )
    }

    /** 一页里的图片并发 3 路下载，返回实际新存下的张数 */
    private fun downloadImages(urls: List<String>): Int {
        val todo = urls.distinct().filter { OfflineStore.imageFile(it) == null }
        if (todo.isEmpty()) return 0
        val saved = AtomicInteger(0)
        val tasks = todo.map { u ->
            Callable<Unit> {
                if (OfflineStore.imageFile(u) == null) {
                    val bytes = runCatching {
                        HiHttp.bytes(u, referer = HiUrls.baseUrl + "index.php")
                    }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty() && OfflineStore.saveImage(u, bytes)) {
                        saved.incrementAndGet()
                    }
                }
            }
        }
        runCatching { imgPool.invokeAll(tasks) }
        return saved.get()
    }

    /** 取页面 HTML：Cloudflare 挑战 / 未登录 / 空包都当作失败，交给上层统计 */
    private fun fetch(url: String): String? = try {
        val r = HiHttp.get(url, referer = HiUrls.baseUrl, quiet = true)
        val body = r.body
        when {
            r.code != 200 -> null
            body.isBlank() -> null
            Page.isChallengeHtml(body) -> null
            else -> body
        }
    } catch (t: Throwable) {
        null
    }
}
