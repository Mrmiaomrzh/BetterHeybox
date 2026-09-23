# BetterHeybox

![BetterHeybox](https://socialify.git.ci/Mrmiaomrzh/BetterHeybox/image?font=Source+Code+Pro&forks=1&issues=1&language=1&name=1&pattern=Floating+Cogs&pulls=1&stargazers=1&theme=Auto)

增强小黑盒（Heybox）的 LSPosed 模块。

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE) [![LSPosed Module](https://img.shields.io/badge/LSPosed-Module-blue.svg)](https://github.com/LSPosed/LSPosed)

> [!CAUTION]
> **免责声明**
> - 本应用与清枫(北京)科技有限公司**无任何关联**，亦未经其授权或认可，请注意甄别来源  
> - 本项目仅供**学习与研究**小黑盒 APP 的部分技术原理，请勿用于任何非法用途  
> - 本模块会修改宿主应用的运行时行为，可能存在账号封禁、数据异常等风险，**请自行评估并承担后果**  
> - 由于项目特殊性，**禁止**将本模块用于商业用途，也不为其在商业场景下的可用性、合规性提供担保或支持  
> - 建议删除本应用及相关文件，并**禁止**在 **小黑盒 / HeyBox** 平台内发布、讨论或传播本模块  
>
> 本项目授权条款以 [`LICENSE`](LICENSE)为准，

> [!Note]
>本应用兼容 [小黑盒 1.3.393](https://github.com/Mrmiaomrzh/BetterHeybox/releases/download/v0.2.0/heybox_1.3.393.apk) 及以上版本，其他版本出现的问题不会进行处理

> [!WARNING]
> 使用免Root框架`「NPatch」`时，需要把`破解签名校验`改成`Extreme`，不然会有缺少参数闪退的问题

> [!Note]
> [Telegram 发布频道](https://t.me/Betterheybox)，欢迎加入交流

## 功能

所有功能开关均可在小黑盒「我的 → 设置 → 通用设置」中的 `BetterHeybox 设置` 入口直接打开模块面板，
开关配置存放在**小黑盒应用目录**
（`/data/data/com.max.xiaoheihe/shared_prefs/betterheybox.xml`）

模块设置面板采用**分类两级结构**：一级页只列「功能分类」入口，点进去才是该类别的全部设置项，
左上角箭头与系统返回键都回到分类页，面板刷新（改一个开关）后仍停留在当前页。

| 分类 | 包含设置 |
| --- | --- |
| 广告与内容过滤 | 广告过滤、发帖过滤、评论过滤、搜索 / 游戏库页面精简、分享净化 |
| 界面与外观 | 液态玻璃底栏、底部导航栏隐藏、消息红点、实验性功能 |
| 浏览与下载 | 解除复制、网页、视频下载 |
| 动态推送 | 关注对象 / 话题 / 关键词、抓取范围、提醒方式、第三方推送、测试与调试 |
| 每日任务 | 自动完成三个分享任务 |
| 通用与备份 | 通知权限、屏蔽更新、日志、配置备份、关于 |

### 广告过滤

| 类型 |
|------|
| 屏蔽开屏广告 |
| 屏蔽信息流广告 |
| 屏蔽气泡广告 |
| 屏蔽角标广告 |
| 屏蔽推广贴|

### 页面精简

开关在设置页「广告与内容过滤」的「搜索页精简 / 游戏库精简」里，默认关闭、切换即时生效：

| 开关 | 隐藏内容 |
|------|----------|
| 隐藏搜索页横幅 | 搜索栏正下方的横幅推荐|
| 隐藏「搜索发现」 | 搜索页的搜索发现标题与推荐列表 |
| 隐藏「黑盒热榜」 | 搜索页的热榜标签页榜单与热词 / 热议卡片 |
| 隐藏游戏库横幅 | 游戏库顶端的三图横幅推荐 |
| 隐藏游戏库小分区 | 黑盒商城、小程序等入口卡片 |
| 隐藏游戏库推荐分区 | 「为你推荐」等分区标题与内容卡 |
| 自定义隐藏类型 | 勾选要隐藏的服务端 type|
| 隐藏指定入口卡片 | 逐个勾选要隐藏的入口卡片，或**长按卡片**直接隐藏 |
| 隐藏指定推荐分区 | 按分区标题逐个勾选要隐藏的分区 |
| 诊断：游戏库精简状态 | 目标解析 / Hook 安装 / 候选数 / 最近一次长按 |

- 长按游戏库入口卡片会弹小黑盒原生样式的「隐藏「XXX」？」确认框，确认即隐藏
- 只作用于搜索落地页与游戏库，搜索历史 / 搜索结果页 / 其它页面不受影响
- 以上配置纳入配置备份

### 帖子过滤（实验性）

| 过滤项 | 说明 |
|------|------|
| 屏蔽低赞帖子 | 点赞数 < 阈值即屏蔽；阈值关闭 / 1 / 5 / 10 / 20 / 50 / 100 / 200 / 500 / 1000 |
| 屏蔽低评论帖子 | 评论数 < 阈值即屏蔽；候选同上 |
| 屏蔽低收藏帖子 | 收藏数 < 阈值即屏蔽；候选同上。**部分列表（旧链社区帖、资讯流）服务端不下发收藏数，这些列表自动放行** |
| 屏蔽低等级帖 | 阈值关闭 / Lv1-Lv10；无等级数据默认放行，可再开「屏蔽无等级用户」 |
| 关键词屏蔽 | 命中标题或正文即屏蔽，一行一个，`regex:` 前缀为正则 |
| AI 标题党识别 | 默认关闭；OpenAI 协议接口，内置 DeepSeek / Kimi / 通义 / 智谱 / OpenAI / OpenRouter / 本地模型预设，标题会发给你配置的服务商 |
| 屏蔽视频帖 | 默认关闭 |
| 屏蔽插眼评论 | #36，**默认开启**；|
| 评论关键词屏蔽 / 无意义评论 | #36，默认关闭；额外屏蔽宿主判定的无意义灌水评论，`regex:` 前缀为正则 |
| 自动清理失效收藏 | 默认关闭；打开收藏列表发现失效内容时自动发起宿主清理 |

覆盖首页推荐流、社区 Tab、话题详情、频道、游戏评测、榜单、搜索结果等列表；收藏夹 / 草稿箱不参与。改动规则后刷新列表或滚动重绑生效；配置纳入配置备份。

### 界面增强

| 功能 | 说明 |
|------|------|
| 液态玻璃底栏 | 实时折射 / 色散玻璃底栏，水滴选中动画、沉浸式小白条、颜色随背景自适应；长按首页标题栏右上角图标或设置页「通用设置」行打开调节面板（底色 / 不透明度 / 高度 / 距底偏移 / 宽度 / Tab 宽度 / 形态）；Android 12 及以下回退毛玻璃 |
| 液态玻璃提供方 | 与独立的「小黑盒液态玻璃」模块共存时可选由谁提供，避免两条玻璃叠加 |
| 底部导航栏隐藏 | 隐藏底栏 tab 项（需重启小黑盒）；剩余 tab 自动等分，选中项被隐藏时自动切换 |
| 屏蔽双列信息流 | 实验性，仅 1.3.395；首页推荐 / 话题 / 百科恢复单列 |

### 消息红点隐藏

开关在「界面与外观」的「消息红点」里，默认关闭、切换即时生效：

| 开关 | 说明 |
|------|------|
| 隐藏消息未读红点 | 打开后首页 / 社区 / 热点 / 我的 / 话题 / 频道都不再显示；底栏「消息」小红点随之消失，底栏数字角标不受影响 |
| 精简消息入口 | 消息列表里按勾选处理入口行：只藏红数字，或整行移除 |
| 隐藏红数字的入口 | 勾选只隐藏右侧红色数字的入口，默认「活动消息」「官方消息」；候选来自匹配的入口行 |
| 完整隐藏的入口 | 勾选整行从消息列表移除的入口，默认不隐藏任何入口 |
| 诊断：消息红点状态 | 开关 / 挂点 / 两个勾选集 / 已观察到的入口标题 |

### 帖子增强

| 功能 | 说明 |
|------|------|
| 解除复制 | 恢复安卓系统标准文本选择，选择手柄可跨行拖动 |
| 评论区自由复制 | #32，默认开启；长按评论的菜单不变，点「复制」后多出 `复制全部内容` / `自由复制` / `复制 @昵称`|
| 图片系统分享 | 图片长按追加「系统分享」，优先存系统相册并修正真实格式 / MIME |
| 净化分享链接 | 复制 / 分享时去掉 h_camp、h_session_id 等追踪参数，保留 link_id 等功能参数 |

### 网页浏览

| 功能 | 说明 |
|------|------|
| 浏览器重定向 | 默认关闭；内置网页外链改走系统浏览器，登录 / 授权 / 支付 / `.apk` 强制留内置 |
| 包含小黑盒域名 | 默认关闭；官方 H5 与小程序页面始终留内置（登录态靠内置 WebView 注入） |
| 重定向浏览器 | 指定用哪个浏览器打开，未设系统默认时不再弹「打开方式」 |
| 强制重定向 / 强制内置域名 | 一行一个（兼容完整链接），优先级高于默认规则 |
| 网页日志 | 默认关闭；记录内置浏览器打开的页面与标题（最近 80 条） |
| 网页 DevTools | 为内置 WebView 开启 Chrome 远程调试 |
| 打开网页 | 输入任意 http/https 地址用内置浏览器打开 |

### 视频下载

| 功能 | 说明 |
|------|------|
| 下载入口 | 视频帖右上角圆形悬浮按钮，正文 / 信息流 / 故事 / 游戏卡片均可 |
| 下载面板 | 准备 / 下载中 / 暂停 / 完成 / 失败五态；面板可关闭，后台继续下载 |
| 格式与续传 | mp4 直链与 HLS 分片；断点续传，HLS 合并后自动转封装 MP4 |
| 保存位置 | 默认相册 `Movies/BetterHeybox`，可改任意文件夹；文件名优先帖子标题，重名加序号不覆盖 |
| 通知与分享 | 通知栏显示进度 / 完成 / 失败，完成后可播放 / 分享 / 删除 |

### 每日任务

| 功能 | 说明 |
|------|------|
| 自动完成三种分享任务 | 分享任意帖子 / 游戏详情 / 游戏评价，各配一个链接；未配置的自动跳过，状态按日期跨天重置 |
| 分享渠道 | QQ / QQ空间、微信 / 朋友圈、微博；在分享面板点对应按钮并伪造成功回调，不会真的发出内容 |
| 完成后返回首页 | 默认开启，顺带清理途中打开的帖子页 |
| 清除今日打卡 | 清除今日已完成状态并立即重试 |

链接格式（3 个链接均支持）：

| 类型 | 示例 |
|------|------|
| 分享链接（带 link_id） | `https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=123456` |
| 网页链接 | `https://xiaoheihe.cn/a/123456` |
| 深链协议 | `heybox://v3/bbs/app/api/web/share?link_id=123456` |
| 游戏详情 | `https://api.xiaoheihe.cn/game/share_game_detail?appid=123456&game_type=pc` |

获取方式：小黑盒内打开帖子 / 游戏页 → 分享 → 复制链接。⚠️ 别用 `game_statistic` 这类中转 / 统计链接（会 302 到应用宝下载页，模块会拦下并跳过该步）。

### 动态推送（#26）

| 功能 | 说明 |
|------|------|
| 关注动态提醒 | 关注作者（userid 或主页链接）有新动态时提醒 |
| 关键词监控 | 一行一个，`regex:` 为正则；可只匹配标题 |
| 关注话题 | 一行一个；可「导入关注话题」或「搜索话题」加入 |
| 关键词 / 话题拉流 | 按关键词搜索、按话题取最新帖；首次只登记基线，每轮最多 5 个关键词 |
| 推荐关键词 | 取小黑盒热搜词 / 联想词，点一下加入监控 |
| 时间窗 / 检查间隔 | 30 分钟 ~ 30 天 / 5 分钟 ~ 12 小时 |
| 提醒方式 | 应用内横幅、系统通知（点击盒内打开）、第三方推送（钉钉 / WxPusher / AstrBot / 自定义 webhook） |
| 检查时机 | 打开小黑盒、宿主收到推送时搭便车、信息流命中；默认最小间隔 10 分钟 |

默认关闭，未配置监控目标时不发起任何请求；取数复用宿主 OkHttp，签名由宿主拦截器补齐。

### 通用

| 功能 | 说明 |
|------|------|
| 版本前置检测 | 仅支持 1.3.393 / 1.3.394 / 1.3.395，不匹配时提示 |
| 首启免责声明 | 首次打开小黑盒需「同意并继续」 |
| 关于 | 查看模块版本号、打开 GitHub 仓库 |
| 伪装通知权限 | 让小黑盒认为通知权限已开启，获得签到加成 |
| 屏蔽更新 | 屏蔽小黑盒更新入口 |
| 记录日志 | 把模块运行日志写入文件 |

## 技术栈

| 项 | 值 |
|----|----|
| 语言 | Java 17 |
| Hook API | `io.github.libxposed:api:102.0.0` |
| Service | `io.github.libxposed:service:102.0.0` |
| 字节码分析 | `org.luckypray:dexkit:2.2.0` |
| 液态玻璃渲染 | `com.github.QWEA0:liquidglass:90f4ea28e3`（JitPack） |
| compileSdk / targetSdk | 37 |
| minSdk | 26 |
| AGP / Gradle | 9.2.1 / 9.7.1 |
| JDK | 17+ |

## 工程结构

```
app/src/main/
├── AndroidManifest.xml          # 模块名/描述 = android:label / android:description
├── java/com/better/heybox/
│   ├── MainModule.java          # 模块入口：生命周期 + Hook 安装编排 + 共享工具
│   ├── App.java                 # Application：连接框架服务、RemotePreferences 存取
│   ├── ViewUtils.java           # 宿主视图/反射解析：findActivity / findOuter / findMethod
│   ├── ThemeUtils.java          # 共享主题工具：Monet 动态取色 / surface 色板 / 设计 token
│   ├── HeyboxPrefs.java         # 小黑盒进程本地配置存储（配置文件放小黑盒目录）
│   ├── Logs.java                # 统一日志出口（Release 只留 error）
│   ├── LogRecorder.java         # 文件日志记录器（日志开关）
│   ├── LogExport.java           # 日志导出
│   ├── Checkpoint.java          # Debug 运行检查点
│   ├── ConfigBackup.java        # 配置导入/导出（JSON）
│   ├── DexKitResolver.java      # DexKit 自动分析：小黑盒更新后自动定位原生弹窗
│   ├── GlassProvider.java       # 液态玻璃实现仲裁：与独立玻璃模块共存时选择提供方
│   ├── VideoDownloadManager.java # 视频下载：任务状态机/断点续传/HLS 分片/转封装/保存/通知
│   ├── CustomTextSelection.java # 自绘制文本选择（禁用系统选择 API）
│   ├── PreferenceReceiver.java  # 设置写回广播接收（镜像同步 RemotePreferences）
│   └── hooks/                   # 各功能 Hook 按模块拆分
│       ├── GeneralHook.java     #   通用：版本检测 / 屏蔽更新 / 伪装通知权限
│       ├── AdFilterHook.java    #   广告过滤：开屏 / 信息流 / 气泡 / 角标
│       ├── SettingsEntryHook.java # 设置页入口注入 + 内嵌设置面板
│       ├── BottomTabHook.java   #   底部导航栏隐藏（tab 名版本自适应）
│       ├── PromotePostHook.java #   推广贴屏蔽
│       ├── PostFilterHook.java #   发帖过滤：等级 / 关键词 / AI 标题党 / 视频帖 / 点赞·评论·收藏阈值
│       ├── CommentFilterHook.java # 评论过滤：插眼 / 无意义评论 + 关键词（#36）
│       ├── FavourAutoCleanHook.java # 自动清理失效收藏（复用宿主清理请求）
│       ├── AIClickbaitChecker.java # AI 标题党判定：OpenAI 兼容 / 批量 / 缓存 / 冷却
│       ├── FeedItemHider.java #   信息流条目隐藏与复用恢复（推广贴 / 发帖过滤共用）
│       ├── SingleColumnFeedHook.java # 单列信息流：屏蔽双列瀑布流（旧布局 + 首页 Epoxy 配对）
│       ├── SearchPageCleanHook.java # 搜索页精简：隐藏搜索发现 / 黑盒热榜 / 顶部横幅
│       ├── MessageRedDotHook.java # 消息红点精简：隐藏 ✉️ 未读红点 / 消息入口红数字（#38）
│       ├── TextSelectHook.java  #   解除复制 / 标准文本选择 / 跨行选择
│       ├── ImageShareHook.java  #   图片系统分享（优先保存系统相册）
│       ├── ShareLinkPurifyHook.java # 净化分享链接
│       ├── BrowserRedirectHook.java # 浏览器重定向 + 网页日志（多层拦截）
│       ├── VideoDownloadHook.java # 视频下载：URL 捕获 + 悬浮按钮 + 底部面板
│       ├── LiquidGlassBottomBarHook.java # 液态玻璃底栏：主 Activity 生命周期触发安装
│       ├── WebViewDevToolsHook.java # 网页 DevTools：WebView Chrome 远程调试
│       └── DailyTaskHook.java   #   每日任务：3 种分享类型自动完成
│   └── liquidglass/             #   液态玻璃底栏：安装器 / 调节面板 / 配置 / 沉浸式 / 毛玻璃降级
├── res/                         # 字符串 / drawable / raw（AGSL shader）
└── resources/META-INF/xposed/   # 模块声明
```

## 模块声明

全部声明在 `META-INF/xposed/`：

```
app/src/main/resources/META-INF/xposed/
├── java_init.list      # 入口类
├── module.prop         # minApiVersion=101
└── scope.list          # 作用域
```

- 模块名称 / 描述：`android:label` / `android:description`（见 `res/values/strings.xml`）

## 构建与使用

1. **环境**：Gradle 9.7.1，SDK Manager 需 **Platform 37**，JDK 17+
2. **编译**：
   - Windows：`gradlew.bat assembleDebug`
   - 命令行/CI：`./gradlew assembleDebug`
   - 或 Android Studio `Build > Make Project`
   - 产物：`app/build/outputs/apk/debug/app-debug.apk`
3. **刷入**：
   - 模拟器/真机需 **支持 API 102 的 LSPosed 框架**
   - 安装 APK → LSPosed Manager 启用模块。
   - 重启小黑盒进程
4. **查看日志**：`adb logcat -s BetterHeybox`；也可在小黑盒设置面板开启「记录日志」，日志自动写入文件便于离线排查

## 开源协议

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

本项目采用 **GNU 通用公共许可证第 3 版**授权，完整条款见仓库根目录的 `LICENSE`。

- 本程序是自由软件：你可以依据自由软件基金会发布的 GPLv3 的条款
  重新发布和/或修改它；本程序不提供任何担保，详见 `LICENSE` 第 15、16 条
- `LICENSE` 由两部分组成：**GPLv3 完整原文**，以及文件末尾依 **GPLv3 第 7 条**作出的
  《BetterHeybox 附加条款与说明》：
  - 保留版权声明、SPDX 标识、附加条款与「与清枫（北京）科技有限公司无关联」的非关联声明
  - 修改版本须显著标注「已修改」与修改日期，不得歪曲来源或暗示作者背书
  - 未经许可不得用 `BetterHeybox` / 作者名义为衍生作品宣传；本许可不授予任何商标权，
    「小黑盒 / Heybox」的权利归清枫（北京）科技有限公司所有
  - 免责与责任限制：Hook 宿主、下载与推送等操作的风险由使用者自行承担
  - **宿主应用例外**：本模块与专有宿主「小黑盒」的运行期结合属于两个独立程序的聚合，
    不使宿主应用成为本程序的衍生作品，也不要求其著作权人公开源代码
- 上方免责声明中的表述均属于作者的风险提示与使用倡议，**不构成 GPLv3 之外的许可条件**；GPLv3 本身不限制商业性使用
- 仓库中移植或依赖的第三方组件各自保留原始许可证，见下方[致谢](#致谢)：
  `libxposed/api`、`DexKit`（Apache-2.0），液态玻璃渲染器相关实现（MIT），
  二者均与本许可证兼容

> GPLv3 条款以英文原文为准，中文说明仅为便于理解：
> <https://www.gnu.org/licenses/gpl-3.0.html>

## 致谢

本项目在开发和实现过程中，参考或使用了以下开源项目和库，在此表示衷心感谢：

- [LSPosed](https://github.com/LSPosed/LSPosed) — Xposed 框架基础
- [Libxposed api](https://github.com/libxposed/api) — Apache-2.0，现代 Xposed 模块 API
- [Dexkit](https://github.com/LuckyPray/DexKit) — Apache-2.0，字节码特征分析
- [HeyBox-LiquidGlass](https://github.com/sjtt2/HeyBox-LiquidGlass) — 液态玻璃底栏移植来源
- [QEA0-Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android) — MIT，液态玻璃渲染器
- [AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView) — MIT，QmDeve，AGSL shader 与 GPU 渲染路径

## 灵感来源

本项目的部分功能设计和实现思路，受到了以下项目的启发，特别感谢：

1. **[SoulFrog](https://github.com/xmnh/SoulFrog)** — **自动化分享**核心实现思路的主要灵感来源，作者 [@xmnh](https://github.com/xmnh)  
2. **[假装开启小黑盒通知权限](https://github.com/Xposed-Modules-Repo/com.chrxw.justenablednotification)** — 提供了功能上的启发  

如果涉及任何代码使用不当或版权问题，请随时联系