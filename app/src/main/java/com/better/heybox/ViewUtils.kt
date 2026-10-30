package com.better.heybox

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field
import java.lang.reflect.Method

object ViewUtils {

    @JvmStatic
    fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                return current
            }
            current = current.baseContext
        }
        return null
    }

    @JvmStatic
    fun findActivity(view: View?): Activity? = findActivity(view?.context)

    @JvmStatic
    fun findDecor(view: View?): ViewGroup? {
        val activity = findActivity(view) ?: return null
        val decor = activity.window.decorView
        return decor as? ViewGroup
    }

    @JvmStatic
    fun findOuter(innerObj: Any?, outerType: Class<*>?): Any? {
        if (innerObj == null) {
            return null
        }
        for (f in innerObj.javaClass.declaredFields) {
            if (f.type == outerType) {
                f.isAccessible = true
                try {
                    return f.get(innerObj)
                } catch (ignored: Throwable) {
                }
            }
        }
        return null
    }

    @JvmStatic
    fun findMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        for (m in clazz.declaredMethods) {
            if (name == m.name && java.util.Arrays.equals(m.parameterTypes, params)) {
                return m
            }
        }
        return null
    }
}
