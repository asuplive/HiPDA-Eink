package com.marvis.hipda.ui.pages

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.net.CfChallengeException
import com.marvis.hipda.net.HiHttp
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async
import java.io.IOException
import kotlin.math.abs

/** 页面基类：MainActivity 用页面栈管理，翻页栏通过这几个回调驱动 */
abstract class Page(val host: MainActivity) : FrameLayout(host) {

    open val title: String get() = ""
    /** 顶栏标题点开后下拉显示的完整标题；空串表示该页面不支持下拉展开 */
    open val expandTitle: String get() = ""
    open fun pageText(): String = ""
    open fun canPrev(): Boolean = false
    open fun canNext(): Boolean = false
    open fun onPrev() {}
    open fun onNext() {}
    /** 返回 true 表示页面自己消费了返回键 */
    open fun onBack(): Boolean = false
    /** 重新载入当前内容（登录 / 验证通过后刷新） */
    open fun refresh() {}

    fun chrome() = host.refreshChrome()

    /** 下拉刷新 / 重新拉取：先废掉页面短缓存，避免刷新后还是 60 秒内的旧页面 */
    protected fun invalidatePageCache() = HiHttp.clearPageCache()

    protected fun attachFull(view: View) {
        addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    protected fun paperBackground() {
        setBackgroundColor(Theme.paper)
    }

    /**
     * 统一取页面 HTML —— 双通道：
     * 1) 先查短缓存（同一帖子/板块在 60 秒内重复进入直接命中，不再走网络）；
     * 2) 再走 OkHttp（快，正常几百毫秒）；
     * 3) 一旦被 Cloudflare JS 挑战、或拿到空壳页/非 200，自动降级到隐藏 WebView：
     *    真浏览器内核能把挑战跑完，也带着用户已登录的 Cookie。
     *
     * 速度关键：以前只要 Cookie 里没有 cf_clearance 就无条件先走 WebView（真实渲染整页，
     * 动辄数秒）；现在改成 OkHttp 优先，只有真的被拦过才让 WebView 接手，
     * 并且被拦后的 3 分钟内后续请求直接走 WebView，不重复空跑。
     */
    protected fun loadHtml(
        url: String,
        forceRefresh: Boolean = false,
        onHtml: (String) -> Unit,
        onFail: (Throwable) -> Unit
    ) {
        if (!forceRefresh) {
            val cached = HiHttp.cachedPage(url)
            if (cached != null) {
                onHtml(cached)
                return
            }
        }
        if (HiHttp.justChallenged() && !HiHttp.hasCfClearance()) {
            fallbackWeb(url, onHtml, onFail, IOException("刚被 Cloudflare 拦截"))
            return
        }
        Async.run(
            work = { HiHttp.get(url, HiUrls.baseUrl) },
            onSuccess = { result ->
                val body = result.body
                if (result.code == 200 && body.length > 600 && !isChallengeHtml(body)) {
                    HiHttp.putPage(url, body)
                    onHtml(body)
                } else {
                    fallbackWeb(url, onHtml, onFail, IOException("HTTP " + result.code))
                }
            },
            onError = { t ->
                fallbackWeb(url, onHtml, onFail, t)
            }
        )
    }

    private fun fallbackWeb(
        url: String,
        onHtml: (String) -> Unit,
        onFail: (Throwable) -> Unit,
        okhttpReason: Throwable? = null
    ) {
        Async.post {
            host.webFetch(url) { html ->
                when {
                    html == null -> onFail(IOException("页面未能载入"))
                    isChallengeHtml(html) -> onFail(CfChallengeException(url))
                    html.length <= 600 -> onFail(IOException("页面内容为空"))
                    else -> onHtml(html)
                }
            }
        }
    }

    companion object {
        /** 判断拿到的是不是 Cloudflare 的挑战/拦截页 —— 绝不能当成正文去解析 */
        fun isChallengeHtml(html: String): Boolean {
            val head = html.take(4000)
            return head.contains("Just a moment", true) ||
                head.contains("Attention Required", true) ||
                head.contains("cf-browser-verification") ||
                head.contains("_cf_chl_opt") ||
                head.contains("cf-chl-widget") ||
                head.contains("challenge-platform") ||
                head.contains("Enable JavaScript and cookies to continue", true)
        }
    }
}

/**
 * 通用纵向滚动页面（墨水屏模式下为整屏翻页）。
 */
abstract class ScrollPage(host: MainActivity) : Page(host) {

