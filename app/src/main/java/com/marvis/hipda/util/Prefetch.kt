package com.marvis.hipda.util

import java.util.concurrent.Executors

/**
 * 预取用的独立单线程队列。
 *
 * 为什么不复用 Async 的 3 线程池：预取是"抢跑"，不能和用户当前正在等的页面请求抢线程，
 * 否则点进帖子时反而被后面的预取任务堵住。单线程顺序执行还顺带避免了并发打站点的压力。
 */
object Prefetch {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hipda-prefetch").apply { isDaemon = true }
    }

    fun submit(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (ignored: Throwable) {
            }
        }
    }
}
