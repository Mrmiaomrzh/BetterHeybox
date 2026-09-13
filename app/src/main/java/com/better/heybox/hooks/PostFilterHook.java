package com.better.heybox.hooks;

import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import io.github.libxposed.api.XposedInterface;

/** 发帖过滤：低等级 / 关键词 / AI 标题党，多挂点分工，fail-open */
public final class PostFilterHook {

    private static volatile PostFilterHook sInstance;

    /** 委托入口 */
    public static PostFilterHook get() {
        return sInstance;
    }

    private final MainModule module;

    /** itemView → 当前绑定帖子的缓存键（AI 判定返回时校验该 view 是否仍显示同一帖子） */
    private final WeakHashMap<View, String> boundPostKeys = new WeakHashMap<>();

    /** 关键词编译缓存：原始串未变则不重解析（bind 高频调用） */
    private final Object keywordLock = new Object();

    private String keywordRaw;
    private List<Object> keywordMatchers;

    /** AI 判定返回（主线程）：命中且 view 仍绑定同一帖子时回补隐藏 */
    private final AIClickbaitChecker.VerdictCallback aiCallback;

    private volatile java.lang.ref.WeakReference<Object> sFlowControllerRef;

    private final android.os.Handler sMain = new android.os.Handler(android.os.Looper.getMainLooper());

    private final AIClickbaitChecker.VerdictCallback listAiCallback = verdicts -> {
        for (java.util.Map.Entry<String, Boolean> e : verdicts.entrySet()) {
            if (e.getValue()) {
                requestFlowRebuild();
                return;
            }
        }
    };

