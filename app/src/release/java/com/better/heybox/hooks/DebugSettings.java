package com.better.heybox.hooks;

/** Release variant: no debug-only rows. */
public final class DebugSettings {

    private DebugSettings() {
    }

    static SettingsEntryHook.SwitchDef[] generalRows() {
        return new SettingsEntryHook.SwitchDef[0];
    }
}
