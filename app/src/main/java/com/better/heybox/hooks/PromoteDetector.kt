package com.better.heybox.hooks

import com.better.heybox.HeyboxTargets
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class PromoteDetector private constructor() {

    companion object {
        const val TARGET_BBS_RENDER = "bbs.render"
        const val TARGET_BBS_PRED_PROMOTE = "bbs.pred.promote"
        const val TARGET_BBS_PRED_AD = "bbs.pred.ad"
        const val TARGET_ADS_SPLASH = "ads.splash"
        const val TARGET_ADS_BUBBLE = "ads.bubble"
        const val TARGET_ADS_CORNER = "ads.corner"
        const val TARGET_FEEDS_BIND = "feeds.list.bind"

        const val TARGET_BBS_LINKS_GETTER = "bbs.links.getter"

        const val TARGET_BBS_LIST_BIND = "bbs.list.bind"

        const val BBS_LINK_OBJ = "com.max.xiaoheihe.bean.bbs.BBSLinkObj"
        const val FEEDS_BASE_OBJ = "com.max.xiaoheihe.bean.news.FeedsContentBaseObj"

        private const val AD_FLOW_MODEL = "com.max.data.model.feeds.AdFeedsFlowItemModel"
        private const val CONTENT_TYPE_PREFIX = "CONTENT_TYPE_"

        private val PROMOTE_USERS: Set<String> = Collections.unmodifiableSet(
            HashSet(listOf("小黑盒推广", "商城看板娘"))
        )

        private val FALLBACK_CONTENT_TYPES: Set<String> = Collections.unmodifiableSet(
            HashSet(listOf("23", "26", "27", "28", "29"))
        )

        private val GETTERS = ConcurrentHashMap<String, Method>()
        private val GETTER_MISS: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        private var sContentTypes: Set<String>? = null

        @Volatile
        private var sContentTypeSource: String = "内置表"

        @Volatile
        private var sContentTypeDetail: String = "23,26,27,28,29"

        @JvmStatic
        fun isPromote(item: Any?): Boolean {
            return matchReason(item) != null
        }

        @JvmStatic
        fun matchReason(item: Any?): String? {
            if (item == null) {
                return null
            }
            val reportOwner = adReportOwner(item)
            if (reportOwner != null) {
                return reportOwner + " 非空（投放上报数据）"
            }
            if (isInstanceNamed(item.javaClass, AD_FLOW_MODEL)) {
                return "首页流广告卡 AdFeedsFlowItemModel"
            }
            val type = contentType(item)
            if (type != null) {
                if (contentTypes().contains(type)) {
                    return "content_type=$type 属于宿主广告常量表"
                }
                if (HeyboxTargets.invokeBoolean(TARGET_BBS_PRED_PROMOTE, type)) {
                    return "content_type=$type 命中宿主判据 w()（社区推广）"
                }
                if (HeyboxTargets.invokeBoolean(TARGET_BBS_PRED_AD, type)) {
                    return "content_type=$type 命中宿主判据 z()（广告位）"
                }
            }
            val label = readGetter(item, "getLabel")
            if (label != null && "advertise".equals("$label")) {
                return "label=advertise"
            }
            val name = username(item)
            if (name != null && PROMOTE_USERS.contains(name)) {
                return "推广号作者 $name"
            }
            return null
        }

        @JvmStatic
        fun hasAdReport(item: Any?): Boolean {
            return adReportOwner(item) != null
        }

        private fun adReportOwner(item: Any?): String? {
            if (item == null) {
                return null
            }
            if (readGetter(item, "getAdReport") != null) {
                return "adReport"
            }
            if (readGetter(item, "getAd_report") != null) {
                return "ad_report"
            }
            return null
        }

        @JvmStatic
        fun contentType(item: Any?): String? {
            var value: Any? = readGetter(item, "getContent_type")
            if (value == null) {
                value = readGetter(item, "getContentType")
            }
            return if (value == null) null else "$value"
        }

        @JvmStatic
        fun username(item: Any?): String? {
            val user = readGetter(item, "getUser")
            if (user == null) {
                return null
            }
            val name = readGetter(user, "getUsername")
            return if (name == null) null else "$name"
        }

        @JvmStatic
        fun author(item: Any?): String? {
            val name = username(item)
            if (name != null) {
                return name
            }
            val author = readGetter(item, "getAuthor")
            if (author == null) {
                return null
            }
            var nested = readGetter(author, "getUsername")
            if (nested == null) {
                nested = readGetter(author, "getNickname")
            }
            return if (nested == null) null else "$nested"
        }

        @JvmStatic
        fun level(item: Any?): String? {
            val user = readGetter(item, "getUser")
            if (user == null) {
                return null
            }
            val info = readGetter(user, "getLevel_info")
            if (info == null) {
                return null
            }
            val value = readGetter(info, "getLevel")
            return if (value == null) null else "$value"
        }

        @JvmStatic
        fun likeCount(item: Any?): Int? {
            val value = readInt(item, "getLinkAwardNum", "getLink_award_num")
            return value ?: readInt(preloadStats(item), "getLikeCnt")
        }

        @JvmStatic
        fun commentCount(item: Any?): Int? {
            val value = readInt(item, "getCommentNum", "getComment_num")
            return value ?: readInt(preloadStats(item), "getCommentCnt")
        }

        @JvmStatic
        fun favourCount(item: Any?): Int? {
            return readInt(preloadStats(item), "getSaveCnt")
        }

        private fun preloadStats(item: Any?): Any? {
            val preload = readGetter(item, "getCommunityPostPreload")
            return if (preload == null) null else readGetter(preload, "getInteractStats")
        }

        private fun readInt(target: Any?, vararg getters: String): Int? {
            if (target == null) {
                return null
            }
            for (name in getters) {
                val value = readGetter(target, name)
                if (value is Number) {
                    return (value as java.lang.Number).intValue()
                }
                if (value is String) {
                    val text = value.trim()
                    if (text.isEmpty()) {
                        continue
                    }
                    try {
                        return Integer.parseInt(text)
                    } catch (ignored: Throwable) {
                    }
                }
            }
            return null
        }

        @JvmStatic
        fun title(item: Any?): String? {
            val value = readGetter(item, "getTitle")
            if (value != null && "$value".isNotEmpty()) {
                return "$value"
            }
            val link = readGetter(item, "getLinkContent")
            if (link != null) {
                val nested = readGetter(link, "getTitle")
                if (nested != null) {
                    return "$nested"
                }
            }
            val desc = readGetter(item, "getDescription")
            return if (desc == null) null else "$desc"
        }

        @JvmStatic
        fun isPromoteUser(name: String?): Boolean {
            return name != null && PROMOTE_USERS.contains(name)
        }

        @JvmStatic
        fun describe(item: Any?): String {
            if (item == null) {
                return "null"
            }
            val sb = StringBuilder()
            sb.append("model=").append(item.javaClass.simpleName)
            sb.append(", ct=").append(contentType(item))
            sb.append(", adReport=").append(hasAdReport(item))
            sb.append(", label=").append(readGetter(item, "getLabel"))
            val author = author(item)
            sb.append(", 作者=").append(author ?: "?")
            val level = level(item)
            if (level != null) {
                sb.append(", 等级=").append(level)
            }
            appendCount(sb, "赞", likeCount(item))
            appendCount(sb, "评", commentCount(item))
            appendCount(sb, "藏", favourCount(item))
            sb.append(", 标题=").append(abbreviate(title(item)))
            val reason = matchReason(item)
            sb.append(", 命中=").append(reason ?: "否")
            return sb.toString()
        }

        private fun appendCount(sb: StringBuilder, label: String, value: Int?) {
            if (value != null) {
                sb.append(", ").append(label).append('=').append(value)
            }
        }

        @JvmStatic
        fun abbreviate(text: String?): String {
            if (text == null) {
                return ""
            }
            val flat = text.replace('\n', ' ').trim()
            return if (flat.length <= 48) flat else flat.substring(0, 48) + "..."
        }

        @JvmStatic
        fun contentTypes(): Set<String> {
            val cached = sContentTypes
            if (cached != null) {
                return cached
            }
            synchronized(PromoteDetector::class.java) {
                val again = sContentTypes
                if (again != null) {
                    return again
                }
                val built = HashSet<String>()
                val detail = StringBuilder()
                val cl = HeyboxTargets.hostClassLoader()
                if (cl != null) {
                    try {
                        val cls = Class.forName(BBS_LINK_OBJ, false, cl)
                        for (field: Field in cls.declaredFields) {
                            if (!Modifier.isStatic(field.modifiers)
                                || field.type != String::class.java
                            ) {
                                continue
                            }
                            val name = field.name
                            if (!name.startsWith(CONTENT_TYPE_PREFIX)) {
                                continue
                            }
                            val tail = name.substring(CONTENT_TYPE_PREFIX.length)
                            if (!isAdTypeName(tail)) {
                                continue
                            }
                            try {
                                field.isAccessible = true
                            } catch (ignored: Throwable) {
                            }
                            val value = field.get(null)
                            if (value is String && value.isNotEmpty()) {
                                built.add(value)
                                detail.append(tail).append('=').append(value).append(' ')
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                if (built.isEmpty()) {
                    built.addAll(FALLBACK_CONTENT_TYPES)
                    sContentTypeSource = "内置表"
                    sContentTypeDetail = "23,26,27,28,29"
                } else {
                    sContentTypeSource = "宿主常量表"
                    sContentTypeDetail = detail.toString().trim()
                }
                val unmodifiable: Set<String> = Collections.unmodifiableSet(built)
                sContentTypes = unmodifiable
                return unmodifiable
            }
        }

        @JvmStatic
        fun contentTypesInfo(): String {
            contentTypes()
            return sContentTypeSource + " " + sContentTypeDetail
        }

        private fun isAdTypeName(tail: String): Boolean {
            return tail == "AD" ||
                tail.startsWith("AD_") ||
                tail.endsWith("_AD") ||
                tail.contains("_AD_")
        }

        private fun isInstanceNamed(cls: Class<*>, name: String): Boolean {
            var walk: Class<*>? = cls
            while (walk != null && walk != Any::class.java) {
                if (name == walk.name) {
                    return true
                }
                walk = walk.superclass
            }
            return false
        }

        private fun readGetter(item: Any?, name: String): Any? {
            if (item == null) {
                return null
            }
            val method = findGetter(item.javaClass, name)
            if (method == null) {
                return null
            }
            try {
                return method.invoke(item)
            } catch (t: Throwable) {
                return null
            }
        }

        private fun findGetter(cls: Class<*>, name: String): Method? {
            val key = cls.name + "#" + name
            val hit = GETTERS[key]
            if (hit != null) {
                return hit
            }
            if (GETTER_MISS.contains(key)) {
                return null
            }
            var walk: Class<*>? = cls
            while (walk != null && walk != Any::class.java) {
                try {
                    val method = walk.getDeclaredMethod(name)
                    if (method.parameterCount == 0) {
                        method.isAccessible = true
                        GETTERS[key] = method
                        return method
                    }
                } catch (ignored: Throwable) {
                }
                walk = walk.superclass
            }
            GETTER_MISS.add(key)
            return null
        }
    }
}
