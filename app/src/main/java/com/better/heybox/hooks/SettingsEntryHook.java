package com.better.heybox.hooks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.better.heybox.App;
import com.better.heybox.BuildFlags;
import com.better.heybox.Checkpoint;
import com.better.heybox.ConfigBackup;
import com.better.heybox.DexKitResolver;
import com.better.heybox.GlassProvider;
import com.better.heybox.HeyboxPrefs;
import com.better.heybox.HeyboxTargets;
import com.better.heybox.LogExport;
import com.better.heybox.LogRecorder;
import com.better.heybox.ThemeUtils;
import com.better.heybox.VersionUtils;
import com.better.heybox.VideoDownloadManager;
import com.better.heybox.liquidglass.GlassSettingsSheet;
import com.better.heybox.liquidglass.LiquidGlassInstaller;
import com.better.heybox.MainModule;
import com.better.heybox.PreferenceReceiver;

public final class SettingsEntryHook {

    private final MainModule module;

    public SettingsEntryHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        hookSettingsEntry(cl);
        hookLaunchPrompt(cl);
    }

    /** 首次检测到独立液态玻璃模块且未做选择时，小黑盒打开即弹实现选择（每次进程启动至多一次） */
    private static volatile boolean sLaunchPromptShown;

    private void hookLaunchPrompt(ClassLoader cl) {
        try {
            Class<?> main = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Method onCreate = main.getDeclaredMethod("onCreate", android.os.Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        final Activity activity = (Activity) self;
                        // 等首帧渲染完成再弹，避免盖在启动画面上
                        activity.getWindow().getDecorView().postDelayed(
                                () -> maybeShowDisclaimer(activity), 1000L);
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "液态玻璃实现启动提示调度失败: " + t);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 液态玻璃实现启动提示 Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "液态玻璃实现启动提示 Hook 失败: " + t);
        }
    }

    private static final String ENTRY_TAG = "betterheybox_entry";

    private static final int REQUEST_EMBEDDED_EXPORT = 0x4248;
    private static final int REQUEST_EMBEDDED_IMPORT = 0x4249;
    private static final int REQUEST_EMBEDDED_LOG_EXPORT = 0x424A;
    private static final int REQUEST_PICK_SAVE_DIR = 0x424B;

    private interface PickCallback {
        void onResult(Uri uri);
    }

    private static PickCallback sPendingPick;

    private WeakReference<View> mSettingsPanel;

    enum Action {
        NONE, EDIT_LINK, CLEAR_DAILY, CHANNEL, EXPORT, IMPORT,
        EXPORT_LOG, CLEAR_LOG, VIEW_LOG, RUNTIME_STATUS, TARGET_STATUS, OPEN_WEB, PICK_DIR, RESET_GLASS, CHOOSE_GLASS,GLASS_SHEET,
        POST_LEVEL, POST_KEYWORDS, AI_PROVIDER, AI_PROMPT, AI_TEST, AI_MAX_TOKENS,
        REDIRECT_FORCE, REDIRECT_BLOCK, REDIRECT_TARGET, WEB_LOG, ABOUT,
        WATCH_USERS, WATCH_KEYWORDS, WATCH_IMPORT_FOLLOW, WATCH_TEST_PUSH, WATCH_CHECK,
        WATCH_DEBUG_PUSH3,WATCH_V2,OPEN_PAGE,
        WATCH_TOPICS, WATCH_IMPORT_TOPICS, WATCH_WINDOW, WATCH_INTERVAL, WATCH_SUGGEST_KEYWORDS,
        WATCH_TOPIC_SEARCH,GAME_LIB_TYPES, GAME_LIB_ENTRIES,GAME_LIB_SECTIONS,GAME_LIB_DIAG
    }

    private static class SwitchDef {
        final String title;
        final String desc;
        final String key;
        final boolean def;
        final boolean restart;
        final boolean clickRow;
        final String editKey;
        final Action action;

        SwitchDef(String title, String desc, String key, boolean def, boolean restart) {
            this(title, desc, key, def, restart, false, null, Action.NONE);
        }

        SwitchDef(String title, String desc, String key, boolean def, boolean restart,
                  boolean clickRow, String editKey) {
            this(title, desc, key, def, restart, clickRow, editKey, Action.EDIT_LINK);
        }

        SwitchDef(String title, String desc, String key, boolean def, boolean restart,
                  boolean clickRow, String editKey, Action action) {
            this.title = title;
            this.desc = desc;
            this.key = key;
            this.def = def;
            this.restart = restart;
            this.clickRow = clickRow;
            this.editKey = editKey;
            this.action = action;
        }
    }

        private static class SettingsGroup {
        final String title;
        final SwitchDef[] items;
        SettingsGroup(String title, SwitchDef[] items) {
            this.title = title;
            this.items = items;
        }
    }

        private static final SettingsGroup[] BASE_GROUPS = new SettingsGroup[]{
            new SettingsGroup("广告过滤", new SwitchDef[]{
                    new SwitchDef("屏蔽开屏广告", null, App.KEY_OPEN_SCREEN, true, false),
                    new SwitchDef("屏蔽信息流广告", null, App.KEY_FEED_AD, true, false),
                    new SwitchDef("屏蔽气泡广告", null, App.KEY_BUBBLE_AD, true, false),
                    new SwitchDef("屏蔽角标广告", null, App.KEY_CORNER_AD, true, false),
                    new SwitchDef("屏蔽推广贴", "首页推广卡、广告横幅、社区推广贴与广告位",
                            App.KEY_PROMOTE_AD, true, false),
                    new SwitchDef("诊断：记录首页流条目", "把每条首页流的判定信息写进日志",
                            App.KEY_FLOW_DIAGNOSE, false, false),
            }),
            new SettingsGroup("视频下载", new SwitchDef[]{
                    new SwitchDef("下载视频", "视频上显示下载入口", App.KEY_VIDEO_DOWNLOAD, true, false),
                    new SwitchDef("保存位置", "选择保存文件夹", null, false, false, true, null, Action.PICK_DIR),
                    new SwitchDef("转存 MP4", "合并后转为 MP4", App.KEY_VIDEO_TO_MP4, true, false),
            }),
            new SettingsGroup("解除复制", new SwitchDef[]{
                    new SwitchDef("解除复制", "恢复系统文本选择", App.KEY_COPY_POST, true, false),
                    new SwitchDef("自绘制文本选择", "修复选区异常", App.KEY_CUSTOM_TEXT_SELECT, false, false),
                    new SwitchDef("评论区自由复制", "长按菜单的复制可自由选择", App.KEY_COMMENT_FREE_COPY, true, false),
                    new SwitchDef("系统分享图片", "图片长按加入系统分享", App.KEY_SYSTEM_SHARE, true, false),
            }),
            new SettingsGroup("搜索页精简", new SwitchDef[]{
                    new SwitchDef("隐藏搜索页横幅", "搜索栏下方横幅推荐",
                            App.KEY_SEARCH_HIDE_BANNER, false, false),
                    new SwitchDef("隐藏「搜索发现」", "搜索页的搜索发现标题与推荐列表",
                            App.KEY_SEARCH_HIDE_DISCOVER, false, false),
                    new SwitchDef("隐藏「黑盒热榜」", "搜索页的热榜标签页与热词卡片",
                            App.KEY_SEARCH_HIDE_HOT_RANK, false, false),
            }),
            new SettingsGroup("分享净化", new SwitchDef[]{
                    new SwitchDef("净化分享链接", null, App.KEY_PURIFY_SHARE_LINK, true, false),
            }),
            new SettingsGroup("收藏管理", new SwitchDef[]{
                    new SwitchDef("自动清理失效收藏",
                            "打开收藏列表发现失效内容时自动清理",
                            App.KEY_FAVOUR_AUTO_CLEAN, false, false),
            }),
            new SettingsGroup("每日任务", new SwitchDef[]{
                    new SwitchDef("自动完成每日分享任务", null, App.KEY_DAILY_TASK_ENABLED, false, false),
                    new SwitchDef("完成后返回首页", "完成后自动退回首页", App.KEY_DAILY_TASK_BACK_HOME, true, false),
                    new SwitchDef("帖子链接", "任务一：分享帖子", null, false, false, true, App.KEY_DAILY_TASK_PICTURE),
                    new SwitchDef("游戏详情链接", "任务二：分享游戏详情", null, false, false, true, App.KEY_DAILY_TASK_NORMAL),
                    new SwitchDef("游戏评价链接", "任务三：分享游戏评价", null, false, false, true, App.KEY_DAILY_TASK_CHANNEL),
                    new SwitchDef("分享渠道", null, App.KEY_SHARE_CHANNEL, false, false, true, null, Action.CHANNEL),
                    new SwitchDef("清除今日打卡", null, null, false, false, true, null, Action.CLEAR_DAILY),
            }),
            new SettingsGroup("通用", new SwitchDef[]{
                    new SwitchDef("伪装通知权限", "伪装通知已开启，获得签到加成", App.KEY_FAKE_NOTIFICATION, false, false),
                    new SwitchDef("屏蔽更新", "屏蔽小黑盒更新入口", App.KEY_BLOCK_UPDATE, false, false),
                    new SwitchDef("记录日志", null, App.KEY_LOG, false, false),
                    new SwitchDef("调试：忽略版本降级限制", "清除版本下限，允许装回更旧的模块",
                            App.KEY_DEBUG_NO_DOWNGRADE, false, false),
                    new SwitchDef("详细日志", "关闭时只记错误日志；开启后记录全部并附带帖子信息",
                            App.KEY_VERBOSE_LOG, false, false),
                    new SwitchDef("查看日志", "预览最近 200 行模块日志", null, false, false,
                            true, null, Action.VIEW_LOG),
                    new SwitchDef("清除日志", "删除模块日志文件与运行检查点", null, false, false,
                            true, null, Action.CLEAR_LOG),
                    new SwitchDef("导出日志", null, null, false, false, true, null, Action.EXPORT_LOG),
            }),
            new SettingsGroup("配置备份", new SwitchDef[]{
                    new SwitchDef("导出配置", null, null, false, false, true, null, Action.EXPORT),
                    new SwitchDef("导入配置", null, null, false, false, true, null, Action.IMPORT),
            }),
            new SettingsGroup("关于", new SwitchDef[]{
                    new SwitchDef("关于 BetterHeybox", "版本与 GitHub 仓库",
                            null, false, false, true, null, Action.ABOUT),
            }),
    };
    private static SettingsGroup buildGameLibGroup() {
        return new SettingsGroup("游戏库精简", new SwitchDef[]{
                new SwitchDef("隐藏游戏库横幅", "游戏库顶端横幅推荐",
                        App.KEY_GAME_LIB_HIDE_BANNER, false, false),
                new SwitchDef("隐藏游戏库小分区", "黑盒商城、小程序等入口卡片",
                        App.KEY_GAME_LIB_HIDE_MENU, false, false),
                new SwitchDef("隐藏游戏库推荐分区", "「为你推荐」等分区标题与内容卡",
                        App.KEY_GAME_LIB_HIDE_SECTIONS, false, false),
                new SwitchDef("自定义隐藏类型", picksDesc(
                        GameLibraryCleanHook.selectedTypes(),
                        "勾选要隐藏的 type"),
                        null, false, false, true, null, Action.GAME_LIB_TYPES),
                new SwitchDef("隐藏指定入口卡片", picksDesc(
                        GameLibraryCleanHook.selectedNames(GameLibraryCleanHook.PICK_ENTRY),
                        "勾选要隐藏的入口卡片"),
                        null, false, false, true, null, Action.GAME_LIB_ENTRIES),
                new SwitchDef("隐藏指定推荐分区", picksDesc(
                        GameLibraryCleanHook.selectedNames(GameLibraryCleanHook.PICK_SECTION),
                        "点这里勾选要隐藏的分区"),
                        null, false, false, true, null, Action.GAME_LIB_SECTIONS),
                new SwitchDef("诊断：游戏库精简状态", "目标解析",
                        null, false, false, true, null, Action.GAME_LIB_DIAG),
        });
    }

    private static String picksDesc(Set<String> picked, String emptyHint) {
        if (picked == null || picked.isEmpty()) {
            return emptyHint;
        }
        StringBuilder sb = new StringBuilder("已选 " + picked.size() + " 项：");
        int shown = 0;
        for (String value : picked) {
            if (shown == 3) {
                sb.append(" 等");
                break;
            }
            if (shown > 0) {
                sb.append('、');
            }
            sb.append(value);
            shown++;
        }
        return sb.toString();
    }

    private static SettingsGroup buildBottomTabGroup(Activity activity) {
        String home = labelOr(BottomTabHook.runtimeTabLabel(0),
                MainModule.getHeyboxTabLabel(activity, "discover", "发现"));
        String slot2 = labelOr(BottomTabHook.runtimeTabLabel(1),
                MainModule.getHeyboxTabLabel(activity, "game_store", "游戏库"));
        String slot4 = labelOr(BottomTabHook.runtimeTabLabel(2),
                MainModule.getHeyboxTabLabel(activity, "bbs", "社区"));
        return new SettingsGroup("底部导航栏隐藏", new SwitchDef[]{
                new SwitchDef("隐藏「" + home + "」", null, App.KEY_HIDE_TAB_HOME, false, true),
                new SwitchDef("隐藏「" + slot2 + "」", null, App.KEY_HIDE_TAB_HOT, false, true),
                new SwitchDef("隐藏「" + slot4 + "」", null, App.KEY_HIDE_TAB_GAME, false, true),
                new SwitchDef("隐藏「加号」", null, App.KEY_HIDE_ADD, false, true),
        });
    }

    private static String labelOr(String runtime, String fallback) {
        return runtime != null && !runtime.trim().isEmpty() ? runtime : fallback;
    }

    private static final String TITLE_GENERAL = "通用";
    private static final String EXPERIMENTAL_HEYBOX_VERSION = "1.3.395";
    private static final long EXPERIMENTAL_HEYBOX_CODE = 1131L;

    private List<SettingsGroup> buildSettingsGroups(Activity activity) {
        List<SettingsGroup> groups = new ArrayList<>();
        com.better.heybox.watch.WatchConfig cfg =
                com.better.heybox.watch.WatchConfig.load(module);
        groups.add(new SettingsGroup("功能分类", new SwitchDef[]{
                entry(PAGE_ADS, "广告与内容过滤",
                        "广告、推广贴、发帖过滤、搜索 / 游戏库精简、分享净化"),
                entry(PAGE_UI, "界面与外观",
                        "液态玻璃、底栏隐藏、单列信息流"),
                entry(PAGE_BROWSE, "浏览与下载",
                        "解除复制、链接重定向、视频下载"),
                entry(PAGE_WATCH, "动态推送",
                        "关注 " + cfg.users.size() + " · 话题 " + cfg.topics.size()
                                + " · 关键词 " + cfg.keywords.size() + " · 时间窗 "
                                + cfg.windowText()),
                entry(PAGE_TASK, "每日任务", "一键完成三个分享任务"),
                entry(PAGE_COMMON, "通用与备份", "通知权限、更新、日志、备份、关于"),
        }));
        return groups;
    }

    private static final String PAGE_ADS = "ads";
    private static final String PAGE_UI = "ui";
    private static final String PAGE_BROWSE = "browse";
    private static final String PAGE_WATCH = "watch";
    private static final String PAGE_TASK = "task";
    private static final String PAGE_COMMON = "common";

    private static SwitchDef entry(String pageId, String title, String desc) {
        return new SwitchDef(title, desc, null, false, false, true, pageId, Action.OPEN_PAGE);
    }

    private static String pageTitle(String pageId) {
        if (PAGE_ADS.equals(pageId)) {
            return "广告与内容过滤";
        }
        if (PAGE_UI.equals(pageId)) {
            return "界面与外观";
        }
        if (PAGE_BROWSE.equals(pageId)) {
            return "浏览与下载";
        }
        if (PAGE_WATCH.equals(pageId)) {
            return "动态推送";
        }
        if (PAGE_TASK.equals(pageId)) {
            return "每日任务";
        }
        if (PAGE_COMMON.equals(pageId)) {
            return "通用与备份";
        }
        return "BetterHeybox 设置";
    }

    private List<SettingsGroup> buildPageGroups(Activity activity, String pageId) {
        List<SettingsGroup> groups = new ArrayList<>();
        if (PAGE_ADS.equals(pageId)) {
            addBase(groups, "广告过滤");
            insertPostFilterGroup(groups);
            addBase(groups, "搜索页精简");
            groups.add(buildGameLibGroup());
            addBase(groups, TITLE_SHARE_PURIFY);
            return groups;
        }
        if (PAGE_UI.equals(pageId)) {
            SettingsGroup glass = buildGlassGroup(activity);
            if (glass != null) {
                groups.add(glass);
            }
            groups.add(buildBottomTabGroup(activity));
            if (VersionUtils.isHeyboxBuild(activity, EXPERIMENTAL_HEYBOX_VERSION,
                    EXPERIMENTAL_HEYBOX_CODE)) {
                groups.add(new SettingsGroup("实验性功能", new SwitchDef[]{
                        new SwitchDef("屏蔽双列信息流",
                                "信息流恢复为单列",
                                App.KEY_SINGLE_COLUMN_FEED, false, false),
                }));
            }
            return groups;
        }
        if (PAGE_BROWSE.equals(pageId)) {
            addBase(groups, "解除复制");
            insertBrowserRedirectGroup(activity, groups);
            addBase(groups, "视频下载");
            return groups;
        }
        if (PAGE_WATCH.equals(pageId)) {
            return buildWatchV2Groups(activity);
        }
        if (PAGE_TASK.equals(pageId)) {
            addBase(groups, "每日任务");
            return groups;
        }
        addBase(groups, TITLE_GENERAL);
        if (BuildFlags.DEBUG) {
            addRuntimeStatusRow(groups);
        }
        addBase(groups, "配置备份");
        addBase(groups, "关于");
        return groups;
    }
    private static void addBase(List<SettingsGroup> out, String title) {
        for (SettingsGroup g : BASE_GROUPS) {
            if (g.title.equals(title)) {
                out.add(g);
                return;
            }
        }
    }

    private static final String TITLE_AD_FILTER = "广告过滤";

    private List<SettingsGroup> buildWatchV2Groups(Activity activity) {
        final com.better.heybox.watch.WatchConfig cfg =
                com.better.heybox.watch.WatchConfig.load(module);
        List<SettingsGroup> groups = new ArrayList<>();

        groups.add(new SettingsGroup("总开关", new SwitchDef[]{
                new SwitchDef("关注动态提醒",
                        "打开小黑盒时检查新动态",
                        App.KEY_WATCH_ENABLED, false, false),
        }));

        groups.add(new SettingsGroup("监控目标", new SwitchDef[]{
                new SwitchDef("关注对象",
                        cfg.users.isEmpty() ? "一行一个 userid 或主页链接"
                                : "已配置 " + cfg.users.size() + " 个",
                        null, false, false, true, null, Action.WATCH_USERS),
                new SwitchDef("导入关注列表", "读取「我关注的」并追加",
                        null, false, false, true, null, Action.WATCH_IMPORT_FOLLOW),
                new SwitchDef("关注的话题",
                        cfg.topics.isEmpty() ? "一行一个：话题名或话题id|话题名"
                                : "已配置 " + cfg.topics.size() + " 个",
                        null, false, false, true, null, Action.WATCH_TOPICS),
                new SwitchDef("导入关注话题", "读取「我关注的话题」",
                        null, false, false, true, null, Action.WATCH_IMPORT_TOPICS),
                new SwitchDef("搜索话题", "搜索话题并一键关注",
                        null, false, false, true, null, Action.WATCH_TOPIC_SEARCH),
                new SwitchDef("监控关键词",
                        cfg.keywords.isEmpty() ? "命中即提醒；regex: 为正则"
                                : "已配置 " + cfg.keywords.size() + " 个",
                        null, false, false, true, null, Action.WATCH_KEYWORDS),
                new SwitchDef("推荐关键词", "从热搜词里挑关键词",
                        null, false, false, true, null, Action.WATCH_SUGGEST_KEYWORDS),
        }));

        groups.add(new SettingsGroup("抓取范围", new SwitchDef[]{
                new SwitchDef("关键词只匹配标题", "不匹配正文",
                        App.KEY_WATCH_TITLE_ONLY, false, false),
                new SwitchDef("话题/关键词拉流",
                        "主动拉取话题/关键词最新帖",
                        App.KEY_WATCH_STREAM_FETCH, false, false),
                new SwitchDef("获取时间窗", "只提醒 " + cfg.windowText() + " 内的帖子",
                        null, false, false, true, null, Action.WATCH_WINDOW),
                new SwitchDef("检查间隔", "自动检查间隔 " + cfg.intervalMin + " 分钟",
                        null, false, false, true, null, Action.WATCH_INTERVAL),
        }));

        groups.add(new SettingsGroup("提醒方式", new SwitchDef[]{
                new SwitchDef("应用内横幅", "界面顶部弹出提醒",
                        App.KEY_WATCH_BANNER, true, false),
                new SwitchDef("系统通知", "通知栏提醒，可点击跳转",
                        App.KEY_WATCH_NOTIFY, true, false),
        }));

        groups.add(new SettingsGroup("第三方推送", new SwitchDef[]{
                new SwitchDef("第三方推送", "转发新动态到外部渠道",
                        App.KEY_WATCH_PUSH_ENABLED, false, false),
                new SwitchDef("钉钉机器人", "webhook 地址或 access_token",
                        null, false, false, true, App.KEY_WATCH_PUSH_DINGTALK),
                new SwitchDef("WxPusher", "appToken|topicId 或 appToken|uid:UID",
                        null, false, false, true, App.KEY_WATCH_PUSH_WXPUSHER),
                new SwitchDef("AstrBot 机器人",
                        "http://主机:6199|群号|令牌",
                        null, false, false, true, App.KEY_WATCH_PUSH_ONEBOT),
                new SwitchDef("自定义 webhook", "支持 {title}{link} 等占位符",
                        null, false, false, true, App.KEY_WATCH_PUSH_CUSTOM),
        }));

        groups.add(new SettingsGroup("测试与调试", new SwitchDef[]{
                new SwitchDef("测试提醒", "发送一条测试消息",
                        null, false, false, true, null, Action.WATCH_TEST_PUSH),
                new SwitchDef("立即检查", "手动检查一次",
                        null, false, false, true, null, Action.WATCH_CHECK),
                new SwitchDef("调试：推送最近 3 条",
                        "立即推送最近 3 条",
                        null, false, false, true, null, Action.WATCH_DEBUG_PUSH3),
        }));

        return groups;
    }

    private static final String[] WATCH_WINDOW_LABELS = {
            "30 分钟", "1 小时", "3 小时", "6 小时", "12 小时", "1 天", "3 天", "7 天", "30 天"};
    private static final int[] WATCH_WINDOW_MINUTES = {
            30, 60, 180, 360, 720, 1440, 4320, 10080, 43200};
    private static final String[] WATCH_INTERVAL_LABELS = {
            "5 分钟", "10 分钟", "15 分钟", "30 分钟", "1 小时", "3 小时", "6 小时", "12 小时"};
    private static final int[] WATCH_INTERVAL_MINUTES = {5, 10, 15, 30, 60, 180, 360, 720};

    private int currentWatchWindowMin() {
        com.better.heybox.watch.WatchConfig cfg = com.better.heybox.watch.WatchConfig.load(module);
        return cfg.windowMin;
    }

    private int currentWatchIntervalMin() {
        com.better.heybox.watch.WatchConfig cfg = com.better.heybox.watch.WatchConfig.load(module);
        return cfg.intervalMin;
    }

    private static int nearestIndex(int[] values, int cur) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - cur) < Math.abs(values[best] - cur)) {
                best = i;
            }
        }
        return best;
    }

    private void showWatchWindowDialog(final Activity activity) {
        withHeyboxDialog(activity, spec -> showWatchWindowDialogNative(activity, spec),
                () -> showWatchWindowDialogFallback(activity));
    }

    private void showWatchWindowDialogNative(Activity activity,
                                             DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        LinearLayout list = buildOptionRowList(activity, WATCH_WINDOW_LABELS,
                nearestIndex(WATCH_WINDOW_MINUTES, currentWatchWindowMin()));
        Dialog dialog = spec.buildAndShow(activity, "获取时间窗", list, null, null,
                "取消", (d, w) -> d.dismiss());
        bindOptionRows(dialog, list, index -> applyWatchWindow(activity, index));
    }

    private void showWatchWindowDialogFallback(Activity activity) {
        showSingleChoiceFallback(activity, "获取时间窗", WATCH_WINDOW_LABELS,
                nearestIndex(WATCH_WINDOW_MINUTES, currentWatchWindowMin()),
                index -> applyWatchWindow(activity, index));
    }

    private void applyWatchWindow(Activity activity, int index) {
        try {
            HeyboxPrefs.init(activity);
            HeyboxPrefs.setString(App.KEY_WATCH_WINDOW_MIN, String.valueOf(WATCH_WINDOW_MINUTES[index]));
            LogRecorder.recordEvent("动态推送时间窗: " + WATCH_WINDOW_LABELS[index]);
            Toast.makeText(activity, "获取时间窗已设为 " + WATCH_WINDOW_LABELS[index],
                    Toast.LENGTH_SHORT).show();
            refreshEmbeddedPanel(activity);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "设置获取时间窗失败: " + t);
        }
    }

    private void showWatchIntervalDialog(final Activity activity) {
        withHeyboxDialog(activity, spec -> showWatchIntervalDialogNative(activity, spec),
                () -> showWatchIntervalDialogFallback(activity));
    }

    private void showWatchIntervalDialogNative(Activity activity,
                                               DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        LinearLayout list = buildOptionRowList(activity, WATCH_INTERVAL_LABELS,
                nearestIndex(WATCH_INTERVAL_MINUTES, currentWatchIntervalMin()));
        Dialog dialog = spec.buildAndShow(activity, "检查间隔", list, null, null,
                "取消", (d, w) -> d.dismiss());
        bindOptionRows(dialog, list, index -> applyWatchInterval(activity, index));
    }

    private void showWatchIntervalDialogFallback(Activity activity) {
        showSingleChoiceFallback(activity, "检查间隔", WATCH_INTERVAL_LABELS,
                nearestIndex(WATCH_INTERVAL_MINUTES, currentWatchIntervalMin()),
                index -> applyWatchInterval(activity, index));
    }

    private void applyWatchInterval(Activity activity, int index) {
        try {
            HeyboxPrefs.init(activity);
            HeyboxPrefs.setString(App.KEY_WATCH_INTERVAL_MIN,
                    String.valueOf(WATCH_INTERVAL_MINUTES[index]));
            LogRecorder.recordEvent("动态推送检查间隔: " + WATCH_INTERVAL_LABELS[index]);
            Toast.makeText(activity, "检查间隔已设为 " + WATCH_INTERVAL_LABELS[index],
                    Toast.LENGTH_SHORT).show();
            refreshEmbeddedPanel(activity);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "设置检查间隔失败: " + t);
        }
    }
    private void importWatchTopics(final Activity activity) {
        Toast.makeText(activity, "正在读取关注话题…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String[] msg = new String[1];
            try {
                List<String[]> list = com.better.heybox.watch.WatchFetcher.fetchFollowedTopics(
                        com.better.heybox.watch.WatchConfig.MAX_TOPICS);
                if (list.isEmpty()) {
                    msg[0] = "未取到关注话题（详情见模块日志）";
                } else {
                    List<String> exist = com.better.heybox.watch.WatchConfig
                            .splitLines(module.getString(App.KEY_WATCH_TOPICS, ""), 999);
                    java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(exist);
                    int added = 0;
                    for (String[] t : list) {
                        String line = com.better.heybox.watch.WatchConfig.formatTopic(t[0], t[1]);
                        if (line.isEmpty() || set.contains(line)
                                || set.size() >= com.better.heybox.watch.WatchConfig.MAX_TOPICS) {
                            continue;
                        }
                        set.add(line);
                        added++;
                    }
                    if (added > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (String line : set) {
                            sb.append(line).append('\n');
                        }
                        boolean ok = HeyboxPrefs.setString(App.KEY_WATCH_TOPICS, sb.toString());
                        LogRecorder.recordEvent("导入关注话题已写入: added=" + added
                                + ", total=" + set.size() + ", ok=" + ok);
                    }
                    msg[0] = added > 0 ? ("已导入 " + added + " 个话题，共 " + set.size()
                            + " 个（重进面板可见）") : "没有新的话题可导入";
                }
            } catch (Throwable t) {
                msg[0] = "导入失败：" + t;
            }
            final String out = msg[0];
            activity.runOnUiThread(() -> {
                try {
                    Toast.makeText(activity, out, Toast.LENGTH_LONG).show();
                    refreshEmbeddedPanel(activity);
                } catch (Throwable ignored) {
                }
            });
        }, "betterheybox-watch-topic-import").start();
    }

    private void showTopicSearchDialog(final Activity activity) {
        withHeyboxDialog(activity, spec -> {
            final EditText input = buildTopicSearchInput(activity);
            spec.buildAndShow(activity, "搜索话题", input, "搜索",
                    (d, w) -> runTopicSearch(activity, input.getText().toString()),
                    "取消", (d, w) -> d.dismiss());
        }, () -> {
            try {
                final EditText input = buildTopicSearchInput(activity);
                new AlertDialog.Builder(activity)
                        .setTitle("搜索话题")
                        .setView(input)
                        .setPositiveButton("搜索", (d, w) ->
                                runTopicSearch(activity, input.getText().toString()))
                        .setNegativeButton("取消", null)
                        .show();
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "搜索话题弹窗失败: " + t);
            }
        });
    }

    private EditText buildTopicSearchInput(Activity activity) {
        EditText input = new EditText(activity);
        int pad = module.dp(activity, 10);
        input.setPadding(pad, pad, pad, pad);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("例如：原神 / 数码硬件");
        int bgId = hostResId(activity, "bg_dialog_edit", "drawable", 0);
        if (bgId != 0) {
            input.setBackgroundResource(bgId);
        }
        return input;
    }

    private void runTopicSearch(final Activity activity, final String keyword) {
        final String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            Toast.makeText(activity, "请输入关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, "正在搜索「" + kw + "」…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final List<String[]> found = new ArrayList<>();
            try {
                found.addAll(com.better.heybox.watch.WatchFetcher.fetchTopicSearch(kw, 10));
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "话题搜索失败: " + t);
            }
            activity.runOnUiThread(() -> {
                try {
                    if (found.isEmpty()) {
                        Toast.makeText(activity, "没搜到话题（详情见模块日志）",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    final String[] labels = new String[found.size()];
                    for (int i = 0; i < found.size(); i++) {
                        String id = found.get(i)[0];
                        labels[i] = found.get(i)[1] + (id == null ? "" : ("  #" + id));
                    }
                    withHeyboxDialog(activity, spec -> {
                        LinearLayout list = buildOptionRowList(activity, labels, -1);
                        Dialog dialog = spec.buildAndShow(activity,
                                "搜索结果（点击加入）", list, null, null,
                                "取消", (d, w) -> d.dismiss());
                        bindOptionRows(dialog, list, index -> addWatchTopic(activity,
                                found.get(index)[0], found.get(index)[1]));
                    }, () -> showListPickFallback(activity, "搜索结果（点击加入）", labels,
                            index -> addWatchTopic(activity, found.get(index)[0],
                                    found.get(index)[1])));
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "话题搜索结果弹窗失败: " + t);
                }
            });
        }, "betterheybox-topic-search").start();
    }

    private void addWatchTopic(Activity activity, String id, String name) {
        try {
            HeyboxPrefs.init(activity);
            String line = com.better.heybox.watch.WatchConfig.formatTopic(id, name);
            if (line.isEmpty()) {
                return;
            }
            List<String> exist = com.better.heybox.watch.WatchConfig
                    .splitLines(module.getString(App.KEY_WATCH_TOPICS, ""), 999);
            for (String e : exist) {
                if (e.equals(line) || com.better.heybox.watch.WatchConfig.topicName(e).equals(name)) {
                    Toast.makeText(activity, "「" + name + "」已在关注的话题里",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            if (exist.size() >= com.better.heybox.watch.WatchConfig.MAX_TOPICS) {
                Toast.makeText(activity, "关注的话题最多 "
                        + com.better.heybox.watch.WatchConfig.MAX_TOPICS + " 个",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            exist.add(line);
            StringBuilder sb = new StringBuilder();
            for (String l : exist) {
                sb.append(l).append('\n');
            }
            HeyboxPrefs.setString(App.KEY_WATCH_TOPICS, sb.toString());
            LogRecorder.recordEvent("已加入关注话题: " + line);
            Toast.makeText(activity, "已加入话题：" + name, Toast.LENGTH_SHORT).show();
            refreshEmbeddedPanel(activity);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "加入话题失败: " + t);
        }
    }

    private void suggestWatchKeywords(final Activity activity) {
        Toast.makeText(activity, "正在获取推荐关键词…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final List<String> words = new ArrayList<>();
            try {
                words.addAll(com.better.heybox.watch.WatchFetcher.fetchHotWords(null, 12));
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "推荐关键词获取失败: " + t);
            }
            activity.runOnUiThread(() -> {
                try {
                    if (words.isEmpty()) {
                        Toast.makeText(activity, "没取到推荐关键词（详情见模块日志）",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    showWatchSuggestDialog(activity, words.toArray(new String[0]));
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "推荐关键词弹窗失败: " + t);
                }
            });
        }, "betterheybox-watch-suggest").start();
    }

    private void showWatchSuggestDialog(final Activity activity, final String[] labels) {
        withHeyboxDialog(activity, spec -> {
            LinearLayout list = buildOptionRowList(activity, labels, -1);
            Dialog dialog = spec.buildAndShow(activity, "推荐关键词（点击添加）", list, null, null,
                    "取消", (d, w) -> d.dismiss());
            bindOptionRows(dialog, list, index -> addWatchKeyword(activity, labels[index]));
        }, () -> showListPickFallback(activity, "推荐关键词（点击添加）", labels,
                index -> addWatchKeyword(activity, labels[index])));
    }

    private void showListPickFallback(final Activity activity, String title,
                                      final String[] labels, final OptionPick onPick) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setItems(labels, (dialog, which) -> {
                        onPick.pick(which);
                        dialog.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "列表弹框失败(" + title + "): " + t);
        }
    }

    private void addWatchKeyword(Activity activity, String word) {
        try {
            HeyboxPrefs.init(activity);
            List<String> exist = com.better.heybox.watch.WatchConfig
                    .splitLines(module.getString(App.KEY_WATCH_KEYWORDS, ""), 999);
            for (String e : exist) {
                if (e.equals(word)) {
                    Toast.makeText(activity, "「" + word + "」已在关键词里", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            if (exist.size() >= com.better.heybox.watch.WatchConfig.MAX_KEYWORDS) {
                Toast.makeText(activity, "关键词最多 "
                        + com.better.heybox.watch.WatchConfig.MAX_KEYWORDS + " 个", Toast.LENGTH_SHORT).show();
                return;
            }
            exist.add(word);
            StringBuilder sb = new StringBuilder();
            for (String line : exist) {
                sb.append(line).append('\n');
            }
            HeyboxPrefs.setString(App.KEY_WATCH_KEYWORDS, sb.toString());
            LogRecorder.recordEvent("推荐关键词已加入: " + word);
            Toast.makeText(activity, "已添加关键词：" + word, Toast.LENGTH_SHORT).show();
            refreshEmbeddedPanel(activity);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "添加关键词失败: " + t);
        }
    }

    private void importWatchFollowing(final Activity activity) {
        Toast.makeText(activity, "正在读取关注列表…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String[] msg = new String[1];
            try {
                java.util.List<String[]> list = com.better.heybox.watch.WatchFetcher
                        .fetchFollowing(com.better.heybox.watch.WatchFetcher.FOLLOW_IMPORT_LIMIT);
                if (list.isEmpty()) {
                    msg[0] = "未取到关注列表：请确认小黑盒已登录（详情见模块日志）";
                } else {
                    java.util.List<String> exist = com.better.heybox.watch.WatchConfig
                            .splitLines(module.getString(App.KEY_WATCH_USERS, ""), 999);
                    java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(exist);
                    int added = 0;
                    for (String[] u : list) {
                        String uid = u[0];
                        boolean dup = false;
                        for (String e : set) {
                            String parsed = com.better.heybox.watch.WatchConfig.parseUserId(e);
                            if (uid.equals(parsed)) {
                                dup = true;
                                break;
                            }
                        }
                        if (dup || set.size() >= com.better.heybox.watch.WatchConfig.MAX_USERS) {
                            continue;
                        }
                        set.add(u[1] == null || u[1].isEmpty() ? uid : (uid + "  # " + u[1]));
                        added++;
                    }
                    if (added > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (String line : set) {
                            sb.append(line).append('\n');
                        }
                        boolean ok = HeyboxPrefs.setString(App.KEY_WATCH_USERS, sb.toString());
                        LogRecorder.recordEvent("导入关注列表已写入: added=" + added
                                + ", total=" + set.size() + ", ok=" + ok);
                    }
                    msg[0] = added > 0 ? ("已导入 " + added + " 个关注，共 " + set.size()
                            + " 个（重进面板可见）")
                            : "没有新的关注对象可导入";
                }
            } catch (Throwable t) {
                msg[0] = "导入失败：" + t;
            }
            activity.runOnUiThread(() -> {
                try {
                    Toast.makeText(activity, msg[0], Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
        }, "betterheybox-watch-import").start();
    }

    private void testWatchPush(final Activity activity) {
        Toast.makeText(activity, "正在发送测试提醒…", Toast.LENGTH_SHORT).show();
        try {
            com.better.heybox.watch.WatchConfig cfg =
                    com.better.heybox.watch.WatchConfig.load(module);
            com.better.heybox.watch.WatchItem item = new com.better.heybox.watch.WatchItem(
                    "betterheybox-test", "测试消息 · BetterHeybox",
                    "这是一条测试提醒：关注对象发布新动态 / 关键词命中时会这样提示",
                    "0", "BetterHeybox", System.currentTimeMillis() / 1000L, "keyword", "");
            final boolean banner = com.better.heybox.watch.WatchOutput.testBanner(activity, item);
            final boolean notify = com.better.heybox.watch.WatchOutput.notifyPost(activity, item);
            new Thread(() -> {
                final String[] msg = new String[1];
                try {
                    int n = com.better.heybox.watch.WatchOutput.pushAll(cfg, item);
                    String push = !cfg.pushEnabled ? "推送未开启"
                            : (n > 0 ? ("推送 " + n + " 个渠道") : "推送失败：检查地址");
                    msg[0] = (banner ? "横幅 ✓" : "横幅 ✗")
                            + " · " + (notify ? "通知 ✓" : "通知 ✗") + " · " + push;
                } catch (Throwable t) {
                    msg[0] = "推送测试异常：" + t;
                }
                activity.runOnUiThread(() -> {
                    try {
                        Toast.makeText(activity, msg[0], Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                });
            }, "betterheybox-watch-test").start();
        } catch (Throwable t) {
            Toast.makeText(activity, "测试失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private void insertPostFilterGroup(List<SettingsGroup> groups) {
        int minLevel = 0;
        try {
            minLevel = Integer.parseInt(module.getString(App.KEY_POST_MIN_LEVEL, "0").trim());
        } catch (Throwable ignored) {
        }
        int kwCount = 0;
        for (String s : module.getString(App.KEY_POST_KEYWORDS, "").split("\n")) {
            if (!s.trim().isEmpty()) {
                kwCount++;
            }
        }
        String providerId = module.getString(App.KEY_AI_PROVIDER, "");
        SettingsGroup group = new SettingsGroup("发帖过滤", new SwitchDef[]{
                new SwitchDef("屏蔽视频帖",
                        "信息流中隐藏视频帖（首页推荐/瀑布流/社区/话题/榜单）；"
                                + "按宿主 link_style 与 has_video 判定",
                        App.KEY_BLOCK_VIDEO_POST, false, false),
                new SwitchDef("屏蔽低等级发帖",
                        minLevel > 0 ? "当前：屏蔽 Lv" + minLevel + " 以下"
                                : "选择等级阈值",
                        null, false, false, true, null, Action.POST_LEVEL),
                new SwitchDef("屏蔽无等级用户",
                        "无等级账号一并屏蔽",
                        App.KEY_POST_NO_LEVEL, false, false),
                new SwitchDef("关键词屏蔽",
                        kwCount > 0 ? "已配置 " + kwCount + " 个"
                                : "命中标题或正文即屏蔽",
                        null, false, false, true, null, Action.POST_KEYWORDS),
                new SwitchDef("AI 标题党识别",
                        "标题会发送给 AI 服务商",
                        App.KEY_POST_AI_ENABLED, false, false),
                new SwitchDef("AI 提供商",
                        "当前：" + AIClickbaitChecker.providerLabel(providerId),
                        null, false, false, true, null, Action.AI_PROVIDER),
                new SwitchDef("API 地址", "OpenAI 兼容接口地址",
                        null, false, false, true, App.KEY_AI_BASE_URL, Action.EDIT_LINK),
                new SwitchDef("模型", "OpenAI 兼容模型名", null, false, false,
                        true, App.KEY_AI_MODEL, Action.EDIT_LINK),
                new SwitchDef("API Token", "本地模型可留空",
                        null, false, false, true, App.KEY_AI_TOKEN, Action.EDIT_LINK),
                new SwitchDef("判定提示词", "留空用内置提示词",
                        null, false, false, true, null, Action.AI_PROMPT),
                new SwitchDef("输出 Token 上限",
                        "当前：" + AIClickbaitChecker.maxTokens(module) + "；"
                                + "过小会截断结果",
                        null, false, false, true, null, Action.AI_MAX_TOKENS),
                new SwitchDef("测试 AI 连接", null, null, false, false,
                        true, null, Action.AI_TEST),
        });
        int insertAt = groups.size();
        for (int i = 0; i < groups.size(); i++) {
            if (TITLE_AD_FILTER.equals(groups.get(i).title)) {
                insertAt = i + 1;
                break;
            }
        }
        groups.add(insertAt, group);
    }

    private static final String TITLE_SHARE_PURIFY = "分享净化";

    private void insertBrowserRedirectGroup(Activity activity, List<SettingsGroup> groups) {
        int forceCount = countConfiguredLines(module.getString(App.KEY_BROWSER_REDIRECT_FORCE, ""));
        int blockCount = countConfiguredLines(module.getString(App.KEY_BROWSER_REDIRECT_BLOCK, ""));
        SettingsGroup group = new SettingsGroup("网页", new SwitchDef[]{
                new SwitchDef("重定向外部链接",
                        "外部链接用系统浏览器打开", App.KEY_BROWSER_REDIRECT, false, false),
                new SwitchDef("包含小黑盒域名",
                        "小黑盒域名也重定向",
                        App.KEY_BROWSER_REDIRECT_KNOWN, false, false),
                new SwitchDef("重定向浏览器",
                        "当前：" + browserTargetLabel(activity),
                        null, false, false, true, null, Action.REDIRECT_TARGET),
                new SwitchDef("强制重定向域名",
                        forceCount > 0 ? "已配置 " + forceCount + " 个"
                                : "一行一个域名",
                        null, false, false, true, null, Action.REDIRECT_FORCE),
                new SwitchDef("强制内置域名",
                        blockCount > 0 ? "已配置 " + blockCount + " 个"
                                : "一行一个域名",
                        null, false, false, true, null, Action.REDIRECT_BLOCK),
                new SwitchDef("网页 DevTools", "开启 Chrome 远程调试", App.KEY_WEBVIEW_DEVTOOLS, false, false),
                new SwitchDef("打开网页", "用内置浏览器打开网页", null, false, false,
                        true, App.KEY_WEBVIEW_ENTRY_URL, Action.OPEN_WEB),
                new SwitchDef("网页日志",
                        "记录打开过的页面与标题", App.KEY_WEB_LOG, false, false),
                new SwitchDef("查看网页日志", null, null, false, false, true, null, Action.WEB_LOG),
        });
        int insertAt = groups.size();
        for (int i = 0; i < groups.size(); i++) {
            if (TITLE_SHARE_PURIFY.equals(groups.get(i).title)) {
                insertAt = i + 1;
                break;
            }
        }
        groups.add(insertAt, group);
    }

    private static int countConfiguredLines(String raw) {
        int count = 0;
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private SettingsGroup buildGlassGroup(Activity activity) {
        boolean switchable = GlassProvider.isHbmodInstalled(activity)
                || GlassProvider.prefersHbmod(module);
        boolean ownGlass = !GlassProvider.prefersHbmod(module);
        List<SwitchDef> rows = new ArrayList<>();
        if (switchable) {
            String label = GlassProvider.providerLabel(
                    module.getString(App.KEY_GLASS_PROVIDER, ""));
            rows.add(new SwitchDef("液态玻璃提供方",
                    "当前：" + label, null, false, false, true, null, Action.CHOOSE_GLASS));
        }
        if (ownGlass) {
            rows.add(new SwitchDef("液态玻璃底栏", "底栏显示液态玻璃", App.KEY_LIQUID_GLASS, true, false));
            rows.add(new SwitchDef("沉浸式小白条", "底栏延伸至手势区域", App.KEY_GLASS_IMMERSIVE, true, false));
            rows.add(new SwitchDef("自适应反色", "文字图标随背景反色", App.KEY_GLASS_ADAPTIVE, true, false));
            rows.add(new SwitchDef("加长选中 Tab",
                    "隐藏标签后选中项加长，底栏随可见数量收缩",
                    App.KEY_GLASS_FIT_TABS, false, false));
            rows.add(new SwitchDef("玻璃条宽度",
                    "宽度模式 / 左右边距 / Tab 宽度，打开调节面板",
                    null, false, false, true, null, Action.GLASS_SHEET));
            rows.add(new SwitchDef("暗色模式底色", "例如 #000000", null, false, false, true, App.KEY_GLASS_DARK_COLOR));
            rows.add(new SwitchDef("暗色模式不透明度", "5-98 的百分比", null, false, false, true, App.KEY_GLASS_DARK_ALPHA));
            rows.add(new SwitchDef("亮色模式底色", "例如 #FFFFFF", null, false, false, true, App.KEY_GLASS_LIGHT_COLOR));
            rows.add(new SwitchDef("亮色模式不透明度", "5-98 的百分比", null, false, false, true, App.KEY_GLASS_LIGHT_ALPHA));
            rows.add(new SwitchDef("玻璃条高度", "0 自动，或 51-99 dp", null, false, false, true, App.KEY_GLASS_BAR_HEIGHT));
            rows.add(new SwitchDef("距屏幕底部", "0-40 dp", null, false, false, true, App.KEY_GLASS_BAR_OFFSET));
            rows.add(new SwitchDef("恢复液态玻璃默认设置", "恢复默认外观与布局", null, false, false, true, null, Action.RESET_GLASS));
        }
        if (rows.isEmpty()) {
            return null;
        }
        return new SettingsGroup("液态玻璃", rows.toArray(new SwitchDef[0]));
    }

    private static void addRuntimeStatusRow(List<SettingsGroup> groups) {
        for (int i = 0; i < groups.size(); i++) {
            SettingsGroup g = groups.get(i);
            if (TITLE_GENERAL.equals(g.title)) {
                SwitchDef[] items = new SwitchDef[g.items.length + 2];
                System.arraycopy(g.items, 0, items, 0, g.items.length);
                items[g.items.length] = new SwitchDef(
                        "运行状态", "查看模块运行检查点", null, false, false,
                        true, null, Action.RUNTIME_STATUS);
                items[g.items.length + 1] = new SwitchDef(
                        "目标解析状态", "查看混淆名定位结果与判定依据", null, false, false,
                        true, null, Action.TARGET_STATUS);
                groups.set(i, new SettingsGroup(g.title, items));
                return;
            }
        }
    }
    private void hookSettingsEntry(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.account.GeneralSettingsActivity", false, cl);
            Method setupMethod = findSetupMethod(clazz);
            if (setupMethod == null) {
                setupMethod = findLifecycleFallback(clazz);
            }
            if (setupMethod == null) {
                module.logd(Log.ERROR, module.TAG, "✘ 未找到设置页入口方法");
                return;
            }
            final Class<?> entryClass = clazz;
            module.hook(setupMethod).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof Activity && entryClass.isInstance(thisObj)) {
                        final Activity activity = (Activity) thisObj;
                        activity.getWindow().getDecorView().post(new Runnable() {
                            @Override
                            public void run() {
                                insertSettingsEntryWithRetry(activity, 0);
                            }
                        });
                    }
                } catch (Throwable t) {
                    module.logd(Log.ERROR, module.TAG, "设置入口插入调度异常", t);
                }
                return result;
            });
            hookActivityResult(clazz);
            module.logd(Log.INFO, module.TAG, "✔ 设置页入口 Hook 已安装 (" + setupMethod.getName() + "+retry)");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 设置页入口 Hook 失败", t);
        }
    }
    private static final String[] SETUP_METHOD_CANDIDATES = {"N1", "L1", "G1"};

    private Method findSetupMethod(Class<?> clazz) {
        for (String name : SETUP_METHOD_CANDIDATES) {
            try {
                return clazz.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private Method findLifecycleFallback(Class<?> clazz) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod("onResume");
                module.logd(Log.WARN, module.TAG, "设置页入口混淆名失效，回退生命周期 Hook: "
                        + c.getSimpleName() + ".onResume");
                return m;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private void hookActivityResult(Class<?> clazz) {
        try {
            Method m = findOnActivityResult(clazz);
            if (m == null) {
                module.logd(Log.WARN, module.TAG, "未找到 onActivityResult，内嵌面板导入/导出不可用");
                return;
            }
            module.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object a0 = chain.getArg(0);
                    Object a1 = chain.getArg(1);
                    Object a2 = chain.getArg(2);
                    int requestCode = a0 instanceof Integer ? (Integer) a0 : 0;
                    int resultCode = a1 instanceof Integer ? (Integer) a1 : 0;
                    Intent data = a2 instanceof Intent ? (Intent) a2 : null;
                    handleEmbeddedPickResult(requestCode, resultCode, data);
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "处理文件选择结果异常: " + t);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ onActivityResult Hook 已安装 ("
                    + m.getDeclaringClass().getSimpleName() + "." + m.getName() + ")");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "onActivityResult Hook 失败，内嵌面板导入/导出不可用: " + t);
        }
    }

    private Method findOnActivityResult(Class<?> clazz) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private void handleEmbeddedPickResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_SAVE_DIR) {
            handleSaveDirResult(resultCode, data);
            return;
        }
        if (requestCode != REQUEST_EMBEDDED_EXPORT && requestCode != REQUEST_EMBEDDED_IMPORT
                && requestCode != REQUEST_EMBEDDED_LOG_EXPORT) {
            return;
        }
        PickCallback cb = sPendingPick;
        sPendingPick = null;
        if (cb == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            cb.onResult(data.getData());
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "执行文件选择回调失败: " + t);
        }
    }

    private void handleSaveDirResult(int resultCode, Intent data) {
        Context context = mSettingsPanel != null && mSettingsPanel.get() != null
                ? ((View) mSettingsPanel.get()).getContext() : null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri treeUri = data.getData();
        try {
            if (context != null) {
                context.getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "持久化保存位置授权失败: " + t);
        }
        HeyboxPrefs.setString(App.KEY_VIDEO_DIR, treeUri.toString());
        String name = context != null ? queryDirDisplayName(context, treeUri) : null;
        Toast.makeText(context, "保存位置已设置：" + (name != null ? name : treeUri),
                Toast.LENGTH_LONG).show();
        LogRecorder.recordEvent("视频保存位置已设置: " + treeUri);
    }

    private String queryDirDisplayName(Context context, Uri treeUri) {
        try {
            android.database.Cursor c = context.getContentResolver().query(
                    treeUri,
                    new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        return c.getString(0);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private TextView buildDialogMessage(Activity activity, String text) {
        TextView message = new TextView(activity);
        int pad = module.dp(activity, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, pad, 0, pad * 2);
        message.setLayoutParams(lp);
        message.setPadding(pad, pad, pad, pad);
        message.setText(text);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int textColor = hostColor(activity, "color_text_primary_day_night", 0);
        if (textColor != 0) {
            message.setTextColor(textColor);
        }
        return message;
    }

    private void withHeyboxDialog(Activity activity, NativeDialogCall nativeCall, Runnable fallback) {
        DexKitResolver.getHeyboxDialogSpec(module, activity, new DexKitResolver.SpecCallback() {
            @Override
            public void onReady(DexKitResolver.HeyboxDialogSpec spec) {
                try {
                    nativeCall.call(spec);
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "小黑盒原生弹窗不可用，回退系统弹窗: " + t);
                    fallback.run();
                }
            }

            @Override
            public void onFailed(String reason) {
                module.logd(Log.WARN, module.TAG, "小黑盒原生弹窗解析失败(" + reason + ")，回退系统弹窗");
                fallback.run();
            }
        });
    }

    private interface NativeDialogCall {
        void call(DexKitResolver.HeyboxDialogSpec spec) throws Throwable;
    }

    private void showSaveDirDialog(final Activity activity) {
        String current = HeyboxPrefs.getString(App.KEY_VIDEO_DIR, null);
        if (current == null || !current.startsWith("content:")) {
            startDirPicker(activity);
            return;
        }
        withHeyboxDialog(activity, spec -> showSaveDirDialogNative(activity, current, spec),
                () -> showSaveDirDialogFallback(activity, current));
    }

    private void showSaveDirDialogNative(final Activity activity, final String current,
                                         DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        TextView message = buildDialogMessage(activity, "当前：" + describeSaveDir(activity, current)
                + "\n\n默认位置为相册 Movies/BetterHeybox");
        DialogInterface.OnClickListener pick = (d, w) -> {
            d.dismiss();
            startDirPicker(activity);
        };
        DialogInterface.OnClickListener reset = (d, w) -> {
            HeyboxPrefs.setString(App.KEY_VIDEO_DIR, "");
            Toast.makeText(activity, "已恢复默认：Movies/BetterHeybox",
                    Toast.LENGTH_SHORT).show();
            LogRecorder.recordEvent("视频保存位置已恢复默认");
            d.dismiss();
        };
        spec.buildAndShow(activity, "保存位置", message, "选择其他文件夹", pick, "恢复默认", reset);
        module.logd(Log.INFO, module.TAG, "✔ 使用小黑盒原生弹窗管理保存位置");
    }

    private String describeSaveDir(Activity activity, String current) {
        String name = queryDirDisplayName(activity, Uri.parse(current));
        if (name == null || name.isEmpty()) {
            try {
                String decoded = Uri.decode(current);
                int idx = decoded.lastIndexOf('/');
                if (idx >= 0 && idx < decoded.length() - 1) {
                    name = decoded.substring(idx + 1);
                }
            } catch (Throwable ignored) {
            }
        }
        return name == null || name.isEmpty() ? "已选择的文件夹" : name;
    }

    private void showSaveDirDialogFallback(final Activity activity, final String current) {
        try {
            String name = queryDirDisplayName(activity, Uri.parse(current));
            new AlertDialog.Builder(activity)
                    .setTitle("保存位置")
                    .setMessage("当前：" + (name != null ? name : current)
                            + "\n\n默认位置为相册 Movies/BetterHeybox")
                    .setPositiveButton("选择其他文件夹", (d, w) -> startDirPicker(activity))
                    .setNeutralButton("恢复默认", (d, w) -> {
                        HeyboxPrefs.setString(App.KEY_VIDEO_DIR, "");
                        Toast.makeText(activity, "已恢复默认：Movies/BetterHeybox",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            startDirPicker(activity);
        }
    }

    private void startDirPicker(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            activity.startActivityForResult(intent, REQUEST_PICK_SAVE_DIR);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "打开文件夹选择器失败: " + t);
            Toast.makeText(activity, "打开文件夹选择器失败", Toast.LENGTH_SHORT).show();
        }
    }
        private void insertSettingsEntryWithRetry(final Activity activity, final int attempt) {
        if (attempt > 20) {
            module.logd(Log.WARN, module.TAG, "设置页未就绪，放弃插入");
            return;
        }
        try {
            boolean ok = tryInsertSettingsEntry(activity);
            if (!ok && !activity.isFinishing()) {
                activity.getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        insertSettingsEntryWithRetry(activity, attempt + 1);
                    }
                }, 50);
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "插入设置入口重试异常: " + t);
        }
    }

    private boolean tryInsertSettingsEntry(Activity activity) {
        LogRecorder.setContext(activity);
        HeyboxPrefs.init(activity);
        try {
            Object binding = getGeneralSettingsBinding(activity);
            if (binding == null) {
                return false;
            }
            LinearLayout list = resolveSettingsList(activity, binding);
            if (list == null) {
                return false;
            }
            for (int i = list.getChildCount() - 1; i >= 0; i--) {
                if (ENTRY_TAG.equals(list.getChildAt(i).getTag())) {
                    list.removeViewAt(i);
                }
            }

            View entry = buildEntryCard(activity);
            if (entry == null) {
                return false;
            }
            entry.setTag(ENTRY_TAG);
            entry.setClickable(true);
            entry.setFocusable(true);
            entry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        showEmbeddedSettings(activity);
                    } catch (Throwable t) {
                        module.logd(Log.ERROR, module.TAG, "渲染内嵌设置界面失败", t);
                        Toast.makeText(activity, "BetterHeybox 内嵌设置加载失败",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            list.addView(entry, 0);
            module.logd(Log.INFO, module.TAG, "✔ 原生 BetterHeybox 入口已作为列表项插入通用设置页顶部");
            return true;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "插入设置入口异常: " + t);
            return false;
        }
    }

    private Object getGeneralSettingsBinding(Activity activity) {
        try {
            for (Field f : activity.getClass().getDeclaredFields()) {
                if (!isViewBindingShape(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object binding = f.get(activity);
                if (binding != null) {
                    module.logd(Log.INFO, module.TAG,
                            "GeneralSettings binding 已按 ViewBinding 形态解析: " + f.getType().getName());
                    return binding;
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "查找 GeneralSettings binding 失败: " + t);
        }
        return null;
    }
    private static boolean isViewBindingShape(Class<?> type) {
        if (type.isInterface() || type.isPrimitive()) {
            return false;
        }
        for (Class<?> itf : type.getInterfaces()) {
            Method[] ms = itf.getDeclaredMethods();
            if (ms.length == 1 && ms[0].getParameterCount() == 0
                    && ms[0].getReturnType() == View.class) {
                return true;
            }
        }
        return false;
    }
    private LinearLayout resolveSettingsList(Activity activity, Object binding) {
        for (Method m : binding.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != LinearLayout.class) {
                continue;
            }
            try {
                Object result = m.invoke(binding);
                if (result instanceof LinearLayout && isViewAttachedUnder((View) result, activity)) {
                    return (LinearLayout) result;
                }
            } catch (Throwable ignored) {
            }
        }
        for (Method m : binding.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != LinearLayout.class) {
                continue;
            }
            try {
                Object result = m.invoke(binding);
                if (result instanceof LinearLayout) {
                    return (LinearLayout) result;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isViewAttachedUnder(View view, Activity activity) {
        try {
            Object decor = activity.getWindow().getDecorView();
            for (ViewParent p = view.getParent(); p instanceof View; p = ((View) p).getParent()) {
                if (p == decor) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String mCurrentPage;

    private void showEmbeddedSettings(final Activity activity) {
        mCurrentPage = null;
        resetSearchQuery();
        openEmbeddedPanel(activity, "BetterHeybox 设置", buildSettingsGroups(activity),
                this::dismissEmbeddedSettings);
    }

    private void showModulePage(final Activity activity, String pageId) {
        mCurrentPage = pageId;
        resetSearchQuery();
        openEmbeddedPanel(activity, pageTitle(pageId), buildPageGroups(activity, pageId),
                () -> showEmbeddedSettings(activity));
    }

    private void showWatchV2Settings(final Activity activity) {
        showModulePage(activity, PAGE_WATCH);
    }

    private String mSearchQuery = "";
    private boolean mPreserveSearch;
    private List<SettingsGroup> mSearchIndex;

    private static final String[] PAGE_IDS = {
            PAGE_ADS, PAGE_UI, PAGE_BROWSE, PAGE_WATCH, PAGE_TASK, PAGE_COMMON};

    private void resetSearchQuery() {
        if (mPreserveSearch) {
            mPreserveSearch = false;
            return;
        }
        mSearchQuery = "";
    }

    private EditText buildPanelSearchBox(Activity activity) {
        EditText input = new EditText(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 40));
        int margin = module.dp(activity, 12);
        lp.setMargins(margin, module.dp(activity, 8), margin, 0);
        input.setLayoutParams(lp);
        input.setSingleLine(true);
        input.setHint("搜索设置项");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        int bgId = hostResId(activity, "bg_dialog_edit", "drawable", 0);
        if (bgId != 0) {
            input.setBackgroundResource(bgId);
        }
        int textColor = hostColor(activity, "color_text_primary_day_night", 0);
        if (textColor != 0) {
            input.setTextColor(textColor);
        }
        input.setHintTextColor(hostColor(activity, "color_text_tertiary_day_night", 0xFF8A8A8A));
        int pad = module.dp(activity, 10);
        input.setPadding(pad, 0, pad, 0);
        return input;
    }

    private List<SettingsGroup> searchIndex(Activity activity) {
        if (mSearchIndex != null) {
            return mSearchIndex;
        }
        List<SettingsGroup> all = new ArrayList<>();
        List<SettingsGroup> roots = buildSettingsGroups(activity);
        if (!roots.isEmpty()) {
            all.add(new SettingsGroup("分类入口", roots.get(0).items));
        }
        for (String pageId : PAGE_IDS) {
            for (SettingsGroup group : buildPageGroups(activity, pageId)) {
                all.add(new SettingsGroup(pageTitle(pageId) + " · " + group.title,
                        group.items));
            }
        }
        mSearchIndex = all;
        return all;
    }

    private List<SettingsGroup> searchGroups(Activity activity, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<SettingsGroup> out = new ArrayList<>();
        for (SettingsGroup group : searchIndex(activity)) {
            boolean groupHit = group.title.toLowerCase(Locale.ROOT).contains(q);
            List<SwitchDef> hits = new ArrayList<>();
            for (SwitchDef def : group.items) {
                if (groupHit || matchSearch(def, q)) {
                    hits.add(def);
                }
            }
            if (!hits.isEmpty()) {
                out.add(new SettingsGroup(group.title, hits.toArray(new SwitchDef[0])));
            }
        }
        return out;
    }

    private static boolean matchSearch(SwitchDef def, String lowerQuery) {
        if (def.title != null && def.title.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        return def.desc != null && def.desc.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static int countGroupsItems(List<SettingsGroup> groups) {
        int count = 0;
        for (SettingsGroup group : groups) {
            count += group.items.length;
        }
        return count;
    }

    private void renderPanelGroups(Activity activity, ClassLoader cl, LinearLayout box,
                                   List<SettingsGroup> groups) {
        box.removeAllViews();
        String query = mSearchQuery == null ? "" : mSearchQuery.trim();
        List<SettingsGroup> shown = query.isEmpty() ? groups : searchGroups(activity, query);
        if (!query.isEmpty()) {
            TextView tip = new TextView(activity);
            tip.setText(shown.isEmpty() ? "没有匹配的设置项"
                    : "找到 " + countGroupsItems(shown) + " 项");
            tip.setTextSize(TypedValue.COMPLEX_UNIT_PX, module.dp(activity, 13));
            tip.setTextColor(hostColor(activity, "color_text_tertiary_day_night", 0xFF8A8A8A));
            LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int tm = module.dp(activity, 12);
            tipLp.setMargins(tm, module.dp(activity, 12), tm, 0);
            tip.setLayoutParams(tipLp);
            box.addView(tip);
        }
        for (SettingsGroup group : shown) {
            View card = buildSectionCard(activity, cl, group);
            if (card != null) {
                box.addView(card);
            }
        }
        appendEmbeddedFooter(activity, box);
    }

    private void openEmbeddedPanel(final Activity activity, String title,
                                   List<SettingsGroup> groups, final Runnable onBack) {
        try {
            dismissEmbeddedSettings();
            HeyboxPrefs.init(activity);
            int appbarBg = hostColor(activity, "appbar_bg_color", 0xFFFFFFFF);
            int pageBg = hostColor(activity, "color_bg_subtle_day_night", 0xFFFFFFFF);

            int statusBarH = 0;
            try {
                int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) {
                    statusBarH = activity.getResources().getDimensionPixelSize(id);
                }
            } catch (Throwable ignored) {
            }
            if (statusBarH <= 0) {
                statusBarH = module.dp(activity, 24);
            }

            final long swallowUntil = android.os.SystemClock.uptimeMillis() + 300L;
            FrameLayout overlay = new FrameLayout(activity) {
                @Override
                public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                    if (android.os.SystemClock.uptimeMillis() < swallowUntil) {
                        return true;
                    }
                    return super.dispatchTouchEvent(ev);
                }
            };
            overlay.setBackgroundColor(pageBg);
            overlay.setClickable(true);
            overlay.setFocusable(true);
            overlay.setFocusableInTouchMode(true);

            LinearLayout page = new LinearLayout(activity);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.addView(page);
            View statusSpacer = new View(activity);
            statusSpacer.setBackgroundColor(appbarBg);
            statusSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, statusBarH));
            page.addView(statusSpacer);
            ClassLoader cl = activity.getClassLoader();
            page.addView(buildEmbeddedTitleBar(activity, cl, appbarBg, title, onBack));
            mSearchIndex = null;
            final EditText searchBox = buildPanelSearchBox(activity);
            page.addView(searchBox);
            ScrollView scroller = new ScrollView(activity);
            scroller.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutParams(new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.setPadding(0, module.dp(activity, 2), 0, 0);
            scroller.addView(box);
            page.addView(scroller);

            searchBox.setText(mSearchQuery);
            searchBox.setSelection(searchBox.getText().length());
            searchBox.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    String next = s == null ? "" : s.toString();
                    if (next.equals(mSearchQuery)) {
                        return;
                    }
                    mSearchQuery = next;
                    renderPanelGroups(activity, cl, box, groups);
                }
            });
            renderPanelGroups(activity, cl, box, groups);
            if (HeyboxPrefs.getBoolean(App.KEY_TARGET_HINT_VISIBLE, true)) {
                View wm = TargetHintHook.createVisibleHint(activity);
                wm.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                overlay.addView(wm);
            }
            attachEmbeddedPanel(activity, overlay, onBack);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "渲染原生设置面板失败", t);
        }
    }

    private View buildEmbeddedTitleBar(Activity activity, ClassLoader cl, int appbarBg,
                                       String title, final Runnable onBack) throws Throwable {
        Class<?> titleBarCls = Class.forName("com.max.hbcommon.component.TitleBar", false, cl);
        Object titleBar = titleBarCls.getConstructor(Context.class).newInstance(activity);
        ((View) titleBar).setBackgroundColor(appbarBg);
        titleBarCls.getMethod("setTitle", CharSequence.class).invoke(titleBar, title);
        titleBarCls.getMethod("setNavigationIcon", int.class)
                .invoke(titleBar, hostResId(activity, "appbar_back", "drawable", 0));
        Class<?> ocl = Class.forName("android.view.View$OnClickListener", false, cl);
        Object backListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onBack != null) {
                    onBack.run();
                } else {
                    dismissEmbeddedSettings();
                }
            }
        };
        titleBarCls.getMethod("setNavigationOnClickListener", ocl).invoke(titleBar, backListener);
        ((View) titleBar).setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 44)));
        return (View) titleBar;
    }

    private void appendEmbeddedFooter(Activity activity, LinearLayout box) {
        try {
            TextView footer = new TextView(activity);
            String displayVersion = moduleVersionName(activity);
            footer.setText("BetterHeybox v" + displayVersion);
            footer.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            footer.setGravity(android.view.Gravity.CENTER);
            footer.setTextColor(hostColor(activity, "color_text_tertiary_day_night", 0xFF8A8A8A));
            LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int fm = module.dp(activity, 16);
            footerLp.setMargins(fm, module.dp(activity, 12), fm, module.dp(activity, 24));
            footer.setLayoutParams(footerLp);
            footer.setOnClickListener(v -> handleFooterClick(activity));
            box.addView(footer);
            module.logd(Log.INFO, module.TAG, "✔ 内嵌面板底部版本号已添加: "
                    + (displayVersion == null ? "unknown" : displayVersion));
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "内嵌面板版本号页脚渲染失败: " + t);
        }
    }

    private int mHintToggleClicks;
    private long mHintToggleLastAt;

    private void handleFooterClick(Activity activity) {
        long now = System.currentTimeMillis();
        if (now - mHintToggleLastAt > 3000L) {
            mHintToggleClicks = 0;
        }
        mHintToggleLastAt = now;
        if (++mHintToggleClicks < 7) {
            return;
        }
        mHintToggleClicks = 0;
        boolean next = !HeyboxPrefs.getBoolean(App.KEY_TARGET_HINT_VISIBLE, true);
        writeEmbeddedBoolean(activity, App.KEY_TARGET_HINT_VISIBLE, next);
        Toast.makeText(activity, next ? "Open!" : "Closed!",
                Toast.LENGTH_SHORT).show();
        showEmbeddedSettings(activity);
    }

    private void attachEmbeddedPanel(Activity activity, FrameLayout overlay, final Runnable onBack) {
        overlay.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (onBack != null) {
                        onBack.run();
                    } else {
                        dismissEmbeddedSettings();
                    }
                    return true;
                }
                return false;
            }
        });
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        decor.addView(overlay);
        overlay.requestFocus();
        mSettingsPanel = new WeakReference<View>(overlay);
        module.logd(Log.INFO, module.TAG, "✔ 原生子页面设置面板已叠加到小黑盒窗口");
    }

    private void dismissEmbeddedSettings() {
        try {
            View panel = mSettingsPanel == null ? null : mSettingsPanel.get();
            if (panel != null && panel.getParent() != null) {
                ((ViewGroup) panel.getParent()).removeView(panel);
            }
        } catch (Throwable ignored) {
        }
        mSettingsPanel = null;
    }

    private View buildSectionCard(Activity activity, ClassLoader cl, SettingsGroup group) {
        try {
            LinearLayout groupRoot = new LinearLayout(activity);
            groupRoot.setOrientation(LinearLayout.VERTICAL);
            groupRoot.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView groupTitle = new TextView(activity);
            groupTitle.setText(group.title);
            int titleSize = module.dp(activity, 13);
            groupTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize);
            groupTitle.setTextColor(hostColor(activity, "color_text_tertiary_day_night", 0xFF8A8A8A));
            groupTitle.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int tm = module.dp(activity, 12);
            titleLp.setMargins(tm, module.dp(activity, 16), tm, 0);
            groupTitle.setLayoutParams(titleLp);
            groupRoot.addView(groupTitle);
            Object[] cardPair = buildHostCard(activity, cl);
            Object card = cardPair[0];
            LinearLayout content = (LinearLayout) cardPair[1];
            for (int i = 0; i < group.items.length; i++) {
                View item = createSettingSwitch(activity, cl, group.items[i]);
                if (item == null) {
                    continue;
                }
                if (i == group.items.length - 1) {
                    try {
                        Class<?> itemCls = Class.forName(
                                "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl);
                        itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, false);
                    } catch (Throwable ignored) {
                    }
                }
                content.addView(item);
            }
            groupRoot.addView((View) card);
            return groupRoot;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "构建设置卡片分区失败: " + t);
            return null;
        }
    }

    private static void setRowClick(Class<?> itemCls, Object item, View.OnClickListener l)
            throws Throwable {
        itemCls.getMethod("setOnClickListener", View.OnClickListener.class).invoke(item, l);
    }

    private View createSettingSwitch(Activity activity, ClassLoader cl, SwitchDef def) {
        try {
            Class<?> itemCls = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl);
            Object item = itemCls.getConstructor(Context.class).newInstance(activity);

            itemCls.getMethod("setTitle", String.class).invoke(item, def.title);
            if (def.desc != null) {
                itemCls.getMethod("setTitleDesc", String.class).invoke(item, def.desc);
                Method descToggle = resolveDescToggle(itemCls, activity);
                if (descToggle != null) {
                    descToggle.invoke(item, true);
                }
            }
            Class<?> typeEnum = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView$Type", false, cl);
            if (def.clickRow) {
                Object arrowType = Enum.valueOf((Class) typeEnum, "Arrow");
                itemCls.getMethod("setRightType", typeEnum).invoke(item, arrowType);
                try {
                    itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, true);
                } catch (Throwable ignored) {
                }
                final String editKey = def.editKey;
                switch (def.action) {
                    case CLEAR_DAILY:
                        setRowClick(itemCls, item, v -> {
                            try {
                                module.clearDailyTaskAndRetry(activity);
                                Toast.makeText(activity, "已清除今日打卡状态，重新尝试中…",
                                        Toast.LENGTH_SHORT).show();
                            } catch (Throwable t) {
                                module.logd(Log.ERROR, module.TAG, "清除今日打卡失败", t);
                            }
                        });
                        break;
                    case CHANNEL:
                        setRowClick(itemCls, item, v -> showChannelDialog(activity));
                        break;
                    case EXPORT:
                        setRowClick(itemCls, item, v -> startEmbeddedExport(activity));
                        break;
                    case IMPORT:
                        setRowClick(itemCls, item, v -> startEmbeddedImport(activity));
                        break;
                    case EXPORT_LOG:
                        setRowClick(itemCls, item, v -> startEmbeddedLogExport(activity));
                        break;
                    case PICK_DIR:
                        setRowClick(itemCls, item, v -> showSaveDirDialog(activity));
                        break;
                    case RUNTIME_STATUS:
                        setRowClick(itemCls, item, v -> showEmbeddedRuntimeStatus(activity));
                        break;
                    case TARGET_STATUS:
                        setRowClick(itemCls, item, v -> showTargetStatus(activity));
                        break;
                    case CLEAR_LOG:
                        setRowClick(itemCls, item, v -> confirmClearLogs(activity));
                        break;
                    case VIEW_LOG:
                        setRowClick(itemCls, item, v -> showLogPreview(activity));
                        break;
                    case OPEN_WEB:
                        setRowClick(itemCls, item, v -> showOpenWebDialog(activity));
                        break;
                    case RESET_GLASS:
                        setRowClick(itemCls, item, v -> resetLiquidGlassSettings(activity));
                        break;
                    case CHOOSE_GLASS:
                        setRowClick(itemCls, item, v -> showGlassProviderDialog(activity));
                        break;
                    case GLASS_SHEET:
                        setRowClick(itemCls, item, v -> GlassSettingsSheet.show(activity));
                        break;
                    case POST_LEVEL:
                        setRowClick(itemCls, item, v -> showPostLevelDialog(activity));
                        break;
                    case POST_KEYWORDS:
                        setRowClick(itemCls, item, v -> showPostKeywordsDialog(activity));
                        break;
                    case GAME_LIB_TYPES:
                        setRowClick(itemCls, item, v -> showGameLibPicker(activity,
                                GameLibraryCleanHook.PICK_TYPE, "自定义隐藏类型"));
                        break;
                    case GAME_LIB_ENTRIES:
                        setRowClick(itemCls, item, v -> showGameLibPicker(activity,
                                GameLibraryCleanHook.PICK_ENTRY, "隐藏指定入口卡片"));
                        break;
                    case GAME_LIB_SECTIONS:
                        setRowClick(itemCls, item, v -> showGameLibPicker(activity,
                                GameLibraryCleanHook.PICK_SECTION, "隐藏指定推荐分区"));
                        break;
                    case GAME_LIB_DIAG:
                        setRowClick(itemCls, item, v -> showGameLibDiagnostics(activity));
                        break;
                    case AI_PROVIDER:
                        setRowClick(itemCls, item, v -> showAiProviderDialog(activity));
                        break;
                    case AI_PROMPT:
                        setRowClick(itemCls, item, v -> showAiPromptDialog(activity));
                        break;
                    case AI_MAX_TOKENS:
                        setRowClick(itemCls, item, v -> showAiMaxTokensDialog(activity));
                        break;
                    case AI_TEST:
                        setRowClick(itemCls, item, v -> testAiConnection(activity));
                        break;
                    case REDIRECT_FORCE:
                        setRowClick(itemCls, item, v -> showMultilineEditDialog(activity,
                                "强制重定向域名", App.KEY_BROWSER_REDIRECT_FORCE,
                                "一行一个域名或完整链接，总是用外部浏览器打开", false));
                        break;
                    case REDIRECT_BLOCK:
                        setRowClick(itemCls, item, v -> showMultilineEditDialog(activity,
                                "强制内置域名", App.KEY_BROWSER_REDIRECT_BLOCK,
                                "一行一个域名或完整链接，总是留在内置浏览器", false));
                        break;
                    case REDIRECT_TARGET:
                        setRowClick(itemCls, item, v -> showBrowserTargetDialog(activity));
                        break;
                    case WEB_LOG:
                        setRowClick(itemCls, item, v -> showWebLogDialog(activity));
                        break;
                    case ABOUT:
                        setRowClick(itemCls, item, v -> showAboutDialog(activity));
                        break;
                    case WATCH_USERS:
                        setRowClick(itemCls, item, v -> showMultilineEditDialog(activity,
                                "关注对象", App.KEY_WATCH_USERS,
                                "一行一个 userid 或主页链接（最多 30 个）", false));
                        break;
                    case WATCH_KEYWORDS:
                        setRowClick(itemCls, item, v -> showMultilineEditDialog(activity,
                                "监控关键词", App.KEY_WATCH_KEYWORDS,
                                "一行一个，命中即提醒；regex: 为正则", false));
                        break;
                    case WATCH_IMPORT_FOLLOW:
                        setRowClick(itemCls, item, v -> importWatchFollowing(activity));
                        break;
                    case OPEN_PAGE:
                        setRowClick(itemCls, item, v -> showModulePage(activity, editKey));
                        break;
                    case WATCH_V2:
                        setRowClick(itemCls, item, v -> showWatchV2Settings(activity));
                        break;
                    case WATCH_TOPICS:
                        setRowClick(itemCls, item, v -> showMultilineEditDialog(activity,
                                "关注的话题", App.KEY_WATCH_TOPICS,
                                "一行一个：话题名或话题id|话题名（「导入关注话题」会自动带上 id）",
                                false));
                        break;
                    case WATCH_IMPORT_TOPICS:
                        setRowClick(itemCls, item, v -> importWatchTopics(activity));
                        break;
                    case WATCH_TOPIC_SEARCH:
                        setRowClick(itemCls, item, v -> showTopicSearchDialog(activity));
                        break;
                    case WATCH_WINDOW:
                        setRowClick(itemCls, item, v -> showWatchWindowDialog(activity));
                        break;
                    case WATCH_INTERVAL:
                        setRowClick(itemCls, item, v -> showWatchIntervalDialog(activity));
                        break;
                    case WATCH_SUGGEST_KEYWORDS:
                        setRowClick(itemCls, item, v -> suggestWatchKeywords(activity));
                        break;
                    case WATCH_TEST_PUSH:
                        setRowClick(itemCls, item, v -> testWatchPush(activity));
                        break;
                    case WATCH_DEBUG_PUSH3:
                        setRowClick(itemCls, item, v -> {
                            Toast.makeText(activity, "正在拉取最近 3 条并推送…", Toast.LENGTH_SHORT).show();
                            com.better.heybox.watch.WatchEngine.debugPushLatest(activity, 3);
                        });
                        break;
                    case WATCH_CHECK:
                        setRowClick(itemCls, item, v -> {
                            com.better.heybox.watch.WatchEngine.checkNow(activity, true);
                            Toast.makeText(activity, "已触发检查，结果见日志", Toast.LENGTH_SHORT).show();
                        });
                        break;
                    case EDIT_LINK:
                    default:
                        setRowClick(itemCls, item, v -> showEditLinkDialog(activity, def.title, editKey));
                        break;
                }
                int itemH = module.dp(activity, 48);
                ((View) item).setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, itemH));
                return (View) item;
            }
            Object switchType = Enum.valueOf((Class) typeEnum, "SwitchButton");
            itemCls.getMethod("setRightType", typeEnum).invoke(item, switchType);
            try {
                itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, true);
            } catch (Throwable ignored) {
            }
            boolean cur = readEmbeddedBoolean(def.key, def.def);
            itemCls.getMethod("setChecked", boolean.class, boolean.class).invoke(item, cur, false);
            registerSwitchItem(def.key, item);
            Class<?> listenerCls = Class.forName(
                    "android.widget.CompoundButton$OnCheckedChangeListener", false, cl);
            Object listener = new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    try {
                        if (writeEmbeddedBoolean(activity, def.key, isChecked) && def.restart) {
                            showRestartAppDialog(activity, cl);
                        }
                        if (App.KEY_CUSTOM_TEXT_SELECT.equals(def.key)
                                || App.KEY_COPY_POST.equals(def.key)) {
                            TextSelectHook.refresh();
                        }
                        if (App.KEY_COMMENT_FREE_COPY.equals(def.key)) {
                            CommentCopyHook.refresh();
                        }
                        if (App.KEY_SEARCH_HIDE_BANNER.equals(def.key)
                                || App.KEY_SEARCH_HIDE_DISCOVER.equals(def.key)
                                || App.KEY_SEARCH_HIDE_HOT_RANK.equals(def.key)) {
                            SearchPageCleanHook.refresh();
                        }
                        if (App.KEY_GAME_LIB_HIDE_BANNER.equals(def.key)
                                || App.KEY_GAME_LIB_HIDE_MENU.equals(def.key)
                                || App.KEY_GAME_LIB_HIDE_SECTIONS.equals(def.key)) {
                            GameLibraryCleanHook.refresh();
                        }
                        if (App.KEY_LIQUID_GLASS.equals(def.key)) {
                            LiquidGlassInstaller.applyGlassEnabled(activity);
                        }
                        if (App.KEY_GLASS_IMMERSIVE.equals(def.key)
                                || App.KEY_GLASS_ADAPTIVE.equals(def.key)
                                || App.KEY_GLASS_FIT_TABS.equals(def.key)) {
                            LiquidGlassInstaller.refreshGlassWith(activity);
                        }
                        if (App.KEY_DEBUG_NO_DOWNGRADE.equals(def.key)) {
                            if (isChecked) {
                                HeyboxPrefs.setString(App.KEY_MODULE_VERSION_FLOOR, "0");
                                LogRecorder.recordEvent("已清除模块版本降级限制");
                                Toast.makeText(activity, "已清除模块版本降级限制", Toast.LENGTH_SHORT).show();
                            } else {
                                LogRecorder.recordEvent("版本降级限制将在下次启动重新生效");
                                Toast.makeText(activity, "版本降级限制将在下次启动重新生效",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                        if (!mutexApplying) {
                            applySwitchMutex(activity, def.key, isChecked);
                        }
                    } catch (Throwable t) {
                        module.logd(Log.ERROR, module.TAG, "开关监听回调异常: " + def.title, t);
                    }
                }
            };
            itemCls.getMethod("setOnCheckedChangeListener", listenerCls).invoke(item, listener);
            int itemH = module.dp(activity, 48);
            ((View) item).setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemH));
            return (View) item;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "创建 SettingItemView 开关失败 (" + def.title + "): " + t);
            return null;
        }
    }
    private final java.util.HashMap<String, WeakReference<Object>> sSwitchItems = new java.util.HashMap<>();

    private void registerSwitchItem(String key, Object item) {
        synchronized (sSwitchItems) {
            sSwitchItems.put(key, new WeakReference<>(item));
        }
    }

    private boolean mutexApplying;

    private void applySwitchMutex(Activity activity, String key, boolean checked) {
        mutexApplying = true;
        try {
            applySwitchMutexInner(activity, key, checked);
        } finally {
            mutexApplying = false;
        }
    }

    private void applySwitchMutexInner(Activity activity, String key, boolean checked) {
        if (App.KEY_BROWSER_REDIRECT.equals(key)) {
            if (checked) {
                setSwitchPref(activity, App.KEY_WEBVIEW_DEVTOOLS, false);
                setSwitchPref(activity, App.KEY_BROWSER_REDIRECT_KNOWN, false);
            } else {
                setSwitchPref(activity, App.KEY_BROWSER_REDIRECT_KNOWN, false);
            }
        } else if (App.KEY_WEBVIEW_DEVTOOLS.equals(key)) {
            if (checked) {
                setSwitchPref(activity, App.KEY_BROWSER_REDIRECT, false);
                setSwitchPref(activity, App.KEY_BROWSER_REDIRECT_KNOWN, false);
            }
        } else if (App.KEY_BROWSER_REDIRECT_KNOWN.equals(key) && checked) {
            setSwitchPref(activity, App.KEY_BROWSER_REDIRECT, true);
        }
    }

    private boolean setSwitchPref(Activity activity, String key, boolean value) {
        boolean current = readEmbeddedBoolean(key,
                Boolean.TRUE.equals(App.BOOLEAN_DEFAULTS.get(key)));
        if (current == value) {
            return false;
        }
        writeEmbeddedBoolean(activity, key, value);
        WeakReference<Object> ref;
        synchronized (sSwitchItems) {
            ref = sSwitchItems.get(key);
        }
        Object item = ref == null ? null : ref.get();
        if (item != null) {
            try {
                item.getClass().getMethod("setChecked", boolean.class, boolean.class)
                        .invoke(item, value, false);
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "联动开关原地更新失败: " + key, t);
            }
        }
        return true;
    }

    private static Method sDescToggle;
    private static final String DESC_PROBE_TEXT = "BH_DESC_PROBE";

    private Method resolveDescToggle(Class<?> itemCls, Activity activity) {
        if (sDescToggle != null) {
            return sDescToggle;
        }
        try {
            Object probe = itemCls.getConstructor(Context.class).newInstance(activity);
            itemCls.getMethod("setTitleDesc", String.class).invoke(probe, DESC_PROBE_TEXT);
            for (Method m : itemCls.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        || m.getParameterCount() != 1
                        || m.getParameterTypes()[0] != boolean.class
                        || m.getReturnType() != void.class) {
                    continue;
                }
                try {
                    m.invoke(probe, true);
                    boolean lit = isProbeDescVisible(probe);
                    m.invoke(probe, false);
                    if (lit) {
                        sDescToggle = m;
                        module.logd(Log.INFO, module.TAG, "desc 可见性开关已解析: " + m.getName() + "(boolean)");
                        return sDescToggle;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isProbeDescVisible(Object root) {
        if (!(root instanceof View)) {
            return false;
        }
        if (root instanceof TextView
                && DESC_PROBE_TEXT.equals(((TextView) root).getText().toString())) {
            return ((TextView) root).getVisibility() == View.VISIBLE;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (isProbeDescVisible(vg.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void showChannelDialog(final Activity activity) {
        withHeyboxDialog(activity, spec -> showChannelDialogNative(activity, spec),
                () -> showChannelDialogFallback(activity));
    }

    private LinearLayout buildOptionRowList(Activity activity, String[] labels, int checked) {
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = module.dp(activity, 8);
        list.setPadding(pad, pad, pad, pad);
        for (int i = 0; i < labels.length; i++) {
            TextView row = new TextView(activity);
            row.setText(labels[i]);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, module.dp(activity, 14), pad, module.dp(activity, 14));
            row.setTextColor(hostColor(activity,
                    i == checked ? "color_text_link_day_night" : "color_text_primary_day_night",
                    i == checked ? 0xFF1677FF : 0xFF333333));
            row.setClickable(true);
            row.setFocusable(true);
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return list;
    }
    private interface OptionPick {
        void pick(int index);
    }

    private void bindOptionRows(Dialog dialog, LinearLayout list, OptionPick onPick) {
        for (int i = 0; i < list.getChildCount(); i++) {
            final int index = i;
            list.getChildAt(i).setOnClickListener(v -> {
                onPick.pick(index);
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private void showSingleChoiceFallback(final Activity activity, String title,
                                          String[] labels, int checked, OptionPick onPick) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        onPick.pick(which);
                        dialog.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "单选弹框失败(" + title + "): " + t);
        }
    }

    private void applyShareChannel(Activity activity, int index) {
        try {
            HeyboxPrefs.init(activity);
            HeyboxPrefs.setString(App.KEY_SHARE_CHANNEL, SHARE_CHANNELS[index]);
            LogRecorder.recordEvent("分享渠道已选择: " + SHARE_CHANNELS[index]);
            Toast.makeText(activity, "分享渠道已设为 " + SHARE_CHANNEL_LABELS[index],
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "保存分享渠道失败: " + t);
        }
    }

    private void showChannelDialogNative(final Activity activity, DexKitResolver.HeyboxDialogSpec spec)
            throws Exception {
        String cur = module.getString(App.KEY_SHARE_CHANNEL, "QQ");
        final int checked = "WECHAT".equals(cur) ? 1 : ("WEIBO".equals(cur) ? 2 : 0);
        LinearLayout list = buildOptionRowList(activity, SHARE_CHANNEL_LABELS, checked);
        Dialog dialog = spec.buildAndShow(activity, "分享渠道", list, null, null,
                "取消", (d, w) -> d.dismiss());
        bindOptionRows(dialog, list, index -> applyShareChannel(activity, index));
        module.logd(Log.INFO, module.TAG, "✔ 使用小黑盒原生弹窗选择分享渠道");
    }

    private void showChannelDialogFallback(final Activity activity) {
        String cur = module.getString(App.KEY_SHARE_CHANNEL, "QQ");
        int checked = "WECHAT".equals(cur) ? 1 : ("WEIBO".equals(cur) ? 2 : 0);
        showSingleChoiceFallback(activity, "分享渠道", SHARE_CHANNEL_LABELS, checked,
                index -> applyShareChannel(activity, index));
    }

    private static final String DEFAULT_WEBVIEW_ENTRY_URL = "https://github.com/Mrmiaomrzh/BetterHeybox";

    private static final String[] SHARE_CHANNELS = {"QQ", "WECHAT", "WEIBO"};
    private static final String[] SHARE_CHANNEL_LABELS = {"QQ / QQ空间", "微信 / 朋友圈", "微博"};

    private static final String[] GLASS_PROVIDER_VALUES = {
            GlassProvider.PROVIDER_OWN, GlassProvider.PROVIDER_HBMOD};
    private static final String[] GLASS_PROVIDER_LABELS = {
            "BetterHeybox（模块自带）", "小黑盒液态玻璃模块"};

    private void showGlassProviderDialog(final Activity activity) {
        withHeyboxDialog(activity, spec -> showGlassProviderDialogNative(activity, spec),
                () -> showGlassProviderDialogFallback(activity));
    }

    private void showGlassProviderDialogNative(final Activity activity,
                                               DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        String current = module.getString(App.KEY_GLASS_PROVIDER, "");
        final int checked = GlassProvider.PROVIDER_HBMOD.equals(current) ? 1 : 0;
        LinearLayout list = buildOptionRowList(activity, GLASS_PROVIDER_LABELS, checked);
        Dialog dialog = spec.buildAndShow(activity, "选择液态玻璃实现", list, null, null,
                "取消", (d, w) -> d.dismiss());
        bindOptionRows(dialog, list, index ->
                chooseGlassProvider(activity, GLASS_PROVIDER_VALUES[index]));
        module.logd(Log.INFO, module.TAG, "✔ 使用小黑盒原生弹窗选择液态玻璃实现");
    }

    private void showGlassProviderDialogFallback(final Activity activity) {
        String current = module.getString(App.KEY_GLASS_PROVIDER, "");
        int checked = GlassProvider.PROVIDER_HBMOD.equals(current) ? 1 : 0;
        showSingleChoiceFallback(activity, "选择液态玻璃实现", GLASS_PROVIDER_LABELS, checked,
                index -> chooseGlassProvider(activity, GLASS_PROVIDER_VALUES[index]));
    }

    private void chooseGlassProvider(Activity activity, String value) {
        try {
            HeyboxPrefs.setString(App.KEY_GLASS_PROVIDER, value);
            LogRecorder.recordEvent("液态玻璃实现已选择: " + value);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "保存液态玻璃实现选择失败: " + t);
            return;
        }
        try {
            View panel = mSettingsPanel == null ? null : mSettingsPanel.get();
            if (panel != null && panel.getParent() != null) {
                showEmbeddedSettings(activity);
            }
        } catch (Throwable ignored) {
        }
                showRestartAppDialog(activity, activity.getClassLoader());
    }

    private static final String[] POST_LEVEL_VALUES = {
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"};
    private static final String[] POST_LEVEL_LABELS = {
            "关闭", "Lv1", "Lv2", "Lv3", "Lv4", "Lv5", "Lv6", "Lv7", "Lv8", "Lv9", "Lv10"};

    private void refreshEmbeddedPanel(Activity activity) {
        View panel = mSettingsPanel == null ? null : mSettingsPanel.get();
        if (panel == null || panel.getParent() == null) {
            return;
        }
        ScrollView old = findScroller(panel);
        final int scrollY = old == null ? 0 : old.getScrollY();
        mPreserveSearch = true;
        if (mCurrentPage != null) {
            showModulePage(activity, mCurrentPage);
        } else {
            showEmbeddedSettings(activity);
        }
        View fresh = mSettingsPanel == null ? null : mSettingsPanel.get();
        if (fresh != null) {
            ScrollView scroller = findScroller(fresh);
            if (scroller != null) {
                scroller.post(() -> scroller.scrollTo(0, scrollY));
            }
        }
    }

    private static ScrollView findScroller(View root) {
        if (root instanceof ScrollView) {
            return (ScrollView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ScrollView found = findScroller(vg.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void showPostLevelDialog(final Activity activity) {
        final int checked = Math.min(Math.max(currentPostMinLevel(), 0),
                POST_LEVEL_VALUES.length - 1);
        withHeyboxDialog(activity, spec -> {
            LinearLayout list = buildOptionRowList(activity, POST_LEVEL_LABELS, checked);
            Dialog dialog = spec.buildAndShow(activity, "屏蔽低等级发帖", list, null, null,
                    "取消", (d, w) -> d.dismiss());
            bindOptionRows(dialog, list, index -> {
                HeyboxPrefs.setString(App.KEY_POST_MIN_LEVEL, POST_LEVEL_VALUES[index]);
                LogRecorder.recordEvent("发帖等级阈值已设置: " + POST_LEVEL_VALUES[index]);
                refreshEmbeddedPanel(activity);
            });
        }, () -> showSingleChoiceFallback(activity, "屏蔽低等级发帖", POST_LEVEL_LABELS,
                checked, index -> {
                    HeyboxPrefs.setString(App.KEY_POST_MIN_LEVEL, POST_LEVEL_VALUES[index]);
                    refreshEmbeddedPanel(activity);
                }));
    }

    private int currentPostMinLevel() {
        try {
            return Integer.parseInt(module.getString(App.KEY_POST_MIN_LEVEL, "0").trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private void showPostKeywordsDialog(Activity activity) {
        showMultilineEditDialog(activity, "屏蔽关键词", App.KEY_POST_KEYWORDS,
                "一行一个，命中标题或正文即屏蔽；regex: 前缀为正则", false);
    }

    private void showAiPromptDialog(Activity activity) {
        showMultilineEditDialog(activity, "判定提示词", App.KEY_AI_PROMPT,
                "留空使用内置默认提示词", true);
    }

    private void showGameLibDiagnostics(final Activity activity) {
        showMultilineInfo(activity, "游戏库精简状态", GameLibraryCleanHook.diagnostics());
    }
    private void showMultilineInfo(Activity activity, String title, String text) {
        try {
            TextView content = buildDialogMessage(activity, text);
            content.setTextIsSelectable(true);
            ScrollView scroller = new ScrollView(activity);
            scroller.addView(content);
            scroller.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 420)));
            withHeyboxDialog(activity,
                    spec -> {
                        spec.buildAndShow(activity, title, scroller, null, null,
                                "关闭", (d, w) -> d.dismiss());
                    },
                    () -> new AlertDialog.Builder(activity)
                            .setTitle(title)
                            .setView(scroller)
                            .setPositiveButton("关闭", null)
                            .show());
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "诊断弹窗失败: " + t);
        }
    }

    private void showGameLibPicker(final Activity activity, final int kind, final String title) {
        withHeyboxDialog(activity,
                spec -> showGameLibPickerNative(activity, kind, title, spec),
                () -> showGameLibPickerFallback(activity, kind, title));
    }

    private void showGameLibPickerNative(final Activity activity, int kind, String title,
                                         DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        List<CheckBox> boxes = new ArrayList<>();
        ScrollView content = buildGameLibPickerView(activity, kind, boxes);
        spec.buildAndShow(activity, title, content, "保存",
                (d, w) -> {
                    saveGameLibPicks(activity, kind, title, boxes);
                    d.dismiss();
                },
                "取消", (d, w) -> d.dismiss());
    }

    private void showGameLibPickerFallback(final Activity activity, int kind, String title) {
        try {
            List<CheckBox> boxes = new ArrayList<>();
            ScrollView content = buildGameLibPickerView(activity, kind, boxes);
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setView(content)
                    .setPositiveButton("保存", (d, w) -> saveGameLibPicks(activity, kind, title, boxes))
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, title + "弹窗失败: " + t);
        }
    }

    private ScrollView buildGameLibPickerView(Activity activity, int kind, List<CheckBox> boxesOut) {
        List<String[]> entries = GameLibraryCleanHook.pickerEntries(kind);
        Set<String> selected = kind == GameLibraryCleanHook.PICK_TYPE
                ? GameLibraryCleanHook.selectedTypes()
                : GameLibraryCleanHook.selectedNames(kind);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = module.dp(activity, 8);
        column.setPadding(pad, pad, pad, pad);
        int textColor = hostColor(activity, "color_text_primary_day_night", 0);
        for (String[] entry : entries) {
            String value = entry[0];
            String label = entry.length > 1 ? entry[1] : null;
            CheckBox box = new CheckBox(activity);
            box.setText(label == null || label.isEmpty() ? value : value + "  " + label);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            box.setChecked(selected.contains(value));
            if (textColor != 0) {
                box.setTextColor(textColor);
            }
            box.setTag(value);
            column.addView(box, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            boxesOut.add(box);
        }
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(column);
        scroller.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 400)));
        return scroller;
    }

    private void saveGameLibPicks(Activity activity, int kind, String title, List<CheckBox> boxes) {
        Set<String> picked = new LinkedHashSet<>();
        for (CheckBox box : boxes) {
            if (box.isChecked() && box.getTag() != null) {
                picked.add(String.valueOf(box.getTag()));
            }
        }
        if (kind == GameLibraryCleanHook.PICK_TYPE) {
            GameLibraryCleanHook.setSelectedTypes(picked);
        } else {
            GameLibraryCleanHook.setSelectedNames(kind, picked);
        }
        LogRecorder.recordEvent(title + "已保存: " + picked.size() + " 项");
        Toast.makeText(activity, picked.isEmpty()
                        ? "已清空「" + title + "」" : "已保存 " + picked.size() + " 项，立即生效",
                Toast.LENGTH_SHORT).show();
        refreshEmbeddedPanel(activity);
    }

    private void showMultilineEditDialog(Activity activity, String title, String key,
                                         String hint, boolean resetToDefault) {
        withHeyboxDialog(activity,
                spec -> showMultilineEditNative(activity, title, key, hint, resetToDefault, spec),
                () -> showMultilineEditFallback(activity, title, key, hint, resetToDefault));
    }

    private EditText buildMultilineInput(Activity activity, String key, String hint) {
        EditText input = new EditText(activity);
        int pad = module.dp(activity, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, pad, 0, pad * 2);
        input.setLayoutParams(lp);
        input.setPadding(pad, pad, pad, pad);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(hint);
        input.setText(HeyboxPrefs.getString(key, ""));
        input.setSelection(input.getText().length());
        int bgId = hostResId(activity, "bg_dialog_edit", "drawable", 0);
        if (bgId != 0) {
            input.setBackgroundResource(bgId);
        }
        int textColor = hostColor(activity, "color_text_primary_day_night", 0);
        if (textColor != 0) {
            input.setTextColor(textColor);
        }
        return input;
    }

    private ScrollView wrapScrollableInput(Activity activity, EditText input) {
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(input);
        scroller.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 400)));
        return scroller;
    }

    private void showMultilineEditNative(final Activity activity, final String title,
                                         final String key, final String hint,
                                         final boolean resetToDefault,
                                         DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        final EditText input = buildMultilineInput(activity, key, hint);
        DialogInterface.OnClickListener negative = resetToDefault
                ? (d, w) -> {
                    saveMultiline(activity, key, "", title);
                    Toast.makeText(activity, "已恢复默认", Toast.LENGTH_SHORT).show();
                    d.dismiss();
                }
                : (d, w) -> d.dismiss();
        spec.buildAndShow(activity, title, wrapScrollableInput(activity, input), "保存",
                (d, w) -> {
                    saveMultiline(activity, key, input.getText().toString(), title);
                    d.dismiss();
                },
                resetToDefault ? "恢复默认" : "取消", negative);
    }

    private void showMultilineEditFallback(final Activity activity, final String title,
                                           final String key, final String hint,
                                           final boolean resetToDefault) {
        try {
            final EditText input = buildMultilineInput(activity, key, hint);
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setView(wrapScrollableInput(activity, input))
                    .setPositiveButton("保存", (d, w) ->
                            saveMultiline(activity, key, input.getText().toString(), title));
            if (resetToDefault) {
                builder.setNegativeButton("恢复默认", (d, w) -> {
                    saveMultiline(activity, key, "", title);
                    Toast.makeText(activity, "已恢复默认", Toast.LENGTH_SHORT).show();
                });
            } else {
                builder.setNegativeButton("取消", null);
            }
            builder.show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "多行编辑框失败(" + title + "): " + t);
        }
    }

    private void saveMultiline(Activity activity, String key, String raw, String title) {
        String normalized;
        if (App.KEY_POST_KEYWORDS.equals(key)) {
            StringBuilder sb = new StringBuilder();
            for (String line : raw.split("\n")) {
                String k = line.trim();
                if (!k.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(k);
                }
            }
            normalized = sb.toString();
        } else if (App.KEY_BROWSER_REDIRECT_FORCE.equals(key)
                || App.KEY_BROWSER_REDIRECT_BLOCK.equals(key)) {
            StringBuilder sb = new StringBuilder();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String line : raw.split("\n")) {
                String d = line.trim().toLowerCase();
                if (d.isEmpty()) {
                    continue;
                }
                if (d.startsWith("http://")) {
                    d = d.substring(7);
                } else if (d.startsWith("https://")) {
                    d = d.substring(8);
                }
                int slash = d.indexOf('/');
                if (slash >= 0) {
                    d = d.substring(0, slash);
                }
                if (!d.isEmpty() && seen.add(d)) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(d);
                }
            }
            normalized = sb.toString();
        } else {
            normalized = raw.trim();
        }
        HeyboxPrefs.setString(key, normalized);
        LogRecorder.recordEvent(title + " 已保存");
        Toast.makeText(activity, "已保存，立即生效", Toast.LENGTH_SHORT).show();
        refreshEmbeddedPanel(activity);
    }

    private void showAiProviderDialog(final Activity activity) {
        String current = module.getString(App.KEY_AI_PROVIDER, "");
        final int checked = Math.max(AIClickbaitChecker.providerIndex(current), 0);
        withHeyboxDialog(activity, spec -> {
            LinearLayout list = buildOptionRowList(activity,
                    AIClickbaitChecker.PROVIDER_LABELS, checked);
            Dialog dialog = spec.buildAndShow(activity, "选择 AI 提供商", list, null, null,
                    "取消", (d, w) -> d.dismiss());
            bindOptionRows(dialog, list, index -> applyAiProvider(activity, index));
        }, () -> showSingleChoiceFallback(activity, "选择 AI 提供商",
                AIClickbaitChecker.PROVIDER_LABELS, checked,
                index -> applyAiProvider(activity, index)));
    }

    private void applyAiProvider(Activity activity, int index) {
        try {
            HeyboxPrefs.setString(App.KEY_AI_PROVIDER, AIClickbaitChecker.PROVIDER_IDS[index]);
            String base = AIClickbaitChecker.PROVIDER_BASE_URLS[index];
            String model = AIClickbaitChecker.PROVIDER_MODELS[index];
            if (!base.isEmpty()) {
                HeyboxPrefs.setString(App.KEY_AI_BASE_URL, base);
            }
            if (!model.isEmpty()) {
                HeyboxPrefs.setString(App.KEY_AI_MODEL, model);
            }
            LogRecorder.recordEvent("AI 提供商已选择: " + AIClickbaitChecker.PROVIDER_IDS[index]);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "保存 AI 提供商失败: " + t);
            return;
        }
        refreshEmbeddedPanel(activity);
    }

    private void showAiMaxTokensDialog(final Activity activity) {
        int current = AIClickbaitChecker.maxTokens(module);
        String[] labelArr = new String[AIClickbaitChecker.MAX_TOKEN_OPTIONS.length];
        int checkedIdx = 0;
        for (int i = 0; i < labelArr.length; i++) {
            int v = AIClickbaitChecker.MAX_TOKEN_OPTIONS[i];
            labelArr[i] = v + (v == 700 ? "（推荐）" : "");
            if (v == current) {
                checkedIdx = i;
            }
        }
        final String[] labels = labelArr;
        final int checked = checkedIdx;
        withHeyboxDialog(activity, spec -> {
            LinearLayout list = buildOptionRowList(activity, labels, checked);
            Dialog dialog = spec.buildAndShow(activity, "输出 Token 上限", list, null, null,
                    "取消", (d, w) -> d.dismiss());
            bindOptionRows(dialog, list, index -> applyAiMaxTokens(activity, index));
        }, () -> showSingleChoiceFallback(activity, "输出 Token 上限", labels, checked,
                index -> applyAiMaxTokens(activity, index)));
    }

    private void applyAiMaxTokens(Activity activity, int index) {
        try {
            HeyboxPrefs.setString(App.KEY_AI_MAX_TOKENS,
                    String.valueOf(AIClickbaitChecker.MAX_TOKEN_OPTIONS[index]));
            LogRecorder.recordEvent("AI 输出上限已选择: "
                    + AIClickbaitChecker.MAX_TOKEN_OPTIONS[index]);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "保存 AI 输出上限失败: " + t);
            return;
        }
        Toast.makeText(activity, "已保存，立即生效", Toast.LENGTH_SHORT).show();
        refreshEmbeddedPanel(activity);
    }

    private void testAiConnection(Activity activity) {
        Toast.makeText(activity, "正在测试 AI 连接…", Toast.LENGTH_SHORT).show();
        AIClickbaitChecker.testConnection(module, (ok, message) -> Toast.makeText(activity,
                ok ? "AI 连接成功：" + message : "AI 连接失败：" + message,
                Toast.LENGTH_LONG).show());
    }

    private static final String DISCLAIMER_TEXT =
            "本应用与清枫(北京)科技有限公司无任何关联，亦未经其授权或认可\n\n"
                    + "本项目仅用于学习与研究小黑盒 APP 的部分技术原理，严禁用于任何商业或非法用途\n\n"
                    + "请在下载后 24 小时内删除本应用及相关文件\n\n"
                    + "禁止在 小黑盒 / HeyBox 平台内发布、讨论或传播本模块的内容，违者后果自负";

    private void maybeShowDisclaimer(final Activity activity) {
        try {
            if (HeyboxPrefs.getBoolean(App.KEY_DISCLAIMER_ACCEPTED, false)) {
                maybePromptGlassProvider(activity);
                return;
            }
            withHeyboxDialog(activity, spec -> showDisclaimerNative(activity, spec),
                    () -> showDisclaimerFallback(activity));
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "免责声明弹窗调度失败: " + t);
        }
    }

    private void showDisclaimerNative(final Activity activity,
                                      DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        TextView message = buildDialogMessage(activity, DISCLAIMER_TEXT);
        Dialog dialog = spec.buildAndShow(activity, "免责声明", message, "同意并继续",
                (d, w) -> {
                    d.dismiss();
                    acceptDisclaimer(activity);
                },
                "不同意并退出", (d, w) -> {
                    d.dismiss();
                    exitWithoutConsent(activity);
                });
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
    }

    private void showDisclaimerFallback(final Activity activity) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("免责声明")
                    .setMessage(DISCLAIMER_TEXT)
                    .setCancelable(false)
                    .setPositiveButton("同意并继续", (d, w) -> acceptDisclaimer(activity))
                    .setNegativeButton("不同意并退出", (d, w) -> exitWithoutConsent(activity))
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "免责声明系统弹窗失败: " + t);
        }
    }

    private void acceptDisclaimer(final Activity activity) {
        writeEmbeddedBoolean(activity, App.KEY_DISCLAIMER_ACCEPTED, true);
        activity.getWindow().getDecorView().postDelayed(
                () -> maybePromptGlassProvider(activity), 600L);
    }

    private void exitWithoutConsent(Activity activity) {
        try {
            activity.finishAffinity();
        } catch (Throwable t) {
            activity.finish();
        }
    }

    private void maybePromptGlassProvider(final Activity activity) {
        try {
            if (sLaunchPromptShown) {
                return;
            }
            if (!HeyboxPrefs.getBoolean(App.KEY_DISCLAIMER_ACCEPTED, false)) {
                return;
            }
            if (!GlassProvider.isHbmodInstalled(activity)) {
                return;
            }
            if (!module.getString(App.KEY_GLASS_PROVIDER, "").isEmpty()) {
                return;
            }
            sLaunchPromptShown = true;
            activity.getWindow().getDecorView().post(() -> showGlassProviderDialog(activity));
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "液态玻璃实现选择提示失败: " + t);
        }
    }

    private void showOpenWebDialog(final Activity activity) {
        withHeyboxDialog(activity, spec -> showOpenWebDialogNative(activity, spec),
                () -> showOpenWebDialogFallback(activity));
    }

    private EditText createWebUrlInput(Activity activity) {
        EditText input = new EditText(activity);
        int pad = module.dp(activity, 10);
        input.setPadding(pad, pad, pad, pad);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("例如：https://example.com");
        String current = HeyboxPrefs.getString(App.KEY_WEBVIEW_ENTRY_URL, DEFAULT_WEBVIEW_ENTRY_URL);
        input.setText(current == null || current.trim().isEmpty() ? DEFAULT_WEBVIEW_ENTRY_URL : current);
        input.setSelection(input.length());
        int bgId = hostResId(activity, "bg_dialog_edit", "drawable", 0);
        if (bgId != 0) input.setBackgroundResource(bgId);
        return input;
    }

    private void saveAndOpenWeb(Activity activity, String raw) {
        String url = raw == null ? "" : raw.trim();
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (url.isEmpty() || uri.getHost() == null || scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            Toast.makeText(activity, "请输入有效的 http/https 网页地址", Toast.LENGTH_SHORT).show();
            return;
        }
        HeyboxPrefs.setString(App.KEY_WEBVIEW_ENTRY_URL, url);
        openNativeWeb(activity, url);
    }

    private void showOpenWebDialogNative(final Activity activity, DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        final EditText input = createWebUrlInput(activity);
        spec.buildAndShow(activity, "打开网页", input, "打开",
                (d, w) -> saveAndOpenWeb(activity, input.getText().toString()),
                "取消", (d, w) -> d.dismiss());
    }

    private void showOpenWebDialogFallback(final Activity activity) {
        try {
            final EditText input = createWebUrlInput(activity);
            new AlertDialog.Builder(activity).setTitle("打开网页")
                    .setMessage("仅支持 http/https，将使用小黑盒内置浏览器打开")
                    .setView(input).setPositiveButton("打开", (d, w) -> saveAndOpenWeb(activity, input.getText().toString()))
                    .setNegativeButton("取消", null).show();
        } catch (Throwable t) { module.logd(Log.WARN, module.TAG, "打开网页编辑框失败", t); }
    }

    private void showAboutDialog(final Activity activity) {
        withHeyboxDialog(activity, spec -> showAboutDialogNative(activity, spec),
                () -> showAboutDialogFallback(activity));
    }

    private void showAboutDialogNative(final Activity activity,
                                       DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        spec.buildAndShow(activity, "关于 BetterHeybox", buildAboutContent(activity), "打开 GitHub 仓库",
                (d, w) -> {
                    d.dismiss();
                    openNativeWeb(activity, DEFAULT_WEBVIEW_ENTRY_URL);
                },
                "关闭", (d, w) -> d.dismiss());
    }

    private void showAboutDialogFallback(final Activity activity) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("关于 BetterHeybox")
                    .setMessage("版本 " + moduleVersionName(activity)
                            + "\nGitHub：" + DEFAULT_WEBVIEW_ENTRY_URL)
                    .setPositiveButton("打开 GitHub 仓库",
                            (d, w) -> openNativeWeb(activity, DEFAULT_WEBVIEW_ENTRY_URL))
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "关于弹窗失败: " + t);
        }
    }

    private View buildAboutContent(Activity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = module.dp(activity, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, pad, 0, pad * 2);
        box.setLayoutParams(lp);
        box.setPadding(pad, pad, pad, pad);

        TextView version = new TextView(activity);
        version.setText("版本 " + moduleVersionName(activity));
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int textColor = hostColor(activity, "color_text_primary_day_night", 0);
        if (textColor != 0) {
            version.setTextColor(textColor);
        }
        box.addView(version);

        TextView link = new TextView(activity);
        link.setText(DEFAULT_WEBVIEW_ENTRY_URL);
        link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int linkColor = hostColor(activity, "color_text_link_day_night", 0xFF3B78E7);
        link.setTextColor(linkColor);
        box.addView(link);
        link.setOnClickListener(v -> openNativeWeb(activity, DEFAULT_WEBVIEW_ENTRY_URL));
        return box;
    }

    private String moduleVersionName(Activity activity) {
        try {
            android.content.pm.ApplicationInfo info = module.getModuleApplicationInfo();
            android.content.pm.PackageInfo pkg = activity.getPackageManager()
                    .getPackageArchiveInfo(info.sourceDir, 0);
            if (pkg != null && pkg.versionName != null) {
                String v = pkg.versionName;
                return v.startsWith("v") ? v.substring(1) : v;
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private void openNativeWeb(Activity activity, String url) {
        try {
            Class<?> webActivity = Class.forName(
                    "com.max.xiaoheihe.module.webview.NativeWebActionActivity", false,
                    activity.getClassLoader());
            Intent intent = new Intent(activity, webActivity)
                    .putExtra("pageurl", url)
                    .putExtra("title", "BetterHeybox");
            activity.startActivity(intent);
            LogRecorder.recordEvent("打开小黑盒内置网页: " + url);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "启动小黑盒内置浏览器失败", t);
            Toast.makeText(activity, "小黑盒内置浏览器不可用", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetLiquidGlassSettings(Activity activity) {
        HeyboxPrefs.setBoolean(App.KEY_LIQUID_GLASS, true);
        HeyboxPrefs.setBoolean(App.KEY_GLASS_IMMERSIVE, true);
        HeyboxPrefs.setBoolean(App.KEY_GLASS_ADAPTIVE, true);
        HeyboxPrefs.setString(App.KEY_GLASS_DARK_COLOR, "#000000");
        HeyboxPrefs.setString(App.KEY_GLASS_DARK_ALPHA, "56");
        HeyboxPrefs.setString(App.KEY_GLASS_LIGHT_COLOR, "#FFFFFF");
        HeyboxPrefs.setString(App.KEY_GLASS_LIGHT_ALPHA, "64");
        HeyboxPrefs.setString(App.KEY_GLASS_BAR_HEIGHT, "0");
        HeyboxPrefs.setString(App.KEY_GLASS_BAR_OFFSET, "16");
        HeyboxPrefs.setBoolean(App.KEY_GLASS_FIT_TABS, false);
        HeyboxPrefs.setString(App.KEY_GLASS_SIDE_MARGIN, "16");
        HeyboxPrefs.setString(App.KEY_GLASS_BAR_WIDTH_MODE, "0");
        HeyboxPrefs.setString(App.KEY_GLASS_BAR_WIDTH_PCT, "100");
        HeyboxPrefs.setString(App.KEY_GLASS_TAB_WIDTH_PCT, "100");
        HeyboxPrefs.setString(App.KEY_GLASS_BAR_LAYOUT, "0");
        maybeRefreshGlassRuntime(activity, App.KEY_LIQUID_GLASS);
        Toast.makeText(activity, "液态玻璃设置已恢复默认", Toast.LENGTH_SHORT).show();
        View panel = mSettingsPanel == null ? null : mSettingsPanel.get();
        if (panel != null && panel.getParent() != null) showEmbeddedSettings(activity);
    }
    private void showEditLinkDialog(final Activity activity, final String title, final String key) {
        withHeyboxDialog(activity, spec -> showEditLinkDialogNative(activity, title, key, spec),
                () -> showEditLinkDialogFallback(activity, title, key));
    }

    private void showEditLinkDialogNative(final Activity activity, final String title, final String key,
                                          DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        final EditText input = new EditText(activity);
        int pad = module.dp(activity, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, pad, 0, pad * 2);
        input.setLayoutParams(lp);
        input.setPadding(pad, pad, pad, pad);
        input.setGravity(Gravity.CENTER_VERTICAL);
        int bgId = hostResId(activity, "bg_dialog_edit", "drawable", 0);
        if (bgId != 0) {
            input.setBackgroundResource(bgId);
        }
        int textColor = hostColor(activity, "color_text_primary_day_night", 0);
        if (textColor != 0) {
            input.setTextColor(textColor);
        }
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setSingleLine(true);
        input.setHint("例如：https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=123456");
        String cur = HeyboxPrefs.getString(key, "");
        input.setText(cur == null ? "" : cur);
        input.setSelection(input.getText().length());
        DialogInterface.OnClickListener saveListener = (d, w) -> {
            try {
                HeyboxPrefs.setString(key, input.getText().toString().trim());
                maybeRefreshGlassRuntime(activity, key);
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show();
                module.logd(Log.INFO, module.TAG, "分享链接已保存: " + key);
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "保存分享链接失败: " + t);
            }
            d.dismiss();
        };
        spec.buildAndShow(activity, title, input, "保存", saveListener, "取消", (d, w) -> d.dismiss());
        module.logd(Log.INFO, module.TAG, "✔ 使用小黑盒原生弹窗编辑链接: " + key);
    }
    private void showEditLinkDialogFallback(final Activity activity, final String title, final String key) {
        try {
            final EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setSingleLine(true);
            input.setHint("例如：https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=123456");
            String cur = HeyboxPrefs.getString(key, "");
            input.setText(cur == null ? "" : cur);
            input.setSelection(input.getText().length());
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setView(input)
                    .setPositiveButton("保存", (dialog, which) -> {
                        try {
                            HeyboxPrefs.setString(key, input.getText().toString().trim());
                            maybeRefreshGlassRuntime(activity, key);
                            Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show();
                            module.logd(Log.INFO, module.TAG, "分享链接已保存: " + key);
                        } catch (Throwable t) {
                            module.logd(Log.WARN, module.TAG, "保存分享链接失败: " + t);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "打开链接编辑框失败: " + t);
        }
    }

    private void showRestartAppDialog(Activity activity, ClassLoader cl) {
        try {
            Class<?> ktCls = Class.forName(
                    "com.max.xiaoheihe.accelworld.AccelWorldWebkitKt", false, cl);
            Method x = ktCls.getDeclaredMethod("x", Context.class, String.class);
            x.invoke(null, activity, "底栏改动需重启小黑盒后生效");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "复用小黑盒重启 Dialog 失败，回退系统 AlertDialog: " + t);
            try {
                new AlertDialog.Builder(activity)
                        .setTitle("重新启动APP生效")
                        .setMessage("底栏改动需重启小黑盒后生效")
                        .setPositiveButton("我知道了", null)
                        .show();
            } catch (Throwable t2) {
                module.logd(Log.ERROR, module.TAG, "回退弹窗也失败", t2);
            }
        }
    }

    private void startEmbeddedExport(final Activity activity) {
        try {
            String json = ConfigBackup.buildJson(module::isEnabled, module::getString);
            if (json == null) {
                Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
                return;
            }
            final String content = json;
            sPendingPick = uri -> writeEmbeddedExport(activity, uri, content);
            String fileName = "BetterHeybox配置_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".json";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            activity.startActivityForResult(intent, REQUEST_EMBEDDED_EXPORT);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "打开导出选择器失败: " + t);
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeEmbeddedExport(Activity activity, Uri uri, String json) {
        try {
            ContentResolver resolver = activity.getContentResolver();
            OutputStream os = resolver.openOutputStream(uri);
            if (os == null) {
                Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
                return;
            }
            try (OutputStream out = os) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            LogRecorder.recordEvent("内嵌面板配置已导出: " + uri);
            Toast.makeText(activity, "配置已导出", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "写入导出文件失败: " + t);
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void startEmbeddedLogExport(final Activity activity) {
        String logPath = LogRecorder.getLogFilePath();
        File logFile = logPath != null ? new File(logPath) : null;
        if (logFile == null || !logFile.exists() || logFile.length() == 0) {
            Toast.makeText(activity, "暂无日志文件：请先开启「记录日志」，再打开一次小黑盒，然后回来导出",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            sPendingPick = uri -> writeEmbeddedLogExport(activity, uri);
            String fileName = "BetterHeybox日志_" + new SimpleDateFormat("yyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".txt";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            activity.startActivityForResult(intent, REQUEST_EMBEDDED_LOG_EXPORT);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "打开日志导出选择器失败: " + t);
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeEmbeddedLogExport(Activity activity, Uri uri) {
        try {
            String content = LogExport.buildExportText(activity);
            ContentResolver resolver = activity.getContentResolver();
            OutputStream os = resolver.openOutputStream(uri);
            if (os == null) {
                Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
                return;
            }
            try (OutputStream out = os) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            LogRecorder.recordEvent("内嵌面板日志已导出: " + uri);
            Toast.makeText(activity, "日志已导出", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "写入日志导出文件失败: " + t);
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void showWebLogDialog(Activity activity) {
        withHeyboxDialog(activity,
                spec -> showWebLogDialogNative(activity, spec),
                () -> showWebLogDialogFallback(activity));
    }

    private TextView buildWebLogText(Activity activity) {
        String data = HeyboxPrefs.getString(App.KEY_WEB_LOG_DATA, "");
        TextView text = buildDialogMessage(activity, data.isEmpty()
                ? "暂无记录，开启「网页日志」后访问内置网页即开始记录" : data);
        text.setTextIsSelectable(true);
        return text;
    }

    private void showWebLogDialogNative(final Activity activity,
                                        DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        int pad = module.dp(activity, 10);
        ScrollView scroller = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView copyRow = new TextView(activity);
        copyRow.setText("全部复制");
        copyRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        copyRow.setPadding(pad, pad, pad, 0);
        copyRow.setTextColor(hostColor(activity, "color_text_link_day_night", 0xFF1677FF));
        copyRow.setClickable(true);
        copyRow.setOnClickListener(v -> copyWebLog(activity));
        box.addView(copyRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(buildWebLogText(activity));
        scroller.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 400)));
        spec.buildAndShow(activity, "网页日志", scroller,
                "清空", (d, w) -> {
                    BrowserRedirectHook.clearLog();
                    Toast.makeText(activity, "网页日志已清空", Toast.LENGTH_SHORT).show();
                    d.dismiss();
                },
                "关闭", (d, w) -> d.dismiss());
    }

    private void showWebLogDialogFallback(final Activity activity) {
        try {
            ScrollView scroller = new ScrollView(activity);
            scroller.addView(buildWebLogText(activity));
            new AlertDialog.Builder(activity)
                    .setTitle("网页日志")
                    .setView(scroller)
                    .setPositiveButton("清空", (d, w) -> {
                        BrowserRedirectHook.clearLog();
                        Toast.makeText(activity, "网页日志已清空", Toast.LENGTH_SHORT).show();
                    })
                    .setNeutralButton("全部复制", (d, w) -> copyWebLog(activity))
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "网页日志弹窗失败: " + t);
        }
    }

    private void copyWebLog(Activity activity) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "BetterHeybox 网页日志", HeyboxPrefs.getString(App.KEY_WEB_LOG_DATA, "")));
                Toast.makeText(activity, "已复制全部日志", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "网页日志复制失败", t);
        }
    }

    private String browserTargetLabel(Activity activity) {
        String pkg = module.getString(App.KEY_BROWSER_TARGET, "");
        if (pkg.isEmpty()) {
            return "跟随系统";
        }
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            return pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0)).toString() + "（" + pkg + "）";
        } catch (Throwable t) {
            return pkg;
        }
    }

    private List<String[]> installedBrowsers(Activity activity) {
        List<String[]> out = new ArrayList<>();
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            List<android.content.pm.ResolveInfo> infos = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com/")), 0);
            java.util.LinkedHashMap<String, android.content.pm.ResolveInfo> byPkg =
                    new java.util.LinkedHashMap<>();
            for (android.content.pm.ResolveInfo info : infos) {
                if (info.activityInfo == null) {
                    continue;
                }
                String pkg = info.activityInfo.packageName;
                if (pkg == null || MainModule.TARGET_PKG.equals(pkg)) {
                    continue;
                }
                byPkg.putIfAbsent(pkg, info);
            }
            for (android.content.pm.ResolveInfo info : byPkg.values()) {
                String label;
                try {
                    label = info.loadLabel(pm).toString();
                } catch (Throwable t) {
                    label = info.activityInfo.packageName;
                }
                out.add(new String[]{label, info.activityInfo.packageName});
            }
            out.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "枚举浏览器失败: " + t);
        }
        return out;
    }

    private void showBrowserTargetDialog(Activity activity) {
        List<String[]> browsers = installedBrowsers(activity);
        if (browsers.isEmpty()) {
            showEditLinkDialog(activity, "重定向浏览器包名", App.KEY_BROWSER_TARGET);
            return;
        }
        String[] values = new String[browsers.size() + 1];
        String[] labels = new String[browsers.size() + 1];
        values[0] = "";
        labels[0] = "跟随系统";
        for (int i = 0; i < browsers.size(); i++) {
            values[i + 1] = browsers.get(i)[1];
            labels[i + 1] = browsers.get(i)[0] + "（" + browsers.get(i)[1] + "）";
        }
        String cur = module.getString(App.KEY_BROWSER_TARGET, "");
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(cur)) {
                checked = i;
                break;
            }
        }
        final int checkedIndex = checked;
        withHeyboxDialog(activity, spec -> {
            LinearLayout list = buildOptionRowList(activity, labels, checkedIndex);
            Dialog dialog = spec.buildAndShow(activity, "选择重定向浏览器", list, null, null,
                    "取消", (d, w) -> d.dismiss());
            bindOptionRows(dialog, list, index -> applyBrowserTarget(activity, values[index], labels[index]));
        }, () -> showSingleChoiceFallback(activity, "选择重定向浏览器", labels, checkedIndex,
                index -> applyBrowserTarget(activity, values[index], labels[index])));
    }

    private void applyBrowserTarget(Activity activity, String pkg, String label) {
        try {
            HeyboxPrefs.init(activity);
            HeyboxPrefs.setString(App.KEY_BROWSER_TARGET, pkg);
            LogRecorder.recordEvent("重定向浏览器已选择: " + pkg);
            Toast.makeText(activity, "重定向浏览器已设为 " + label, Toast.LENGTH_SHORT).show();
            refreshEmbeddedPanel(activity);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "保存重定向浏览器失败: " + t);
        }
    }

    private void showEmbeddedRuntimeStatus(Activity activity) {        try {
            StringBuilder sb = new StringBuilder();
            sb.append("构建类型: ").append(BuildFlags.DEBUG ? "debug" : "release").append('\n');
            sb.append('\n').append("—— 本进程（小黑盒）运行检查点 ——\n")
                    .append(Checkpoint.dump(150));
            new AlertDialog.Builder(activity)
                    .setTitle("运行状态")
                    .setMessage(sb.toString())
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "运行状态弹窗失败: " + t);
        }
    }
    private void showLogPreview(final Activity activity) {
        withHeyboxDialog(activity,
                spec -> showLogPreviewNative(activity, spec),
                () -> showLogPreviewFallback(activity));
    }

    private TextView buildLogPreviewText(Activity activity) {
        String tail = LogRecorder.readTail(200);
        TextView text = buildDialogMessage(activity, tail == null
                ? "暂无日志：请先开启「记录日志」；关闭「详细日志」时只会记录错误日志"
                : "当前日志：" + LogRecorder.sizeInfo() + "（以下为最近 200 行）\n\n" + tail);
        text.setTextIsSelectable(true);
        return text;
    }

    private void copyLogPreview(Activity activity) {
        try {
            String tail = LogRecorder.readTail(2000);
            if (tail == null) {
                Toast.makeText(activity, "暂无可复制的日志", Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "BetterHeybox \u65e5\u5fd7", tail));
                Toast.makeText(activity, "已复制最近日志", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "日志复制失败", t);
        }
    }

    private void showLogPreviewNative(final Activity activity,
                                      DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        int pad = module.dp(activity, 10);
        ScrollView scroller = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView copyRow = new TextView(activity);
        copyRow.setText("复制最近日志");
        copyRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        copyRow.setPadding(pad, pad, pad, 0);
        copyRow.setTextColor(hostColor(activity, "color_text_link_day_night", 0xFF1677FF));
        copyRow.setClickable(true);
        copyRow.setOnClickListener(v -> copyLogPreview(activity));
        box.addView(copyRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(buildLogPreviewText(activity));
        scroller.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 400)));
        spec.buildAndShow(activity, "模块日志", scroller,
                "清空", (d, w) -> {
                    doClearLogs(activity);
                    d.dismiss();
                },
                "关闭", (d, w) -> d.dismiss());
    }

    private void showLogPreviewFallback(final Activity activity) {
        try {
            ScrollView scroller = new ScrollView(activity);
            scroller.addView(buildLogPreviewText(activity));
            new AlertDialog.Builder(activity)
                    .setTitle("模块日志")
                    .setView(scroller)
                    .setPositiveButton("清空", (d, w) -> doClearLogs(activity))
                    .setNeutralButton("复制最近日志", (d, w) -> copyLogPreview(activity))
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "日志预览弹窗失败: " + t);
        }
    }

    private void confirmClearLogs(final Activity activity) {
        withHeyboxDialog(activity,
                spec -> clearLogsNative(activity, spec),
                () -> clearLogsFallback(activity));
    }

    private void clearLogsNative(Activity activity, DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        TextView message = buildDialogMessage(activity,
                "将删除模块日志文件（log.txt / log.1.txt）与运行检查点，确定继续？");
        spec.buildAndShow(activity, "清除日志", message, "清除",
                (d, w) -> {
                    doClearLogs(activity);
                    d.dismiss();
                },
                "取消", (d, w) -> d.dismiss());
        module.logd(Log.INFO, module.TAG, "✔ 使用小黑盒原生弹窗确认清除日志");
    }

    private void clearLogsFallback(final Activity activity) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("清除日志")
                    .setMessage("将删除模块日志文件（log.txt / log.1.txt）与运行检查点，确定继续？")
                    .setPositiveButton("清除", (d, w) -> doClearLogs(activity))
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "清除日志弹窗失败: " + t);
        }
    }

    private void doClearLogs(Activity activity) {
        try {
            LogRecorder.setContext(activity);
            String freed = LogRecorder.clear();
            Checkpoint.clear();
            LogRecorder.recordEvent("模块日志已清除: " + (freed == null ? "无日志文件" : freed));
            String text = freed == null
                    ? "已清除运行检查点（暂无日志文件）"
                    : "已清除日志 " + freed + " 与运行检查点";
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            module.logd(Log.INFO, module.TAG, text);
            refreshEmbeddedPanel(activity);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "清除日志失败: " + t);
        }
    }

    private void showTargetStatus(Activity activity) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("广告 content_type: ").append(PromoteDetector.contentTypesInfo()).append('\n');
            sb.append(HeyboxTargets.report());
            new AlertDialog.Builder(activity)
                    .setTitle("目标解析状态")
                    .setMessage(sb.toString())
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "目标解析状态弹窗失败: " + t);
        }
    }

    private void startEmbeddedImport(final Activity activity) {
        withHeyboxDialog(activity, spec -> startEmbeddedImportNative(activity, spec),
                () -> showEmbeddedImportConfirmFallback(activity));
    }

    private void startEmbeddedImportNative(final Activity activity, DexKitResolver.HeyboxDialogSpec spec)
            throws Exception {
        TextView message = buildDialogMessage(activity,
                "导入将覆盖当前所有设置（开关、分享链接、分享渠道等），确定继续？");
        DialogInterface.OnClickListener importListener = (d, w) -> {
            launchImportPicker(activity);
            d.dismiss();
        };
        spec.buildAndShow(activity, "导入配置", message, "导入", importListener,
                "取消", (d, w) -> d.dismiss());
        module.logd(Log.INFO, module.TAG, "✔ 使用小黑盒原生弹窗确认导入配置");
    }

    private void showEmbeddedImportConfirmFallback(final Activity activity) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("导入配置")
                    .setMessage("导入将覆盖当前所有设置（开关、分享链接、分享渠道等），确定继续？")
                    .setPositiveButton("导入", (dialog, which) -> launchImportPicker(activity))
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "导入确认弹框失败: " + t);
        }
    }

    private void launchImportPicker(Activity activity) {
        try {
            sPendingPick = uri -> readEmbeddedImport(activity, uri);
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            activity.startActivityForResult(intent, REQUEST_EMBEDDED_IMPORT);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "打开导入选择器失败: " + t);
            Toast.makeText(activity, "导入失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void readEmbeddedImport(final Activity activity, Uri uri) {
        try {
            ContentResolver resolver = activity.getContentResolver();
            InputStream is = resolver.openInputStream(uri);
            if (is == null) {
                Toast.makeText(activity, "导入失败：文件格式无效或已损坏", Toast.LENGTH_SHORT).show();
                return;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = is) {
                byte[] chunk = new byte[8192];
                int len;
                while ((len = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, len);
                }
            }
            String json = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            HeyboxPrefs.init(activity);
            ConfigBackup.ApplyResult result = ConfigBackup.applyJson(
                    json,
                    (key, value) -> writeEmbeddedBoolean(activity, key, value),
                    (key, value) -> HeyboxPrefs.setString(key, value));
            if (result == null) {
                Toast.makeText(activity, "导入失败：文件格式无效或已损坏", Toast.LENGTH_SHORT).show();
                return;
            }
            LogRecorder.recordEvent("内嵌面板配置已导入: " + result.applied + " 项, uri=" + uri);
            Toast.makeText(activity, "配置已导入（" + result.applied + " 项）", Toast.LENGTH_SHORT).show();
            View panel = mSettingsPanel == null ? null : mSettingsPanel.get();
            if (panel != null && panel.getParent() != null) {
                showEmbeddedSettings(activity);
            }
            if (result.restartRequired) {
                showRestartAppDialog(activity, activity.getClassLoader());
            }
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "读取导入文件失败: " + t);
            Toast.makeText(activity, "导入失败：文件格式无效或已损坏", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean readEmbeddedBoolean(String key, boolean defaultValue) {
        return module.isEnabled(key, defaultValue);
    }

    private String modulePackageName() {
        try {
            android.content.pm.ApplicationInfo info = module.getModuleApplicationInfo();
            if (info != null && info.packageName != null && !info.packageName.isEmpty()) {
                return info.packageName;
            }
        } catch (Throwable ignored) {
        }
        return "com.better.heybox";
    }

    private void maybeRefreshGlassRuntime(Activity activity, String key) {
        if (key == null || activity == null) {
            return;
        }
        if (!App.KEY_LIQUID_GLASS.equals(key) && !key.startsWith("glass_")) {
            return;
        }
        try {
            LiquidGlassInstaller.refreshGlassWith(activity);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "玻璃设置运行时刷新失败: " + key, t);
        }
    }

    private boolean writeEmbeddedBoolean(Activity activity, String key, boolean value) {
        LogRecorder.setContext(activity);
        HeyboxPrefs.init(activity);
        boolean localOk = HeyboxPrefs.setBoolean(key, value);
        LogRecorder.recordEvent("内嵌面板开关已写入小黑盒本地配置: key=" + key
                + ", value=" + value + ", ok=" + localOk);
        try {
            Intent request = new Intent(PreferenceReceiver.ACTION_SET_BOOLEAN)
                    .setComponent(new android.content.ComponentName(
                            modulePackageName(), PreferenceReceiver.class.getName()))
                    .putExtra(PreferenceReceiver.EXTRA_KEY, key)
                    .putExtra(PreferenceReceiver.EXTRA_VALUE, value)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            activity.sendBroadcast(request);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "远程镜像广播失败（本地配置已生效，不影响使用）: " + key, t);
        }
        if (App.KEY_CUSTOM_TEXT_SELECT.equals(key) || App.KEY_COPY_POST.equals(key)) {
            TextSelectHook.refresh();
        }
        return localOk;
    }


    public static int hostResId(Context context, String name, String type, int fallback) {
        try {
            int id = context.getResources().getIdentifier(name, type, MainModule.TARGET_PKG);
            return id != 0 ? id : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
    public static int hostColor(Context context, String name, int fallback) {
        int id = hostResId(context, name, "color", 0);
        if (id != 0) {
            try {
                return context.getColor(id);
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    private static Object[] buildHostCard(Activity activity, ClassLoader cl) throws Throwable {
        Class<?> cardCls = Class.forName("androidx.cardview.widget.CardView", false, cl);
        Object card = cardCls.getConstructor(Context.class).newInstance(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        cardCls.getMethod("setRadius", float.class).invoke(card, 8f * density);
        cardCls.getMethod("setCardElevation", float.class).invoke(card, 0f);
        try {
            cardCls.getMethod("setMaxCardElevation", float.class).invoke(card, 0f);
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = ThemeUtils.dp(activity, 12);
        cardLp.setMargins(m, ThemeUtils.dp(activity, 8), m, 0);
        ((View) card).setLayoutParams(cardLp);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((ViewGroup) card).addView(content);
        return new Object[]{card, content};
    }

    private View buildEntryCard(final Activity activity) {
        try {
            ClassLoader cl = activity.getClassLoader();
            Object[] cardPair = buildHostCard(activity, cl);
            Object card = cardPair[0];
            LinearLayout content = (LinearLayout) cardPair[1];
            Class<?> itemCls = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl);
            Object item = itemCls.getConstructor(Context.class).newInstance(activity);
            itemCls.getMethod("setTitle", String.class).invoke(item, "BetterHeybox 设置");
            try {
                itemCls.getMethod("setTitleDesc", String.class).invoke(item, "广告过滤与界面增强");
            } catch (Throwable ignored) {
            }
            Class<?> typeEnum = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView$Type", false, cl);
            Object arrow = Enum.valueOf((Class) typeEnum, "Arrow");
            itemCls.getMethod("setRightType", typeEnum).invoke(item, arrow);
            try {
                itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, true);
            } catch (Throwable ignored) {
            }
            int itemH = module.dp(activity, 48);
            ((View) item).setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemH));
            content.addView((View) item);
            return (View) card;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "构建原生入口卡片失败: " + t);
            return null;
        }
    }
}
