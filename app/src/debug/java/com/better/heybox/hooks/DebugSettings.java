package com.better.heybox.hooks;

import com.better.heybox.App;

/** Debug-only settings rows. */
public final class DebugSettings {

    private DebugSettings() {
    }

    static SettingsEntryHook.SwitchDef[] generalRows() {
        return new SettingsEntryHook.SwitchDef[]{
                new SettingsEntryHook.SwitchDef("调试：忽略版本降级限制",
                        "清除版本下限，允许装回更旧的模块",
                        App.KEY_DEBUG_NO_DOWNGRADE, false, false),
        };
    }
}
