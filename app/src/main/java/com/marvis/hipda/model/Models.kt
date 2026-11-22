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
    val maxPage: Int = 1
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
    val notice: String = ""
)

data class PostPage(
    val tid: String,
    val title: String,
    val posts: List<PostItem>,
    val page: Int,
    val lastPage: Int,
    val fid: String = ""
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
    val hidden: Map<String, String> = emptyMap()
) {
    /** 表单地址里带 action=newthread 就是发新帖，否则是回帖 */
    val isNewThread: Boolean
        get() = action.contains("action=newthread") ||
            (tid.isBlank() && hint.contains("newthread"))

    /** 表单来源地址，用于判定发帖/回帖 */
    var hint: String = ""
}

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
    val href: String = ""
)
