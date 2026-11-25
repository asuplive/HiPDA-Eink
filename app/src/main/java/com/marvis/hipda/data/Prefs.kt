package com.marvis.hipda.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局偏好设置。
 * E-Ink 模式是核心开关：开启后界面退化为纯灰阶、无圆角无阴影无动画、按整屏分页翻页。
 */
object Prefs {

    private const val NAME = "hipda_prefs"
    private lateinit var sp: SharedPreferences

    const val DEFAULT_SERVER = "https://www.4d4y.com"
    const val DEFAULT_IMAGE_HOST = "https://img02.4d4y.com"

    /** 帖子图片加载方式 */
    const val IMAGE_AUTO = 0
    const val IMAGE_TAP = 1
    const val IMAGE_NONE = 2

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    /** E-Ink 模式：灰阶 / 无动画 / 分页翻页 */
    var einkMode: Boolean
        get() = sp.getBoolean("eink_mode", true)
        set(value) = sp.edit().putBoolean("eink_mode", value).apply()

    /** 深色（黑底白字）主题，等价截图菜单里的「切换主题」 */
    var darkMode: Boolean
        get() = sp.getBoolean("dark_mode", false)
        set(value) = sp.edit().putBoolean("dark_mode", value).apply()

    /** 正文字号，单位 sp */
    var fontSize: Int
        get() = sp.getInt("font_size", 16).coerceIn(10, 30)
        set(value) = sp.edit().putInt("font_size", value.coerceIn(10, 30)).apply()

    /** 框间距缩放百分比（50~200，100 为默认）：作用于全站 dp 留白 */
    var gapScale: Int
        get() = sp.getInt("gap_scale", 100).coerceIn(50, 200)
        set(value) = sp.edit().putInt("gap_scale", value.coerceIn(50, 200)).apply()

    /** 行间距缩放百分比（60~250，100 为默认）：只作用于多行文字的行间留白，不影响字号与框间距 */
    var lineSpacing: Int
        get() = sp.getInt("line_spacing", 100).coerceIn(60, 250)
        set(value) = sp.edit().putInt("line_spacing", value.coerceIn(60, 250)).apply()

    /** 是否显示帖子内图片（旧开关，保留兼容；新逻辑统一看 imageMode） */
    var showImages: Boolean
        get() = imageMode == IMAGE_AUTO
        set(value) {
            imageMode = if (value) IMAGE_AUTO else IMAGE_TAP
        }

    /**
     * 帖子图片加载方式：
     * IMAGE_AUTO = 自动下载（进帖即加载）
     * IMAGE_TAP  = 点击下载（先占位，点一下才加载，省流量、翻页更快）
     * IMAGE_NONE = 不显示（只留图注）
     * 默认「点击下载」：既不会一进帖就下载一堆图拖慢打开速度，也不会像旧默认那样完全看不到图。
     */
    var imageMode: Int
        get() {
            val raw = sp.getInt("image_mode", -1)
            if (raw in 0..2) return raw
            return IMAGE_TAP
        }
        set(value) = sp.edit().putInt("image_mode", value.coerceIn(0, 2)).apply()

    fun imageModeLabel(): String = when (imageMode) {
        IMAGE_AUTO -> "自动下载"
        IMAGE_TAP -> "点击下载"
        else -> "不显示"
    }

    // ────────────────────────────────────────────────────────────────
    // 帖子设置：头像 / 帖子状态标注 / 作者资料（默认只有「帖子状态」开启）
    // ────────────────────────────────────────────────────────────────

    /** 是否显示用户头像：开启后板块列表与帖子详情都显示，默认关（墨水屏上省地方） */
    var showAvatar: Boolean
        get() = sp.getBoolean("post_avatar", false)
        set(value) = sp.edit().putBoolean("post_avatar", value).apply()

    /** 帖子状态标注：置顶 / 精华 / 高亮等，以 [状态] 前缀加粗显示，默认开 */
    var showStatus: Boolean
        get() = sp.getBoolean("post_status", true)
        set(value) = sp.edit().putBoolean("post_status", value).apply()

    /** 楼层作者 UID，默认关 */
    var showUid: Boolean
        get() = sp.getBoolean("post_uid", false)
        set(value) = sp.edit().putBoolean("post_uid", value).apply()

    /** 楼层作者帖子数，默认关 */
    var showPostCount: Boolean
        get() = sp.getBoolean("post_count", false)
        set(value) = sp.edit().putBoolean("post_count", value).apply()

    /** 楼层作者积分，默认关 */
    var showCredits: Boolean
        get() = sp.getBoolean("post_credits", false)
        set(value) = sp.edit().putBoolean("post_credits", value).apply()

