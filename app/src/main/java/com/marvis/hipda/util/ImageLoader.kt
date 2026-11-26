package com.marvis.hipda.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.marvis.hipda.data.OfflineStore
import com.marvis.hipda.net.HiHttp
import com.marvis.hipda.net.HiUrls
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 极简图片加载：复用 OkHttp 的 Cookie，图片请求同样能过 Cloudflare。
 *
 * 提速点（相对旧版）：
 * 1) 磁盘二级缓存：图片落盘复用，隔天再进同一个帖子不必重新下载；
 * 2) 解码降采样：先只读尺寸，再按屏宽算 inSampleSize，避免把 4000px 原图整张解进内存；
 * 3) 并发 2 → 3，且同一 URL 的重复请求会被合并。
 */
object ImageLoader {

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = mutableSetOf<String>()

    private var cacheDir: File? = null
    private var targetWidth = 1080

    fun init(ctx: Context) {
        val dir = File(ctx.cacheDir, "img")
        if (!dir.exists()) dir.mkdirs()
        cacheDir = dir
        val w = ctx.resources.displayMetrics.widthPixels
        if (w > 0) targetWidth = w
    }

    fun load(url: String, onReady: (Bitmap?) -> Unit) {
        if (url.isBlank()) {
            onReady(null)
            return
        }
        val cached = cache.get(url)
        if (cached != null) {
            onReady(cached)
            return
        }
        synchronized(inFlight) {
            if (!inFlight.add(url)) return
        }
        executor.execute {
            var bitmap: Bitmap? = null
            try {
                val file = diskFile(url)
                // 离线包里存过的图片优先读：出门前缓存过的帖子，断网点开也要有图
                val offline = OfflineStore.imageFile(url)
                val bytes = when {
                    offline != null -> offline.readBytes()
                    file != null && file.exists() && file.length() > 0 -> file.readBytes()
                    else -> {
                        // 带上站点 Referer：附件图走防盗链时不会被换成一张统一的占位图
                        val downloaded = HiHttp.bytes(url, referer = HiUrls.baseUrl + "index.php")
                        if (downloaded != null && downloaded.isNotEmpty() && file != null) {
                            runCatching { file.writeBytes(downloaded) }
                        }
                        downloaded
                    }
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    bitmap = decode(bytes)
                    if (bitmap == null) {
                        // 解码失败说明缓存文件是坏数据（错误页 / 被拦截的 HTML），删掉免得一直显示不出来
                        runCatching { file?.delete() }
                    } else if (isTooTiny(bitmap)) {
                        // 20x20 附件图标、1x1 占位图之类：铺到满屏宽就是一张「模糊的图」，直接按加载失败处理
                        bitmap = null
                    }
                }
            } catch (ignored: Throwable) {
            }
            synchronized(inFlight) { inFlight.remove(url) }
            if (bitmap != null) cache.put(url, bitmap)
            main.post { onReady(bitmap) }
        }
    }

    /** 先只解尺寸，再按目标宽度降采样 —— 大图解码时间与内存都能降一个量级 */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val w = bounds.outWidth
        if (w > 0) {
            while (w / (sample * 2) >= targetWidth) sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    /** 站点装饰图 / 占位图尺寸都很小，放大渲染只会得到一张糊图，直接判为无效 */
    private fun isTooTiny(bmp: Bitmap): Boolean = bmp.width <= 32 && bmp.height <= 32

    private fun diskFile(url: String): File? {
        val dir = cacheDir ?: return null
        val name = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, name)
    }
}
