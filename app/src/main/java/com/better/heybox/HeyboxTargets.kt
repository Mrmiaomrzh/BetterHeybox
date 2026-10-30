package com.better.heybox

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

import com.better.heybox.hooks.FakeUsageAccessHook
import com.better.heybox.hooks.GameLibraryCleanHook
import com.better.heybox.hooks.PromoteDetector


import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HeyboxTargets private constructor() {

    fun interface Validator {
        fun accept(method: Method): Boolean
    }

    fun interface MethodHook {
        fun hook(method: Method)
    }


    fun interface HitProbe {
        fun attach(method: Method, onHit: Runnable)
    }

    class Target internal constructor(
        key: String,
        classes: Array<String>?,
        methods: Array<String>?,
        classAnchors: Array<String>?,
        methodAnchors: Array<String>?,
        minParams: Int,
        maxParams: Int,
        validator: Validator?
    ) {
        @JvmField
        val key: String

        @JvmField
        val classes: Array<String>

        @JvmField
        val methods: Array<String>

        @JvmField
        val classAnchors: Array<String>

        @JvmField
        val methodAnchors: Array<String>

        @JvmField
        val minParams: Int

        @JvmField
        val maxParams: Int

        @JvmField
        val validator: Validator?

        init {
            this.key = key
            this.classes = if (classes == null) emptyArray() else classes
            this.methods = if (methods == null) emptyArray() else methods
            this.classAnchors = if (classAnchors == null) emptyArray() else classAnchors
            this.methodAnchors = if (methodAnchors == null) emptyArray() else methodAnchors
            this.minParams = minParams
            this.maxParams = maxParams
            this.validator = validator
        }
    }

    companion object {

        private val TAG = MainModule.TAG

        private const val CACHE_NS = "targets"


        private const val PROBE_PREF = "targets.probe"


        private const val MISS_LIMIT = 5

        private val SEARCH_PACKAGES = arrayOf(
            "com.max.xiaoheihe", "com.max.hbcommon", "com.max.data", "com.max.feature"
        )

        private const val MAX_RELAXED = 4

        private val BBS_LIST_ADAPTERS = arrayOf(
            "com.max.xiaoheihe.module.bbs.adapter.t",
            "com.max.xiaoheihe.module.bbs.LinkRankingFragment\$a",
            "com.max.xiaoheihe.module.bbs.UserBBSInfoFragment\$p",
            "com.max.xiaoheihe.module.search.viewholderbinder.a",
            "com.max.xiaoheihe.module.search.page.b\$a",
            "com.max.xiaoheihe.module.search.page.d\$b",
            "com.max.xiaoheihe.module.news.adapter.d",
            "com.max.xiaoheihe.module.news.viewholderbinder.f\$d",
        )

        @Volatile
        private var sViewHolderClass: Class<*>? = null

        private val TARGETS = ConcurrentHashMap<String, Target>()
        private val RESOLVED = ConcurrentHashMap<String, MutableList<Method>>()
        private val SOURCE = ConcurrentHashMap<String, String>()
        private val PENDING = ConcurrentHashMap<String, MutableList<MethodHook>>()
        private val HOOKED: MutableSet<String> = ConcurrentHashMap.newKeySet()


        private val PROBED: MutableSet<String> = ConcurrentHashMap.newKeySet()


        private val HIT: MutableSet<String> = ConcurrentHashMap.newKeySet()


        private val ARMED: MutableSet<String> = ConcurrentHashMap.newKeySet()


        private val PROBE_ATTACHED: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private val MISS_COUNTS = LinkedHashMap<String, Int>()

        private val PROBE_LOCK = Any()

        @Volatile
        private var sSessionCounted: Boolean = false

        @Volatile
        private var sHitProbe: HitProbe? = null

        @Volatile
        private var sCacheKey: String = ""

        private val ORDER: MutableList<Target> = CopyOnWriteArrayList()

        private var sExecutor: ExecutorService? = null

        private fun executor(): ExecutorService = synchronized(this) {
            val current = sExecutor
            if (current == null || current.isShutdown || current.isTerminated) {
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "bhx-targets").apply { isDaemon = true }
                }.also { sExecutor = it }
            } else {
                current
            }
        }

        @Volatile
        private var sCl: ClassLoader? = null

        @Volatile
        private var sContext: Context? = null

        @Volatile
        private var sMain: Handler? = null

        @Volatile
        private var sVersionCode: Long = 0

        @Volatile
        private var sStarted: Boolean = false

        @Volatile
        private var sGeneration: Long = 0

        @JvmStatic
        fun hostClassLoader(): ClassLoader? {
            return sCl
        }

        @JvmStatic
        fun setHitProbe(probe: HitProbe?) {
            sHitProbe = probe
        }

        @JvmStatic
        @Synchronized
        fun init(cl: ClassLoader?, context: Context?) {
            if (sStarted) {
                return
            }
            sStarted = true
            sGeneration++
            sCl = cl
            sContext = context ?: App.resolveAppContext()
            try {
                sMain = Handler(Looper.getMainLooper())
            } catch (ignored: Throwable) {
                sMain = null
            }
            val all = definitions()
            for (target in all) {
                TARGETS.put(target.key, target)
                ORDER.add(target)
            }
            sVersionCode = versionCode(sContext)
            sCacheKey = cacheKey(sContext)
            loadCache(sCacheKey)

            val dropped = if (SessionGuard.isMainProcess()) applyMissCounts(sCacheKey) else false
            val missing: MutableList<Target> = ArrayList()
            for (target in all) {
                if (RESOLVED.containsKey(target.key)) {
                    continue
                }
                val hit = resolveCandidates(target)
                if (!hit.isEmpty()) {
                    RESOLVED.put(target.key, hit)
                    SOURCE.put(target.key, "候选")
                } else if (target.methodAnchors.size > 0 || target.classAnchors.size > 0) {
                    missing.add(target)
                }
            }
            if (dropped) {

                writeCache(sCacheKey)
            }
            LOG("目标解析完成: " + summary())
            if (!missing.isEmpty()) {
                val todo = missing
                val generation = sGeneration
                executor().execute { scanWithDexKit(todo, generation) }
            }
        }

        @JvmStatic
        fun sourceOf(key: String): String {
            val source = SOURCE[key]
            return source ?: "\u672a\u89e3\u6790"
        }

        @JvmStatic
        fun shutdown() {
            DexKit.Progress.shutdown()
            sMain?.removeCallbacksAndMessages(null)
            val executor = synchronized(this) {
                sGeneration++
                sExecutor.also { sExecutor = null }
            }
            try {
                executor?.shutdownNow()
            } catch (ignored: Throwable) {
            }
            synchronized(this) {
                TARGETS.clear()
                RESOLVED.clear()
                SOURCE.clear()
                PENDING.clear()
                HOOKED.clear()
                PROBED.clear()
                HIT.clear()
                PROBE_ATTACHED.clear()
                ARMED.clear()
                synchronized(PROBE_LOCK) {
                    MISS_COUNTS.clear()
                }
                sSessionCounted = false
                sHitProbe = null
                sCacheKey = ""
                ORDER.clear()
                sCl = null
                sContext = null
                sMain = null
                sVersionCode = 0
                sStarted = false
                sViewHolderClass = null
            }
        }

        @JvmStatic
        fun methods(key: String): List<Method> {
            val list = RESOLVED[key]
            return list ?: Collections.emptyList()
        }

        @JvmStatic
        fun install(key: String, hook: MethodHook) {
            PENDING.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(hook)
            for (method in methods(key)) {
                hookOnce(key, method, hook)
            }
        }

        @JvmStatic
        fun installGroup(keyPrefix: String, hook: MethodHook) {
            var matched = false
            for (target in ORDER) {
                if (target.key == keyPrefix || target.key.startsWith(keyPrefix + ".")) {
                    install(target.key, hook)
                    matched = true
                }
            }
            if (!matched) {
                install(keyPrefix, hook)
            }
        }

        @JvmStatic
        fun invokeBoolean(key: String, arg: String?): Boolean {
            if (arg == null) {
                return false
            }
            for (method in methods(key)) {
                if (method.parameterCount != 1) {
                    continue
                }
                if (PROBED.contains(key)) {
                    ARMED.add(key)
                }
                noteHit(key)
                try {
                    val value = method.invoke(null, arg)
                    if (value is Boolean && value) {
                        return true
                    }
                } catch (ignored: Throwable) {
                }
            }
            return false
        }

        @JvmStatic
        fun report(): String {
            val sb = StringBuilder()
            sb.append("宿主版本码: ").append(sVersionCode).append("\n")
            sb.append("缓存键: ").append(if (sCacheKey.isEmpty()) "—" else sCacheKey).append("\n")
            for (target in ORDER) {
                val list = RESOLVED[target.key]
                val source = SOURCE[target.key]
                sb.append("\n").append(target.key).append("  [")
                    .append(if (source == null) "未解析" else source)
                if (PROBED.contains(target.key)) {
                    val miss = synchronized(PROBE_LOCK) { MISS_COUNTS[target.key] ?: 0 }
                    sb.append(if (HIT.contains(target.key)) " · 已命中" else " · 未命中 " + miss + "/" + MISS_LIMIT)
                }
                sb.append("]\n")
                if (list == null || list.isEmpty()) {
                    sb.append("    —\n")
                    continue
                }
                for (method in list) {
                    sb.append("    ").append(method.declaringClass.name)
                        .append("#").append(method.name)
                        .append("/").append(method.parameterCount).append("\n")
                }
            }
            return sb.toString()
        }

        private fun summary(): String {
            val sb = StringBuilder()
            for (target in ORDER) {
                val source = SOURCE[target.key]
                sb.append(target.key).append("=").append(source ?: "-").append(" ")
            }
            return sb.toString().trim()
        }

        private fun flushPending() {
            for ((key, hooks) in PENDING) {
                for (hook in hooks) {
                    for (method in methods(key)) {
                        hookOnce(key, method, hook)
                    }
                }
            }
        }

        private fun hookOnce(key: String, method: Method, hook: MethodHook) {
            if (!HOOKED.add(key + "|" + signature(method))) {
                return
            }
            try {
                hook.hook(method)
            } catch (t: Throwable) {
                LOG("目标挂载失败 " + key + " -> " + signature(method) + ": " + t)
                return
            }
            attachProbe(key, method)
        }

        private fun attachProbe(key: String, method: Method) {
            if (!PROBED.contains(key)) {
                return
            }
            val probe = sHitProbe ?: return
            if (!PROBE_ATTACHED.add(key + "|" + signature(method))) {
                return
            }
            try {
                probe.attach(method, Runnable { noteHit(key) })
                ARMED.add(key)
            } catch (t: Throwable) {
                LOG("命中探针挂载失败 " + key + ": " + t)
            }
        }






        private fun noteHit(key: String) {
            if (!PROBED.contains(key) || !HIT.add(key)) {
                return
            }
            val cacheKey = sCacheKey
            if (cacheKey.isEmpty()) {
                return
            }
            synchronized(PROBE_LOCK) {
                if (!sSessionCounted) {
                    sSessionCounted = true
                    for (other in ARMED) {
                        if (!HIT.contains(other)) {
                            MISS_COUNTS[other] = (MISS_COUNTS[other] ?: 0) + 1
                        }
                    }
                }
                for (hit in HIT) {
                    MISS_COUNTS.remove(hit)
                }
                writeMissCounts(cacheKey)
            }
        }


        private fun applyMissCounts(cacheKey: String): Boolean {
            synchronized(PROBE_LOCK) {
                MISS_COUNTS.clear()
                MISS_COUNTS.putAll(parseMissCounts(HeyboxPrefs.getString(PROBE_PREF, ""), cacheKey))
            }
            var dropped = false
            for (target in ORDER) {
                val key = target.key
                if (SOURCE[key] != "缓存") {
                    continue
                }
                val miss = synchronized(PROBE_LOCK) { MISS_COUNTS[key] ?: 0 }
                if (miss >= MISS_LIMIT) {
                    RESOLVED.remove(key)
                    SOURCE.remove(key)
                    synchronized(PROBE_LOCK) { MISS_COUNTS.remove(key) }
                    dropped = true
                    LOG("命中自检: " + key + " 连续 " + miss + " 次会话未调用，作废缓存重新解析")
                    continue
                }
                PROBED.add(key)
            }
            synchronized(PROBE_LOCK) {

                MISS_COUNTS.keys.retainAll(PROBED)
                if (cacheKey.isNotEmpty()) {
                    writeMissCounts(cacheKey)
                }
            }
            return dropped
        }

        private fun writeMissCounts(cacheKey: String) {
            HeyboxPrefs.setString(PROBE_PREF, formatMissCounts(cacheKey, MISS_COUNTS))
        }


        @JvmStatic
        @JvmName("parseMissCounts")
        internal fun parseMissCounts(raw: String?, cacheKey: String): Map<String, Int> {
            val out = LinkedHashMap<String, Int>()
            if (raw.isNullOrEmpty() || cacheKey.isEmpty()) {
                return out
            }
            val lines = raw.split("\n")
            if (lines.isEmpty() || lines[0].trim() != cacheKey) {
                return out
            }
            for (i in 1 until lines.size) {
                val line = lines[i]
                val eq = line.indexOf('=')
                if (eq <= 0) {
                    continue
                }
                val count = line.substring(eq + 1).trim().toIntOrNull() ?: continue
                if (count > 0) {
                    out[line.substring(0, eq).trim()] = count
                }
            }
            return out
        }

        @JvmStatic
        @JvmName("formatMissCounts")
        internal fun formatMissCounts(cacheKey: String, counts: Map<String, Int>): String {
            val sb = StringBuilder(cacheKey).append("\n")
            for ((key, count) in counts) {
                if (count > 0) {
                    sb.append(key).append("=").append(count).append("\n")
                }
            }
            return sb.toString()
        }

        private fun resolveCandidates(target: Target): MutableList<Method> {
            val cl = sCl
            if (cl == null) {
                return Collections.emptyList()
            }
            for (name in target.classes) {
                try {
                    val cls = Class.forName(name, false, cl)
                    val hit = match(cls, target, true)
                    if (!hit.isEmpty()) {
                        return hit
                    }
                } catch (ignored: Throwable) {
                }
            }
            return Collections.emptyList()
        }

        private fun match(cls: Class<*>, target: Target, useNames: Boolean): MutableList<Method> {
            val out: MutableList<Method> = ArrayList()
            val declared: Array<Method> = try {
                cls.declaredMethods
            } catch (t: Throwable) {
                return out
            }
            for (method in declared) {
                if (Modifier.isAbstract(method.modifiers)) {
                    continue
                }
                if (useNames && target.methods.size > 0 && !contains(target.methods, method.name)) {
                    continue
                }
                val count = method.parameterCount
                if (count < target.minParams || count > target.maxParams) {
                    continue
                }
                if (target.validator != null && !accept(target.validator, method)) {
                    continue
                }
                try {
                    method.setAccessible(true)
                } catch (ignored: Throwable) {
                }
                out.add(method)
            }
            return out
        }

        private fun matchWithFallback(cls: Class<*>, target: Target): MutableList<Method> {
            val strict = match(cls, target, true)
            if (!strict.isEmpty()) {
                return strict
            }
            val relaxed = match(cls, target, false)
            if (relaxed.size > MAX_RELAXED) {
                LOG("目标 " + target.key + " 在 " + cls.name + " 上宽松匹配到 " +
                    relaxed.size + " 个方法，超出上限已放弃")
                return Collections.emptyList()
            }
            return relaxed
        }

        private fun accept(validator: Validator, method: Method): Boolean {
            try {
                return validator.accept(method)
            } catch (t: Throwable) {
                return false
            }
        }

        private fun isCurrent(generation: Long): Boolean = synchronized(this) {
            sStarted && sGeneration == generation
        }

        private fun scanWithDexKit(missing: List<Target>, generation: Long) {
            if (!isCurrent(generation)) {
                return
            }
            val start = android.os.SystemClock.elapsedRealtime()
            val path = DexKit.apkPath(sContext, sCl)
            if (path == null) {
                LOG("DexKit 跳过: 取不到小黑盒 APK 路径")
                return
            }
            val found = LinkedHashMap<String, MutableList<Method>>()
            val progress = DexKit.Progress.begin("定位 Hook 目标", missing.size)
            try {
                DexKit.scan(path, sCl!!) { scanner ->
                    scanner.setThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)))
                    for ((index, target) in missing.withIndex()) {
                        if (!isCurrent(generation)) {
                            break
                        }
                        progress.step(index, target.key)
                        val hit = LinkedHashMap<String, Method>()
                        collectByMethodAnchors(scanner, target, hit)
                        collectByClassAnchors(scanner, target, hit)
                        if (!hit.isEmpty()) {
                            found.put(target.key, ArrayList(hit.values))
                        }
                    }
                }
            } catch (t: Throwable) {
                LOG("DexKit 扫描异常: " + t)
            } finally {
                progress.finish()
            }
            val cost = android.os.SystemClock.elapsedRealtime() - start
            if (found.isEmpty()) {
                LOG("DexKit 未补到任何目标 (" + cost + "ms)")
                return
            }
            if (!isCurrent(generation)) {
                return
            }
            for ((key, value) in found) {
                RESOLVED.put(key, value)
                SOURCE.put(key, "DexKit")
            }
            writeCache(sCacheKey)
            LOG("DexKit 补挂 " + found.keys + " (" + cost + "ms)")
            val main = sMain
            if (main != null) {
                main.post {
                    if (isCurrent(generation)) {
                        flushPending()
                    }
                }
            } else if (isCurrent(generation)) {
                flushPending()
            }
        }

        private fun collectByMethodAnchors(
            scanner: DexKit.Scanner,
            target: Target,
            out: LinkedHashMap<String, Method>
        ) {
            if (target.methodAnchors.size == 0) {
                return
            }
            try {
                val found = scanner.methodsUsingStrings(
                    Arrays.asList(*target.methodAnchors), target.minParams, target.maxParams, SEARCH_PACKAGES
                )
                for (method in found) {
                    if (target.methods.size > 0 && !contains(target.methods, method.name)) {
                        continue
                    }
                    if (target.validator != null && !accept(target.validator, method)) {
                        continue
                    }
                    out.put(signature(method), method)
                }
            } catch (t: Throwable) {
                LOG("DexKit 方法锚点查询失败 " + target.key + ": " + t)
            }
        }

        private fun collectByClassAnchors(
            scanner: DexKit.Scanner,
            target: Target,
            out: LinkedHashMap<String, Method>
        ) {
            if (target.classAnchors.size == 0) {
                return
            }
            try {
                for (cls in scanner.classesUsingStrings(Arrays.asList(*target.classAnchors), SEARCH_PACKAGES)) {
                    for (method in matchWithFallback(cls, target)) {
                        out.put(signature(method), method)
                    }
                }
            } catch (t: Throwable) {
                LOG("DexKit 类锚点查询失败 " + target.key + ": " + t)
            }
        }


        private fun cacheKey(context: Context?): String = DexKit.fingerprint(context) ?: ""

        private fun loadCache(cacheKey: String) {
            if (cacheKey.isEmpty()) {
                return
            }
            val raw = DexKit.read(sContext, CACHE_NS)
            if (raw == null || raw.isEmpty()) {
                return
            }
            for (line in raw.split("\n")) {
                val eq = line.indexOf('=')
                if (eq <= 0) {
                    continue
                }
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                val target = TARGETS[key]
                if (target == null) {
                    continue
                }
                val method = decode(value, target)
                if (method == null) {
                    continue
                }
                var list = RESOLVED[key]
                if (list == null) {
                    list = ArrayList()
                    RESOLVED.put(key, list)
                    SOURCE.put(key, "缓存")
                }
                list.add(method)
            }
        }

        private fun writeCache(cacheKey: String) {
            if (cacheKey.isEmpty()) {
                return
            }
            val sb = StringBuilder()
            for ((key, value) in RESOLVED) {
                for (method in value) {
                    sb.append(key).append("=").append(signature(method)).append("\n")
                }
            }
            DexKit.write(sContext, CACHE_NS, sb.toString())
        }

        private fun decode(value: String?, target: Target): Method? {
            if (value == null || sCl == null) {
                return null
            }
            val parts = value.split("#")
            if (parts.size != 3) {
                return null
            }
            try {
                val cls = Class.forName(parts[0], false, sCl)
                val count = parts[2].toInt()
                for (method in cls.declaredMethods) {
                    if (method.name != parts[1] || method.parameterCount != count) {
                        continue
                    }
                    if (target.validator != null && !accept(target.validator, method)) {
                        continue
                    }
                    method.setAccessible(true)
                    return method
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        @JvmStatic
        @JvmName("signature")
        internal fun signature(method: Method): String {
            return method.declaringClass.name + "#" + method.name +
                "#" + method.parameterCount
        }

        private fun versionCode(context: Context?): Long {
            if (context == null) {
                return 0L
            }
            try {
                val info: PackageInfo = context.packageManager
                    .getPackageInfo(MainModule.TARGET_PKG, 0)
                return if (android.os.Build.VERSION.SDK_INT >= 28) {
                    info.longVersionCode
                } else {
                    info.versionCode.toLong()
                }
            } catch (t: Throwable) {
                return 0L
            }
        }

        @JvmStatic
        @JvmName("contains")
        internal fun contains(array: Array<String>, value: String): Boolean {
            for (item in array) {
                if (item == value) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        @JvmName("hasParam")
        internal fun hasParam(method: Method, paramClassName: String): Boolean {
            for (type in method.parameterTypes) {
                if (paramClassName == type.name) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        @JvmName("isStringPredicate")
        internal fun isStringPredicate(method: Method): Boolean {
            return method.parameterCount == 1
                && method.parameterTypes[0] === String::class.java
                && method.returnType === Boolean::class.javaPrimitiveType
        }

        @JvmStatic
        @JvmName("isInnerParam")
        internal fun isInnerParam(method: Method): Boolean {
            if (method.parameterCount != 1) {
                return false
            }
            val owner = method.declaringClass.name
            return method.parameterTypes[0].name.startsWith(owner + "$")
        }

        @JvmStatic
        @JvmName("isUtilParam")
        internal fun isUtilParam(method: Method): Boolean {
            return method.parameterCount == 1
                && method.parameterTypes[0].name.startsWith("com.max.xiaoheihe.utils.")
        }

        @JvmStatic
        @JvmName("isBooleanFlagIn")
        internal fun isBooleanFlagIn(method: Method): Boolean {
            return method.parameterCount == 1
                && method.parameterTypes[0] === Boolean::class.javaPrimitiveType
                && method.returnType !== Void.TYPE
        }

        @JvmStatic
        @JvmName("isFeedsBinder")
        internal fun isFeedsBinder(method: Method): Boolean {
            return method.parameterCount == 2
                && "com.max.xiaoheihe.bean.news.FeedsContentBaseObj"
                    .equals(method.parameterTypes[1].name)
        }

        @JvmStatic
        @JvmName("isBbsLinkBinder")
        internal fun isBbsLinkBinder(method: Method): Boolean {
            if (method.isBridge || method.isSynthetic) {
                return false
            }
            if (method.returnType !== Void.TYPE || method.parameterCount != 2) {
                return false
            }
            val types = method.parameterTypes
            if (PromoteDetector.BBS_LINK_OBJ != types[1].name) {
                return false
            }
            return isViewHolderParam(types[0])
        }

        @JvmStatic
        @JvmName("isViewHolderParam")
        internal fun isViewHolderParam(type: Class<*>): Boolean {
            var holder = sViewHolderClass
            if (holder == null) {
                try {
                    holder = Class.forName(
                        "androidx.recyclerview.widget.RecyclerView\$ViewHolder",
                        false, sCl
                    )
                } catch (ignored: Throwable) {
                }
                if (holder != null) {
                    sViewHolderClass = holder
                }
            }
            if (holder != null && holder.isAssignableFrom(type)) {
                return true
            }
            return type.name.startsWith("com.max.hbcommon.base.adapter.s\$")
        }

        @JvmStatic
        @JvmName("isRecommendBinder")
        internal fun isRecommendBinder(method: Method): Boolean {
            if (method.returnType !== Void.TYPE || method.parameterCount != 2) {
                return false
            }
            val types = method.parameterTypes
            return types[1] === Any::class.java && isViewHolderParam(types[0])
        }

        @JvmStatic
        @JvmName("isBigBrotherBinder")
        internal fun isBigBrotherBinder(method: Method): Boolean {
            if (method.returnType !== Void.TYPE || method.parameterCount != 2) {
                return false
            }
            val types = method.parameterTypes
            return types[1] === Integer.TYPE
                && isViewHolderParam(types[0])
                && ("androidx.recyclerview.widget.RecyclerView\$ViewHolder"
                    != types[0].name)
        }

        @JvmStatic
        @JvmName("isBBDelegateBinder")
        internal fun isBBDelegateBinder(method: Method): Boolean {
            if (method.returnType !== Void.TYPE || method.parameterCount != 3) {
                return false
            }
            val types = method.parameterTypes
            if (types[2] === Any::class.java || !isViewHolderParam(types[0])) {
                return false
            }
            var cls: Class<*>? = types[1]
            while (cls != null && cls !== Any::class.java) {
                if ("com.max.hbcommon.base.adapter.s" == cls.name) {
                    return true
                }
                cls = cls.superclass
            }
            return false
        }

        @JvmStatic
        @JvmName("isLinksGetter")
        internal fun isLinksGetter(method: Method): Boolean {
            return !method.isBridge
                && method.parameterCount == 0
                && java.util.List::class.java.isAssignableFrom(method.returnType)
        }

        internal fun isContextBoolean(method: Method): Boolean {
            return method.parameterCount == 1
                && method.parameterTypes[0] === Context::class.java
                && method.returnType === java.lang.Boolean.TYPE
        }

        internal fun isContextList(method: Method): Boolean {
            return method.parameterCount == 1
                && method.parameterTypes[0] === Context::class.java
                && java.util.List::class.java.isAssignableFrom(method.returnType)
        }

        internal fun isUsageUpload(method: Method): Boolean {
            if (method.parameterCount != 5 || method.returnType !== Void.TYPE) {
                return false
            }
            val types = method.parameterTypes
            return types[0] === Context::class.java
                && types[2] === java.lang.Boolean.TYPE
                && types[3] === java.lang.Boolean.TYPE
        }

        private fun definitions(): Array<Target> {
            val list: MutableList<Target> = ArrayList()
            Collections.addAll(list, *baseDefinitions())
            for (i in BBS_LIST_ADAPTERS.indices) {
                list.add(
                    Target(
                        PromoteDetector.TARGET_BBS_LIST_BIND + "." + i,
                        arrayOf(BBS_LIST_ADAPTERS[i]),
                        emptyArray(),
                        emptyArray(),
                        emptyArray(),
                        2, 2,
                        Validator { method -> isBbsLinkBinder(method) }
                    )
                )
            }
            return list.toTypedArray()
        }

        private fun baseDefinitions(): Array<Target> {
            return arrayOf(
                Target(
                    GameLibraryCleanHook.TARGET_GAME_REC_BIND,
                    arrayOf(GameLibraryCleanHook.ADAPTER_CLASS),
                    emptyArray(),
                    GameLibraryCleanHook.CLASS_ANCHORS,
                    emptyArray(),
                    2, 2,
                    Validator { method -> isRecommendBinder(method) }
                ),

                Target(
                    GameLibraryCleanHook.TARGET_GAME_REC_WRAPPER,
                    arrayOf(GameLibraryCleanHook.WRAPPER_CLASS),
                    emptyArray(),
                    emptyArray(),
                    emptyArray(),
                    2, 2,
                    Validator { method -> isBigBrotherBinder(method) }
                ),

                Target(
                    GameLibraryCleanHook.TARGET_GAME_REC_BB,
                    arrayOf(GameLibraryCleanHook.BB_DELEGATE_CLASS),
                    emptyArray(),
                    GameLibraryCleanHook.BB_CLASS_ANCHORS,
                    emptyArray(),
                    3, 3,
                    Validator { method -> isBBDelegateBinder(method) }
                ),

                Target(
                    PromoteDetector.TARGET_BBS_RENDER,
                    arrayOf(
                        "com.max.xiaoheihe.module.bbs.utils.b",
                        "com.max.xiaoheihe.module.bbs.utils.BBSKtUtils"
                    ),
                    arrayOf("L", "N", "o"),
                    emptyArray(),
                    arrayOf("\u63a8\u5e7f"),
                    1, 8,
                    Validator { method ->
                        method.returnType === Void.TYPE
                            && hasParam(method, "com.max.xiaoheihe.bean.bbs.BBSLinkObj")
                    }
                ),

                Target(
                    PromoteDetector.TARGET_BBS_PRED_PROMOTE,
                    arrayOf("com.max.xiaoheihe.module.bbs.utils.b"),
                    arrayOf("w"),
                    emptyArray(),
                    arrayOf("28", "29"),
                    1, 1,
                    Validator { method -> isStringPredicate(method) }
                ),

                Target(
                    PromoteDetector.TARGET_BBS_PRED_AD,
                    arrayOf("com.max.xiaoheihe.module.bbs.utils.b"),
                    arrayOf("z"),
                    emptyArray(),
                    arrayOf("23"),
                    1, 1,
                    Validator { method -> isStringPredicate(method) }
                ),

                Target(
                    PromoteDetector.TARGET_ADS_SPLASH,
                    arrayOf("com.max.xiaoheihe.module.ads.e"),
                    arrayOf("g"),
                    arrayOf("AdsImgDownLoad"),
                    emptyArray(),
                    1, 1,
                    Validator { method -> isBooleanFlagIn(method) }
                ),

                Target(
                    PromoteDetector.TARGET_ADS_BUBBLE,
                    arrayOf("com.max.xiaoheihe.module.ads.h"),
                    arrayOf("s", "l"),
                    arrayOf("KEY_HOME_CORNER_AD_STATE"),
                    emptyArray(),
                    1, 1,
                    Validator { method -> isInnerParam(method) }
                ),

                Target(
                    PromoteDetector.TARGET_ADS_CORNER,
                    arrayOf("com.max.xiaoheihe.module.ads.h"),
                    arrayOf("i", "h"),
                    arrayOf("KEY_HOME_CORNER_AD_STATE"),
                    emptyArray(),
                    1, 1,
                    Validator { method -> isUtilParam(method) }
                ),

                Target(
                    PromoteDetector.TARGET_FEEDS_BIND,
                    arrayOf("com.max.xiaoheihe.module.news.adapter.a"),
                    arrayOf("y"),
                    emptyArray(),
                    emptyArray(),
                    2, 2,
                    Validator { method -> isFeedsBinder(method) }
                ),

                Target(
                    PromoteDetector.TARGET_BBS_LINKS_GETTER,
                    arrayOf("com.max.xiaoheihe.bean.bbs.BBSLinkListResultObj"),
                    arrayOf("getLinks"),
                    emptyArray(),
                    emptyArray(),
                    0, 0,
                    Validator { method -> isLinksGetter(method) }
                ),

                Target(
                    FakeUsageAccessHook.TARGET_FAKE_USAGE_GRANTED,
                    arrayOf(FakeUsageAccessHook.GRANT_CLASS),
                    arrayOf("i", "a"),
                    arrayOf("usagestats"),
                    arrayOf("usagestats", "queryUsageStats"),
                    1, 1,
                    Validator { method -> isContextBoolean(method) }
                ),

                Target(
                    FakeUsageAccessHook.TARGET_FAKE_USAGE_LIST,
                    arrayOf(FakeUsageAccessHook.USAGE_CLASS),
                    arrayOf("r"),
                    arrayOf("UsageManager"),
                    arrayOf("queryUsageStats", "queryEvents"),
                    1, 1,
                    Validator { method -> isContextList(method) }
                ),

                Target(
                    FakeUsageAccessHook.TARGET_FAKE_USAGE_UPLOAD,
                    arrayOf(FakeUsageAccessHook.USAGE_CLASS),
                    arrayOf("h"),
                    arrayOf("UsageManager"),
                    arrayOf("getAndUploadUsageInfoInWhiteList", "mobileapp_white_list_version"),
                    5, 5,
                    Validator { method -> isUsageUpload(method) }
                ),
            )
        }

        private fun LOG(message: String) {
            try {
                Log.i(TAG, message)
            } catch (ignored: Throwable) {
            }
        }
    }
}
