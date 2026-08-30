package com.better.heybox.hooks;

import android.net.Uri;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.better.heybox.App;
import com.better.heybox.MainModule;

/**
 * 净化分享链接：去掉 sid、share_app_id 等追踪参数。
 * 拦截点：所有分享出口统一经 model 的 getShareUrl() 取链接，Hook 各 model getter 全覆盖（CopyAction.getUrl / HBShareProtocolData.getShare_url 兜底）
 */
public final class ShareLinkPurifyHook {

    private final MainModule module;

    /** 分享出口 model：Kotlin 属性 getter 名字稳定，类按存在性逐个尝试（393/394 通用） */
    private static final String[] TARGET_CLASSES = {
            "com.max.data.model.community.LinkPostForwardModel",
            "com.max.data.model.community.CommentForwardModel",
            "com.max.data.model.community.GameCommentForwardModel",
            "com.max.data.model.community.PostShareContentModel",
            "com.max.data.model.game.GameDetailModel",
            "com.max.data.model.game.GameDetailScreenShotModel",
            "com.max.data.model.game.GameReviewShareContentModel",
            "com.max.data.model.common.CommonContentModel",
            "com.max.data.model.share.ArticleModel",
            "com.max.data.model.share.MeModel",
            "com.max.data.model.share.WebViewModel",
            "com.max.data.bean.community.LinkShareInfoDto",
            // 兜底：复制链接动作与协议分享数据
            "com.max.data.model.share.IAction$CopyAction",
            "com.max.hbshare.bean.HBShareProtocolData",
    };

    /** 小黑盒域名后缀（仅净化自家链接，第三方链接原样放行） */
    private static final String HEYBOX_HOST_SUFFIX = "xiaoheihe.cn";

    /** 追踪参数黑名单（匹配 query 参数名，小写比较） */
    private static final List<String> BLACKLIST = Arrays.asList(
            // 小黑盒实测分享链接携带的 h_* 追踪参数
            "h_camp",                 // 分享活动归因
            "h_session_id",           // 分享会话标识
            "new_post_share_style",   // 分享样式 A/B 实验标记
            "h_src",                  // base64 信息流曝光轨迹
            // 其他常见追踪参数
            "sid",                    // 分享会话/来源标识
            "share_app_id",           // 分享渠道
            "share_strategys",        // 分享策略
            "share_xy_from", "sh_from", "share_from", "share_channel", "share_xy",
            "web_sign",               // 签名（link_id 短链不需要）
            "identify",               // 设备/用户识别
            "heybox_id", "user_id", "userid",
            "did", "device_id",
            "from", "spm", "traceid", "request_id",
            "gclid", "fbclid"
    );

    public ShareLinkPurifyHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        int installed = 0;
        StringBuilder names = new StringBuilder();
        for (String className : TARGET_CLASSES) {
            Class<?> clazz;
            try {
                clazz = Class.forName(className, false, cl);
            } catch (Throwable t) {
                continue;
            }
            boolean hooked = false;
            // getShareUrl：分享 model；getShare_url：HBShareProtocolData；getUrl：CopyAction
            for (String methodName : new String[]{"getShareUrl", "getShare_url", "getUrl"}) {
                try {
                    Method method = clazz.getDeclaredMethod(methodName);
                    if (method.getReturnType() != String.class) {
                        continue;
                    }
                    module.hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if (module.isEnabled(App.KEY_PURIFY_SHARE_LINK, true)
                                && result instanceof String) {
                            return purify((String) result);
                        }
                        return result;
                    });
                    hooked = true;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (hooked) {
                installed++;
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(className.substring(className.lastIndexOf('.') + 1));
            }
        }
        if (installed > 0) {
            module.logd(Log.INFO, module.TAG,
                    "✔ 分享链接净化 Hook 已安装: " + installed + " 个出口 [" + names + "]");
        } else {
            module.logd(Log.WARN, module.TAG, "✘ 分享链接净化 Hook 未命中任何分享出口");
        }
    }

    /**
 * 去掉追踪参数；仅当确实删除参数时重建 URL（避免重新编码破坏未识别参数）
 */
    String purify(String url) {
        if (url == null) {
            return url;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return url;
        }
        if (!isHeyboxHost(url)) {
            return url;
        }
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return url;
        }
        int fragmentStart = url.indexOf('#', queryStart);
        String query = fragmentStart < 0
                ? url.substring(queryStart + 1)
                : url.substring(queryStart + 1, fragmentStart);
        if (query.isEmpty()) {
            return url;
        }

        StringBuilder kept = new StringBuilder();
        List<String> removed = new ArrayList<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String normalized = Uri.decode(name).toLowerCase();
            if (isTrackingParam(normalized)) {
                removed.add(normalized);
            } else {
                if (kept.length() > 0) {
                    kept.append('&');
                }
                kept.append(pair);
            }
        }
        if (removed.isEmpty()) {
            return url;
        }

        StringBuilder out = new StringBuilder();
        if (kept.length() > 0) {
            out.append(url, 0, queryStart + 1).append(kept);
        } else {
            // 参数全部被剔除时丢弃悬空的 '?'
            out.append(url, 0, queryStart);
        }
        if (fragmentStart >= 0) {
            out.append(url, fragmentStart, url.length());
        }
        module.logd(Log.INFO, module.TAG,
                "净化分享链接: 已去除 " + removed + " ← " + url);
        return out.toString();
    }

    private boolean isTrackingParam(String name) {
        return name.startsWith("utm_") || BLACKLIST.contains(name);
    }

    private boolean isHeyboxHost(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            return host.equals(HEYBOX_HOST_SUFFIX) || host.endsWith("." + HEYBOX_HOST_SUFFIX);
        } catch (Throwable t) {
            return false;
        }
    }
}
