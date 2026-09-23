package com.better.heybox.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.better.heybox.HeyboxTargets;

public final class PromoteDetector {

    public static final String TARGET_BBS_RENDER = "bbs.render";
    public static final String TARGET_BBS_PRED_PROMOTE = "bbs.pred.promote";
    public static final String TARGET_BBS_PRED_AD = "bbs.pred.ad";
    public static final String TARGET_ADS_SPLASH = "ads.splash";
    public static final String TARGET_ADS_BUBBLE = "ads.bubble";
    public static final String TARGET_ADS_CORNER = "ads.corner";
    public static final String TARGET_FEEDS_BIND = "feeds.list.bind";
    /** List data target. */
    public static final String TARGET_BBS_LINKS_GETTER = "bbs.links.getter";
    /** List bind target, one per class. */
    public static final String TARGET_BBS_LIST_BIND = "bbs.list.bind";

    public static final String BBS_LINK_OBJ = "com.max.xiaoheihe.bean.bbs.BBSLinkObj";
    public static final String FEEDS_BASE_OBJ = "com.max.xiaoheihe.bean.news.FeedsContentBaseObj";

    private static final String AD_FLOW_MODEL = "com.max.data.model.feeds.AdFeedsFlowItemModel";
    private static final String CONTENT_TYPE_PREFIX = "CONTENT_TYPE_";