    private void requestFlowRebuild() {
        java.lang.ref.WeakReference<Object> ref = sFlowControllerRef;
        final Object controller = ref != null ? ref.get() : null;
        if (controller == null) {
            return;
        }
        module.logd(Log.INFO, module.TAG, "AI 判定标题党，触发首页流重建");
        sMain.post(() -> {
            try {
                Object data = controller.getClass().getMethod("getCurrentData").invoke(controller);
                controller.getClass().getMethod("setData", Object.class).invoke(controller, data);
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "首页流重建失败: " + t);
            }
        });
    }

    public PostFilterHook(MainModule module) {
        this.module = module;
        sInstance = this;
        this.aiCallback = verdicts -> {
            for (java.util.Map.Entry<String, Boolean> e : verdicts.entrySet()) {
                if (!e.getValue()) {
                    continue;
                }
                View bound = findBoundView(e.getKey());
                if (bound != null && e.getKey().equals(boundPostKeys.get(bound))
                        && bound.isAttachedToWindow()) {
                    module.logd(Log.INFO, module.TAG, "AI 判定标题党，延迟隐藏");
                    FeedItemHider.hide(bound);
                }
            }
        };
    }

    public void install(ClassLoader cl) {
        hookWaterfallCard(cl);
        hookNewsListAdapter(cl);
        hookFeedsModelDeserializer(cl);
        hookRecommendFlowController(cl);
    }

    private void hookRecommendFlowController(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.max.feature.feeds.view.RecommendFlowRVController", false, cl);
            Class<?> listCls = Class.forName("java.util.List", false, cl);
            Method m = null;
            for (Method mm : c.getDeclaredMethods()) {
                if ("buildModels".equals(mm.getName())
                        && mm.getParameterCount() == 1 && mm.getParameterTypes()[0] == listCls) {
                    m = mm;
                    break;
                }
            }
            if (m == null) {
                com.better.heybox.Checkpoint.mark("发帖过滤列表层安装: 未找到 buildModels");
                return;
            }
            module.hook(m).intercept(chain -> {
                Object ctrl = chain.getThisObject();
                sFlowControllerRef = ctrl == null ? null : new java.lang.ref.WeakReference<>(ctrl);
                List<?> replacement = null;
                try {
                    replacement = filterFlowList(chain.getArg(0));
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "列表过滤异常，放行: " + t);
                }
                return replacement != null
                        ? chain.proceed(new Object[]{replacement})
                        : chain.proceed();
            });
            com.better.heybox.Checkpoint.mark("发帖过滤列表层 Hook 安装: ok");
        } catch (Throwable t) {
            com.better.heybox.Checkpoint.mark("发帖过滤列表层 Hook 安装失败: %s", String.valueOf(t));
            module.logd(Log.WARN, module.TAG, "✘ 发帖过滤列表层 Hook 失败: " + t);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<?> filterFlowList(Object listObj) {
        if (!(listObj instanceof List)) {
            return null;
        }
        List list = (List) listObj;
        boolean aiEnabled = module.isEnabled(App.KEY_POST_AI_ENABLED, false)
                && !module.getString(App.KEY_AI_BASE_URL, "").trim().isEmpty()
                && !module.getString(App.KEY_AI_MODEL, "").trim().isEmpty();
        java.util.Iterator<?> it = list.iterator();
        while (it.hasNext()) {
            Object item = it.next();
            if (item == null || !isPostFlowModel(item)) {
                continue;
            }
            Object link = safeInvoke(item, "getLinkContent");
            String title = link == null ? "" : safeGet(link, "getTitle");
            String desc = link == null ? "" : safeGet(link, "getDescription");
            if (videoBlocked(item)) {
                module.logd(Log.INFO, module.TAG, "屏蔽视频帖 (首页流列表) " + abbreviate(title));
                try {
                    it.remove();
                } catch (Throwable t) {
                    return filteredCopy(list, aiEnabled);
                }
                continue;
            }
            if (levelBlocked(item) || keywordBlockedText(title, desc)) {
                module.logd(Log.INFO, module.TAG, "发帖过滤命中 (首页流列表, "
                        + (levelBlocked(item) ? "lv" : "kw") + ") " + abbreviate(title));
                try {
                    it.remove();
                } catch (Throwable t) {
                    return filteredCopy(list, aiEnabled);
                }
                continue;
            }
            if (aiEnabled && !title.isEmpty()
                    && AIClickbaitChecker.getCached(title) == null) {
                AIClickbaitChecker.requestVerdicts(module, title, title, listAiCallback);
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<?> filteredCopy(List list, boolean aiEnabled) {
        List<Object> keep = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null || !isPostFlowModel(item)) {
                keep.add(item);
                continue;
            }
            Object link = safeInvoke(item, "getLinkContent");
            String title = link == null ? "" : safeGet(link, "getTitle");
            String desc = link == null ? "" : safeGet(link, "getDescription");
            if (videoBlocked(item) || levelBlocked(item) || keywordBlockedText(title, desc)) {
                continue;
            }
            if (aiEnabled && !title.isEmpty()
                    && AIClickbaitChecker.getCached(title) == null) {
                AIClickbaitChecker.requestVerdicts(module, title, title, aiCallback);
            }
            keep.add(item);
        }
        return keep;
    }

    public Object onDeserialized(Object result) {
        try {
            if (applySyncFilters(result)) {
                return emptyFeedObj(result);
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "数据层过滤异常，放行: " + t);
        }
        return null;
    }

    /** 空条目占位 */
    private Object emptyFeedObj(Object sample) {
        try {
            ClassLoader cl = sample != null ? sample.getClass().getClassLoader()
                    : getClass().getClassLoader();
            Class<?> base = Class.forName("com.max.xiaoheihe.bean.news.FeedsContentBaseObj", false, cl);
            Object empty = base.getDeclaredConstructor().newInstance();
            base.getMethod("setContent_type", String.class).invoke(empty, "0");
            try {
                base.getMethod("setShowDivider", boolean.class).invoke(empty, false);
            } catch (Throwable ignored) {
            }
            return empty;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "创建空 FeedsContentBaseObj 失败: " + t);
            return null;
        }
    }

    // ---------- 首页流数据层 ----------

    /** 首页流数据层过滤，丢弃条目返回 null */
    private void hookFeedsModelDeserializer(ClassLoader cl) {
        try {
            Class<?> d = Class.forName(
                    "com.max.data.deserializer.FeedsFlowItemModelDeserializer", false, cl);
            Class<?> jsonElement = Class.forName("com.google.gson.JsonElement", false, cl);
            Class<?> type = Class.forName("java.lang.reflect.Type", false, cl);
            Class<?> ctx = Class.forName("com.google.gson.JsonDeserializationContext", false, cl);
            int installed = 0;
            for (String name : new String[]{"a", "deserialize"}) {
                try {
                    Method m = d.getDeclaredMethod(name, jsonElement, type, ctx);
                    module.hook(m).intercept(chain -> filterFlowModel(chain));
                    installed++;
                } catch (NoSuchMethodException ignored) {
                }
            }
            com.better.heybox.Checkpoint.mark("发帖过滤流模型 Hook 安装: %d 处", installed);
        } catch (Throwable t) {
            com.better.heybox.Checkpoint.mark("发帖过滤流模型 Hook 安装失败: %s", String.valueOf(t));
            module.logd(Log.WARN, module.TAG, "✘ 流模型数据层 Hook 失败: " + t);
        }
    }

    private Object filterFlowModel(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
            if (result == null || !isPostFlowModel(result)) {
                return result;
            }
            Object link = safeInvoke(result, "getLinkContent");
            String title = link == null ? "" : safeGet(link, "getTitle");
            String desc = link == null ? "" : safeGet(link, "getDescription");
            if (videoBlocked(result)) {
                module.logd(Log.INFO, module.TAG, "屏蔽视频帖 (首页流) " + abbreviate(title));
                return null;
            }
            if (levelBlocked(result) || keywordBlockedText(title, desc)) {
                module.logd(Log.INFO, module.TAG, "发帖过滤命中 (首页流, "
                        + (levelBlocked(result) ? "lv" : "kw") + ") " + abbreviate(title));
                return null;
            }
            if (module.isEnabled(App.KEY_POST_AI_ENABLED, false) && !title.isEmpty()) {
                Boolean verdict = AIClickbaitChecker.getCached(title);
                if (verdict != null && verdict) {
                    module.logd(Log.INFO, module.TAG, "AI 判定标题党（缓存）: " + abbreviate(title));
                    return null;
                }
                if (verdict == null) {
                    AIClickbaitChecker.requestVerdicts(module, title, title, listAiCallback);
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "流模型过滤异常，放行: " + t);
        }
        return result;
    }

    /** 帖子模型判定缓存 */
    private final java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean> postModelCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 视频帖判定：模型 hasVideo() 查找结果缓存（命中 + 未命中分别记录，避免反复查找） */
    private final java.util.concurrent.ConcurrentHashMap<Class<?>, java.lang.reflect.Method> hasVideoCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Class<?>> hasVideoMiss =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>());

    private boolean isPostFlowModel(Object result) {
        Class<?> c = result.getClass();
        Boolean cached = postModelCache.get(c);
        if (cached != null) {
            return cached;
        }
        boolean isPost = false;
        Class<?> walk = c;
        while (walk != null && walk != Object.class) {
            if ("com.max.data.model.feeds.LinkFeedsFlowItemModel".equals(walk.getName())) {
                isPost = true;
                break;
            }
            walk = walk.getSuperclass();
        }
        postModelCache.put(c, isPost);
        return isPost;
    }

    // ---------- 视频帖过滤 ----------

    /** 视频帖开关开启时判定条目是否为视频帖 */
    private boolean videoBlocked(Object item) {
        if (item == null || !module.isEnabled(App.KEY_BLOCK_VIDEO_POST, false)) {
            return false;
        }
        return isPostFlowModel(item) ? isVideoModel(item) : isVideoLegacy(item);
    }

    /**
     * 首页流模型：宿主 LinkFeedsFlowItemModel.hasVideo() 即 getLinkStyle() == LinkStyle.VIDEO_LINK；
     * 方法改名时回退比较 LinkStyle 名字。
     */
    private boolean isVideoModel(Object model) {
        try {
            java.lang.reflect.Method m = hasVideoMethod(model.getClass());
            if (m != null) {
                Object v = m.invoke(model);
                return v instanceof Boolean && (Boolean) v;
            }
        } catch (Throwable ignored) {
        }
        Object style = safeInvoke(model, "getLinkStyle");
        return style != null && "VIDEO_LINK".equals(String.valueOf(style));
    }

    /** 旧链 BBSLinkObj：has_video 标志位 / video_url / video_info 任一命中即视为视频帖 */
    private boolean isVideoLegacy(Object item) {
        if (isTruthy(safeGet(item, "getHas_video"))) {
            return true;
        }
        if (!safeGet(item, "getVideo_url").isEmpty()) {
            return true;
        }
        return safeInvoke(item, "getVideo_info") != null;
    }

    private java.lang.reflect.Method hasVideoMethod(Class<?> c) {
        java.lang.reflect.Method cached = hasVideoCache.get(c);
        if (cached != null) {
            return cached;
        }
        if (hasVideoMiss.contains(c)) {
            return null;
        }
        java.lang.reflect.Method found = null;
        Class<?> walk = c;
        while (walk != null && walk != Object.class) {
            try {
                java.lang.reflect.Method m = walk.getDeclaredMethod("hasVideo");
                if (m.getParameterCount() == 0
                        && (m.getReturnType() == boolean.class
                        || m.getReturnType() == Boolean.class)) {
                    m.setAccessible(true);
                    found = m;
                    break;
                }
            } catch (Throwable ignored) {
            }
            walk = walk.getSuperclass();
        }
        if (found == null) {
            hasVideoMiss.add(c);
        } else {
            hasVideoCache.put(c, found);
        }
        return found;
    }

    /** "1" / "true" / "yes" 视为真（宿主 has_video 是布尔串） */
    private static boolean isTruthy(String s) {
        if (s == null) {
            return false;
        }
        String v = s.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }

    // ---------- 委托入口：PromotePostHook 渲染链 ----------

    /**
     * 旧渲染链（b.L/b.N）bind 时由 PromotePostHook 委托调用。
     */
    public boolean onRenderBind(Object bbsLink, Object viewHolder) {
        try {
            if (applySyncFilters(bbsLink)) {
                FeedItemHider.hide(FeedItemHider.getItemView(viewHolder));
                return true;
            }
            View view = FeedItemHider.getItemView(viewHolder);
            FeedItemHider.restore(view);
            aiCheck(bbsLink, postCacheKey(bbsLink), view);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "发帖过滤判断异常，放行: " + t);
        }
        return false;
    }

    // ---------- 瀑布流卡片 ----------

    private void hookWaterfallCard(ClassLoader cl) {
        try {
            Class<?> model = Class.forName(
                    "com.max.data.model.feeds.WaterfallLinkFeedsFlowItemModel", false, cl);
            int installed = 0;
            // 不同版本/登录态下瀑布流卡片在 V1/V2 间切换，两处都挂
            for (String name : new String[]{
                    "com.max.feature.feeds.view.itemview.WaterfallFeedsFlowItemViewV2",
                    "com.max.feature.feeds.view.itemview.WaterfallFeedsFlowItemView"}) {
                try {
                    Class<?> card = Class.forName(name, false, cl);
                    Method setData = card.getDeclaredMethod("setData", model);
                    module.hook(setData).intercept(chain -> onCardBind(chain));
                    installed++;
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "瀑布流卡片类不可用: " + name);
                }
            }
            com.better.heybox.Checkpoint.mark("发帖过滤瀑布流卡片安装: %d 处", installed);
        } catch (Throwable t) {
            com.better.heybox.Checkpoint.mark("发帖过滤瀑布流卡片安装异常: %s", String.valueOf(t));
            module.logd(Log.WARN, module.TAG, "✘ 发帖过滤首页卡片 Hook 失败: " + t);
        }
    }

    private Object onCardBind(XposedInterface.Chain chain) throws Throwable {
        View cardView = chain.getThisObject() instanceof View
                ? (View) chain.getThisObject() : null;
        if (cardView != null) {
            FeedItemHider.restore(cardView);
        }
        Object result = chain.proceed();
        try {
            if (cardView == null) {
                return result;
            }
            Object model = chain.getArg(0);
            if (model == null) {
                return result;
            }
            Object link = safeInvoke(model, "getLinkContent");
            String title = link == null ? "" : safeGet(link, "getTitle");
            String desc = link == null ? "" : safeGet(link, "getDescription");
            if (videoBlocked(model)) {
                module.logd(Log.INFO, module.TAG, "屏蔽视频帖 (首页卡片) " + abbreviate(title));
                FeedItemHider.hide(cardView);
                return result;
            }
            if (levelBlocked(model) || keywordBlockedText(title, desc)) {
                module.logd(Log.INFO, module.TAG, "发帖过滤命中 (首页卡片, "
                        + (levelBlocked(model) ? "lv" : "kw") + ") " + abbreviate(title));
                FeedItemHider.hide(cardView);
                return result;
            }
            if (!module.isEnabled(App.KEY_POST_AI_ENABLED, false) || title.isEmpty()) {
                return result;
            }
            Boolean verdict = AIClickbaitChecker.getCached(title);
            if (verdict != null) {
                if (verdict) {
                    module.logd(Log.INFO, module.TAG, "AI 判定标题党（缓存）: " + abbreviate(title));
                    FeedItemHider.hide(cardView);
                }
                return result;
            }
            boundPostKeys.put(cardView, title);
            AIClickbaitChecker.requestVerdicts(module, title, title, aiCallback);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "卡片过滤异常，放行: " + t);
        }
        return result;
    }

    // ---------- news.adapter.a 列表 ----------

    private void hookNewsListAdapter(ClassLoader cl) {
        try {
            Class<?> a = Class.forName("com.max.xiaoheihe.module.news.adapter.a", false, cl);
            Class<?> se = Class.forName("com.max.hbcommon.base.adapter.s$e", false, cl);
            Class<?> fcbo = Class.forName(
                    "com.max.xiaoheihe.bean.news.FeedsContentBaseObj", false, cl);
            Method y = a.getDeclaredMethod("y", se, fcbo);
            module.hook(y).intercept(chain -> {
                Object holder = chain.getArg(0);
                View itemView;
                try {
                    itemView = holder == null ? null
                            : (View) se.getField("itemView").get(holder);
                } catch (Throwable t) {
                    itemView = null;
                }
                if (itemView != null) {
                    FeedItemHider.restore(itemView);
                }
                Object data = chain.getArg(1);
                try {
                    if (applySyncFilters(data)) {
                        FeedItemHider.hide(itemView);
                        return null; // 跳过原绑定
                    }
                    aiCheck(data, postCacheKey(data), itemView);
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "列表过滤异常，放行: " + t);
                }
                return chain.proceed();
            });
            com.better.heybox.Checkpoint.mark("发帖过滤列表 Hook 安装: ok");
        } catch (Throwable t) {
            com.better.heybox.Checkpoint.mark("发帖过滤列表 Hook 安装失败: %s", String.valueOf(t));
            module.logd(Log.WARN, module.TAG, "✘ 发帖过滤列表 Hook 失败: " + t);
        }
    }

    // ---------- 同步过滤判定 ----------

    private boolean applySyncFilters(Object item) {
        if (videoBlocked(item)) {
            module.logd(Log.INFO, module.TAG, "屏蔽视频帖 (ct=" + getContentType(item) + ") "
                    + abbreviate(safeTitle(item)));
            return true;
        }
        if (levelBlocked(item)) {
            module.logd(Log.INFO, module.TAG, "屏蔽低等级发帖 (ct=" + getContentType(item) + ")");
            return true;
        }
        if (keywordBlocked(item)) {
            module.logd(Log.INFO, module.TAG, "关键词命中屏蔽 (ct=" + getContentType(item) + ")");
            return true;
        }
        return false;
    }

    /** AI 判定入口 */
    private void aiCheck(Object bbsLink, String cacheKey, View boundView) {
        if (!module.isEnabled(App.KEY_POST_AI_ENABLED, false) || cacheKey == null) {
            return;
        }
        String baseUrl = module.getString(App.KEY_AI_BASE_URL, "").trim();
        String model = module.getString(App.KEY_AI_MODEL, "").trim();
        if (baseUrl.isEmpty() || model.isEmpty()) {
            return;
        }
        Boolean verdict = AIClickbaitChecker.getCached(cacheKey);
        if (verdict != null) {
            if (verdict && boundView != null) {
                module.logd(Log.INFO, module.TAG, "AI 判定标题党（缓存）: " + abbreviate(cacheKey));
                FeedItemHider.hide(boundView);
            }
            return;
        }
        String title = safeTitle(bbsLink);
        if (title.isEmpty() || boundView == null) {
            return;
        }
        boundPostKeys.put(boundView, cacheKey);
        AIClickbaitChecker.requestVerdicts(module, cacheKey, title, aiCallback);
    }

    /** itemView 当前绑定的帖子键，异步判定回补用 */
    private View findBoundView(String cacheKey) {
        for (java.util.Map.Entry<View, String> e : boundPostKeys.entrySet()) {
            if (cacheKey.equals(e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }

    // ---------- 等级过滤 ----------

    private boolean levelBlocked(Object item) {
        int min = parseIntSafe(module.getString(App.KEY_POST_MIN_LEVEL, "0"));
        if (min <= 0) {
            return false;
        }
        Integer level = readUserLevel(item);
        if (level == null) {
            if (!module.isEnabled(App.KEY_POST_NO_LEVEL, false)) {
                return false;
            }
            if (isPostFlowModel(item)) {
                return true;
            }
            return safeInvoke(item, "getUser") != null;
        }
        return level < min;
    }

    private Integer readUserLevel(Object item) {
        try {
            Object user = item == null ? null
                    : item.getClass().getMethod("getUser").invoke(item);
            if (user == null) {
                return null;
            }
            Object info = user.getClass().getMethod("getLevel_info").invoke(user);
            if (info == null) {
                return null;
            }
            Object lv = info.getClass().getMethod("getLevel").invoke(info);
            if (lv == null) {
                return null;
            }
            return Integer.parseInt(String.valueOf(lv).trim());
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- 关键词过滤 ----------

    private boolean keywordBlocked(Object item) {
        if (item == null) {
            return false;
        }
        return keywordBlockedText(safeTitle(item), safeText(item));
    }

    /** 共用匹配核心 */
    private boolean keywordBlockedText(String title, String text) {
        List<Object> matchers = keywordMatchers();
        if (matchers.isEmpty()) {
            return false;
        }
        if ((title == null || title.isEmpty()) && (text == null || text.isEmpty())) {
            return false;
        }
        String titleLower = title == null ? "" : title.toLowerCase();
        String textLower = text == null ? "" : text.toLowerCase();
        for (Object m : matchers) {
            if (m instanceof Pattern) {
                if (((Pattern) m).matcher(titleLower).find()
                        || ((Pattern) m).matcher(textLower).find()) {
                    return true;
                }
            } else {
                String kw = (String) m;
                if (titleLower.contains(kw) || textLower.contains(kw)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 条目为小写子串或预编译正则 */
    private List<Object> keywordMatchers() {
        String raw = module.getString(App.KEY_POST_KEYWORDS, "");
        synchronized (keywordLock) {
            if (keywordMatchers != null && raw.equals(keywordRaw)) {
                return keywordMatchers;
            }
        }
        List<Object> list = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String kw = line.trim();
            if (kw.isEmpty()) {
                continue;
            }
            if (kw.startsWith("regex:")) {
                try {
                    list.add(Pattern.compile(kw.substring(6).trim(), Pattern.CASE_INSENSITIVE));
                    continue;
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "无效正则已忽略: " + kw);
                    continue;
                }
            }
            list.add(kw.toLowerCase());
        }
        synchronized (keywordLock) {
            keywordRaw = raw;
            keywordMatchers = list;
        }
        return list;
    }

    // ---------- 通用工具 ----------

    /** 标题优先，正文兜底 */
    private String postCacheKey(Object bbsLink) {
        String title = safeTitle(bbsLink);
        if (!title.isEmpty()) {
            return title;
        }
        String text = safeText(bbsLink);
        if (!text.isEmpty()) {
            return text.substring(0, Math.min(text.length(), 64));
        }
        return null;
    }

    private String safeTitle(Object item) {
        return safeGet(item, "getTitle");
    }

    private String safeText(Object item) {
        return safeGet(item, "getText");
    }

    private String safeGet(Object item, String getter) {
        try {
            if (item == null) {
                return "";
            }
            Object v = item.getClass().getMethod(getter).invoke(item);
            return v == null ? "" : String.valueOf(v).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private Object safeInvoke(Object item, String getter) {
        try {
            return item == null ? null : item.getClass().getMethod(getter).invoke(item);
        } catch (Throwable t) {
            return null;
        }
    }

    private String getContentType(Object item) {
        try {
            Object v = item == null ? null
                    : item.getClass().getMethod("getContent_type").invoke(item);
            return v == null ? "?" : String.valueOf(v);
        } catch (Throwable t) {
            return "?";
        }
    }

    private String abbreviate(String s) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        return s.length() <= 16 ? s : s.substring(0, 16);
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s == null ? "0" : s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }
}
