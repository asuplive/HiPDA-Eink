package com.marvis.hipda.ui.pages

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.net.HiHttp
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD

/**
 * 网页查看页。
 * 墨水屏模式下用注入脚本接管网页滚动：手指上下滑动 = 网页整屏翻一页，
 * 不做跟手滚动，避免网页内容半屏错位与残影。
 */
class WebViewPage(
    host: MainActivity,
    override val title: String,
    private val url: String
) : Page(host) {

    private lateinit var webView: WebView
    private lateinit var progress: android.widget.TextView

    /**
     * 落地前先把地址修干净。
     *
     * 线上出现过「回复短消息」直接报 net::ERR_CLEARTEXT_NOT_PERMITTED 错误页，
     * 根因是调用方把标题当成了 URL（拼出 http://汉字串/）。这类地址在安卓 9+ 的
     * WebView 里必然打不开，所以这里做两道兜底：站点是 https 就把明文地址升到 https，
     * 完全不成形的地址干脆不交给 WebView，直接给一句人话提示。
     */
    private val safeUrl: String = normalizeUrl(url)

    private fun normalizeUrl(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("http://", ignoreCase = true) &&
            Prefs.server.lowercase().startsWith("https://")
        ) {
            t = "https://" + t.substring(7)
        }
        return t
    }

    private fun looksLikeUrl(t: String): Boolean =
        t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)

    init {
        build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build() {
        paperBackground()
        val col = Ui.column(host)

        val bar = Ui.row(host)
        bar.setPadding(dpD(12f), dpD(8f), dpD(12f), dpD(8f))

        val back = Ui.text(host, "‹ 返回", Theme.smallSize + 1, Theme.ink)
        back.setPadding(dpD(2f), dpD(6f), dpD(12f), dpD(6f))
        back.isClickable = true
        back.setOnClickListener { host.pop() }
        bar.addView(back)

        val t = Ui.text(host, title, Theme.smallSize, Theme.muted)
        Ui.singleLine(t)
        bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        progress = Ui.text(host, "", Theme.smallSize - 1, Theme.muted, gravity = Gravity.END or Gravity.CENTER_VERTICAL)
        bar.addView(progress)

        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(Ui.vsep(host, Theme.line))

        if (!looksLikeUrl(safeUrl)) {
            // 地址不成立就别渲染 WebView，免得用户只看到一张「网页无法打开」的系统错误页
            val tip = Ui.text(host, "这个入口没有有效链接：" + url, Theme.bodySize, Theme.body)
            tip.setPadding(dpD(16f), dpD(20f), dpD(16f), dpD(10f))
            col.addView(tip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            attachFull(col)
            return
        }

        webView = WebView(host)
        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        // 与 OkHttp 同一 UA：站点据此输出桌面模板，帖子列表才完整
        s.userAgentString = HiHttp.UA
        webView.setBackgroundColor(Theme.paper)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                progress.text = "载入中"
            }

            override fun onPageFinished(view: WebView?, u: String?) {
                progress.text = ""
                val target = u ?: safeUrl
                // WebView 里登录 / 验证后立刻把 Cookie 同步给后台请求
                HiHttp.cookies.syncFromWebView(target)
                HiHttp.cookies.flushCookieManager()
                if (Theme.eink) view?.evaluateJavascript(EINK_PAGER_JS, null)
            }
        }

        col.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        attachFull(col)

        HiHttp.cookies.pushToWebView(safeUrl)
        webView.loadUrl(safeUrl)
    }

    override fun onBack(): Boolean {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    override fun pageText(): String = ""

    override fun refresh() {
        if (::webView.isInitialized) webView.reload()
    }

    companion object {
        /**
         * 网页整屏翻页：
         * 1) 纵向滑动时阻止浏览器默认的跟手滚动；
         * 2) 松手后按滑动方向把页面位移对齐到「当前屏 ± 一屏」，无动画；
         * 3) 点击、横向滑动不受影响，链接照常可点。
         */
        private const val EINK_PAGER_JS = """
(function(){
  if (window.__hiPagerInstalled) return; window.__hiPagerInstalled = true;
  var sy = 0, sx = 0, dragging = false;
  function H(){ return window.innerHeight || document.documentElement.clientHeight || 800; }
  window.addEventListener('touchstart', function(e){
    if (e.touches.length !== 1) return;
    sy = e.touches[0].clientY; sx = e.touches[0].clientX; dragging = false;
  }, {passive: true});
  window.addEventListener('touchmove', function(e){
    if (e.touches.length !== 1) return;
    var dy = e.touches[0].clientY - sy;
    var dx = e.touches[0].clientX - sx;
    if (Math.abs(dy) > Math.abs(dx) * 1.3 && Math.abs(dy) > 10) {
      e.preventDefault(); dragging = true;
    }
  }, {passive: false});
  window.addEventListener('touchend', function(e){
    if (!dragging) return;
    dragging = false;
    var dy = (e.changedTouches[0].clientY - sy);
    if (Math.abs(dy) < 12) return;
    var h = H();
    var y = window.pageYOffset || document.documentElement.scrollTop || 0;
    var target = dy < 0 ? (Math.floor(y / h) + 1) * h : (Math.ceil(y / h) - 1) * h;
    var max = Math.max(
      (document.documentElement.scrollHeight || document.body.scrollHeight) - h, 0);
    var top = Math.max(0, Math.min(target, max));
    try { window.scrollTo({top: top, left: 0, behavior: 'instant'}); }
    catch (err) { window.scrollTo(0, top); }
  }, {passive: true});
})();
"""

        @Suppress("unused")
        private val UNUSED_VIEW = View::class.java
    }
}
