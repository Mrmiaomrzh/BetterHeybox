package com.better.heybox.hooks

import com.better.heybox.App

object DebugSettings {
    @JvmStatic
    internal fun generalRows(): Array<SettingsEntryHook.SwitchDef> = arrayOf(
        SettingsEntryHook.SwitchDef(
            "调试：忽略版本降级限制",
            "清除版本下限，允许装回更旧的模块",
            App.KEY_DEBUG_NO_DOWNGRADE,
            false,
            false
        )
    )
}
