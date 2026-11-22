package com.marvis.hipda.ui.pages

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.marvis.hipda.data.Prefs
import com.marvis.hipda.net.HiApi
import com.marvis.hipda.net.HiHttp
import com.marvis.hipda.net.HiUrls
import com.marvis.hipda.ui.MainActivity
import com.marvis.hipda.ui.Theme
import com.marvis.hipda.ui.Ui
import com.marvis.hipda.ui.dpD
import com.marvis.hipda.util.Async

/** 登录页：账号直连 + 内嵌浏览器两条通道 */
class LoginPage(host: MainActivity) : ScrollPage(host) {

    override val title: String = "登录"

    private lateinit var nameInput: EditText
    private lateinit var passInput: EditText
    private lateinit var statusView: android.widget.TextView

    init {
        render()
    }

    private fun render() {
        clearBody()

        body.addView(
            Ui.text(host, "HiPDA", Theme.titleSize + 6, Theme.ink, bold = true)
                .apply { setPadding(0, dpD(6f), 0, dpD(4f)) }
        )

        val logged = Prefs.isLoggedIn()
        body.addView(
            Ui.text(
                host,
                if (logged) "当前已登录：" + Prefs.username.ifBlank { "浏览器会话" }
                else "登录后可查看交易区、发现等受限板块，以及我的帖子、收藏与短消息。",
                Theme.smallSize,
                Theme.muted
            ).apply { setPadding(0, 0, 0, dpD(14f)) }
        )

        // ── 主推：内嵌浏览器登录
        val webBtn = Ui.text(host, "用内嵌浏览器登录（推荐）", Theme.bodySize, Theme.paper, bold = true, gravity = Gravity.CENTER)
        webBtn.background = Ui.solid(host, Theme.ink, 4f)
        webBtn.isClickable = true
        webBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpD(48f)
        )
        webBtn.setOnClickListener {
            host.startCloudflareCheck(HiUrls.loginFormHash)
        }
        body.addView(webBtn)

        body.addView(
            Ui.text(
                host,
                "在打开的真实网页里输入账号密码登录，看到页面右上角出现自己的用户名后，" +
                    "点顶部「完成」回到软件即可，登录态会自动带上。若要过人机验证 / 图形验证码，也只能走这条路。",
                Theme.smallSize - 1,
                Theme.muted
            ).apply { setPadding(0, dpD(8f), 0, dpD(18f)) }
        )

        // ── 次选：账号直连
        body.addView(Ui.vsep(host, Theme.line))
        body.addView(labelOf("账号密码登录（在网页里自动填入并提交，验证码可就地输入）"))

        nameInput = input("用户名", InputType.TYPE_CLASS_TEXT)
        nameInput.setText(Prefs.username)
        body.addView(nameInput)

        passInput = input("密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        body.addView(passInput)

        statusView = Ui.text(host, "", Theme.smallSize, Theme.muted)
        statusView.setPadding(0, dpD(12f), 0, dpD(6f))
        body.addView(statusView)

        val loginBtn = Ui.text(host, "登 录", Theme.bodySize, Theme.ink, bold = true, gravity = Gravity.CENTER)
        loginBtn.background = Ui.card(host, stroke = true, fill = false)
        loginBtn.isClickable = true
        loginBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpD(46f)
        ).apply { topMargin = dpD(4f) }
        loginBtn.setOnClickListener { doLogin() }
        body.addView(loginBtn)

        if (logged) {
            val outBtn = Ui.text(host, "退出登录", Theme.smallSize + 1, Theme.muted, gravity = Gravity.CENTER)
            outBtn.isClickable = true
            outBtn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpD(44f)
            ).apply { topMargin = dpD(14f) }
            outBtn.setOnClickListener {
                Prefs.clearAccount()
                HiHttp.cookies.clear()
                host.top?.refresh()
                host.refreshChrome()
                render()
            }
            body.addView(outBtn)
        }
    }

    private fun labelOf(text: String): View {
        val tv = Ui.text(host, text, Theme.smallSize, Theme.muted)
        tv.setPadding(dpD(2f), dpD(14f), 0, dpD(8f))
        return tv
    }

    private fun input(hint: String, inputType: Int): EditText {
        val et = EditText(host)
        et.hint = hint
        et.inputType = inputType
        et.setSingleLine(true)
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Theme.bodySize)
        et.setTextColor(Theme.body)
        et.setHintTextColor(Theme.muted)
        et.background = Ui.card(host, stroke = true, fill = false)
        et.setPadding(dpD(12f), dpD(10f), dpD(12f), dpD(10f))
        et.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpD(8f) }
        return et
    }

    /**
     * 不再用 OkHttp 直连提交表单：logging.php 常被 Cloudflare 挑战，
     * 取不到 formhash 就会报"未获取到登录参数"。改为把账号密码交给内嵌浏览器，
     * 由站点自己的 JS 校验与提交，验证码也可以直接在网页里输入。
     */
    private fun doLogin() {
        val user = nameInput.text.toString().trim()
        val pass = passInput.text.toString()
        if (user.isBlank() || pass.isBlank()) {
            statusView.text = "请填写用户名与密码"
            return
        }
        statusView.text = "正在打开登录页，自动填入账号并提交…"
        host.startWebLogin(user, pass)
    }
}
