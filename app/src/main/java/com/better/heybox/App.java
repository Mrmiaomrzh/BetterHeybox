package com.better.heybox;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 基类：经 libxposed service 与框架通信，把功能开关写入 RemotePreferences 供 Hook 侧跨进程读取。
 * 写入策略：服务已连接直接写；未连接先写待提交缓存，服务绑定后由 tryFlush 补交。
 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "BetterHeybox";

    /** RemotePreferences 分组名 */
    public static final String PREFS_GROUP = "betterheybox";

    /** 本地待提交缓存 */
    public static final String PENDING_PREFS = "betterheybox_pending";

    public static final String KEY_OPEN_SCREEN = "open_screen";
    public static final String KEY_FEED_AD = "feed_ad";
    public static final String KEY_BUBBLE_AD = "bubble_ad";
    public static final String KEY_CORNER_AD = "corner_ad";
    public static final String KEY_PROMOTE_AD = "promote_ad";
    public static final String KEY_HIDE_TAB_HOME = "hide_tab_home";
    public static final String KEY_HIDE_TAB_HOT = "hide_tab_hot";
    public static final String KEY_HIDE_TAB_GAME = "hide_tab_game";
    public static final String KEY_HIDE_ADD = "hide_add";
    public static final String KEY_COPY_POST = "copy_post";

    /** 自绘制文本选择：由模块自行实现选区/高亮/复制，绕过小黑盒与系统原生选择 UI */
    public static final String KEY_CUSTOM_TEXT_SELECT = "custom_text_select";

    /** Comment free-copy: the menu "copy" opens a centered selectable-text card */
    public static final String KEY_COMMENT_FREE_COPY = "comment_free_copy";

    public static final String KEY_SEARCH_HIDE_BANNER = "search_hide_banner";

    public static final String KEY_SEARCH_HIDE_DISCOVER = "search_hide_discover";

    public static final String KEY_SEARCH_HIDE_HOT_RANK = "search_hide_hot_rank";

    public static final String KEY_GAME_LIB_HIDE_BANNER = "game_lib_hide_banner";

    public static final String KEY_GAME_LIB_HIDE_MENU = "game_lib_hide_menu";

    public static final String KEY_GAME_LIB_HIDE_SECTIONS = "game_lib_hide_sections";

    public static final String KEY_GAME_LIB_HIDE_TYPES = "game_lib_hide_types";

    public static final String KEY_GAME_LIB_HIDE_ENTRIES = "game_lib_hide_entries";

    public static final String KEY_GAME_LIB_HIDE_SECTION_NAMES = "game_lib_hide_section_names";

    public static final String KEY_BLOCK_UPDATE = "block_update";
    public static final String KEY_SYSTEM_SHARE = "system_share";

    /** 每日任务：自动完成分享任务开关 */
    public static final String KEY_DAILY_TASK_ENABLED = "daily_task_enabled";

    /** 每日任务：图片帖分享链接（第一种分享类型） */
    public static final String KEY_DAILY_TASK_PICTURE = "daily_task_picture";

    /** 每日任务：普通帖分享链接（第二种分享类型） */
    public static final String KEY_DAILY_TASK_NORMAL = "daily_task_normal";

    /** 每日任务：频道关注链接（第三种分享类型） */
    public static final String KEY_DAILY_TASK_CHANNEL = "daily_task_channel";

    /** 每日任务：今日已完成日期（yyyy-MM-dd，跨天自动重置） */
    public static final String KEY_DAILY_TASK_DONE_DATE = "daily_task_done_date";

    public static final String KEY_DAILY_TASK_RESET = "daily_task_reset";

    /** 每日任务：分享渠道（QQ=QQ/QQ空间，WECHAT=微信好友/朋友圈；默认 QQ） */
    public static final String KEY_SHARE_CHANNEL = "daily_task_channel_type";

    /** 每日任务：三种分享全部完成后自动退回首页（默认开） */
    public static final String KEY_DAILY_TASK_BACK_HOME = "daily_task_back_home";

    /** 伪装授予通知权限：让小黑盒认为通知已开启，获得签到加成（不真正申请权限） */
    public static final String KEY_FAKE_NOTIFICATION = "fake_notification";

    /** 视频下载：在支持的视频（mp4 直链等）上显示下载入口 */
    public static final String KEY_VIDEO_DOWNLOAD = "video_download";

    /** 视频下载保存目录：相册 Movies/ 下的子目录名（默认 BetterHeybox） */
    public static final String KEY_VIDEO_DIR = "video_download_dir";

    /** 视频下载：HLS/TS 下载完成后自动转封装为 MP4（默认开，失败保留 ts） */
    public static final String KEY_VIDEO_TO_MP4 = "video_download_to_mp4";

    /** 净化分享链接：复制链接 / 系统分享时去掉 sid、share_app_id 等追踪参数（默认开） */
    public static final String KEY_PURIFY_SHARE_LINK = "purify_share_link";

    /** 浏览器重定向：内置网页中的外部链接改由系统浏览器打开（默认关） */
    public static final String KEY_BROWSER_REDIRECT = "browser_redirect";

    /** 浏览器重定向：已知小黑盒域名也重定向（登录/支付等敏感页仍强制内置） */
    public static final String KEY_BROWSER_REDIRECT_KNOWN = "browser_redirect_known_hosts";

    /** 浏览器重定向：强制重定向域名，一行一个（优先级最高，开了重定向才生效） */
    public static final String KEY_BROWSER_REDIRECT_FORCE = "browser_redirect_force_domains";

    /** 浏览器重定向：强制内置域名，一行一个（优先级最高） */
    public static final String KEY_BROWSER_REDIRECT_BLOCK = "browser_redirect_block_domains";

    /** 浏览器重定向：指定打开用的浏览器包名（空=跟随系统默认，未设默认时系统会弹选择框） */
    public static final String KEY_BROWSER_TARGET = "browser_redirect_package";

    /** 网页日志：记录内置浏览器打开的页面与标题 */
    public static final String KEY_WEB_LOG = "web_log";

    /** 网页日志数据：最近 N 条页面记录 */
    public static final String KEY_WEB_LOG_DATA = "web_log_data";

    /** 日志开关：开启后自动记录模块日志到文件 */
    public static final String KEY_LOG = "log";

    /** 小黑盒内置 WebView 的 Chrome DevTools 远程调试开关 */
    public static final String KEY_WEBVIEW_DEVTOOLS = "webview_devtools";

    /** 液态玻璃底栏开关及参考项目兼容配置 */
    public static final String KEY_LIQUID_GLASS = "liquid_glass";
    public static final String KEY_GLASS_IMMERSIVE = "glass_immersive";
    public static final String KEY_GLASS_ADAPTIVE = "glass_adaptive";
    public static final String KEY_GLASS_DARK_COLOR = "glass_dark_color";
    public static final String KEY_GLASS_DARK_ALPHA = "glass_dark_alpha";
    public static final String KEY_GLASS_LIGHT_COLOR = "glass_light_color";
    public static final String KEY_GLASS_LIGHT_ALPHA = "glass_light_alpha";
    public static final String KEY_GLASS_BAR_HEIGHT = "glass_bar_height";
    public static final String KEY_GLASS_BAR_OFFSET = "glass_bar_offset";
    /** Glass bar side inset in dp (0-40), honored by every width mode (#34). */
    public static final String KEY_GLASS_SIDE_MARGIN = "glass_side_margin";

    public static final String KEY_GLASS_FIT_TABS = "glass_fit_tabs";

    /** 玻璃条宽度模式：0=自适应（按内容居中）1=占满 2=自定义百分比（glass_bar_width_pct） */
    public static final String KEY_GLASS_BAR_WIDTH_MODE = "glass_bar_width_mode";
    /** Custom width: percent of the usable width (side insets excluded), 40-100. */
    public static final String KEY_GLASS_BAR_WIDTH_PCT = "glass_bar_width_pct";
    /** Tab 项宽度缩放，等分默认值的百分比（50-150） */
    public static final String KEY_GLASS_TAB_WIDTH_PCT = "glass_tab_width_pct";
    /** 底栏形态：0=自动 1=经典居中加号 2=右侧圆形玻璃钮 */
    public static final String KEY_GLASS_BAR_LAYOUT = "glass_bar_layout";

    /** 液态玻璃提供方：GlassProvider.PROVIDER_* */
    public static final String KEY_GLASS_PROVIDER = "glass_provider";

    /** 实验性功能：屏蔽双列瀑布流 */
    public static final String KEY_SINGLE_COLUMN_FEED = "single_column_feed";

    /** 发帖过滤：等级阈值，0 为关闭 */
    public static final String KEY_POST_MIN_LEVEL = "post_min_level";

    /** 发帖过滤：点赞阈值，帖子点赞 < 该值即屏蔽，0 为关闭 */
    public static final String KEY_POST_MIN_LIKE = "post_min_like";

    /** 发帖过滤：评论阈值，帖子评论 < 该值即屏蔽，0 为关闭 */
    public static final String KEY_POST_MIN_COMMENT = "post_min_comment";

    /** 发帖过滤：收藏阈值，帖子收藏 < 该值即屏蔽，0 为关闭；无收藏数据的列表自动放行 */
    public static final String KEY_POST_MIN_FAVOUR = "post_min_favour";

    /** 发帖过滤：无等级用户也屏蔽 */
    public static final String KEY_POST_NO_LEVEL = "post_filter_no_level";

    /** 发帖过滤：关键词，一行一个 */
    public static final String KEY_POST_KEYWORDS = "post_filter_keywords";

    /** 发帖过滤：AI 标题党识别 */
    public static final String KEY_POST_AI_ENABLED = "post_filter_ai_enabled";

    /** 发帖过滤：首页信息流屏蔽视频帖 */
    public static final String KEY_BLOCK_VIDEO_POST = "block_video_post";
    /** 评论过滤：强制开启小黑盒自带的「屏蔽插眼」 */
    public static final String KEY_HOST_HIDE_CY = "host_hide_cy";

    /** 评论过滤：模块兜底屏蔽 cy（插眼）/ 无意义灌水评论 */
    public static final String KEY_BLOCK_CY_COMMENT = "block_cy_comment";

    /** 评论过滤：关键词，一行一个，命中评论正文即屏蔽 */
    public static final String KEY_COMMENT_KEYWORDS = "comment_filter_keywords";

    /** 诊断：记录首页流条目判定信息 */
    public static final String KEY_FLOW_DIAGNOSE = "flow_diagnose";

    /** 详细屏蔽日志：屏蔽日志附带标题、作者、等级等帖子信息 */
    public static final String KEY_VERBOSE_LOG = "verbose_log";

    /** 收藏：打开收藏列表发现失效内容时自动清理 */
    public static final String KEY_FAVOUR_AUTO_CLEAN = "favour_auto_clean";

    /** 消息红点：隐藏各页面右上角 ✉️ 的未读红点 */
    public static final String KEY_HIDE_MSG_DOT = "hide_msg_dot";

    /** 消息红点：隐藏消息列表里指定入口的红色数字 */
    public static final String KEY_HIDE_MSG_BADGE = "hide_msg_badge";

    /** 消息红点：隐藏的入口标题 */
    public static final String KEY_MSG_BADGE_ENTRIES = "msg_badge_entries";

    /** 消息红点：完整隐藏入口标题 */
    public static final String KEY_MSG_FULL_HIDE_ENTRIES = "msg_full_hide_entries";

    /** AI 提供商预设 */
    public static final String KEY_AI_PROVIDER = "ai_provider";

    /** AI 接口地址 */
    public static final String KEY_AI_BASE_URL = "ai_base_url";

    /** AI 模型名 */
    public static final String KEY_AI_MODEL = "ai_model";

    /** AI Token */
    public static final String KEY_AI_TOKEN = "ai_token";

    /** AI 判定提示词，空用默认 */
    public static final String KEY_AI_PROMPT = "ai_prompt";

    /** AI 单次请求输出 token 上限 */
    public static final String KEY_AI_MAX_TOKENS = "ai_max_tokens";

    /** 参考项目玻璃颜色预设/透明度/布局参数 */
    public static final String KEY_GLASS_DARK_PRESET = "glass_dark_preset";
    public static final String KEY_GLASS_LIGHT_PRESET = "glass_light_preset";

    /** 设置页「打开网页」入口保存的 URL */
    public static final String KEY_WEBVIEW_ENTRY_URL = "webview_entry_url";

    /** 运行状态检查点 */
    public static final String KEY_RUNTIME_STATUS = "runtime_status";

    /** 免责声明 */
    public static final String KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted";
    public static final String KEY_MODULE_VERSION_FLOOR = "module_version_floor";

    /** 调试：忽略并清除模块版本降级限制（允许装回更旧的模块） */
    public static final String KEY_DEBUG_NO_DOWNGRADE = "debug_no_downgrade";
    public static final String KEY_TARGET_HINT_VISIBLE = "target_hint_visible";

    // ===== 动态推送（关注作者 / 关键词监控）=====
    /** 动态推送总开关 */
    public static final String KEY_WATCH_ENABLED = "watch_enabled";
    /** 关注对象：一行一个 userid 或用户主页链接 */
    public static final String KEY_WATCH_USERS = "watch_users";
    /** 监控关键词：一行一个，regex: 前缀为正则 */
    public static final String KEY_WATCH_KEYWORDS = "watch_keywords";
    /** 应用内横幅提醒 */
    public static final String KEY_WATCH_BANNER = "watch_banner";
    /** 系统通知栏提醒 */
    public static final String KEY_WATCH_NOTIFY = "watch_notify";
    /** 检查间隔（分钟） */
    public static final String KEY_WATCH_INTERVAL_MIN = "watch_interval_min";
    /** 时间窗（天）：只提醒最近 N 天内的新帖（旧键，保留兼容） */
    public static final String KEY_WATCH_WINDOW_DAYS = "watch_window_days";
    /** 获取时间窗（分钟）：只提醒最近 N 分钟内的新帖，优先于上面的「天」 */
    public static final String KEY_WATCH_WINDOW_MIN = "watch_window_min";
    /** 关注的话题：一行一个 "话题id|话题名"（导入关注话题时写入） */
    public static final String KEY_WATCH_TOPICS = "watch_topics";
    /** 最近浏览过的话题 id（从宿主请求里顺手记录，供导入用） */
    public static final String KEY_WATCH_RECENT_TOPICS = "watch_recent_topics";
    /** 关键词只匹配标题（默认标题+正文） */
    public static final String KEY_WATCH_TITLE_ONLY = "watch_title_only";
    /** 关键词/话题主动拉流：按关键词搜索、按话题取最新帖（默认关） */
    public static final String KEY_WATCH_STREAM_FETCH = "watch_stream_fetch";
    /** 第三方推送总开关 */
    public static final String KEY_WATCH_PUSH_ENABLED = "watch_push_enabled";
    /** 钉钉机器人：webhook 或 access_token */
    public static final String KEY_WATCH_PUSH_DINGTALK = "watch_push_dingtalk";
    /** WxPusher：appToken|topicId 或 appToken|uid:UID */
    public static final String KEY_WATCH_PUSH_WXPUSHER = "watch_push_wxpusher";
    /** OneBot：baseUrl|群号 或 baseUrl|private:QQ */
    public static final String KEY_WATCH_PUSH_ONEBOT = "watch_push_onebot";
    /** 自定义 webhook（支持 {title} {author} {link} 占位符） */
    public static final String KEY_WATCH_PUSH_CUSTOM = "watch_push_custom";
    /** 已提醒过的 link_id（有界，逗号分隔） */
    public static final String KEY_WATCH_SEEN = "watch_seen";
    /** 上次检查时间（毫秒） */
    public static final String KEY_WATCH_LAST_CHECK = "watch_last_check";
    /** 已完成首轮基线的关注对象（逗号分隔，首轮只记录不推送） */
    public static final String KEY_WATCH_BASELINED = "watch_baselined";

    /** receiver 白名单与备份列表 */
    public static final java.util.Map<String, Boolean> BOOLEAN_DEFAULTS = buildBooleanDefaults();

    private static java.util.Map<String, Boolean> buildBooleanDefaults() {
        java.util.Map<String, Boolean> m = new java.util.LinkedHashMap<>();
        m.put(KEY_OPEN_SCREEN, true);
        m.put(KEY_FEED_AD, true);
        m.put(KEY_BUBBLE_AD, true);
        m.put(KEY_CORNER_AD, true);
        m.put(KEY_PROMOTE_AD, true);
        m.put(KEY_HIDE_TAB_HOME, false);
        m.put(KEY_HIDE_TAB_HOT, false);
        m.put(KEY_HIDE_TAB_GAME, false);
        m.put(KEY_HIDE_ADD, false);
        m.put(KEY_COPY_POST, true);
        m.put(KEY_CUSTOM_TEXT_SELECT, false);
        m.put(KEY_COMMENT_FREE_COPY, true);
        m.put(KEY_SEARCH_HIDE_BANNER, false);
        m.put(KEY_SEARCH_HIDE_DISCOVER, false);
        m.put(KEY_SEARCH_HIDE_HOT_RANK, false);
        m.put(KEY_GAME_LIB_HIDE_BANNER, false);
        m.put(KEY_GAME_LIB_HIDE_MENU, false);
        m.put(KEY_GAME_LIB_HIDE_SECTIONS, false);
        m.put(KEY_SYSTEM_SHARE, true);
        m.put(KEY_VIDEO_DOWNLOAD, true);
        m.put(KEY_VIDEO_TO_MP4, true);
        m.put(KEY_PURIFY_SHARE_LINK, true);
        m.put(KEY_BROWSER_REDIRECT, false);
        m.put(KEY_BROWSER_REDIRECT_KNOWN, false);
        m.put(KEY_WEB_LOG, false);
        m.put(KEY_BLOCK_UPDATE, false);
        m.put(KEY_DAILY_TASK_ENABLED, false);
        m.put(KEY_DAILY_TASK_BACK_HOME, true);
        m.put(KEY_FAKE_NOTIFICATION, false);
        m.put(KEY_LOG, false);
        m.put(KEY_WEBVIEW_DEVTOOLS, false);
        m.put(KEY_LIQUID_GLASS, true);
        m.put(KEY_GLASS_IMMERSIVE, true);
        m.put(KEY_GLASS_ADAPTIVE, true);
        m.put(KEY_GLASS_FIT_TABS, false);
        m.put(KEY_SINGLE_COLUMN_FEED, false);
        m.put(KEY_POST_AI_ENABLED, false);
        m.put(KEY_POST_NO_LEVEL, false);
        m.put(KEY_BLOCK_VIDEO_POST, false);
        m.put(KEY_HOST_HIDE_CY, true);
        m.put(KEY_BLOCK_CY_COMMENT, false);
        m.put(KEY_FLOW_DIAGNOSE, false);
        m.put(KEY_VERBOSE_LOG, false);
        m.put(KEY_FAVOUR_AUTO_CLEAN, false);
        m.put(KEY_HIDE_MSG_DOT, false);
        m.put(KEY_HIDE_MSG_BADGE, false);
        m.put(KEY_DISCLAIMER_ACCEPTED, false);
        m.put(KEY_TARGET_HINT_VISIBLE, true);
        m.put(KEY_DEBUG_NO_DOWNGRADE, false);
        m.put(KEY_WATCH_ENABLED, false);
        m.put(KEY_WATCH_BANNER, true);
        m.put(KEY_WATCH_NOTIFY, true);
        m.put(KEY_WATCH_PUSH_ENABLED, false);
        m.put(KEY_WATCH_TITLE_ONLY, false);
        m.put(KEY_WATCH_STREAM_FETCH, false);
        return m;
    }

    /** volatile  */
    private static volatile XposedService sService;

    private static volatile App sApp;

    /** 服务绑定监听 */
    private static final List<OnServiceBoundListener> sBoundListeners = new ArrayList<>();

    /** 回调在主线程执行 */
    public interface OnServiceBoundListener {
        void onServiceBound();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
        LogRecorder.setContext(this);
        Checkpoint.mark("模块进程启动 (pid=%d)", android.os.Process.myPid());
        Logs.i(TAG, "App.onCreate: pid=" + android.os.Process.myPid());
        XposedServiceHelper.registerListener(this);
        Logs.i(TAG, "已注册 XposedService 监听器");
    }

    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
        Checkpoint.mark("XposedService 已绑定: %s", describe(service));
        LogRecorder.setEnabled(readBoolean(KEY_LOG, false));
        LogRecorder.setVerbose(readBoolean(KEY_VERBOSE_LOG, false));
        LogRecorder.recordEvent("XposedService 已绑定: " + describe(service));
        SharedPreferences pending = getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
        Logs.i(TAG, "XposedService 已绑定: service=" + describe(service)
                + ", pendingCount=" + pending.getAll().size());
        PreferenceReceiver.tryFlush(this, pending);
        notifyServiceBound();
    }

    @Override
    public void onServiceDied(XposedService service) {
        Checkpoint.mark("XposedService 断开: %s", describe(service));
        Logs.w(TAG, "XposedService 已断开: service=" + describe(service)
                + ", current=" + describe(sService));
        sService = null;
    }

    /** 跨进程开关存储 */
    public static SharedPreferences getPrefs() {
        XposedService service = sService;
        if (service == null) {
            Logs.w(TAG, "获取 RemotePreferences 失败: XposedService 未绑定");
            return null;
        }
        try {
            SharedPreferences prefs = service.getRemotePreferences(PREFS_GROUP);
            if (prefs == null) {
                Logs.e(TAG, "获取 RemotePreferences 失败: service 返回 null, group=" + PREFS_GROUP);
            } else {
                Logs.i(TAG, "获取 RemotePreferences 成功: group=" + PREFS_GROUP);
            }
            return prefs;
        } catch (Throwable t) {
            Logs.e(TAG, "获取 RemotePreferences 异常: group=" + PREFS_GROUP, t);
            return null;
        }
    }

    public static boolean readBoolean(String key, boolean defaultValue) {
        App app = sApp;
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            if (pending.contains(key)) {
                return pending.getBoolean(key, defaultValue);
            }
        }
        SharedPreferences remote = getPrefs();
        return remote != null ? remote.getBoolean(key, defaultValue) : defaultValue;
    }

    public static String readString(String key, String defaultValue) {
        App app = sApp;
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            if (pending.contains(key)) {
                return pending.getString(key, defaultValue);
            }
        }
        SharedPreferences remote = getPrefs();
        return remote != null ? remote.getString(key, defaultValue) : defaultValue;
    }

    public static void writeString(String key, String value) {
        App app = sApp;
        SharedPreferences remote = getPrefs();
        if (remote != null) {
            remote.edit().putString(key, value).apply();
            LogRecorder.recordEvent("字符串已写入 RemotePreferences: key=" + key);
            return;
        }
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            pending.edit().putString(key, value).commit();
            LogRecorder.recordEvent("字符串写入待提交缓存: key=" + key);
            PreferenceReceiver.tryFlush(app, pending);
        }
    }

    public static void writeBoolean(String key, boolean value) {
        App app = sApp;
        SharedPreferences remote = getPrefs();
        if (remote != null) {
            remote.edit().putBoolean(key, value).apply();
            LogRecorder.recordEvent("开关已写入 RemotePreferences: key=" + key + ", value=" + value);
            return;
        }
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            pending.edit().putBoolean(key, value).commit();
            LogRecorder.recordEvent("服务未连接，开关写入待提交缓存: key=" + key + ", value=" + value);
            PreferenceReceiver.tryFlush(app, pending);
        }
    }

    public static void addOnServiceBoundListener(OnServiceBoundListener listener) {
        synchronized (sBoundListeners) {
            if (!sBoundListeners.contains(listener)) {
                sBoundListeners.add(listener);
            }
        }
    }

    private static void notifyServiceBound() {
        final List<OnServiceBoundListener> snapshot;
        synchronized (sBoundListeners) {
            snapshot = new ArrayList<>(sBoundListeners);
        }
        if (snapshot.isEmpty()) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                for (OnServiceBoundListener listener : snapshot) {
                    try {
                        listener.onServiceBound();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private static String describe(XposedService service) {
        return service == null ? "null"
                : service.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(service));
    }

    /** 获取框架服务实例 */
    public static XposedService getService() {
        return sService;
    }

    /** 获取 App 实例 */
    public static Context getAppContext() {
        return sApp;
    }

    public static Context resolveAppContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 当前进程名（pid） */
    public static String currentProcessName() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object name = activityThread.getMethod("currentProcessName").invoke(null);
            return name != null ? name.toString() : String.valueOf(android.os.Process.myPid());
        } catch (Throwable ignored) {
            return String.valueOf(android.os.Process.myPid());
        }
    }
}
