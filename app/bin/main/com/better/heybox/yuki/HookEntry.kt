package com.better.heybox.yuki

import com.better.heybox.BuildFlags
import com.better.heybox.MainModule
import com.highcapable.yukihookapi.annotation.xposed.YukiHookLibXposedEntry
import com.highcapable.yukihookapi.hook.factory.configure
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.YukiHookXposedModule

@YukiHookLibXposedEntry(
    minApiVersion = 101,
    targetApiVersion = 102,
    staticScope = true,
    scope = [MainModule.TARGET_PKG]
)
object HookEntry : YukiHookXposedModule {

    override fun onInit() = configure {
        debug = BuildFlags.DEBUG
    }

    override fun onHook() = encase {
        loadApp(MainModule.TARGET_PKG) {
            MainModule.attach(this)
        }
    }
}
