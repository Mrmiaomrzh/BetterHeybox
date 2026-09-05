# BetterHeybox

![BetterHeybox](https://socialify.git.ci/Mrmiaomrzh/BetterHeybox/image?font=Source+Code+Pro&forks=1&issues=1&language=1&name=1&pattern=Floating+Cogs&pulls=1&stargazers=1&theme=Auto)

增强小黑盒（Heybox）的 LSPosed 模块。

> [!CAUTION]
> **免责声明**
> - 本应用与清枫(北京)科技有限公司**无任何关联**，亦未经其授权或认可  
> - 本项目仅用于**学习与研究**小黑盒 APP 的部分技术原理，**严禁**用于任何商业或非法用途  
> - 请在下载后 **24 小时内**删除本应用及相关文件  
> - **禁止**在 **小黑盒 / HeyBox** 平台内发布、讨论或传播本模块的内容，违者后果自负  

> [!Note]
>本应用兼容 [小黑盒 1.3.393](https://github.com/Mrmiaomrzh/BetterHeybox/releases/download/v0.2.0/heybox_1.3.393.apk) 及以上版本，其他版本出现的问题不会进行处理

> [!WARNING]
> 使用免Root框架`「NPatch」`时，需要把`破解签名校验`改成`Extreme`，不然会有缺少参数闪退的问题

## 功能

所有功能开关均可在小黑盒「我的 → 设置 → 通用设置」中的 `BetterHeybox 设置` 入口直接打开模块面板，
开关配置存放在**小黑盒应用目录**
（`/data/data/com.max.xiaoheihe/shared_prefs/betterheybox.xml`）

### 广告过滤

| 类型 |
|------|
| 屏蔽开屏广告 |
| 屏蔽信息流广告 |
| 屏蔽气泡广告 |
| 屏蔽角标广告 |
| 屏蔽推广贴 |

### 帖子过滤（实验性）

- **屏蔽低等级帖**：按帖子自带等级数据过滤低等级用户的发帖，
  阈值可选关闭 / Lv1-Lv10；无等级数据的账号默认放行，
  可通过「屏蔽无等级用户」开关一并屏蔽（含官方号，需先开启等级阈值）
- **关键词屏蔽**：命中帖子标题或正文即屏蔽，一行一个；
  `regex:` 前缀条目按正则匹配（忽略大小写），如 `regex:白嫖.{0,4}资格`
- **AI 标题党识别**（默认关闭）：调用 AI 逐条判定标题党并屏蔽；
  兼容 OpenAI 协议接口，内置 DeepSeek / Kimi / 通义 / 智谱 / OpenAI / OpenRouter /
  本地模型（Ollama 等）预设（选中自动填充地址与模型），判定提示词可自定义，
  支持一键测试连接；帖子标题会发送到你配置的 AI 服务商，请知悉
- 覆盖首页推荐 / 热点 / 话题详情等信息流列表；改动规则后需刷新信息流或加载新分页生效，
  本地缓存已渲染的帖子会在滚动重新绑定时补拦
- 以上配置纳入配置备份（AI Token 除外）

### 界面增强

- **底部导航栏优化**（需重启小黑盒生效）：隐藏底栏 tab 项；
  隐藏后剩余 tab 自动等分填满整条，当前选中的 tab 被隐藏时自动切换到第一个可见 tab
- **液态玻璃底栏**（需重启小黑盒生效，Android 13+ 为玻璃效果）：
  底部导航栏渲染为实时折射/色散的「液态玻璃」效果，带玻璃水滴选中动画、沉浸式小白条，
  标签/图标颜色随背景亮度自适应反色；**长按首页标题栏右上角图标**或
  设置页**「通用设置」行**可打开顶部调节面板，实时调节深/浅底色、不透明度、高度、
  距底部偏移、玻璃条宽度三模式（自适应 / 占满 / 自定义百分比）、Tab 宽度（50-150%）、
  底栏形态（经典居中 / 右侧圆钮 / 自动），有标签被隐藏时选中项自动加长；
  与底栏隐藏联动，隐藏 tab 后玻璃条同步收缩，重复点击当前 tab 按宿主语义刷新，
  点击加号有弹跳反馈，底部 Toast 与应用内横幅（更新 / 推荐通知）自动抬升到玻璃条上方；
  Android 12 及以下自动回退毛玻璃效果
- **液态玻璃实现选择**：与独立的「小黑盒液态玻璃」模块共存时，首次打开小黑盒
  自动弹出选择，可让玻璃效果由该模块提供——本模块的玻璃底栏与长按入口同步让位，
  避免两条玻璃底栏叠加；选择后仍可在设置面板「液态玻璃提供方」行切换
- **屏蔽双列信息流**（实验性，仅 1.3.394）：将首页推荐 / 话题 / 百科的
  双列瀑布流恢复为单列，封面宽高比同步修正回旧单列卡样式

### 帖子增强

- **解除复制**：Hook 小黑盒自定义 `TextSelectHandler` 的长按拦截，
  恢复安卓系统标准文本选择
- **拖动跨行选择修复**：文本选择激活时放行滚动容器的触摸拦截，选择手柄可跨行拖动
- **图片系统分享**：图片查看器中长按图片，在原有分享面板追加「系统分享」动作，
  下载当前图片后**优先保存到系统相册**（可被相册真正查看、可被任意 App 分享），
  自动识别 jpg/png/gif/webp/bmp 真实格式并修正 MIME；可通过「系统分享图片」开关关闭
- **净化分享链接**：复制链接 / 分享到 QQ、微信等渠道时，自动去掉小黑盒链接上的
  h_camp、h_session_id、h_src、new_post_share_style 等追踪参数
  （如 `...web/share?h_camp=link&h_session_id=xxx&link_id=abc&new_post_share_style=true`
  净化为 `...web/share?link_id=abc`；仅处理小黑盒域名，保留 link_id / id / hkey
  等功能参数，链接照常打开）；默认开启，可通过「净化分享链接」开关关闭，
  去除内容会记录到模块日志

### 网页浏览

- **浏览器重定向**（默认关闭）：内置网页中的链接按规则改由系统浏览器打开。
  默认仅重定向非小黑盒域名的外链，登录 / 授权 / 支付等敏感页与 `.apk` 下载强制留内置
  （登录态 Cookie 只在内置 WebView 注入，外链误跳会断登录）；开关联动：
  「重定向外部链接」与「网页 DevTools」互斥，「包含小黑盒域名」跟随重定向主开关
- **包含小黑盒域名**：已知小黑盒域名也重定向，敏感页仍强制内置
- **重定向浏览器**：指定打开链接用的浏览器，未设系统默认浏览器时不再弹「打开方式」选择框；
  换机 / 卸载自动回退系统解析
- **强制重定向域名 / 强制内置域名**：一行一个域名（兼容粘贴完整链接），
  分别总是走外部浏览器 / 总是留内置，优先级高于默认规则
- **网页日志**（默认关闭）：记录内置浏览器打开的页面与标题（最近 80 条），
  设置面板可查看、全部复制、清空
- **网页 DevTools**：为小黑盒内置 WebView 开启 Chrome 远程调试，电脑 `chrome://inspect` 可调试内置网页
- **打开网页**：输入任意 http/https 地址，用小黑盒内置浏览器打开

### 视频下载

- **下载入口**：视频帖右上角圆形 Monet 渐变悬浮按钮
- **底部下载面板**：
  - 准备：标题 / 来源 / 预计大小 → 「开始下载」
  - 下载中：实时百分比、已下载/总大小、当前速度 → 「暂停下载」「取消下载」
  - 暂停：「继续下载」
  - 完成：保存路径 → 「播放」「分享」「完成」
  - 失败：错误原因 → 「重新下载」
- **后台下载**：面板可随时关闭，下载继续进行；悬浮按钮进度环持续反馈
- **全类型视频**：正文 / 信息流 / 故事 / 游戏卡片均可；mp4 直链与 HLS（m3u8）分片流均支持
- **断点续传**  
- **自动转封装 MP4**：HLS 合并后自动无损转封装为 MP4
- **智能命名**：文件名优先使用**帖子标题**，HLS 通用名（segs/index）自动回退时间戳；
  重名自动加 `(n)` 后缀，绝不覆盖
- **保存位置**：默认相册 `Movies/BetterHeybox`；设置「保存位置」可调起**系统文件选择器**
  选择任意文件夹，完成通知显示实际保存路径
- **通知栏反馈**：进度（含暂停/取消）、完成（播放/分享/删除 + 保存路径）、失败（重试/取消）
- **系统分享**：完成后一键分享视频文件  

### 每日任务

- **自动完成每日分享任务**：自动完成小黑盒每日任务的 **3 种分享任务**
  - 任务一：**分享任意帖子**（配置帖子链接）
  - 任务二：**分享游戏详情**（配置游戏详情链接）
  - 任务三：**分享游戏评价**（配置游戏评价链接）
- **3 个独立链接设置**：帖子链接 / 游戏详情链接 / 游戏评价链接，各自独立配置；
  未配置的任务自动跳过；每日状态按日期记录，跨天重置
- **分享渠道可配置**：设置面板「分享渠道」可选 **QQ / QQ空间**、**微信 / 朋友圈** 或 **微博**，
  自动分享按所选渠道在分享面板点击对应按钮并伪造成功回调（默认 QQ；抖音因无分享成功回调暂不支持）
- **完成后返回首页**：三种分享全部完成后自动退回小黑盒首页，清理途中打开的帖子页（设置面板可关闭，默认开启）
- **清除今日打卡**：打卡失败或想重新执行时，点击「清除今日打卡」清除今日已完成状态并立即重新尝试

#### 链接格式（3 个分享链接均支持以下任意一种）

| 类型 | 示例 |
|------|------|
| 分享链接（带 link_id） | `https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=123456` |
| 网页链接（xiaoheihe.cn） | `https://xiaoheihe.cn/a/123456` |
| 深链协议（heybox://） | `heybox://v3/bbs/app/api/web/share?link_id=123456` |

> 链接经小黑盒 RouterActivity 自动路由到对应帖子/游戏页；未配置的类型自动跳过。  
> **获取方式**：在小黑盒 App 打开目标帖子 → 分享 → 复制链接，取分享链接或网页链接均可；
> 游戏/频道页同理复制分享链接  

### 通用

- **版本前置检测**：检测小黑盒版本是否为受支持版本 `1.3.393` / `1.3.394`，不匹配时显示提示
- **伪装通知权限**：让小黑盒认为通知权限已开启，获得**签到加成**  
- **屏蔽更新**：提供可选开关，屏蔽小黑盒更新   
- **记录日志**：提供「记录日志」开关，开启后自动把模块运行日志写入文件

### 更新兼容（DexKit 自动分析）

小黑盒更新常会打乱混淆名，模块通过 [DexKit](https://github.com/LuckyPray/DexKit) 字节码特征分析
自动重新定位，不必等模块发版适配：

- **原生弹窗自动定位**：以 HeyBoxDialog 内的品牌常量字符串为锚点定位对话框类；
  Builder 的标题/正文、View 槽位、正向/负向按钮等同签名混淆方法，用 alpha=0 的
  「隐形探针」按实际渲染位置自动分类；
  分享渠道 / 链接编辑 / 导入确认 / 保存位置等弹窗全部走该通道，解析失败自动回退系统弹窗
- **设置页启发式定位**：binding 字段按 ViewBinding 接口形态判定

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
│       ├── PostFilterHook.java #   发帖过滤：等级 / 关键词 / AI 标题党
│       ├── AIClickbaitChecker.java # AI 标题党判定：OpenAI 兼容 / 批量 / 缓存 / 冷却
│       ├── FeedItemHider.java #   信息流条目隐藏与复用恢复（推广贴 / 发帖过滤共用）
│       ├── SingleColumnFeedHook.java # 单列信息流：屏蔽双列瀑布流（旧布局 + 首页 Epoxy 配对）
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

1. **环境**：Android Studio 打开本目录（首次自动下载 Gradle 9.7.1 + 依赖，需网络；
   若提示缺 wrapper 让 AS 自动补全；SDK Manager 需装有 **Platform 37**）
2. **编译**：
   - Windows：`gradlew.bat assembleDebug`
   - 命令行/CI：`./gradlew assembleDebug`
   - 或 Android Studio `Build > Make Project`
   - 产物：`app/build/outputs/apk/debug/app-debug.apk`
   - 正式版 `assembleRelease`：启用 R8 裁剪第三方依赖体积，
     自有代码在 `proguard-rules.pro` 整包 keep，不影响 Hook 目标定位
3. **刷入**：
   - 模拟器/真机需 root + **支持 API 102 的 LSPosed**
   - 安装 APK → LSPosed Manager 启用模块。
     `staticScope=true` 时作用域固定为 scope.list 中的小黑盒，无需（也无法）手动勾选其它应用
   - 重启小黑盒进程
4. **看日志**：`adb logcat -s BetterHeybox`（每个 Hook 安装成功/失败均有 ✔/✘ 日志）；
   也可在小黑盒设置面板开启「记录日志」，日志自动写入文件便于离线排查

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