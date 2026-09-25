package com.better.heybox;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

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

    public static boolean prefersHbmod(MainModule module) {
        try {
            return PROVIDER_HBMOD.equals(module.getString(App.KEY_GLASS_PROVIDER, ""));
        } catch (Throwable t) {
            return false;
        }
    }

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