    /** 整页翻页：默认对所有界面都开启（墨水屏习惯），与是否 E-Ink 配色无关 */
    protected val pager: EinkPager? = if (Prefs.pagedTurn) EinkPager(host) else null
    protected val scroller: ScrollView = pager ?: ScrollView(host)
    protected val body = LinearLayout(host)

    /**
     * 下拉刷新阈值：60dp，只按屏幕密度换算，**不乘「框间距」设置**——
     * 否则把框间距调到 200% 时阈值会变成 120dp，得拖很久才响应。
     */
    protected val pullThresholdPx: Int
        get() = (60f * resources.displayMetrics.density).toInt().coerceAtLeast(60)

    /**
     * 「已在顶部还继续下拉」的回调，由具体页面按需实现（默认不处理）。
     *
     * @param distancePx 本次手势从按下点算起的纵向位移（px），必然 ≥ pullThresholdPx
     * @return true 表示手势被本页消费（不再翻页/回上一页）；false 则继续走原有翻页逻辑
     */
    protected open fun onPullDownRefresh(distancePx: Int): Boolean = false

    /** 非整屏翻页模式（普通 ScrollView）下的下拉判定状态 */
    private var plainDownY = 0f
    private var plainPulled = false

    init {
        paperBackground()
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dpD(14f), dpD(10f), dpD(14f), dpD(18f))
        scroller.isFillViewport = true
        scroller.isVerticalScrollBarEnabled = false
        scroller.overScrollMode = View.OVER_SCROLL_NEVER
        scroller.isSmoothScrollingEnabled = false
        scroller.addView(
            body,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        scroller.setOnScrollChangeListener { _, _, y, oldY, _ ->
            if (y > oldY + dpD(24f)) host.onPageTurnedForward()
            else if (y < oldY - dpD(24f)) host.onPageTurnedBackward()
            chrome()
        }
        pager?.onBoundary = { dir ->
            if (dir > 0) {
                if (hasNextChunk()) onReachedEnd()
            } else {
                if (hasPrevChunk()) onReachedStart()
            }
        }
        // 下拉刷新与整屏翻页共存：只有「已在顶部继续下拉」才走刷新，
        // 其余纵向滑动仍然由 EinkPager 整屏翻页，互不干扰。
        pager?.onPullDown = { dy -> onPullDownRefresh(dy) }
        if (pager == null) {
            scroller.setOnTouchListener { _, ev -> handlePlainPull(ev) }
        }
        attachFull(scroller)
    }

    /**
     * 普通滚动模式下的下拉判定：顶部（scrollY <= 0）且下滑超过阈值才问页面要不要刷新；
     * 一旦被消费，本次手势后续的 MOVE/UP 全部吞掉，避免顺带触发点击或滚动。
     */
    private fun handlePlainPull(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                plainDownY = ev.y
                plainPulled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (plainPulled) return true
                val dy = ev.y - plainDownY
                if (dy >= pullThresholdPx && scroller.scrollY <= 0) {
                    if (onPullDownRefresh(dy.toInt())) {
                        plainPulled = true
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (plainPulled) {
                    plainPulled = false
                    return true
                }
            }
        }
        return false
    }

    /** 墨水屏：整屏位移；普通模式：留一点重叠便于衔接 */
    protected val screenStep: Int
        get() = if (Theme.eink) scroller.height.coerceAtLeast(dpD(120f))
        else (scroller.height - dpD(16f)).coerceAtLeast(dpD(120f))

    /** 还有没有"下一批内容"（列表下一页 / 帖子下一页） */
    open fun hasNextChunk(): Boolean = false
    open fun hasPrevChunk(): Boolean = false
    open fun onReachedEnd() {}
    open fun onReachedStart() {}