    /** 楼层作者注册时间，默认关 */
    var showRegTime: Boolean
        get() = sp.getBoolean("post_regtime", false)
        set(value) = sp.edit().putBoolean("post_regtime", value).apply()

    /** 楼层个性签名，默认关 */
    var showSignature: Boolean
        get() = sp.getBoolean("post_signature", false)
        set(value) = sp.edit().putBoolean("post_signature", value).apply()

    /** 楼主回帖时显示「楼主」标签，默认开 */
    var showOpBadge: Boolean
        get() = sp.getBoolean("post_op_badge", true)
        set(value) = sp.edit().putBoolean("post_op_badge", value).apply()

    /** 每隔多少页做一次全刷（E-Ink 残影控制） */
    var fullRefreshPages: Int
        get() = sp.getInt("full_refresh_pages", 6)
        set(value) = sp.edit().putInt("full_refresh_pages", value.coerceIn(1, 30)).apply()

    /** 刷屏模式：智能局部刷 + 定期全刷 */
    var smartRefresh: Boolean
        get() = sp.getBoolean("smart_refresh", true)
        set(value) = sp.edit().putBoolean("smart_refresh", value).apply()

    /** 每次翻页前闪一下全屏（部分墨水屏设备可减少残影） */
    var flashOnPage: Boolean
        get() = sp.getBoolean("flash_on_page", false)
        set(value) = sp.edit().putBoolean("flash_on_page", value).apply()

    /** 阅读字体：true = 宋体/等宽阅读体，false = 系统无衬线 */
    var serifFont: Boolean
        get() = sp.getBoolean("serif_font", false)
        set(value) = sp.edit().putBoolean("serif_font", value).apply()

    var username: String
        get() = sp.getString("username", "").orEmpty()
        set(value) = sp.edit().putString("username", value).apply()

    /** 已 md5 处理过的密码 */
    var password: String
        get() = sp.getString("password", "").orEmpty()
        set(value) = sp.edit().putString("password", value).apply()

    var uid: String
        get() = sp.getString("uid", "").orEmpty()
        set(value) = sp.edit().putString("uid", value).apply()

    /**
     * 内嵌浏览器登录标记。
     * 站点开 Cloudflare 后，账号密码直连登录拿不到通行 cookie，
     * 只能靠 WebView 走一次真实登录；此时没有本地保存的密码，靠该标记表示已登录。
     */
    var webSession: Boolean
        get() = sp.getBoolean("web_session", false)
        set(value) = sp.edit().putBoolean("web_session", value).apply()

    var autoLogin: Boolean
        get() = sp.getBoolean("auto_login", true)
        set(value) = sp.edit().putBoolean("auto_login", value).apply()

    var server: String
        get() = sp.getString("server", DEFAULT_SERVER).orEmpty().ifBlank { DEFAULT_SERVER }
        set(value) = sp.edit().putString("server", value.trim().trimEnd('/')).apply()

    var imageHost: String
        get() = sp.getString("image_host", DEFAULT_IMAGE_HOST).orEmpty().ifBlank { DEFAULT_IMAGE_HOST }
        set(value) = sp.edit().putString("image_host", value.trim().trimEnd('/')).apply()

    /**
     * 整页翻页（墨水屏习惯）：所有页面上下滑动都整屏翻，不做跟手滚动。
     * 默认开启，设置页可关。
     */
    var pagedTurn: Boolean
        get() = sp.getBoolean("paged_turn", true)
        set(value) = sp.edit().putBoolean("paged_turn", value).apply()

    /**
     * 按键翻页总开关，默认开启：
     * 音量+/-、方向键 ↑↓←→、PageUp/PageDown 都能翻一屏。
     */
    var keyTurn: Boolean
        get() = sp.getBoolean("key_turn", true)
        set(value) = sp.edit().putBoolean("key_turn", value).apply()

    /** 自定义上翻页键（KeyEvent keyCode），0 = 未指定，只走内置键位 */
    var keyPrevCode: Int
        get() = sp.getInt("key_prev_code", 24) // KEYCODE_VOLUME_UP
        set(value) = sp.edit().putInt("key_prev_code", value).apply()

    /** 自定义下翻页键（KeyEvent keyCode），0 = 未指定，只走内置键位 */
    var keyNextCode: Int
        get() = sp.getInt("key_next_code", 25) // KEYCODE_VOLUME_DOWN
        set(value) = sp.edit().putInt("key_next_code", value).apply()

    // ────────────────────────────────────────────────────────────────
    // 底部导航栏（E-Ink 模式）：上屏 / 下屏 / 返回 三个按钮，可单独隐藏
    // ────────────────────────────────────────────────────────────────

