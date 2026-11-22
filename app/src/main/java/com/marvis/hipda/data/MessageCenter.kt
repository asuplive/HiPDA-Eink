package com.marvis.hipda.data

import com.marvis.hipda.model.NoticeItem
import com.marvis.hipda.net.HiApi
import com.marvis.hipda.net.NoticeApi
import com.marvis.hipda.net.PromptCounts
import com.marvis.hipda.util.Async
import com.marvis.hipda.util.MsgPoll
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * 消息提醒的进程内状态中心。
 *
 * 设计要点：
 * 1) 网络一律走 Async，回调统一回主线程，listener 一定在主线程被调用；
 * 2) 前台 60 秒节流：反复 onResume、反复进菜单不会反复打站点；
 * 3) 未读口径与论坛一致：短消息 + 帖子消息 + 系统消息 + 好友消息；
 * 4) 顶栏信封只认「站点总数 - 已确认基线」：点过信封就把当前这批记为已确认，
 *    图标立刻消失，后续刷新也不会把它重新点亮，只有真来了新消息才会再出现；
 * 5) 异常只在内部置 ok=false 并保留上次数值，绝不向外抛 —— 提醒功能坏掉不能影响正常浏览。
 */
object MessageCenter {

    data class State(
        val pm: Int = 0,
        val thread: Int = 0,
        val system: Int = 0,
        val friend: Int = 0,
        /** 顶栏信封用的有效未读数：站点总数 - 已确认基线，0 = 不显示信封 */
        val total: Int = 0,
        val newCount: Int = 0,
        val maxId: Long = 0L,
        val ok: Boolean = false,
        val at: Long = 0L
    ) {
        /** 提醒类合计（帖子 + 系统 + 好友），菜单页「提醒」行用 */
        val notice: Int get() = thread + system + friend

        /** 站点上真实的未读合计（含短消息） */
        val raw: Int get() = pm + notice
    }

    private const val THROTTLE_MS = 60_000L
    private const val MAX_SEEN = 200

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @Volatile
    private var cached: State? = null

    @Volatile
    private var loading = false

    @Volatile
    private var lastTryAt = 0L

    /** 刚点过信封 / 进过消息页：下一次成功刷新时把当时站点总数记成新基线 */
    @Volatile
    private var ackPending = false

    /** 进程内缓存；首次访问时从 Prefs 恢复上次的未读数 */
    var state: State
        get() {
            val hit = cached
            if (hit != null) return hit
            val pm = intPref { Prefs.msgPmUnread }
            val thread = intPref { Prefs.msgThreadUnread }
            val friend = intPref { Prefs.msgFriendUnread }
            val restored = State(
                pm = pm,
                thread = thread,
                friend = friend,
                total = effectiveOf(pm + thread + friend),
                maxId = longPref { Prefs.msgMaxId },
                at = longPref { Prefs.msgCheckedAt }
            )
            cached = restored
            return restored
        }
        set(value) {
            cached = value
        }

    fun addListener(l: (State) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    /** 拉取未读汇总；force=false 时 60 秒内不重复请求。异常全部吞掉 */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (loading) return
        if (!force && now - lastTryAt < THROTTLE_MS) return
        lastTryAt = now

        val logged = try {
            Prefs.isLoggedIn() || HiApi.hasSessionCookie()
        } catch (t: Throwable) {
            false
        }
        if (!logged) return

        loading = true
        Async.run(
            work = {
                val counts = NoticeApi.fetchCounts()
                // 顺手取一次帖子提醒列表：用来算"本次真正新增几条"，取不到不影响未读数
                val items = try {
                    NoticeApi.noticeList(NoticeApi.KIND_THREAD)
                } catch (t: Throwable) {
                    emptyList<NoticeItem>()
                }
                counts to items
            },
            onSuccess = { pair ->
                loading = false
                applyCounts(pair.first, pair.second)
            },
            onError = { _ ->
                loading = false
                publish(state.copy(ok = false))
            }
        )
    }

    /**
     * 用户点了顶栏红色信封：立刻把图标消掉，并把当前这批未读记为"已确认"。
     * 不依赖任何网络请求，也不改站点上的已读状态。
     */
    fun acknowledge() {
        val cur = state
        ackPending = true
        runCatching { Prefs.msgAckTotal = cur.raw }
        publish(cur.copy(total = 0))
        runCatching { MsgPoll.syncBaseline(cur.raw) }
    }

    /** 把一批提醒 ID 标记为已见（更新 seenIds 与 maxId），只保留最近 200 个 */
    fun markSeen(ids: List<Long>) {
        if (ids.isEmpty()) return
        try {
            var maxId = Prefs.msgMaxId
            val seen = LinkedHashSet<Long>(seenIds())
            for (id in ids) {
                seen.add(id)
                if (abs(id) > abs(maxId)) maxId = id
            }
            while (seen.size > MAX_SEEN) {
                val first = seen.firstOrNull() ?: break
                seen.remove(first)
            }
            Prefs.msgSeenIds = seen.joinToString(",")
            Prefs.msgMaxId = maxId
            publish(state.copy(newCount = 0, maxId = maxId))
        } catch (t: Throwable) {
        }
    }

