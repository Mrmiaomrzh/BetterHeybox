package com.better.heybox.util;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class HostFileProvider {

    private static final String PROVIDER_CLASS = "androidx.core.content.FileProvider";
    private static final String AUTHORITY_SUFFIX = ".fileprovider";

    private static volatile Method cachedMethod;

    private HostFileProvider() {
    }

    public static String authority(Context context) {
        if (context == null) {
            return null;
        }
        try {
            String pkg = context.getPackageName();
            return pkg == null || pkg.isEmpty() ? null : pkg + AUTHORITY_SUFFIX;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Uri getUriForFile(Context context, File file) {
        if (context == null || file == null) {
            return null;
        }
        String authority = authority(context);
        if (authority == null) {
            return null;
        }
        try {
            Method method = resolve(context.getClassLoader());
            if (method == null) {
                return null;
            }
            Object uri = method.invoke(null, context, authority, file);
            return uri instanceof Uri ? (Uri) uri : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method resolve(ClassLoader loader) {
        Method cached = cachedMethod;
        if (cached != null) {
            return cached;
        }
        synchronized (HostFileProvider.class) {
            if (cachedMethod != null) {
                return cachedMethod;
            }
            Method found;
            try {
                Class<?> provider = Class.forName(PROVIDER_CLASS, false, loader);
                found = findByName(provider, "getUriForFile");
                if (found == null) {
                    found = findBySignature(provider);
                }
            } catch (Throwable ignored) {
                return null;
            }
            cachedMethod = found;
            return found;
        }
    }

    private static Method findByName(Class<?> provider, String name) {
        try {
            Method method = provider.getDeclaredMethod(
                    name, Context.class, String.class, File.class);
            return matches(method) ? method : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findBySignature(Class<?> provider) {
        Method[] methods;
        try {
            methods = provider.getDeclaredMethods();
        } catch (Throwable ignored) {
            return null;
        }
        for (Method method : methods) {
            if (!matches(method)) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 3
                    && Context.class.equals(params[0])
                    && String.class.equals(params[1])
                    && File.class.equals(params[2])) {
                return method;
            }
        }
        return null;
    }

    private static boolean matches(Method method) {
        return Modifier.isStatic(method.getModifiers())
                && Modifier.isPublic(method.getModifiers())
                && Uri.class.equals(method.getReturnType());
    }
}
