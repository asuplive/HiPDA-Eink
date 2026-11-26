package com.marvis.hipda

import android.app.Application
import com.marvis.hipda.data.OfflineStore
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.net.HiHttp
import com.marvis.hipda.util.ImageLoader
import com.marvis.hipda.util.MsgPoll

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        OfflineStore.init(this)
        HiHttp.init(this)
        ImageLoader.init(this)
        // 消息提醒：建一次通知渠道，再按用户的轮询设置排好闹钟（没开或没登录时内部自动跳过）
        MsgPoll.ensureChannel(this)
        MsgPoll.schedule(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