    private static final Set<String> PROMOTE_USERS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("\u5c0f\u9ed1\u76d2\u63a8\u5e7f",
                    "\u5546\u57ce\u770b\u677f\u5a18")));

    private static final Set<String> FALLBACK_CONTENT_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("23", "26", "27", "28", "29")));

    private static final ConcurrentHashMap<String, Method> GETTERS = new ConcurrentHashMap<>();
    private static final Set<String> GETTER_MISS = ConcurrentHashMap.newKeySet();

    private static volatile Set<String> sContentTypes;
    private static volatile String sContentTypeSource = "\u5185\u7f6e\u8868";
    private static volatile String sContentTypeDetail = "23,26,27,28,29";

    private PromoteDetector() {
    }

    public static boolean isPromote(Object item) {
        return matchReason(item) != null;
    }

    public static String matchReason(Object item) {
        if (item == null) {
            return null;
        }
        String reportOwner = adReportOwner(item);
        if (reportOwner != null) {
            return reportOwner + " \u975e\u7a7a\uff08\u6295\u653e\u4e0a\u62a5\u6570\u636e\uff09";
        }
        if (isInstanceNamed(item.getClass(), AD_FLOW_MODEL)) {
            return "\u9996\u9875\u6d41\u5e7f\u544a\u5361 AdFeedsFlowItemModel";
        }
        String type = contentType(item);
        if (type != null) {
            if (contentTypes().contains(type)) {
                return "content_type=" + type + " \u5c5e\u4e8e\u5bbf\u4e3b\u5e7f\u544a\u5e38\u91cf\u8868";
            }
            if (HeyboxTargets.invokeBoolean(TARGET_BBS_PRED_PROMOTE, type)) {
                return "content_type=" + type + " \u547d\u4e2d\u5bbf\u4e3b\u5224\u636e w()\uff08\u793e\u533a\u63a8\u5e7f\uff09";
            }
            if (HeyboxTargets.invokeBoolean(TARGET_BBS_PRED_AD, type)) {
                return "content_type=" + type + " \u547d\u4e2d\u5bbf\u4e3b\u5224\u636e z()\uff08\u5e7f\u544a\u4f4d\uff09";
            }
        }
        Object label = readGetter(item, "getLabel");
        if (label != null && "advertise".equals(String.valueOf(label))) {
            return "label=advertise";
        }
        String name = username(item);
        if (name != null && PROMOTE_USERS.contains(name)) {
            return "\u63a8\u5e7f\u53f7\u4f5c\u8005 " + name;
        }
        return null;
    }

    public static boolean hasAdReport(Object item) {
        return adReportOwner(item) != null;
    }

    private static String adReportOwner(Object item) {
        if (item == null) {
            return null;
        }
        if (readGetter(item, "getAdReport") != null) {
            return "adReport";
        }
        if (readGetter(item, "getAd_report") != null) {
            return "ad_report";
        }
        return null;
    }

    public static String contentType(Object item) {
        Object value = readGetter(item, "getContent_type");
        if (value == null) {
            value = readGetter(item, "getContentType");
        }
        return value == null ? null : String.valueOf(value);
    }

    public static String username(Object item) {
        Object user = readGetter(item, "getUser");
        if (user == null) {
            return null;
        }
        Object name = readGetter(user, "getUsername");
        return name == null ? null : String.valueOf(name);
    }

    public static String author(Object item) {
        String name = username(item);
        if (name != null) {
            return name;
        }
        Object author = readGetter(item, "getAuthor");
        if (author == null) {
            return null;
        }
        Object nested = readGetter(author, "getUsername");
        if (nested == null) {
            nested = readGetter(author, "getNickname");
        }
        return nested == null ? null : String.valueOf(nested);
    }

    public static String level(Object item) {
        Object user = readGetter(item, "getUser");
        if (user == null) {
            return null;
        }
        Object info = readGetter(user, "getLevel_info");
        if (info == null) {
            return null;
        }
        Object value = readGetter(info, "getLevel");
        return value == null ? null : String.valueOf(value);
    }

    public static Integer likeCount(Object item) {
        Integer value = readInt(item, "getLinkAwardNum", "getLink_award_num");
        return value != null ? value : readInt(preloadStats(item), "getLikeCnt");
    }

    public static Integer commentCount(Object item) {
        Integer value = readInt(item, "getCommentNum", "getComment_num");
        return value != null ? value : readInt(preloadStats(item), "getCommentCnt");
    }

    public static Integer favourCount(Object item) {
        return readInt(preloadStats(item), "getSaveCnt");
    }

    private static Object preloadStats(Object item) {
        Object preload = readGetter(item, "getCommunityPostPreload");
        return preload == null ? null : readGetter(preload, "getInteractStats");
    }

    private static Integer readInt(Object target, String... getters) {
        if (target == null) {
            return null;
        }
        for (String name : getters) {
            Object value = readGetter(target, name);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (text.isEmpty()) {
                    continue;
                }
                try {
                    return Integer.parseInt(text);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    public static String title(Object item) {
        Object value = readGetter(item, "getTitle");
        if (value != null && !String.valueOf(value).isEmpty()) {
            return String.valueOf(value);
        }
        Object link = readGetter(item, "getLinkContent");
        if (link != null) {
            Object nested = readGetter(link, "getTitle");
            if (nested != null) {
                return String.valueOf(nested);
            }
        }
        Object desc = readGetter(item, "getDescription");
        return desc == null ? null : String.valueOf(desc);
    }

    public static boolean isPromoteUser(String name) {
        return name != null && PROMOTE_USERS.contains(name);
    }

    public static String describe(Object item) {
        if (item == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("model=").append(item.getClass().getSimpleName());
        sb.append(", ct=").append(contentType(item));
        sb.append(", adReport=").append(hasAdReport(item));
        sb.append(", label=").append(readGetter(item, "getLabel"));
        String author = author(item);
        sb.append(", \u4f5c\u8005=").append(author == null ? "?" : author);
        String level = level(item);
        if (level != null) {
            sb.append(", \u7b49\u7ea7=").append(level);
        }
        appendCount(sb, "\u8d5e", likeCount(item));
        appendCount(sb, "\u8bc4", commentCount(item));
        appendCount(sb, "\u85cf", favourCount(item));
        sb.append(", \u6807\u9898=").append(abbreviate(title(item)));
        String reason = matchReason(item);
        sb.append(", \u547d\u4e2d=").append(reason == null ? "\u5426" : reason);
        return sb.toString();
    }

    private static void appendCount(StringBuilder sb, String label, Integer value) {
        if (value != null) {
            sb.append(", ").append(label).append('=').append(value);
        }
    }

    public static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= 48 ? flat : flat.substring(0, 48) + "...";
    }

    public static Set<String> contentTypes() {
        Set<String> cached = sContentTypes;
        if (cached != null) {
            return cached;
        }
        synchronized (PromoteDetector.class) {
            if (sContentTypes != null) {
                return sContentTypes;
            }
            Set<String> built = new HashSet<>();
            StringBuilder detail = new StringBuilder();
            ClassLoader cl = HeyboxTargets.hostClassLoader();
            if (cl != null) {
                try {
                    Class<?> cls = Class.forName(BBS_LINK_OBJ, false, cl);
                    for (Field field : cls.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers())
                                || field.getType() != String.class) {
                            continue;
                        }
                        String name = field.getName();
                        if (!name.startsWith(CONTENT_TYPE_PREFIX)) {
                            continue;
                        }
                        String tail = name.substring(CONTENT_TYPE_PREFIX.length());
                        if (!isAdTypeName(tail)) {
                            continue;
                        }
                        try {
                            field.setAccessible(true);
                        } catch (Throwable ignored) {
                        }
                        Object value = field.get(null);
                        if (value instanceof String && !((String) value).isEmpty()) {
                            built.add((String) value);
                            detail.append(tail).append('=').append((String) value).append(' ');
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (built.isEmpty()) {
                built.addAll(FALLBACK_CONTENT_TYPES);
                sContentTypeSource = "\u5185\u7f6e\u8868";
                sContentTypeDetail = "23,26,27,28,29";
            } else {
                sContentTypeSource = "\u5bbf\u4e3b\u5e38\u91cf\u8868";
                sContentTypeDetail = detail.toString().trim();
            }
            sContentTypes = Collections.unmodifiableSet(built);
            return sContentTypes;
        }
    }

    public static String contentTypesInfo() {
        contentTypes();
        return sContentTypeSource + " " + sContentTypeDetail;
    }

    private static boolean isAdTypeName(String tail) {
        return tail.equals("AD")
                || tail.startsWith("AD_")
                || tail.endsWith("_AD")
                || tail.contains("_AD_");
    }

    private static boolean isInstanceNamed(Class<?> cls, String name) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            if (name.equals(walk.getName())) {
                return true;
            }
            walk = walk.getSuperclass();
        }
        return false;
    }

    private static Object readGetter(Object item, String name) {
        if (item == null) {
            return null;
        }
        Method method = findGetter(item.getClass(), name);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(item);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findGetter(Class<?> cls, String name) {
        String key = cls.getName() + "#" + name;
        Method hit = GETTERS.get(key);
        if (hit != null) {
            return hit;
        }
        if (GETTER_MISS.contains(key)) {
            return null;
        }
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method method = walk.getDeclaredMethod(name);
                if (method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    GETTERS.put(key, method);
                    return method;
                }
            } catch (Throwable ignored) {
            }
            walk = walk.getSuperclass();
        }
        GETTER_MISS.add(key);
        return null;
    }
}
