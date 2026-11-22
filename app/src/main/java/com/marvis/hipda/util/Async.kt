package com.marvis.hipda.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** 极简后台任务封装，避免页面层引入协程依赖 */
object Async {

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, onSuccess: (T) -> Unit, onError: ((Throwable) -> Unit)? = null) {
        executor.execute {
            try {
                val result = work()
                main.post { onSuccess(result) }
            } catch (t: Throwable) {
                main.post {
                    if (onError != null) onError(t) else resultError(t)
                }
            }
        }
    }

    fun post(action: () -> Unit) {
        main.post(action)
    }

    private fun resultError(t: Throwable) {
        android.util.Log.e("HiPDA", "async error", t)
    }
}
