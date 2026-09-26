package com.better.heybox.liquidglass

import com.better.heybox.MainModule
import com.highcapable.yukihookapi.hook.param.HookChain
import java.lang.reflect.Executable

object LiquidGlassHookBridge {

    @Volatile private var module: MainModule? = null

    @JvmStatic
    fun setModule(value: MainModule?) {
        module = value
    }

    @JvmStatic
    fun hookExecutable(executable: Executable, function: ChainFunction) {
        val m = module ?: return
        m.hook(executable).intercept { chain ->
            try {
                function.apply(chain)
            } catch (t: Throwable) {
                chain.proceed()
            }
        }
    }

    fun interface ChainFunction {
        @Throws(Throwable::class)
        fun apply(chain: HookChain): Any?
    }
}
