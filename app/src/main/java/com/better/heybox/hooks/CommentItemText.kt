package com.better.heybox.hooks

internal fun knownCommentItemText(item: Any): String? {
    for (name in listOf("c0", "d0")) {
        val method = try {
            item.javaClass.getMethod(name)
        } catch (_: NoSuchMethodException) {
            continue
        }
        if (method.returnType != String::class.java) continue
        return try {
            (method.invoke(item) as? String)?.trim() ?: ""
        } catch (_: ReflectiveOperationException) {
            null
        }
    }
    return null
}
