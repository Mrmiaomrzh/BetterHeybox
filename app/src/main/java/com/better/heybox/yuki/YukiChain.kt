package com.better.heybox.yuki

import com.highcapable.yukihookapi.hook.param.HookChain

interface YukiChain {

    @Throws(Throwable::class)
    fun proceed(): Any?

    @Throws(Throwable::class)
    fun proceed(args: Array<Any?>): Any?

    fun getThisObject(): Any?

    fun getArg(index: Int): Any?

    fun getArgs(): List<@JvmSuppressWildcards Any?>
}

internal class YukiChainView(private val chain: HookChain) : YukiChain {

    override fun proceed(): Any? = chain.proceed()

    override fun proceed(args: Array<Any?>): Any? = chain.proceed(args)

    override fun getThisObject(): Any? = chain.instanceOrNull

    override fun getArg(index: Int): Any? = chain.arg(index)

    override fun getArgs(): List<Any?> = chain.args
}

fun interface YukiChainFunction {

    @Throws(Throwable::class)
    fun apply(chain: YukiChain): Any?
}
