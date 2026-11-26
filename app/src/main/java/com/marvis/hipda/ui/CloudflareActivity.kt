package com.marvis.hipda.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.marvis.hipda.net.HiHttp
import com.marvis.hipda.net.HiUrls

/**
 * Cloudflare 人机验证页。
 * 站点的 JS 挑战必须由真实浏览器内核执行，这里用 WebView 打开目标页，
 * 用户手动点一下校验框；验证成功后把 WebView 的 Cookie 同步回 OkHttp，
 * 后台请求即可正常访问，后续长期免验证。
 */
class CloudflareActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "extra_url"

        private const val EXTRA_USER = "extra_user"
        private const val EXTRA_PASS = "extra_pass"

        fun intent(context: Context, url: String): Intent =
            Intent(context, CloudflareActivity::class.java).putExtra(EXTRA_URL, url)

        /** 自动填表登录：打开站点登录页，自动填入账号密码并提交 */
        fun loginIntent(context: Context, user: String, pass: String): Intent =
            Intent(context, CloudflareActivity::class.java)
                .putExtra(EXTRA_URL, HiUrls.loginFormHash)
                .putExtra(EXTRA_USER, user)
                .putExtra(EXTRA_PASS, pass)
    }

    private lateinit var webView: WebView
    private lateinit var hintView: android.widget.TextView
    private lateinit var doneButton: android.widget.TextView
    private var targetUrl: String = ""
    private var loginUser: String = ""
    private var loginPass: String = ""
    private var fillTries = 0
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetUrl = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: HiUrls.home
        loginUser = intent?.getStringExtra(EXTRA_USER).orEmpty()
        loginPass = intent?.getStringExtra(EXTRA_PASS).orEmpty()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.paper)

        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(Theme.subtle)
        topBar.setPadding(dp(14), dp(10), dp(10), dp(10))

        hintView = android.widget.TextView(this)
        hintView.text = if (loginUser.isNotBlank()) "正在打开登录页…" else "正在检查站点安全校验…"
        hintView.setTextColor(Theme.secondary)
        hintView.textSize = Theme.smallSize
        topBar.addView(hintView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        doneButton = android.widget.TextView(this)
        doneButton.text = "完成"
        doneButton.setTextColor(Theme.paper)
        doneButton.gravity = Gravity.CENTER
        doneButton.textSize = Theme.smallSize
        doneButton.setBackgroundColor(Theme.ink)
        doneButton.setPadding(dp(18), dp(8), dp(18), dp(8))
        doneButton.setOnClickListener { finishWithResult() }
        topBar.addView(doneButton)
        root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        webView = WebView(this)
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // 必须与 OkHttp 用同一个 UA：Cloudflare 的 cf_clearance 与浏览器 UA 绑定，
        // 站点也会按 UA 决定输出桌面模板还是 WAP 模板。
        settings.userAgentString = HiHttp.UA
        webView.setBackgroundColor(Theme.paper)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val target = url ?: return
                HiHttp.cookies.pushToWebView(target)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val page = url ?: return
                HiHttp.cookies.syncFromWebView(page)
                HiHttp.cookies.flushCookieManager()
                if (loginUser.isNotBlank() && !finished) {
                    if (hasAuthCookie(page)) {
                        hintView.text = "登录成功，正在返回…"
                        autoFinish()
                        return
                    }
                    if (fillTries < 2) {
                        fillTries++
                        fillAndSubmit()
                    }
                }
                inspect(page)
            }
        }

        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        HiHttp.cookies.pushToWebView(targetUrl)
        webView.loadUrl(targetUrl)
    }

    private fun hasAuthCookie(url: String): Boolean {
        val raw = CookieManager.getInstance().getCookie(url) ?: return false
        return raw.contains("cdb_auth=")
    }

    /** 把账号密码自动填进站点原生登录表单并提交：formhash 由站点自己生成，不再"取不到登录参数" */
    private fun fillAndSubmit() {
        val user = org.json.JSONObject.quote(loginUser)
        val pass = org.json.JSONObject.quote(loginPass)
        val js = "(function(u,p){try{" +
            "var f=document.querySelector('form[name=\"login\"]')||document.querySelector('#loginform')" +
            "||document.querySelector('form[action*=\"logging.php\"]');" +
            "if(!f)return 'nofield';" +
            "var n=f.querySelector('input[name=\"username\"]'),w=f.querySelector('input[name=\"password\"]');" +
            "if(!n||!w)return 'nofield';" +
            "n.value=u;w.value=p;" +
            "var q=f.querySelector('input[name=\"questionid\"]');if(q){q.value='0';}" +
            "var a=f.querySelector('input[name=\"answer\"]');if(a){a.value='';}" +
            "var s=f.querySelector('input[name=\"cookietime\"]');if(s){s.value='2592000';}" +
            "f.submit();return 'ok';}catch(e){return 'err';}})(" + user + "," + pass + ")"
        hintView.text = "正在填入账号并提交…"
        webView.evaluateJavascript(js) { result ->
            val r = result?.trim()?.trim('"').orEmpty()
            if (r == "nofield") hintView.text = "请在下方网页里手动完成登录，登录后点「完成」"
        }
    }

    private fun autoFinish() {
        if (finished) return
        finished = true
        webView.postDelayed({ finishWithResult() }, 600)
    }

    private fun inspect(url: String) {
        val onChallenge = url.contains("cdn-cgi") || url.contains("__cf_chl") || url.contains("challenge")
        webView.evaluateJavascript("document.title") { title ->
            val clean = title?.replace("\"", "")?.trim().orEmpty()
            val stillChallenge = onChallenge ||
                clean.contains("Just a moment", true) ||
                clean.contains("Attention Required", true) ||
                clean.contains("请稍候", true) ||
                clean.contains("Checking your browser", true)
            hintView.text = when {
                stillChallenge -> "请在上方页面点选验证框，通过后点「完成」"
                hasAuthCookie(url) -> "已登录，点「完成」返回软件"
                loginUser.isNotBlank() -> "若页面显示已登录，点「完成」即可；需要验证码时请就地输入"
                else -> "页面已能正常打开；若刚登录过，点「完成」即可同步登录态"
            }
        }
    }

    private fun finishWithResult() {
        val current = webView.url ?: targetUrl
        HiHttp.cookies.syncFromWebView(current)
        HiHttp.cookies.flushCookieManager()
        com.marvis.hipda.data.Prefs.cfPassedAt = System.currentTimeMillis()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, current))
        finish()
    }

    override fun onDestroy() {
        HiHttp.cookies.syncFromWebView(webView.url ?: targetUrl)
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (ignored: Throwable) {
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
