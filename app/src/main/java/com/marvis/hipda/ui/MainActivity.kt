package com.marvis.hipda.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.marvis.hipda.data.MessageCenter
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.net.HiApi
import com.marvis.hipda.net.HiHttp
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.ui.pages.ForumPage
import com.marvis.hipda.ui.pages.LoginPage
import com.marvis.hipda.ui.pages.MenuPage
import com.marvis.hipda.ui.pages.NoticePage
import com.marvis.hipda.ui.pages.Page
import com.marvis.hipda.ui.pages.PmListPage
import com.marvis.hipda.util.Async
import com.marvis.hipda.util.MsgPoll

class MainActivity : AppCompatActivity() {

    private lateinit var titleView: TextView
    /** 顶栏标题是否处于展开态：收起为单行，展开后顶栏自身向下伸高最多三行 */
    private var titleExpanded = false
    /** 上一次刷新顶栏时的页面对象与标题文字：换页或标题变了就收回单行，避免带到其他页面 */
    private var chromePage: Page? = null
    private var chromeTitle: CharSequence = ""
    private lateinit var counterView: TextView
    private lateinit var contentHost: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var bottomSep: View
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var backBtn: TextView
    private lateinit var pageBtn: TextView
    private lateinit var column: LinearLayout
    private lateinit var rootFrame: FrameLayout
    private lateinit var envelope: EnvelopeView

    private var msgListener: ((MessageCenter.State) -> Unit)? = null

    private val stack = ArrayList<Page>()
    val top: Page? get() = stack.lastOrNull()