    /**
     * 打开提醒页 / 短消息页 / 全部标为已读时调用：清对应频道的本地未读计数并落库。
     * which：0=短消息，1=三类提醒（帖子/系统/好友），2=全部
     */
    fun clearUnread(which: Int) {
        val current = state
        val pm = if (which == 0 || which == 2) 0 else current.pm
        val thread = if (which == 1 || which == 2) 0 else current.thread
        val system = if (which == 1 || which == 2) 0 else current.system
        val friend = if (which == 1 || which == 2) 0 else current.friend
        val raw = pm + thread + system + friend
        try {
            Prefs.msgPmUnread = pm
            Prefs.msgThreadUnread = thread
            Prefs.msgFriendUnread = friend
            Prefs.msgAckTotal = raw
        } catch (t: Throwable) {
        }
        // 看过消息了：下一次刷新以站点当时的真实总数为新基线，避免角标/信封立刻又亮起来
        ackPending = true
        publish(
            current.copy(
                pm = pm,
                thread = thread,
                system = system,
                friend = friend,
                total = 0,
                newCount = 0,
                ok = true
            )
        )
        // 用户已经看过消息了：把通知基线拉回当前值，
        // 否则退出后后台轮询发现"总数没超过旧基线"，新消息永远不会再提醒
        runCatching { MsgPoll.syncBaseline(raw) }
    }

    /** 已见提醒 ID 集合（用于判断某条是否算未读） */
    fun seenIds(): Set<Long> {
        val raw = try {
            Prefs.msgSeenIds
        } catch (t: Throwable) {
            ""
        }
        if (raw.isBlank()) return emptySet()
        val out = LinkedHashSet<Long>()
        for (part in raw.split(',')) {
            val v = part.trim().toLongOrNull() ?: continue
            out.add(v)
        }
        return out
    }

    // ────────────────────────────────────────────────────────────────

    /**
     * 后台轮询（MsgPoll）拿到未读数后调用：只更新计数并落库，
     * 不碰 maxId / seenIds —— 后台线程没有列表，没资格推断"哪些是新消息"。
     * 可安全地在非主线程调用，listener 仍回主线程。
     */
    fun applyCounts(pm: Int, thread: Int, system: Int, friend: Int) {
        val at = System.currentTimeMillis()
        val raw = pm + thread + system + friend
        try {
            Prefs.msgPmUnread = pm
            Prefs.msgThreadUnread = thread
            Prefs.msgFriendUnread = friend
            Prefs.msgCheckedAt = at
        } catch (t: Throwable) {
        }
        val total = settleAck(raw)
        publish(
            State(
                pm = pm,
                thread = thread,
                system = system,
                friend = friend,
                total = total,
                newCount = 0,
                maxId = longPref { Prefs.msgMaxId },
                ok = true,
                at = at
            )
        )
    }

    private fun applyCounts(counts: PromptCounts, items: List<NoticeItem>) {
        val prevMax = longPref { Prefs.msgMaxId }
        val seen = seenIds()

        var maxId = prevMax
        var newCount = 0
        var baseline = false

        if (items.isNotEmpty()) {
            // 首次拿到列表（既没有 maxId 也没有已见集合）只当基准，不算"新增"，避免一登录就报一堆新消息
            baseline = prevMax == 0L && seen.isEmpty()
            for (item in items) {
                if (abs(item.id) > abs(maxId)) maxId = item.id
                if (!baseline && !seen.contains(item.id) && abs(item.id) > abs(prevMax)) newCount++
            }
        }

        val at = System.currentTimeMillis()
        val raw = counts.pm + counts.thread + counts.system + counts.friend
        try {
            Prefs.msgPmUnread = counts.pm
            Prefs.msgThreadUnread = counts.thread
            Prefs.msgFriendUnread = counts.friend
            Prefs.msgCheckedAt = at
            if (baseline && maxId != 0L) Prefs.msgMaxId = maxId
        } catch (t: Throwable) {
        }

        val total = settleAck(raw)
        publish(
            State(
                pm = counts.pm,
                thread = counts.thread,
                system = counts.system,
                friend = counts.friend,
                total = total,
                newCount = newCount,
                maxId = maxId,
                ok = true,
                at = at
            )
        )
    }

    /**
     * 拿到站点真实未读总数后结算基线，返回顶栏信封该用的有效未读数。
     * - 刚点过信封（ackPending）：以这次的总数为新基线，结果必然 0，图标不会闪回来；
     * - 平时：站点总数涨了就大于基线（显示信封），站点侧读掉一部分则把基线跟着降下来。
     */
    private fun settleAck(raw: Int): Int {
        var ack = intPref { Prefs.msgAckTotal }
        if (ackPending) {
            ackPending = false
            ack = raw
            runCatching { Prefs.msgAckTotal = ack }
        } else if (raw < ack) {
            ack = raw
            runCatching { Prefs.msgAckTotal = ack }
        }
        return (raw - ack).coerceAtLeast(0)
    }

    private fun effectiveOf(raw: Int): Int {
        val ack = intPref { Prefs.msgAckTotal }
        return (raw - ack).coerceAtLeast(0)
    }

    private fun publish(s: State) {
        cached = s
        Async.post {
            for (l in listeners) {
                try {
                    l(s)
                } catch (t: Throwable) {
                }
            }
        }
    }

    private fun intPref(block: () -> Int): Int = try {
        block()
    } catch (t: Throwable) {
        0
    }

    private fun longPref(block: () -> Long): Long = try {
        block()
    } catch (t: Throwable) {
        0L
    }
}
