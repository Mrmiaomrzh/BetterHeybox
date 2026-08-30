package com.better.heybox;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ViewUtils {

    private ViewUtils() {
    }

    public static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public static Activity findActivity(View view) {
        return findActivity(view != null ? view.getContext() : null);
    }

    public static ViewGroup findDecor(View view) {
        Activity activity = findActivity(view);
        if (activity != null) {
            View decor = activity.getWindow().getDecorView();
            if (decor instanceof ViewGroup) {
                return (ViewGroup) decor;
            }
        }
        return null;
    }

    public static Object findOuter(Object innerObj, Class<?> outerType) {
        for (Field f : innerObj.getClass().getDeclaredFields()) {
            if (f.getType() == outerType) {
                f.setAccessible(true);
                try {
                    return f.get(innerObj);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    public static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (name.equals(m.getName())
                    && java.util.Arrays.equals(m.getParameterTypes(), params)) {
                return m;
            }
        }
        return null;
    }
}