    override fun pageText(): String {
        val p = pager ?: return ""
        if (p.pageCount() <= 1) return ""
        return (p.pageIndex() + 1).toString() + "/" + p.pageCount()
    }

    override fun canPrev(): Boolean {
        val p = pager ?: return scroller.scrollY > dpD(4f) || hasPrevChunk()
        return p.prevPossible() || hasPrevChunk()
    }

    override fun canNext(): Boolean {
        if (pager == null) {
            val content = body.height
            if (content <= 0) return hasNextChunk()
            return content - scroller.height - scroller.scrollY > dpD(4f) || hasNextChunk()
        }
        return pager.nextPossible() || hasNextChunk()
    }

    override fun onPrev() {
        val p = pager
        if (p != null) {
            if (!p.prevPage() && hasPrevChunk()) onReachedStart()
            return
        }
        val target = (scroller.scrollY - screenStep).coerceAtLeast(0)
        scroller.scrollTo(0, target)
        host.onPageTurnedBackward()
        if (target <= 0 && hasPrevChunk()) onReachedStart()
    }

    override fun onNext() {
        val p = pager
        if (p != null) {
            if (!p.nextPage() && hasNextChunk()) onReachedEnd()
            return
        }
        val max = (body.height - scroller.height).coerceAtLeast(0)
        val target = (scroller.scrollY + screenStep).coerceAtMost(max)
        scroller.scrollTo(0, target)
        host.onPageTurnedForward()
        if (target >= max && hasNextChunk()) onReachedEnd()
    }

    protected fun clearBody() {
        body.removeAllViews()
        scroller.scrollTo(0, 0)
    }

    protected fun addBlock(view: View) {
        body.addView(view)
    }

    /**
     * 把某个子视图滚到屏幕顶部（提醒定位用）。
     * 普通模式直接对齐该视图；墨水屏整屏模式向下取整到一个整屏边界，
     * 保证目标行完整落在屏首，不会只露出半行。
     */
    protected fun scrollToView(target: View) {
        scroller.post {
            val y = yOf(target)
            val p = pager
            val dest = if (p != null) {
                val s = p.height.coerceAtLeast(1)
                ((y / s) * s).coerceAtMost(p.maxScroll())
            } else {
                y.coerceAtMost((body.height - scroller.height).coerceAtLeast(0))
            }
            scroller.scrollTo(0, dest.coerceAtLeast(0))
            chrome()
        }
    }

    /** 视图相对 body 顶部的纵坐标 */
    private fun yOf(v: View): Int {
        var y = 0
        var cur: View? = v
        var guard = 0
        while (cur != null && cur !== body && guard < 12) {
            y += cur.top
            val parent = cur.parent
            cur = if (parent is View) parent else null
            guard++
        }
        return y
    }

    /** 页面内的提示条（加载 / 错误 / Cloudflare） */
    protected fun noticeView(text: String, actionLabel: String? = null, action: (() -> Unit)? = null): LinearLayout {
        val wrapper = Ui.column(host)
        wrapper.setPadding(0, dpD(6f), 0, dpD(6f))

        val box = Ui.column(host)
        box.setPadding(dpD(12f), dpD(12f), dpD(12f), dpD(12f))
        box.background = Ui.card(host, stroke = true, fill = false)

        val tv = Ui.text(host, text, Theme.smallSize + 1, Theme.secondary)
        box.addView(tv)

        if (actionLabel != null && action != null) {
            val btn = Ui.text(host, actionLabel, Theme.smallSize + 1, Theme.ink, bold = true)
            btn.setPadding(0, dpD(10f), 0, dpD(2f))
            btn.isClickable = true
            btn.setOnClickListener { action() }
            box.addView(btn)
        }
        wrapper.addView(box)
        return wrapper
    }