    private var turnCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        buildUi()
        bindMessageCenter()
        push(ForumPage(this), false)
        openNoticeIfAsked(intent)
    }

    /** 点系统通知进来时直接落到提醒页 */
    private fun openNoticeIfAsked(from: Intent?) {
        if (from?.getBooleanExtra(EXTRA_OPEN_NOTICE, false) == true) {
            from.removeExtra(EXTRA_OPEN_NOTICE)
            push(NoticePage(this), false)
        }
    }

    /** 应用已在后台/前台时点通知：走这里，不重建 Activity */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        openNoticeIfAsked(intent)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        // 前台刷新：60 秒内复用上一次结果，来回切页面不会反复打站点
        MessageCenter.refresh()
        envelope.setCount(MessageCenter.state.total)
    }

    /**
     * 信封点击落点：
     * 点下去先把信封消掉（这批未读视为已确认），再按有没有提醒分流 ——
     * 有帖子/系统/好友提醒 → 提醒页；只有短消息 → 直接进短消息栏目。
     */
    private fun openMessages() {
        if (!Prefs.isLoggedIn()) {
            Toast.makeText(this, "登录后才能查看消息", Toast.LENGTH_SHORT).show()
            push(LoginPage(this))
            return
        }
        val s = MessageCenter.state
        val toNotice = s.notice > 0
        envelope.setCount(0)
        MessageCenter.acknowledge()
        if (toNotice) push(NoticePage(this)) else push(PmListPage(this))
    }

    override fun onStop() {
        super.onStop()
        MsgPoll.schedule(this)
    }

    private fun bindMessageCenter() {
        if (msgListener != null) return
        val l: (MessageCenter.State) -> Unit = { st ->
            // 顶栏可能还没搭好（onCreate 早期就会注册监听），此时跳过即可，onResume 会补一次
            if (!isFinishing && !isDestroyed && ::envelope.isInitialized) envelope.setCount(st.total)
        }
        msgListener = l
        MessageCenter.addListener(l)
    }

    private fun unbindMessageCenter() {
        msgListener?.let { MessageCenter.removeListener(it) }
        msgListener = null
    }

    private fun applySystemBars() {
        window.statusBarColor = Theme.paper
        window.navigationBarColor = Theme.paper
        var flags = 0
        if (!Theme.dark) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun buildUi() {
        rootFrame = FrameLayout(this)
        rootFrame.setBackgroundColor(Theme.paper)

        val column = LinearLayout(this)
        this.column = column
        column.orientation = LinearLayout.VERTICAL
        column.setBackgroundColor(Theme.paper)
        rootFrame.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // ── 顶栏：Hi 方块 + 标题 + 页码
        val topBar = Ui.row(this)
        topBar.setPadding(dpD(12f), dpD(8f), dpD(12f), dpD(8f))

        val logo = Ui.text(this, "Hi", 14f, Theme.paper, bold = true, gravity = Gravity.CENTER)
        logo.background = Ui.solid(this, Theme.ink, 3f)
        logo.layoutParams = LinearLayout.LayoutParams(dpD(34f), dpD(28f))
        logo.setOnClickListener { push(MenuPage(this), true) }
        topBar.addView(logo)

        val gap = View(this)
        topBar.addView(gap, LinearLayout.LayoutParams(dpD(10f), 1))

        titleView = Ui.text(this, "", Theme.titleSize, Theme.ink, bold = true)
        // 顶栏标题收起态固定单行：字数超出时末尾收成「…」（这三点就是展开入口）。
        // 点标题本身（含三点）让顶栏自己向下伸高，在顶栏内部铺开最多三行完整标题；
        // 再点一下回到单行。不支持展开的页面（expandTitle 为空）点了没有反应。
        Ui.singleLine(titleView)
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        titleView.isClickable = true
        titleView.setOnClickListener { toggleTitleExpand() }
        topBar.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        // ── 右上角红色信封：只在有未读时出现（无未读时整只隐藏）；点击区 26dp × 28dp 与左侧 Hi 方块齐平，
        // 信封本身按扁宽比例居中绘制（比视图形矮），高度不抢视觉
        envelope = EnvelopeView(this)
        envelope.isClickable = true
        envelope.setOnClickListener { openMessages() }
        topBar.addView(envelope, LinearLayout.LayoutParams(dpD(26f), dpD(28f)))

        counterView = Ui.text(this, "", Theme.smallSize, Theme.muted, gravity = Gravity.END or Gravity.CENTER_VERTICAL)
        topBar.addView(counterView)

        column.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(Ui.vsep(this, Theme.line))

        // ── 内容区
        contentHost = FrameLayout(this)
        column.addView(
            contentHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // ── 底部导航栏（E-Ink 模式专用）：上屏 / 下屏 / 返回 三个按钮，可单独隐藏；
        //    左手习惯时整排镜像为 返回 / 下屏 / 上屏
        bottomSep = Ui.vsep(this, Theme.line)
        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.gravity = Gravity.CENTER_VERTICAL
        bottomBar.setBackgroundColor(Theme.paper)

        prevBtn = navButton("◂ 上屏", Gravity.START or Gravity.CENTER_VERTICAL)
        prevBtn.setOnClickListener {
            if (top?.canPrev() == true) {
                top?.onPrev()
                refreshChrome()
            }
        }

        pageBtn = Ui.text(this, "", Theme.smallSize + 1, Theme.muted, gravity = Gravity.CENTER)
        pageBtn.setPadding(dpD(8f), dpD(12f), dpD(8f), dpD(12f))
        Ui.singleLine(pageBtn)

        nextBtn = navButton("下屏 ▸", Gravity.END or Gravity.CENTER_VERTICAL)
        nextBtn.setOnClickListener {
            if (top?.canNext() == true) {
                top?.onNext()
                refreshChrome()
            }
        }

        // 返回：三角向上，与上屏 / 下屏那两个小三角同款；点击回上一页
        backBtn = navButton("▴ 返回", Gravity.START or Gravity.CENTER_VERTICAL)
        backBtn.setOnClickListener { goBack() }

        layoutBottomBar()

        column.addView(bottomSep, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpD(1f)))
        column.addView(
            bottomBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        setContentView(rootFrame)
        applyAdaptiveWidth()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val page = top
                if (page != null && page.onBack()) return
                if (!pop()) finish()
            }
        })
    }

    /**
     * 自适应宽度：手机竖屏铺满；平板/横屏时把正文限制在 660dp 以内并居中，
     * 避免长行阅读时视线跨度太大。旋转屏幕时在 onConfigurationChanged 里重算。
     */
    private fun applyAdaptiveWidth() {
        val w = resources.configuration.screenWidthDp
        val side = if (w > 660) (w - 660) / 2 else 0
        val pad = dpD(side.toFloat())
        column.setPadding(pad, 0, pad, 0)
        column.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
        applyAdaptiveWidth()
    }

    private fun navButton(label: String, gravity: Int): TextView {
        val tv = Ui.text(this, label, Theme.smallSize + 1, Theme.ink, gravity = gravity)
        // 底栏最多并排三个按钮，左右内边距收窄并强制单行，避免字号调大后换行撑高
        tv.setPadding(dpD(10f), dpD(12f), dpD(10f), dpD(12f))
        // 只剩一两个按钮时按钮收成文字宽度，给个最小宽度保证点击区域不至于太小
        tv.minimumWidth = dpD(84f)
        Ui.singleLine(tv)
        tv.isClickable = true
        return tv
    }

    /**
     * 按「左右手习惯」+「三按钮显隐」重排底部导航栏。页码（pageBtn）始终参与排布：
     *
     * 三个按钮（右手）：上屏 | 页码 | 下屏 ⟶ 返回，最后两个按钮之间留一道空档，免得挤在一起；
     * 三个按钮（左手）：返回 ⟶ 下屏 | 页码 | 上屏，空档加在左边两个之间（整排镜像）。
     * 两个按钮：两个按钮分置左右两端，页码居中。
     * 一个按钮：右手放在最右边，左手放在最左边，页码占另一侧。
     */
    private fun layoutBottomBar() {
        bottomBar.removeAllViews()

        val navs = ArrayList<TextView>(3)
        if (Prefs.leftHanded) {
            if (Prefs.navBackVisible) navs.add(backBtn)
            if (Prefs.navNextVisible) navs.add(nextBtn)
            if (Prefs.navPrevVisible) navs.add(prevBtn)
        } else {
            if (Prefs.navPrevVisible) navs.add(prevBtn)
            if (Prefs.navNextVisible) navs.add(nextBtn)
            if (Prefs.navBackVisible) navs.add(backBtn)
        }

        fun stretch(weight: Float) {
            bottomBar.addView(Ui.spacer(this, weight))
        }

        fun nav(v: View) {
            bottomBar.addView(
                v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        fun tight(v: View) {
            bottomBar.addView(
                v,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        /** 固定空档：给相邻两个按钮之间留呼吸空间 */
        fun gap() {
            bottomBar.addView(View(this), LinearLayout.LayoutParams(dpD(18f), 1))
        }

        when (navs.size) {
            3 -> {
                if (Prefs.leftHanded) {
                    nav(navs[0]); gap(); nav(navs[1]); tight(pageBtn); nav(navs[2])
                } else {
                    nav(navs[0]); tight(pageBtn); nav(navs[1]); gap(); nav(navs[2])
                }
            }
            2 -> {
                tight(navs[0]); stretch(1f); tight(pageBtn); stretch(1f); tight(navs[1])
            }
            1 -> {
                if (Prefs.leftHanded) {
                    tight(navs[0]); stretch(1f); tight(pageBtn); stretch(1f)
                } else {
                    stretch(1f); tight(pageBtn); stretch(1f); tight(navs[0])
                }
            }
            else -> {
                stretch(1f); tight(pageBtn); stretch(1f)
            }
        }
        bottomBar.requestLayout()
    }

    /** 设置页改完「左右手 / 按钮显隐」后即时生效，不重建界面 */
    fun refreshBottomBar() {
        if (!::bottomBar.isInitialized) return
        layoutBottomBar()
        refreshChrome()
    }

    /** 底部「返回」：与系统返回键同逻辑，但已在最底层时不做退出 */
    private fun goBack() {
        val page = top
        if (page != null && page.onBack()) {
            refreshChrome()
            return
        }
        if (stack.size > 1) pop()
    }

    fun push(page: Page, animate: Boolean = true) {
        stack.add(page)
        contentHost.removeAllViews()
        contentHost.addView(
            page,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        page.requestLayout()
        refreshChrome()
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        val removed = stack.removeAt(stack.size - 1)
        (removed.parent as? ViewGroup)?.removeView(removed)
        val next = stack.last()
        contentHost.removeAllViews()
        contentHost.addView(
            next,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        refreshChrome()
        return true
    }

    fun resetTo(page: Page) {
        val copy = ArrayList(stack)
        stack.clear()
        contentHost.removeAllViews()
        for (p in copy) (p.parent as? ViewGroup)?.removeView(p)
        push(page, false)
    }

    fun refreshChrome() {
        val page = top ?: return
        // 换页（push / pop / resetTo 后页面对象变了）或标题文字变了，就收回单行，
        // 免得上一页展开的标题跟到新页面上；同页只刷新其他信息则保持当前展开状态。
        val title = page.title
        if (page !== chromePage || title != chromeTitle) {
            chromePage = page
            chromeTitle = title
            setTitleExpanded(false)
        }
        titleView.text = title
        if (titleExpanded) applyTitleExpandedLineSpacing()
        val label = page.pageText()
        counterView.text = label
        pageBtn.text = label
        if (::envelope.isInitialized) envelope.setCount(MessageCenter.state.total)
        if (Theme.eink) {
            val anyBtn = Prefs.navPrevVisible || Prefs.navNextVisible || Prefs.navBackVisible
            val showBar = anyBtn || label.isNotBlank()
            bottomBar.visibility = if (showBar) View.VISIBLE else View.GONE
            bottomSep.visibility = if (showBar) View.VISIBLE else View.GONE
            prevBtn.alpha = if (page.canPrev()) 1f else 0.3f
            nextBtn.alpha = if (page.canNext()) 1f else 0.3f
            backBtn.alpha = if (stack.size > 1) 1f else 0.3f
            counterView.visibility = View.GONE
        } else {
            bottomBar.visibility = View.GONE
            bottomSep.visibility = View.GONE
            counterView.visibility = View.VISIBLE
        }
    }

    /** 点顶栏标题：有完整标题就把顶栏自己撑高到最多三行；expandTitle 为空的页面点了没反应 */
    private fun toggleTitleExpand() {
        if (!::titleView.isInitialized) return
        val full = top?.expandTitle ?: ""
        if (full.isBlank()) return
        setTitleExpanded(!titleExpanded)
    }

    /**
     * 切换顶栏标题的单行 / 展开态。
     * 收起：maxLines = 1，超出收成「…」；展开：maxLines = 3，行距略收紧，顶栏随 WRAP_CONTENT 自动伸高，
     * 三行内完整显示（仍超长保留「…」兜底）。顶栏是 CENTER_VERTICAL 的横排，Hi 方块 / 信封 / 页码自动垂直居中。
     */
    private fun setTitleExpanded(expanded: Boolean) {
        if (!::titleView.isInitialized) return
        titleExpanded = expanded
        if (expanded) {
            titleView.maxLines = 3
            titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        } else {
            Ui.singleLine(titleView)
        }
        applyTitleExpandedLineSpacing()
    }

    /** 展开态用略紧凑的行距（lineGap 的 0.8 倍，不小于 0）；收起态回到常规行距 */
    private fun applyTitleExpandedLineSpacing() {
        if (!::titleView.isInitialized) return
        val gap = lineGap()
        titleView.setLineSpacing(if (titleExpanded) (gap * 0.8f).coerceAtLeast(0f) else gap, 1f)
    }

    /** 翻页后按设定触发一次整屏"全刷"，减少墨水屏残影 */
    fun onPageTurnedForward() {
        turnCounter++
        if (Prefs.flashOnPage) flashScreen()
        val every = Prefs.fullRefreshPages
        if (Prefs.smartRefresh && every > 0 && turnCounter % every == 0) {
            flashScreen()
        }
    }

    fun onPageTurnedBackward() {
        turnCounter++
    }

    fun flashScreen() {
        val overlay = View(this)
        overlay.setBackgroundColor(if (Theme.dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        contentHost.addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        overlay.postDelayed({ contentHost.removeView(overlay) }, 90)
    }

    /** 设置里切换 E-Ink 模式后重建界面 */
    fun applyThemeChange() {
        recreate()
    }

    /** 行间距调整后，把栈内已建好的页面文本按新行距原地刷新（不重建界面、不丢导航状态） */
    fun refreshLineSpacingNow() {
        for (p in stack) Ui.applyLineSpacing(p)
    }

    // ────────────────────────────────────────────────────────────────
    // 翻页按键：音量键 / 方向键 / PageUp / PageDown
    // ────────────────────────────────────────────────────────────────

    /** 上一次按下时被我们消费掉的翻页键，抬起时要一并吞掉，免得漏给系统 */
    private var consumedKeyCode = 0

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Prefs.keyTurn) {
            val dir = PageKeys.resolve(event.keyCode, Prefs.keyPrevCode, Prefs.keyNextCode)
            if (dir != 0 && shouldCaptureKey(event.keyCode)) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (turnPageByKey(dir)) {
                            consumedKeyCode = event.keyCode
                            return true
                        }
                        // 翻不动：音量键交还系统（保留调音量），其余翻页键仍然吞掉，避免焦点乱跑
                        if (!PageKeys.isVolume(event.keyCode)) return true
                    }
                    KeyEvent.ACTION_UP -> {
                        if (event.keyCode == consumedKeyCode) {
                            consumedKeyCode = 0
                            return true
                        }
                        if (!PageKeys.isVolume(event.keyCode)) return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 光标在输入框里时不抢方向键，保证正常编辑文字 */
    private fun shouldCaptureKey(code: Int): Boolean {
        if (PageKeys.isVolume(code)) return true
        return currentFocus !is android.widget.EditText
    }

    /** 按键翻一屏，返回 true 表示真的翻了 */
    private fun turnPageByKey(dir: Int): Boolean {
        val page = top ?: return false
        return if (dir > 0) {
            if (!page.canNext()) return false
            page.onNext()
            refreshChrome()
            true
        } else {
            if (!page.canPrev()) return false
            page.onPrev()
            refreshChrome()
            true
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 内嵌浏览器（Cloudflare 验证 / 登录）回传处理
    // ────────────────────────────────────────────────────────────────

    /**
     * 用户在浏览器里登录完成、点「完成」后必须真正校验一次登录态。
     * 顺序很关键：
     * 1) 先把 WebView 的 Cookie（cdb_auth / cdb_sid / cf_clearance）灌进 OkHttp；
     * 2) 先用本地 Cookie 直接判定 —— 这一步不联网，不会因为 Cloudflare 挡住而误判成"未登录"；
     * 3) 本地判定不成立时再用 OkHttp 联网复核；
     * 4) 任一成立就落库标记已登录并刷新当前页。
     */
    private val cfLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val url = result.data?.getStringExtra("extra_url") ?: HiUrls.baseUrl
        HiHttp.cookies.syncFromWebView(url)
        HiHttp.cookies.flushCookieManager()

        val local = HiApi.localLoginState(url)
        if (local.loggedIn) {
            onSessionReady(local.name)
            return@registerForActivityResult
        }

        // 本地 Cookie 还没有会话：用隐藏 WebView（带同一份 Cookie）抓一次首页复核。
        // 这条通道能过 Cloudflare，所以不会再出现"网页里明明登录了、软件说未登录"。
        webFetch(HiUrls.home) { html ->
            val state = if (html != null) {
                runCatching { HiApi.applyLoginHtml(html, HiUrls.home) }.getOrDefault(HiApi.localLoginState(url))
            } else {
                runCatching { HiApi.resolveLoginState(url) }.getOrDefault(local)
            }
            if (state.loggedIn) {
                onSessionReady(state.name)
            } else {
                Toast.makeText(
                    this,
                    "还没读到登录态。请在网页里完成登录（看到右上角出现自己的用户名），再点「完成」",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 开启「系统通知」时申请一次通知权限（Android 13+）。
     * 被拒也不影响主流程：消息提醒退化为应用内角标 + 信封。
     */
    fun askNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT < 33) return
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (t: Throwable) {
        }
    }

    private val notifyLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "未授予通知权限，消息只在应用内提示。", Toast.LENGTH_LONG).show()
        }
    }

    private fun onSessionReady(name: String) {
        Prefs.cfPassedAt = System.currentTimeMillis()
        Prefs.webSession = true
        if (top is LoginPage && stack.size > 1) pop()
        top?.refresh()
        refreshChrome()
        val who = name.ifBlank { Prefs.username }.ifBlank { "当前账号" }
        Toast.makeText(this, "已登录：" + who, Toast.LENGTH_SHORT).show()
    }

    fun startCloudflareCheck(url: String? = null) {
        cfLauncher.launch(CloudflareActivity.intent(this, url ?: HiUrls.home))
    }

    /** 账号密码登录：交给内嵌浏览器自动填表提交，绕开 formhash 取不到的问题 */
    fun startWebLogin(user: String, pass: String) {
        cfLauncher.launch(CloudflareActivity.loginIntent(this, user, pass))
    }

    // ────────────────────────────────────────────────────────────────
    // OkHttp 被 Cloudflare 挡住时的兜底通道：
    // 用一个不可见 WebView 真正把页面渲染出来，再把 DOM 交给解析器。
    // 版块列表 / 帖子详情都走这条通道，这是"点进板块看不到帖子"的修复点。
    // ────────────────────────────────────────────────────────────────

    private val fetchQueue = ArrayDeque<Pair<String, (String?) -> Unit>>()
    private var fetchBusy = false
    private var fetchView: WebView? = null

    fun webFetch(url: String, callback: (String?) -> Unit) {
        if (isFinishing || isDestroyed) {
            callback(null)
            return
        }
        fetchQueue.addLast(url to callback)
        pumpFetch()
    }

    private fun pumpFetch() {
        if (fetchBusy) return
        val next = fetchQueue.removeFirstOrNull() ?: return
        fetchBusy = true
        val url = next.first
        val callback = next.second
        val wv = ensureFetchView()

        var done = false
        var retried = false

        fun finish(html: String?) {
            if (done) return
            done = true
            try {
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                wv.loadUrl("about:blank")
            } catch (ignored: Throwable) {
            }
            fetchBusy = false
            callback(html)
            pumpFetch()
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, u: String?) {
                if (done) return
                val target = u ?: url
                HiHttp.cookies.syncFromWebView(target)
                view?.evaluateJavascript(GRAB_DOM_JS) { raw ->
                    val html = decodeJs(raw)
                    if (html != null && HiApi.isChallengeHtml(html) && !retried) {
                        retried = true
                        view.postDelayed({ runCatching { view.loadUrl(url) } }, 1500)
                    } else {
                        finish(if (html != null && html.length > 200) html else null)
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    view?.evaluateJavascript(GRAB_DOM_JS) { raw ->
                        val html = decodeJs(raw)
                        finish(if (html != null && html.length > 200) html else null)
                    }
                }
            }
        }
        wv.postDelayed({ finish(null) }, 25000)
        HiHttp.cookies.pushToWebView(url)
        runCatching { wv.loadUrl(url) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureFetchView(): WebView {
        fetchView?.let { return it }
        val wv = WebView(this)
        val s: WebSettings = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = HiHttp.UA
        wv.setBackgroundColor(Theme.paper)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webChromeClient = WebChromeClient()
        // 放到屏幕外：既不遮挡界面，也不会抢走触摸事件
        val lp = FrameLayout.LayoutParams(dpD(360f), dpD(640f))
        lp.leftMargin = -dpD(3000f)
        lp.topMargin = -dpD(3000f)
        rootFrame.addView(wv, lp)
        fetchView = wv
        return wv
    }

    private fun decodeJs(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return null
        return try {
            org.json.JSONTokener(raw).nextValue() as? String
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        unbindMessageCenter()
        fetchQueue.clear()
        try {
            fetchView?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.stopLoading()
                it.destroy()
            }
        } catch (ignored: Throwable) {
        }
        fetchView = null
        super.onDestroy()
    }

    companion object {
        /** 通知点进来时带上的标记：启动后直接打开提醒页 */
        const val EXTRA_OPEN_NOTICE = "extra_open_notice"

        /** 抓取渲染后的完整 DOM */
        private const val GRAB_DOM_JS =
            "(function(){try{return document.documentElement.outerHTML||document.body.innerHTML||'';}catch(e){return '';}})()"
    }
}
