package com.better.heybox;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * 液态玻璃实现仲裁：与独立的「小黑盒液态玻璃模块」（com.hbmod.liquidglass）共存时，
 * 由用户选择玻璃效果由谁提供，避免两条玻璃底栏与两份长按入口叠加。
 * LSPosed 无跨模块激活查询，以包安装状态为检测依据（宿主已声明 QUERY_ALL_PACKAGES）。
 */
public final class GlassProvider {

    public static final String HBMOD_PACKAGE = "com.hbmod.liquidglass";
    public static final String PROVIDER_OWN = "betterheybox";
    public static final String PROVIDER_HBMOD = "hbmod";

    private GlassProvider() {
    }

    public static boolean isHbmodInstalled(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(HBMOD_PACKAGE, 0);
            return info != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 仅按配置判断：Hook 安装期无 Context，用它决定是否安装自带玻璃；未选择或选自带时为 false */
    public static boolean prefersHbmod(MainModule module) {
        try {
            return PROVIDER_HBMOD.equals(module.getString(App.KEY_GLASS_PROVIDER, ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 带 Context 的判断：读宿主本地配置（选择结果的真实存放处），运行时挂载点用这份 */
    public static boolean prefersHbmod(Context context) {
        try {
            HeyboxPrefs.init(context);
            return PROVIDER_HBMOD.equals(HeyboxPrefs.getString(App.KEY_GLASS_PROVIDER, ""));
        } catch (Throwable t) {
            return false;
        }
    }

    public static String providerLabel(String provider) {
        return PROVIDER_HBMOD.equals(provider) ? "小黑盒液态玻璃模块" : "BetterHeybox";
    }
}