    protected fun loadingView(): TextView {
        val tv = Ui.text(host, "载入中…", Theme.smallSize + 1, Theme.muted)
        tv.setPadding(0, dpD(24f), 0, 0)
        return tv
    }
}

/**
 * 墨水屏整页翻页容器 —— 完全按墨水屏习惯设计：
 * 1) 手指上下滑动不做"跟手滚动"，而是直接整屏翻一页；位移量恒等于一屏高度，
 *    不会出现半页内容、半行字，也没有拖动残影；
 * 2) 无平滑动画，位移一次性完成，只触发一次面板刷新；
 * 3) 纵向滑动之外的触摸（点击、横向滑动）仍然放行给子视图，链接和按钮可正常点。
 */
class EinkPager(context: Context) : ScrollView(context) {

    private var pageStep = 0
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var turned = false

    /** 已经翻到边界还要继续翻时回调：+1 下一页边界，-1 上一页边界 */
    var onBoundary: ((Int) -> Unit)? = null

    /**
     * 已经在最顶端还继续下拉时的回调，参数为从按下点算起的纵向位移（px）。
     * 返回 true 表示这次手势归它所有（不再触发"回上一页"）；返回 false 则照旧走翻页边界逻辑。
     */
    var onPullDown: ((Int) -> Boolean)? = null

    /** 下拉刷新阈值：60dp，只按屏幕密度换算，不受「框间距」设置影响 */
    private val pullThreshold: Int
        get() = (60f * resources.displayMetrics.density).toInt().coerceAtLeast(60)

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isSmoothScrollingEnabled = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pageStep = h
    }

    private fun step(): Int = if (pageStep > 0) pageStep else height

    fun maxScroll(): Int = ((getChildAt(0)?.height ?: 0) - height).coerceAtLeast(0)

    /** 当前屏序号：以"滚动位置落在哪一屏"为准，避免半像素误差 */
    fun pageIndex(): Int {
        val s = step()
        if (s <= 0) return 0
        return (scrollY + s / 2) / s
    }

    fun pageCount(): Int {
        val s = step()
        val child = getChildAt(0)?.height ?: 0
        if (s <= 0 || child <= 0) return 1
        return ((child + s - 1) / s).coerceAtLeast(1)
    }

    fun nextPossible(): Boolean {
        if (step() <= 0) return false
        return scrollY < maxScroll() - 2
    }

    fun prevPossible(): Boolean {
        if (step() <= 0) return false
        return scrollY > 2
    }

    /** 返回 false 表示已经到最后一页 */
    fun nextPage(): Boolean {
        if (!nextPossible()) {
            onBoundary?.invoke(1)
            return false
        }
        val s = step()
        if (s <= 0) return false
        scrollTo(0, (scrollY + s).coerceAtMost(maxScroll()))
        return true
    }

    /** 返回 false 表示已经在第一页顶部 */
    fun prevPage(): Boolean {
        if (!prevPossible()) {
            onBoundary?.invoke(-1)
            return false
        }
        val s = step()
        if (s <= 0) return false
        scrollTo(0, (scrollY - s).coerceAtLeast(0))
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                turned = false
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (turned) return true
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.4f) {
                    // 已在最顶端（向上无可滚）时的下拉：先攒位移，够 60dp 再决定动作。
                    // 页面接了刷新就消费手势；不接（比如还停在第 3 页）就照旧回上一页。
                    if (dy > 0 && scrollY <= 2) {
                        if (dy >= pullThreshold) {
                            turned = true
                            val cb = onPullDown
                            if (cb == null || !cb(dy.toInt())) prevPage()
                            cancelChild(ev)
                            return true
                        }
                        // 未到阈值：本次手势既不翻页也不刷新，等后续 MOVE 继续攒
                        return true
                    }
                    turned = true
                    if (dy < 0) nextPage() else prevPage()
                    cancelChild(ev)
                    return true
                }
                // 纵向移动不驱动滚动：墨水屏上"跟手漂移"只会留下残影和半行文字。
                // 这里不向子视图补 cancel，保证轻触/点击仍能正常命中按钮。
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (turned) {
                    turned = false
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun cancelChild(ev: MotionEvent) {
        try {
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        } catch (ignored: Exception) {
        }
    }
}
