package com.better.heybox.hooks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
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
import java.util.List;
import java.util.Locale;

import com.better.heybox.App;
import com.better.heybox.BuildFlags;
import com.better.heybox.Checkpoint;
import com.better.heybox.ConfigBackup;
import com.better.heybox.DexKitResolver;
import com.better.heybox.GlassProvider;
import com.better.heybox.HeyboxPrefs;
import com.better.heybox.LogExport;
import com.better.heybox.LogRecorder;
import com.better.heybox.ThemeUtils;
import com.better.heybox.VersionUtils;
import com.better.heybox.VideoDownloadManager;
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
                                () -> maybePromptGlassProvider(activity), 1000L);
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
        EXPORT_LOG, RUNTIME_STATUS, OPEN_WEB, PICK_DIR, RESET_GLASS, CHOOSE_GLASS
    }

    private static class SwitchDef {
        final String title;
        final String desc;
        final String key;
        final boolean def;
        final boolean restart;
        final boolean clickRow;
        final String editKey; // EDIT_LINK 时编辑的字符串配置 key
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
                    new SwitchDef("屏蔽推广贴", null, App.KEY_PROMOTE_AD, true, false),
            }),
            new SettingsGroup("视频下载", new SwitchDef[]{
                    new SwitchDef("下载视频", "在支持的视频上显示下载入口", App.KEY_VIDEO_DOWNLOAD, true, false),
                    new SwitchDef("保存位置", "点击选择保存文件夹", null, false, false, true, null, Action.PICK_DIR),
                    new SwitchDef("转存 MP4", "下载合并后自动转封装为 MP4", App.KEY_VIDEO_TO_MP4, true, false),
            }),
            new SettingsGroup("解除复制", new SwitchDef[]{
                    new SwitchDef("解除复制", "恢复系统标准文本选择", App.KEY_COPY_POST, true, false),
                    new SwitchDef("自绘制文本选择", "用于修复可能的选区错误（需开启「解除复制」）", App.KEY_CUSTOM_TEXT_SELECT, false, false),
                    new SwitchDef("系统分享图片", "在图片长按菜单中打开系统分享", App.KEY_SYSTEM_SHARE, true, false),
            }),
            new SettingsGroup("分享净化", new SwitchDef[]{
                    new SwitchDef("净化分享链接", null, App.KEY_PURIFY_SHARE_LINK, true, false),
            }),
            new SettingsGroup("每日任务", new SwitchDef[]{
                    new SwitchDef("自动完成每日分享任务", null, App.KEY_DAILY_TASK_ENABLED, false, false),
                    new SwitchDef("帖子链接", "任务一：分享任意帖子", null, false, false, true, App.KEY_DAILY_TASK_PICTURE),
                    new SwitchDef("游戏详情链接", "任务二：分享游戏详情", null, false, false, true, App.KEY_DAILY_TASK_NORMAL),
                    new SwitchDef("游戏评价链接", "任务三：分享游戏评价", null, false, false, true, App.KEY_DAILY_TASK_CHANNEL),
                    new SwitchDef("分享渠道", null, App.KEY_SHARE_CHANNEL, false, false, true, null, Action.CHANNEL),
                    new SwitchDef("清除今日打卡", null, null, false, false, true, null, Action.CLEAR_DAILY),
            }),
            new SettingsGroup("通用", new SwitchDef[]{
                    new SwitchDef("伪装通知权限", "让小黑盒认为通知已开启，获得签到加成", App.KEY_FAKE_NOTIFICATION, false, false),
                    new SwitchDef("屏蔽更新", "屏蔽小黑盒更新入口", App.KEY_BLOCK_UPDATE, false, false),
                    new SwitchDef("记录日志", null, App.KEY_LOG, false, false),
                    new SwitchDef("网页 DevTools", "为小黑盒内置网页开启 Chrome 远程调试", App.KEY_WEBVIEW_DEVTOOLS, false, false),
                    new SwitchDef("打开网页", "使用小黑盒内置浏览器打开指定网页", null, false, false,
                            true, App.KEY_WEBVIEW_ENTRY_URL, Action.OPEN_WEB),
                    new SwitchDef("导出日志", null, null, false, false, true, null, Action.EXPORT_LOG),
            }),
            new SettingsGroup("配置备份", new SwitchDef[]{
                    new SwitchDef("导出配置", null, null, false, false, true, null, Action.EXPORT),
                    new SwitchDef("导入配置", null, null, false, false, true, null, Action.IMPORT),
            }),
    };
    private static SettingsGroup buildBottomTabGroup(Activity activity) {
        String home = MainModule.getHeyboxTabLabel(activity, "discover", "发现");
        String store = MainModule.getHeyboxTabLabel(activity, "game_store", "游戏库");
        String bbs = MainModule.getHeyboxTabLabel(activity, "bbs", "社区");
        return new SettingsGroup("底部导航栏隐藏", new SwitchDef[]{
                new SwitchDef("隐藏「" + home + "」", null, App.KEY_HIDE_TAB_HOME, false, true),
                new SwitchDef("隐藏「" + store + "」", null, App.KEY_HIDE_TAB_HOT, false, true),
                new SwitchDef("隐藏「" + bbs + "」", null, App.KEY_HIDE_TAB_GAME, false, true),
                new SwitchDef("隐藏「加号」", null, App.KEY_HIDE_ADD, false, true),
        });
    }

    /** 「通用」分组标题（BASE_GROUPS 中的插入定位点，勿随意改名） */
    private static final String TITLE_GENERAL = "通用";
    /** 实验性功能（屏蔽双列）目标构建：1.3.394 (1127)，其余版本不显示开关 */
    private static final String EXPERIMENTAL_HEYBOX_VERSION = "1.3.394";
    private static final long EXPERIMENTAL_HEYBOX_CODE = 1127L;

    /** 顺序组装全部分组：底部导航栏隐藏置顶 → BASE_GROUPS → 运行状态行（Debug）→ 液态玻璃插到「通用」前 → 实验组 */
    private List<SettingsGroup> buildSettingsGroups(Activity activity) {
        List<SettingsGroup> groups = new ArrayList<>();
        groups.add(buildBottomTabGroup(activity));
        for (SettingsGroup g : BASE_GROUPS) {
            groups.add(g);
        }
        if (BuildFlags.DEBUG) {
            addRuntimeStatusRow(groups);
        }
        SettingsGroup glass = buildGlassGroup(activity);
        if (glass != null) {
            int insertAt = groups.size();
            for (int i = 0; i < groups.size(); i++) {
                if (TITLE_GENERAL.equals(groups.get(i).title)) {
                    insertAt = i;
                    break;
                }
            }
            groups.add(insertAt, glass);
        }
        if (VersionUtils.isHeyboxBuild(activity, EXPERIMENTAL_HEYBOX_VERSION,
                EXPERIMENTAL_HEYBOX_CODE)) {
            groups.add(new SettingsGroup("实验性功能", new SwitchDef[]{
                    new SwitchDef("屏蔽双列信息流",
                            "将首页推荐/话题/百科信息流从双列样式恢复为单列", App.KEY_SINGLE_COLUMN_FEED, false, false),
            }));
        }
        return groups;
    }

    /**
     * 液态玻璃分组动态组装：提供方切换行仅在检测到独立模块（或已做过选择）时出现，
     * 自带玻璃各选项仅在自带实现生效时出现，避免选外部模块后残留无效选项
     */
    private SettingsGroup buildGlassGroup(Activity activity) {
        boolean switchable = GlassProvider.isHbmodInstalled(activity)
                || GlassProvider.prefersHbmod(module);
        boolean ownGlass = !GlassProvider.prefersHbmod(module);
        List<SwitchDef> rows = new ArrayList<>();
        if (switchable) {
            String label = GlassProvider.providerLabel(
                    module.getString(App.KEY_GLASS_PROVIDER, ""));
            rows.add(new SwitchDef("液态玻璃提供方",
                    "当前：" + label + "，点击切换", null, false, false, true, null, Action.CHOOSE_GLASS));
        }
        if (ownGlass) {
            rows.add(new SwitchDef("液态玻璃底栏", "在底部导航显示液态玻璃效果（需重启小黑盒）", App.KEY_LIQUID_GLASS, true, true));
            rows.add(new SwitchDef("沉浸式小白条", "让底栏延伸到系统手势区域", App.KEY_GLASS_IMMERSIVE, true, false));
            rows.add(new SwitchDef("自适应反色", "标签文字与图标随背景亮度切换黑白", App.KEY_GLASS_ADAPTIVE, true, false));
            rows.add(new SwitchDef("玻璃宽度自适应", "隐藏标签后底栏宽度随可见标签数收缩", App.KEY_GLASS_FIT_TABS, false, false));
            rows.add(new SwitchDef("暗色模式底色", "输入颜色值，例如 #000000", null, false, false, true, App.KEY_GLASS_DARK_COLOR));
            rows.add(new SwitchDef("暗色模式不透明度", "输入 5-98 的百分比", null, false, false, true, App.KEY_GLASS_DARK_ALPHA));
            rows.add(new SwitchDef("亮色模式底色", "输入颜色值，例如 #FFFFFF", null, false, false, true, App.KEY_GLASS_LIGHT_COLOR));
            rows.add(new SwitchDef("亮色模式不透明度", "输入 5-98 的百分比", null, false, false, true, App.KEY_GLASS_LIGHT_ALPHA));
            rows.add(new SwitchDef("玻璃条高度", "输入 0 为自动，或 51-99 dp", null, false, false, true, App.KEY_GLASS_BAR_HEIGHT));
            rows.add(new SwitchDef("距屏幕底部", "输入 0-40 dp", null, false, false, true, App.KEY_GLASS_BAR_OFFSET));
            rows.add(new SwitchDef("恢复液态玻璃默认设置", "恢复参考项目的默认外观与布局参数", null, false, false, true, null, Action.RESET_GLASS));
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
                SwitchDef[] items = new SwitchDef[g.items.length + 1];
                System.arraycopy(g.items, 0, items, 0, g.items.length);
                items[g.items.length] = new SwitchDef(
                        "运行状态", "查看模块运行检查点", null, false, false,
                        true, null, Action.RUNTIME_STATUS);
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
                // 混淆名全部失效时的兜底：挂生命周期方法，靠重试循环等待列表构建完成
                setupMethod = findLifecycleFallback(clazz);
            }
            if (setupMethod == null) {
                module.logd(Log.ERROR, module.TAG, "✘ 未找到设置页入口方法（G1/L1/onResume 均不可用）");
                return;
            }
            final Class<?> entryClass = clazz;
            module.hook(setupMethod).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object thisObj = chain.getThisObject();
                    // 兜底走生命周期方法时可能命中父类实现，仅对设置页 Activity 生效
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
            // 内嵌面板导入/导出依赖文件选择结果
            hookActivityResult(clazz);
            module.logd(Log.INFO, module.TAG, "✔ 设置页入口 Hook 已安装 (" + setupMethod.getName() + "+retry)");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 设置页入口 Hook 失败", t);
        }
    }
    /** 入口方法解析：先按已知混淆名快速匹配，跨版本失效后由 {@link #findLifecycleFallback} 兜底 */
    private Method findSetupMethod(Class<?> clazz) {
        for (String name : new String[]{"G1", "L1"}) {
            try {
                return clazz.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** 沿继承链找 onResume（框架方法名永不混淆） */
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

    /** 文件选择结果分发：仅处理内嵌面板的请求码，其余原样放行 */
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

    /** 保存位置选择结果：持久化授权并写入配置（空值 = 恢复默认 Movies/BetterHeybox） */
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

    /** 查询文件夹显示名（查询失败返回 null） */
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

    /** 原生确认弹窗正文：主文字色 + 弹窗内边距（保存位置/导入确认共用） */
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

    /** 未设置时直接打开选择器，已设置时给换目录/恢复默认选项 */
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

    /** 保存位置展示名：优先查 DocumentsProvider 显示名，失败则取 URI 末段，再不行给通用描述 */
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

    /** 调起系统文件夹选择器（SAF），结果经 onActivityResult Hook 回调 */
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
            module.logd(Log.WARN, module.TAG, "设置页布局迟迟未就绪，放弃插入入口");
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

    private void showEmbeddedSettings(final Activity activity) {
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

            FrameLayout overlay = new FrameLayout(activity);
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
            page.addView(buildEmbeddedTitleBar(activity, cl, appbarBg));
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

            for (SettingsGroup group : buildSettingsGroups(activity)) {
                View card = buildSectionCard(activity, cl, group);
                if (card != null) {
                    box.addView(card);
                }
            }
            appendEmbeddedFooter(activity, box);
            attachEmbeddedPanel(activity, overlay);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "渲染原生设置面板失败", t);
        }
    }

    private View buildEmbeddedTitleBar(Activity activity, ClassLoader cl, int appbarBg) throws Throwable {
        Class<?> titleBarCls = Class.forName("com.max.hbcommon.component.TitleBar", false, cl);
        Object titleBar = titleBarCls.getConstructor(Context.class).newInstance(activity);
        ((View) titleBar).setBackgroundColor(appbarBg);
        titleBarCls.getMethod("setTitle", CharSequence.class).invoke(titleBar, "BetterHeybox 设置");
        titleBarCls.getMethod("setNavigationIcon", int.class)
                .invoke(titleBar, hostResId(activity, "appbar_back", "drawable", 0));
        Class<?> ocl = Class.forName("android.view.View$OnClickListener", false, cl);
        Object backListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissEmbeddedSettings();
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
            String moduleVersion = null;
            try {
                android.content.pm.ApplicationInfo moduleInfo = module.getModuleApplicationInfo();
                android.content.pm.PackageInfo pkgInfo = activity.getPackageManager()
                        .getPackageArchiveInfo(moduleInfo.sourceDir, 0);
                if (pkgInfo != null) {
                    moduleVersion = pkgInfo.versionName;
                }
            } catch (Throwable ignored) {
            }
            String displayVersion = moduleVersion;
            if (displayVersion != null && displayVersion.startsWith("v")) {
                displayVersion = displayVersion.substring(1);
            }
            footer.setText("BetterHeybox v"
                    + (displayVersion == null ? "unknown" : displayVersion));
            footer.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            footer.setGravity(android.view.Gravity.CENTER);
            footer.setTextColor(hostColor(activity, "color_text_tertiary_day_night", 0xFF8A8A8A));
            LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int fm = module.dp(activity, 16);
            footerLp.setMargins(fm, module.dp(activity, 12), fm, module.dp(activity, 24));
            footer.setLayoutParams(footerLp);
            box.addView(footer);
            module.logd(Log.INFO, module.TAG, "✔ 内嵌面板底部版本号已添加: "
                    + (displayVersion == null ? "unknown" : displayVersion));
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "内嵌面板版本号页脚渲染失败: " + t);
        }
    }

    private void attachEmbeddedPanel(Activity activity, FrameLayout overlay) {
        overlay.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                    dismissEmbeddedSettings();
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
                // setTitleDesc 只写文本且默认 GONE，还需用探针解析出的方法打开可见性开关
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
                    case OPEN_WEB:
                        setRowClick(itemCls, item, v -> showOpenWebDialog(activity));
                        break;
                    case RESET_GLASS:
                        setRowClick(itemCls, item, v -> resetLiquidGlassSettings(activity));
                        break;
                    case CHOOSE_GLASS:
                        setRowClick(itemCls, item, v -> showGlassProviderDialog(activity));
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
            Class<?> listenerCls = Class.forName(
                    "android.widget.CompoundButton$OnCheckedChangeListener", false, cl);
            Object listener = new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    try {
                        if (writeEmbeddedBoolean(activity, def.key, isChecked) && def.restart) {
                            showRestartAppDialog(activity, cl);
                        }
                        // 文本选择相关开关：对已展示的帖子立即重放，无需重启即运行时生效
                        if (App.KEY_CUSTOM_TEXT_SELECT.equals(def.key)
                                || App.KEY_COPY_POST.equals(def.key)) {
                            TextSelectHook.refresh();
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
    /** 「标题下描述」可见性开关（SettingItemView.f(boolean)），每进程解析一次 */
    private static Method sDescToggle;
    private static final String DESC_PROBE_TEXT = "BH_DESC_PROBE";

    /**
 * 解析描述可见性开关：探针试出能把 setTitleDesc 文本点亮的 boolean 单参方法
 */
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

    /** 查找探针文本是否可见 */
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

    /** 原生弹窗选项行列表：TextView 纵排、当前项高亮（分享渠道/玻璃提供方共用） */
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

    /** 选项行点击绑定：回调后统一 dismiss */
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

    /** 系统弹窗兜底的单选列表（原生框解析失败时） */
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

    /** 样式与「分享渠道」弹窗一致：小黑盒原生框 + 选项行，当前项高亮 */
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
        // 复用小黑盒自带重启提示（含重启入口）
        showRestartAppDialog(activity, activity.getClassLoader());
    }

    /** 未选择提供方时弹实现选择（触发点：MainActivity 启动）；sLaunchPromptShown 保证每次进程启动至多一次 */
    private void maybePromptGlassProvider(final Activity activity) {
        try {
            if (sLaunchPromptShown) {
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

    /** 内嵌面板导出配置：打开系统「保存到」选择器（免存储权限），结果经 onActivityResult Hook 回调写入 */
    private void startEmbeddedExport(final Activity activity) {
        try {
            // 导出的值 = 当前生效值（本地 HeyboxPrefs 优先，其次 RemotePreferences），与模块设置页文件格式一致
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

    private void showEmbeddedRuntimeStatus(Activity activity) {
        try {
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

    /** 打开系统「选择文件」选择器挑选配置备份，结果经 onActivityResult Hook 回调写入 */
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

    private boolean writeEmbeddedBoolean(Activity activity, String key, boolean value) {
        LogRecorder.setContext(activity);
        HeyboxPrefs.init(activity);
        boolean localOk = HeyboxPrefs.setBoolean(key, value);
        LogRecorder.recordEvent("内嵌面板开关已写入小黑盒本地配置: key=" + key
                + ", value=" + value + ", ok=" + localOk);
        try {
            Intent request = new Intent(PreferenceReceiver.ACTION_SET_BOOLEAN)
                    .setComponent(new android.content.ComponentName(
                            "com.better.heybox", "com.better.heybox.PreferenceReceiver"))
                    .putExtra(PreferenceReceiver.EXTRA_KEY, key)
                    .putExtra(PreferenceReceiver.EXTRA_VALUE, value)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            activity.sendBroadcast(request);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "远程镜像广播失败（本地配置已生效，不影响使用）: " + key, t);
        }
        // 文本选择相关开关（含配置导入路径）：对已展示的帖子立即重放，运行时生效
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

    /** 解析 day_night 颜色资源 */
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

    /** 返回 {card, content} */
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
