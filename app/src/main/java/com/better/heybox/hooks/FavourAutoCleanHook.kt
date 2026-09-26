package com.better.heybox.hooks

import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.better.heybox.App
import com.better.heybox.Checkpoint
import com.better.heybox.MainModule
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.WeakHashMap

class FavourAutoCleanHook(private val module: MainModule) {

    private val main = Handler(Looper.getMainLooper())

    private val lastTrigger: MutableMap<Any, Long> = WeakHashMap()

    @Volatile
    private var confirmSpec: ConfirmSpec? = null

    @Volatile
    private var confirmResolved = false

    fun install(cl: ClassLoader) {
        var installed = 0
        for (name in FRAGMENT_CLASSES) {
            if (hookFragment(cl, name)) {
                installed++
            }
        }
        Checkpoint.mark("自动清理失效收藏安装: %d 处", installed)
        if (installed == 0) {
            module.logd(
                Log.WARN, MainModule.TAG, "✘ 自动清理失效收藏未安装：收藏列表页均未命中"
            )
        }
    }


    private fun hookFragment(cl: ClassLoader, className: String): Boolean {
        try {
            val fragment: Class<*> = Class.forName(className, false, cl)
            var installed = 0
            val setter = findListSetter(fragment)
            if (setter != null && hookListMethod(setter, fragment, false)) {
                installed++
            }
            val wrapper = findStaticListSetter(fragment)
            if (wrapper != null && hookListMethod(wrapper, fragment, true)) {
                installed++
            }
            if (installed == 0) {
                Checkpoint.mark("自动清理失效收藏: %s 未找到列表装载方法", className)
                module.logd(Log.WARN, MainModule.TAG, "✘ 未找到列表装载方法: $className")
                return false
            }
            Checkpoint.mark("自动清理失效收藏: %s 安装 %d 处", className, installed)
            return true
        } catch (t: Throwable) {
            Checkpoint.mark("自动清理失效收藏: %s 安装失败 %s", className, stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 自动清理失效收藏 Hook 失败: $className", t)
            return false
        }
    }

    private fun hookListMethod(method: Method, fragment: Class<*>, staticWrapper: Boolean): Boolean {
        try {
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    val target = if (staticWrapper) chain.getArg(0) else chain.getThisObject()
                    val list = if (staticWrapper) chain.getArg(1) else chain.getArg(0)
                    onListLoaded(target, list, method.declaringClass.classLoader)
                } catch (t: Throwable) {
                    module.logd(Log.WARN, MainModule.TAG, "失效收藏检查异常，放行: $t")
                }
                result
            }
            module.logd(
                Log.INFO, MainModule.TAG, "✔ 自动清理失效收藏 Hook 已安装: " +
                        fragment.simpleName + "." + method.name +
                        (if (staticWrapper) "(fragment,List)" else "(List)")
            )
            return true
        } catch (t: Throwable) {
            module.logd(
                Log.WARN, MainModule.TAG, "✘ 列表装载 Hook 失败: " +
                        fragment.simpleName + "." + method.name, t
            )
            return false
        }
    }

    private fun findStaticListSetter(fragment: Class<*>): Method? {
        var found: Method? = null
        for (m in fragment.declaredMethods) {
            val ps = m.parameterTypes
            if (ps.size != 2 || ps[0] !== fragment || ps[1] !== List::class.java
                || m.returnType !== Void.TYPE
                || !Modifier.isStatic(m.modifiers)
            ) {
                continue
            }
            if (found != null) {
                return null
            }
            found = m
        }
        return found
    }

    private fun findListSetter(fragment: Class<*>): Method? {
        var found: Method? = null
        for (m in fragment.declaredMethods) {
            val ps = m.parameterTypes
            if (ps.size != 1 || ps[0] !== List::class.java || m.returnType !== Void.TYPE
                || Modifier.isStatic(m.modifiers)
            ) {
                continue
            }
            if (found != null) {
                module.logd(
                    Log.WARN, MainModule.TAG, "列表装载方法不唯一，放弃该挂点: " + fragment.name
                )
                return null
            }
            found = m
        }
        return found
    }

    private fun onListLoaded(fragment: Any?, listArg: Any?, cl: ClassLoader?) {
        if (fragment == null || !module.isEnabled(App.KEY_FAVOUR_AUTO_CLEAN, false)) {
            return
        }
        val invalid = countInvalid(listArg)
        if (invalid <= 0) {
            return
        }
        if (!allowTrigger(fragment)) {
            return
        }
        module.logd(Log.INFO, MainModule.TAG, "收藏列表含 $invalid 条失效内容，自动清理")
        triggerClean(fragment, cl)
    }

    private fun countInvalid(listArg: Any?): Int {
        if (listArg !is List<*>) {
            return 0
        }
        var count = 0
        for (item in listArg) {
            if ("1" == safeGet(item, "getIs_deleted")) {
                count++
            }
        }
        return count
    }

    private fun allowTrigger(fragment: Any): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastTrigger) {
            val last = lastTrigger[fragment]
            if (last != null && now - last < MIN_INTERVAL_MS) {
                return false
            }
            lastTrigger[fragment] = now
        }
        return true
    }


    private fun triggerClean(fragment: Any, cl: ClassLoader?) {
        try {
            val spec = resolveConfirmSpec(cl)
            if (spec == null) {
                module.logd(
                    Log.WARN, MainModule.TAG, "✘ 未定位到宿主清理确认监听器，跳过自动清理"
                )
                return
            }
            val disposable = newInstance(spec.disposableType)
            if (disposable == null) {
                module.logd(
                    Log.WARN, MainModule.TAG, "✘ 无法创建 CompositeDisposable，跳过自动清理"
                )
                return
            }
            val folderId = folderIdOf(fragment)
            val ref = WeakReference(fragment)
            val isActive = newProxy(
                cl, spec.activeType, InvocationHandler { _, method, _ ->
                    defaultValue(method.returnType)
                }
            )
            val onFinish = newProxy(
                cl, spec.finishType, InvocationHandler { _, method, _ ->
                    if (method.declaringClass !== Any::class.java) {
                        refreshLater(ref)
                    }
                    defaultValue(method.returnType)
                }
            )
            if (isActive == null || onFinish == null) {
                module.logd(Log.WARN, MainModule.TAG, "✘ 无法创建宿主回调代理，跳过自动清理")
                return
            }
            val listener: Any? = spec.ctor.newInstance(folderId, disposable, isActive, onFinish)
            val dialog: Any? = Proxy.newProxyInstance(
                cl, arrayOf<Class<*>>(DialogInterface::class.java),
                InvocationHandler { _, method, _ -> defaultValue(method.returnType) }
            )
            spec.onClick.invoke(listener, dialog, -1)
            module.logd(
                Log.INFO, MainModule.TAG,
                "✔ 已触发宿主清理失效内容请求 (folder_id=" +
                        (if (folderId == null) "null" else folderId) + ")"
            )
        } catch (t: Throwable) {
            Checkpoint.mark("自动清理失效收藏触发失败: %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "✘ 自动清理失效收藏触发失败: $t")
        }
    }

    private fun refreshLater(ref: WeakReference<Any>) {
        main.post {
            val fragment = ref.get() ?: return@post
            try {
                val added = fragment.javaClass.getMethod("isAdded").invoke(fragment)
                if (added is Boolean && !added) {
                    return@post
                }
                fragment.javaClass.getMethod("onRefresh").invoke(fragment)
                module.logd(Log.INFO, MainModule.TAG, "自动清理完成，已刷新收藏列表")
            } catch (t: Throwable) {
                module.logd(Log.WARN, MainModule.TAG, "清理后刷新收藏列表失败: $t")
            }
        }
    }

    private fun folderIdOf(fragment: Any): String? {
        try {
            val args = fragment.javaClass.getMethod("getArguments").invoke(fragment)
            if (args is Bundle) {
                return args.getString(ARG_FOLDER_ID)
            }
        } catch (ignored: Throwable) {
        }
        return null
    }


    private class ConfirmSpec(
        val ctor: Constructor<*>,
        val onClick: Method,
        val disposableType: Class<*>,
        val activeType: Class<*>,
        val finishType: Class<*>
    )

    private fun resolveConfirmSpec(cl: ClassLoader?): ConfirmSpec? {
        if (confirmResolved) {
            return confirmSpec
        }
        synchronized(this) {
            if (confirmResolved) {
                return confirmSpec
            }
            confirmSpec = findConfirmSpec(cl)
            confirmResolved = true
            return confirmSpec
        }
    }

    private fun findConfirmSpec(cl: ClassLoader?): ConfirmSpec? {
        try {
            val companion: Class<*> = Class.forName(COMPANION_CLASS, false, cl)
            val onClickIface: Class<*> = DialogInterface.OnClickListener::class.java
            for (inner in companion.declaredClasses) {
                if (inner.isInterface || !onClickIface.isAssignableFrom(inner)) {
                    continue
                }
                val onClick = findOnClickMethod(inner)
                if (onClick == null) {
                    continue
                }
                for (ctor in inner.declaredConstructors) {
                    val ps = ctor.parameterTypes
                    if (ps.size != 4 || ps[0] !== String::class.java
                        || !ps[2].isInterface || !ps[3].isInterface
                    ) {
                        continue
                    }
                    try {
                        ctor.setAccessible(true)
                    } catch (t: Throwable) {
                        continue
                    }
                    module.logd(
                        Log.INFO, MainModule.TAG, "已定位宿主清理确认监听器: " + inner.name
                    )
                    return ConfirmSpec(ctor, onClick, ps[1], ps[2], ps[3])
                }
            }
        } catch (t: Throwable) {
            Checkpoint.mark("自动清理失效收藏: 确认监听器解析失败 %s", stringify(t))
            module.logd(Log.WARN, MainModule.TAG, "确认监听器解析失败: $t")
        }
        return null
    }


    private fun stringify(value: Any?): String = if (value == null) "null" else value.toString()

    private fun findOnClickMethod(inner: Class<*>): Method? {
        return try {
            inner.getMethod("onClick", DialogInterface::class.java, Integer.TYPE)
        } catch (t: Throwable) {
            null
        }
    }

    private fun newInstance(type: Class<*>): Any? {
        return try {
            val ctor = type.getDeclaredConstructor()
            ctor.setAccessible(true)
            ctor.newInstance()
        } catch (t: Throwable) {
            null
        }
    }

    private fun newProxy(cl: ClassLoader?, iface: Class<*>, handler: InvocationHandler): Any? {
        return try {
            Proxy.newProxyInstance(cl, arrayOf<Class<*>>(iface), handler)
        } catch (t: Throwable) {
            null
        }
    }

    private fun defaultValue(returnType: Class<*>): Any? {
        if (returnType === Boolean::class.javaPrimitiveType ||
            returnType === Boolean::class.javaObjectType
        ) {
            return true
        }
        if (returnType === Void.TYPE) {
            return null
        }
        if (returnType === Int::class.javaPrimitiveType || returnType === Int::class.javaObjectType) {
            return 0
        }
        if (returnType === Long::class.javaPrimitiveType || returnType === Long::class.javaObjectType) {
            return 0L
        }
        if (returnType === Float::class.javaPrimitiveType ||
            returnType === Float::class.javaObjectType
        ) {
            return 0f
        }
        if (returnType === Double::class.javaPrimitiveType ||
            returnType === Double::class.javaObjectType
        ) {
            return 0.0
        }
        if (returnType === Short::class.javaPrimitiveType ||
            returnType === Short::class.javaObjectType
        ) {
            return 0.toShort()
        }
        if (returnType === Byte::class.javaPrimitiveType ||
            returnType === Byte::class.javaObjectType
        ) {
            return 0.toByte()
        }
        if (returnType === Char::class.javaPrimitiveType ||
            returnType === Char::class.javaObjectType
        ) {
            return 0.toChar()
        }
        return null
    }

    private fun safeGet(item: Any?, getter: String): String {
        return try {
            if (item == null) {
                ""
            } else {
                val v = item.javaClass.getMethod(getter).invoke(item)
                if (v == null) "" else stringify(v).trim()
            }
        } catch (t: Throwable) {
            ""
        }
    }

    companion object {

        private val FRAGMENT_CLASSES = arrayOf(
            "com.max.xiaoheihe.module.favour.FavourCollectionContentFragment",
            "com.max.xiaoheihe.module.favour.FavourLinkFolderFragment"
        )

        private const val COMPANION_CLASS =
            "com.max.xiaoheihe.module.bbs.utils.BBSKtUtils\$Companion"

        private const val ARG_FOLDER_ID = "folder_id"

        private const val MIN_INTERVAL_MS = 30_000L
    }
}
