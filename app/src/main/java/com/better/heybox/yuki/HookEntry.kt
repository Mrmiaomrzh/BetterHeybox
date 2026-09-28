package com.better.heybox.yuki

import com.better.heybox.BuildFlags
import com.better.heybox.MainModule
import com.better.heybox.ModuleResourceCleanup
import com.highcapable.yukihookapi.annotation.xposed.YukiHookLibXposedEntry
import com.highcapable.yukihookapi.hook.factory.configure
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.YukiHookXposedModule
import com.highcapable.yukihookapi.hook.xposed.reload.lifecycle.registerModuleLifecycle

@YukiHookLibXposedEntry(
    minApiVersion = 102,
    targetApiVersion = 102,
    staticScope = true,
    hotReload = YukiHookLibXposedEntry.HotReload.MANUAL,
    scope = [MainModule.TARGET_PKG]
)
object HookEntry : YukiHookXposedModule {

    override fun onInit() {
        configure {
            debug = BuildFlags.DEBUG
        }
        registerModuleLifecycle {
            onDispose { ModuleResourceCleanup.releaseAll() }
        }
    }

    override fun onHook() = encase {
        loadApp(MainModule.TARGET_PKG) {
            MainModule.attach(this)
        }
    }
}