    /** 底部栏是否显示「上屏」按钮 */
    var navPrevVisible: Boolean
        get() = sp.getBoolean("nav_prev_visible", true)
        set(value) = sp.edit().putBoolean("nav_prev_visible", value).apply()

    /** 底部栏是否显示「下屏」按钮 */
    var navNextVisible: Boolean
        get() = sp.getBoolean("nav_next_visible", true)
        set(value) = sp.edit().putBoolean("nav_next_visible", value).apply()

    /** 底部栏是否显示「返回」按钮 */
    var navBackVisible: Boolean
        get() = sp.getBoolean("nav_back_visible", true)
        set(value) = sp.edit().putBoolean("nav_back_visible", value).apply()

    /**
     * 左右手习惯：false = 右手（上屏、下屏、返回），
     * true = 左手（整排镜像：返回、下屏、上屏）。
     */
    var leftHanded: Boolean
        get() = sp.getBoolean("left_handed", false)
        set(value) = sp.edit().putBoolean("left_handed", value).apply()

    /** 站点首页解析出的板块清单缓存（JSON），保证板块跟站点一致 */
    var forumIndexJson: String
        get() = sp.getString("forum_index_json", "").orEmpty()
        set(value) = sp.edit().putString("forum_index_json", value).apply()

    var forumIndexAt: Long
        get() = sp.getLong("forum_index_at", 0L)
        set(value) = sp.edit().putLong("forum_index_at", value).apply()

    /** Cloudflare 验证通过时间戳，用于 UI 提示 */
    var cfPassedAt: Long
        get() = sp.getLong("cf_passed_at", 0L)
        set(value) = sp.edit().putLong("cf_passed_at", value).apply()

    /** 私人消息未读数（界面角标直接用，避免每次进菜单都联网） */
    var msgPmUnread: Int
        get() = sp.getInt("msg_pm_unread", 0)
        set(value) = sp.edit().putInt("msg_pm_unread", value.coerceAtLeast(0)).apply()

    /** 帖子提醒（回复 / @）未读数 */
    var msgThreadUnread: Int
        get() = sp.getInt("msg_thread_unread", 0)
        set(value) = sp.edit().putInt("msg_thread_unread", value.coerceAtLeast(0)).apply()

    /** 好友消息未读数 */
    var msgFriendUnread: Int
        get() = sp.getInt("msg_friend_unread", 0)
        set(value) = sp.edit().putInt("msg_friend_unread", value.coerceAtLeast(0)).apply()

    /**
     * 已确认基线：用户点过顶栏信封（或进过提醒 / 短消息页）时站点上的未读总数。
     * 顶栏信封只在「站点未读总数 > 该基线」时显示，点完立即消失，不会被随后的一次刷新重新点亮。
     */
    var msgAckTotal: Int
        get() = sp.getInt("msg_ack_total", 0)
        set(value) = sp.edit().putInt("msg_ack_total", value.coerceAtLeast(0)).apply()

    /** 见过的最大提醒 ID：用来判断"哪些是新的" */
    var msgMaxId: Long
        get() = sp.getLong("msg_max_id", 0L)
        set(value) = sp.edit().putLong("msg_max_id", value).apply()

    /** 上次成功取到提醒的时间戳 */
    var msgCheckedAt: Long
        get() = sp.getLong("msg_checked_at", 0L)
        set(value) = sp.edit().putLong("msg_checked_at", value).apply()

    /** 已打开的提醒 ID（逗号分隔，最多保留 200 个） */
    var msgSeenIds: String
        get() = sp.getString("msg_seen_ids", "").orEmpty()
        set(value) = sp.edit().putString("msg_seen_ids", value).apply()

    /** 后台轮询间隔（分钟），0 = 只在打开 App 时刷新，不进后台 */
    var msgPollMinutes: Int
        get() = sp.getInt("msg_poll_minutes", 30).coerceIn(0, 240)
        set(value) = sp.edit().putInt("msg_poll_minutes", value.coerceIn(0, 240)).apply()

    /** 后台发现新提醒时发系统通知 */
    var msgNotify: Boolean
        get() = sp.getBoolean("msg_notify", true)
        set(value) = sp.edit().putBoolean("msg_notify", value).apply()

    /** 后台通知用：上次发出系统通知时的未读总数，避免同一批消息重复通知 */
    var msgNotifiedTotal: Int
        get() = sp.getInt("msg_notified_total", 0)
        set(value) = sp.edit().putInt("msg_notified_total", value.coerceAtLeast(0)).apply()

    fun isLoggedIn(): Boolean =
        webSession || (username.isNotBlank() && password.isNotBlank())

    fun clearLogin() = clearAccount()

    fun clearAccount() {
        sp.edit().remove("username").remove("password").remove("uid").remove("web_session").apply()
    }
}
