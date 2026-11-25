---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: f704a8a7f741e45ee2c1d03a2be26832_467a96bae62611f1bfe1525400dc9a98
    ReservedCode1: h1PqqJCSlTppENNA9LFwCYp6vLncnzUriMW/us2elzg9c0kkRPB2kZDqE1TrhLFtDTThmlp/XH3UVGVjiLMQ476DGnSSAGD1OGwNHb/P5I+j9WbXOku8ii/S+Ir1ytTPVrEWj5b11q//TYJkCSz3bJ0C2CPrrW4nZ2b8PQljWzXIAuefP2XFRzmPmGw=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: f704a8a7f741e45ee2c1d03a2be26832_467a96bae62611f1bfe1525400dc9a98
    ReservedCode2: h1PqqJCSlTppENNA9LFwCYp6vLncnzUriMW/us2elzg9c0kkRPB2kZDqE1TrhLFtDTThmlp/XH3UVGVjiLMQ476DGnSSAGD1OGwNHb/P5I+j9WbXOku8ii/S+Ir1ytTPVrEWj5b11q//TYJkCSz3bJ0C2CPrrW4nZ2b8PQljWzXIAuefP2XFRzmPmGw=
---

# HiPDA Eink

HiPDA 论坛（[www.4d4y.com](https://www.4d4y.com/forum/)）的第三方 Android 客户端，界面按墨水屏阅读习惯重新设计：纯灰阶、无动画、整屏翻页、局部刷新。

- 包名：`com.marvis.hipda`
- 当前版本：**1.4.1**（versionCode 11）
- minSdk 26 / targetSdk 34 / compileSdk 34
- 技术栈：Kotlin + 代码构建视图（无布局 XML）、OkHttp、Jsoup、AndroidX
- 非官方作品，与 HiPDA 站点无隶属关系，仅用于个人阅读便利

## 下载

| 渠道 | 地址 |
| --- | --- |
| GitHub Releases | [最新版本](https://github.com/asuplive/HiPDA-Eink/releases/latest) |
| 蓝奏云（国内，不限速） | [https://wwr.lanzoui.com/b01i6194kb](https://wwr.lanzoui.com/b01i6194kb) 提取码 **6ma8** |

安装前请允许「安装未知来源应用」。APK 为 debug 签名，仅供自用，勿用于任何商业用途。

## 功能

| 模块 | 说明 |
| --- | --- |
| 版块列表 | 首屏板块与站点实时同步，站点上隐藏的板块不会出现 |
| 主题列表 | 分页读取标题、作者、回复/查看数，支持下拉刷新与翻页 |
| 帖子详情 | 楼层正文、作者、时间、楼层号；上滑到底自动加载下一页；内嵌图片点击看大图，可整体关闭图片显示以提速 |
| 发帖 / 回复 | 支持新主题与引用回复 |
| 我的主题 / 我的回复 | 菜单页直达个人帖子列表 |
| 短消息 | 私信列表与对话页，打开即同步站点已读状态 |
| 提醒 | 帖子消息、系统消息、好友消息三类分频道查看，支持「全部标为已读」 |
| 顶栏信封 | 有未读时右上角出现红色信封（与左侧 Hi 方块等高对齐，无动效）；点一下即消失，只有真来了新消息才会再次出现 |
| 后台提醒 | AlarmManager 低频轮询（最短 15 分钟，可选更长），未读增加时才发系统通知；Android 13+ 需授予通知权限，被拒则退化为角标提示 |
| 设置 | 显示 / 翻页 / 网络 / 账号四组设置，含深色模式、E-Ink 模式、图片开关、翻页按键等 |
| 登录 | 内嵌浏览器走站点原生登录（含 Cloudflare 校验），登录后自动同步 Cookie 并复核登录态 |

## 墨水屏适配

- **E-Ink 模式**：全部配色退化为纯灰阶，圆角与阴影归零，卡片改为 1px 描边
- **整屏翻页**：上下滑动整屏翻页、无动画，底部显示「‹ 上屏 / 下屏 ›」翻页栏
- **智能全刷**：连续翻屏 N 次后插入一次整屏闪刷，缓解残影（N 可选 4/8/12/16 或关闭）
- **按键翻页**：音量 +/− 默认上/下翻页，另支持方向键、PageUp/PageDown，可在设置中关闭
- **局部刷新**：菜单角标、提醒信封只重绘自身区域，不做整页刷新

## 构建

需要 JDK 17、Android SDK（compileSdk 34）、Gradle 8.9：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
gradle assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。若使用 Android Studio，直接打开工程即可，无需额外配置。

## 更新日志

### v1.4.1

- 顶栏信封收窄为 24×28dp，与左侧 Hi 方块等高对齐
- 取消信封跳动动效，未读时静态红信封，无未读时整只隐藏（不再显示灰色线稿）
- 未读口径补齐「好友消息」，与站点提醒页三类频道一致
- 点击信封即消失：这批未读记为已确认，刷新不会重新点亮，只有新消息才再出现
- 点击信封按内容分流：有提醒进提醒页，只有短消息直接进短消息栏目

### v1.4.0

- 新增消息提醒：顶栏信封 + 菜单角标，帖子/系统/好友三类分频道
- 新增后台低频轮询与系统通知（未读增加时才提醒）
- 新增开机自动恢复轮询闹钟

### v1.3.5

- 新增按键翻页：音量键默认上/下翻，支持方向键与 PageUp/PageDown

### v1.3.4

- 应用名统一为 HiPDA，新增应用图标

### v1.3.3

- 首个公开版本：板块 / 主题 / 帖子、登录、发帖回复、短消息、E-Ink 模式

## 致谢

界面参考 `kindle-hipda-redesign` 设计稿；数据接口与页面解析思路参考开源项目 [GreenSkinMonster/hipda](https://github.com/GreenSkinMonster/hipda)。

## 免责声明

本项目仅用于个人学习与阅读便利，所有内容版权归 HiPDA 论坛及原作者所有。请勿用于商业用途，使用产生的一切后果由使用者自行承担。
*（内容由AI生成，仅供参考）*
