package com.marvis.hipda.model

/** 板块 */
data class Forum(
    val fid: String,
    val name: String,
    val desc: String = "",
    val group: String = ""
)

/** 主题列表项 */
data class ThreadItem(
    val tid: String,
    val title: String,
    val author: String = "",
    val authorId: String = "",
    val type: String = "",
    val lastPost: String = "",
    val lastPostTime: String = "",
    val createTime: String = "",
    val replyCount: String = "",
    val viewCount: String = "",
    val sticky: Boolean = false,
    val isNew: Boolean = false,
    val maxPage: Int = 1,
    /** 特殊状态标注：置顶 / 精华 / 高亮 / 热 等，列表里以 [状态] 前缀显示（设置可关） */
    val statuses: List<String> = emptyList()
)

data class ThreadPage(
    val items: List<ThreadItem>,
    val page: Int,
    val lastPage: Int,
    val title: String = ""
)

/** 帖子正文块 */
sealed class Block {
    data class Text(val text: String) : Block()
    data class Quote(val text: String) : Block()
    data class Link(val text: String, val url: String) : Block()
    data class Image(val url: String) : Block()
}

/** 楼层 */
data class PostItem(
    val postId: String,
    val floor: Int,
    val author: String,
    val uid: String,
    val time: String,
    val blocks: List<Block>,
    val op: Boolean = false,
    val notice: String = "",
    /** 以下资料来自楼层左侧作者区，默认不显示，由「帖子设置」逐项控制 */
    val postCount: String = "",
    val credits: String = "",
    val regTime: String = "",
    val signature: String = ""
)

data class PostPage(
    val tid: String,
    val title: String,
    val posts: List<PostItem>,
    val page: Int,
    val lastPage: Int,
    val fid: String = "",
    /** 本帖楼主名与 uid：解析时确定（本页 1 楼优先，其次外部传入兜底），供翻页继续判定「楼主」 */
    val opAuthor: String = "",
    val opUid: String = ""
)

data class LoginResult(val success: Boolean, val message: String)

/**
 * 「我的帖子 / 我的回复 / 我的收藏 / 搜索结果」的单列条目。
 * 站点这几张表本来是 7 列的网页表格，手机上一屏挤不下，这里拍平成一条条记录。
 */
data class FeedItem(
    val tid: String,
    val pid: String,
    val title: String,
    val forumName: String = "",
    val author: String = "",
    val time: String = "",
    val info: String = ""
)

data class FeedPage(
    val items: List<FeedItem>,
    val page: Int,
    val lastPage: Int,
    val title: String = "",
    val empty: String = ""
)

/** 短消息会话条目 */
data class PmItem(
    val uid: String,
    val name: String,
    val time: String = "",
    val summary: String = "",
    val unread: Boolean = false
)

data class PmPage(
    val items: List<PmItem>,
    val page: Int,
    val lastPage: Int
)

/** 短消息会话里的一条消息 */
data class PmMessage(
    val name: String,
    val time: String,
    val text: String,
    val self: Boolean = false
)

/** 短消息会话内容 */
data class PmChat(
    val uid: String,
    val peer: String,
    val messages: List<PmMessage>,
    val empty: String = "",
    /** 会话页 form#pmform 里的防伪串，回复短消息提交时必须原样带回 */
    val formhash: String = "",
    /**
     * 会话页 form#pmform 的隐藏域（formhash / lastdaterange 等）原样搬运。
     * 回复就是「把这个表单的隐藏域原封不动 + 换上正文」再 POST，
     * 少带一个站点要求的字段就会被退回「来路不明」。
     */
    val replyFields: Map<String, String> = emptyMap()
)

/** 发帖 / 回帖表单规格（站点是 formhash + posttime 防伪） */
data class FormSpec(
    val action: String,
    val formhash: String,
    val posttime: String,
    val fid: String = "",
    val tid: String = "",
    val subject: String = "",
    val categories: List<Pair<String, String>> = emptyList(),
    val error: String = "",
    /** 表单里的全部隐藏域（formhash / posttime / wysiwyg / notice* 等），提交时原样带回 */
    val hidden: Map<String, String> = emptyMap(),
    /** textarea[name=message] 的预填内容：引用回复时站点会把被引用楼层塞在这里 */
    val messageText: String = ""
) {
    /** 表单地址里带 action=newthread 就是发新帖，否则是回帖 */
    val isNewThread: Boolean
        get() = action.contains("action=newthread") ||
            (tid.isBlank() && hint.contains("newthread"))

    /** 表单来源地址，用于判定发帖/回帖 */
    var hint: String = ""
}

/**
 * 报告（举报）表单规格。Discuz 的报告页是独立的一张表单：
 * form#reportform，含 select[name=reason]（举报理由下拉）+ textarea[name=message]（补充说明）+ formhash。
 * 与发帖表单不同，它不认 posttime，也不能漏掉表单里的隐藏域，所以同样整表搬运。
 */
data class ReportSpec(
    val action: String,
    val formhash: String,
    /**
     * 理由控件的字段名。本站实际是单选组 name=type（1=推荐、0=举报），
     * 不是 select[name=reason]；reason 反而是「我的意见」多行文本框。
     */
    val reasonField: String = "type",
    /** 「我的意见」输入框的字段名，本站是 reason */
    val messageField: String = "reason",
    val reasons: List<Pair<String, String>> = emptyList(),
    val hidden: Map<String, String> = emptyMap(),
    val error: String = "",
    /** 需要写进说明框的默认文字（站点通常预填「请填写举报原因」之类） */
    val messageHint: String = "",
    /** 站点默认勾选的理由值（value 字段，本站默认 1=推荐） */
    val defaultReason: String = "",
    /** 说明框上方的标题文字，站点里是 h4「我的意见」 */
    val messageLabel: String = "我的意见"
)

/** 提交结果 */
data class PostResult(val ok: Boolean, val message: String)

/**
 * 一条消息提醒：帖子回复 / @、系统消息、好友消息。
 * id 用 pid 优先，没有 pid 时退化成 tid 偏移量或内容哈希，只用于本地"看过没有"的判断。
 */
data class NoticeItem(
    val id: Long = 0L,
    val kind: Int = 0,
    val title: String = "",
    val from: String = "",
    val uid: String = "",
    val time: String = "",
    val tid: String = "",
    val pid: String = "",
    val href: String = "",
    /** 提醒原因：站点条目里的动作短语，如「回复了你发表的帖子」「被 4d4c 限时高亮」 */
    val reason: String = "",
    /** 被回复 / 被评论的那一段原文（列表里一行浅灰摘要，方便判断值不值得点开） */
    val excerpt: String = ""
)
