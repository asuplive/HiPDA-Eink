package com.marvis.hipda.util

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.marvis.hipda.data.MessageCenter
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.net.NoticeApi
import com.marvis.hipda.ui.MainActivity

/**
 * 后台低频轮询 + 系统通知。
 *
 * 设计取舍（省电优先）：
 * 1) 用 setInexactRepeating 而不是精确闹钟 —— 精确闹钟在国产 ROM 的省电策略下很容易被拦，
 *    而且消息提醒本来就不需要秒级准点，允许系统把多次唤醒合并到一次；
 * 2) 间隔下限 15 分钟，比这更密对墨水屏设备没有意义，只会耗电；
 * 3) 所有网络/解析异常一律静默，绝不弹 Toast、绝不抛给系统（否则会 ANR/崩溃）；
 * 4) 只有未读总数变大才发通知，避免每次轮询都响一次。
 */
object MsgPoll {

    const val CHANNEL_ID = "hipda_messages"
    private const val CHANNEL_NAME = "消息提醒"
    private const val REQ_POLL = 1001
    private const val REQ_OPEN = 1002
    private const val NOTIFY_ID = 2001

    /** 最小轮询间隔（分钟） */
    private const val MIN_MINUTES = 15

    /** 通知渠道：重复调用无副作用，App 启动与发通知前都可以调 */
    fun ensureChannel(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            )
        } catch (t: Throwable) {
        }
    }

    /** 按 Prefs.msgPollMinutes 注册/取消后台轮询；0 = 关闭 */
    fun schedule(ctx: Context) {
        try {
            val minutes = Prefs.msgPollMinutes
            if (minutes <= 0 || !Prefs.isLoggedIn()) {
                cancel(ctx)
                return
            }
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val interval = minutes.coerceAtLeast(MIN_MINUTES) * 60_000L
            val pi = pollIntent(ctx)
            am.setInexactRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + interval,
                interval,
                pi
            )
        } catch (t: Throwable) {
        }
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(pollIntent(ctx))
        } catch (t: Throwable) {
        }
    }

    private fun pollIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx.applicationContext, MsgPollReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx.applicationContext,
            REQ_POLL,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 拉一次未读汇总（阻塞，必须在后台线程调用）。
     * 成功后写 Prefs 并判断是否需要发通知；任何异常都只是跳过。
     */
    fun pollNow(ctx: Context) {
        runCatching {
            val counts = NoticeApi.fetchCounts()
            Prefs.msgPmUnread = counts.pm
            Prefs.msgThreadUnread = counts.thread
            Prefs.msgFriendUnread = counts.friend
            Prefs.msgCheckedAt = System.currentTimeMillis()
            MessageCenter.applyCounts(counts.pm, counts.thread, counts.system, counts.friend)
            notifyIfNew(ctx, counts.total, counts.pm, counts.notice)
        }
    }

    /** 未读总数比上次通知时更大才发一条通知 */
    fun notifyIfNew(ctx: Context, total: Int, pm: Int, notice: Int) {
        try {
            val app = ctx.applicationContext
            if (!Prefs.msgNotify) return
            if (total <= Prefs.msgNotifiedTotal) return
            if (Build.VERSION.SDK_INT >= 33) {
                val granted = ContextCompat.checkSelfPermission(
                    app, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val nmc = NotificationManagerCompat.from(app)
            if (!nmc.areNotificationsEnabled()) return

            ensureChannel(app)
            val open = Intent(app, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_NOTICE, true)
            }
            val pi = PendingIntent.getActivity(
                app,
                REQ_OPEN,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("HiPDA 有 " + total + " 条新消息")
                .setContentText("短消息 " + pm + " 条 · 提醒 " + notice + " 条")
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
            nmc.notify(NOTIFY_ID, builder.build())

            // 发出去之后再更新基线，避免权限被拒时把新增永久吞掉
            Prefs.msgNotifiedTotal = total
        } catch (t: Throwable) {
        }
    }

    /** 用户已读后把通知基线拉回当前未读数，防止退出后又被旧差值通知一次 */
    fun syncBaseline(total: Int) {
        runCatching { Prefs.msgNotifiedTotal = total }
    }
}

/** 后台轮询广播：goAsync 里开线程干活，做完 finish，异常一律吞掉 */
class MsgPollReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        // 开机：闹钟全清了，重新注册一次就退出，不在这里联网
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching { MsgPoll.schedule(app) }
            return
        }
        val pending = goAsync()
        try {
            val worker = Thread {
                try {
                    MsgPoll.pollNow(app)
                } catch (t: Throwable) {
                } finally {
                    try {
                        pending.finish()
                    } catch (t: Throwable) {
                    }
                }
            }
            worker.isDaemon = true
            worker.start()
        } catch (t: Throwable) {
            try {
                pending.finish()
            } catch (ignored: Throwable) {
            }
        }
    }
}
