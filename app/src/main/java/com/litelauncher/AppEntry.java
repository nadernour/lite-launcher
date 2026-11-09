package com.litelauncher;

import android.content.ComponentName;

/** One tile on the home screen. A null component is the Settings tile. */
final class AppEntry {
    private static final String SETTINGS_KEY = "settings";

    /** Package name, so saved choices survive an app renaming its launch activity. */
    final String key;
    final String label;
    final ComponentName component;
    final boolean leanback;

    AppEntry(String label, ComponentName component, boolean leanback) {
        this.key = component == null ? SETTINGS_KEY : component.getPackageName();
        this.label = label;
        this.component = component;
        this.leanback = leanback;
    }

    boolean isSettings() {
        return component == null;
    }
}
