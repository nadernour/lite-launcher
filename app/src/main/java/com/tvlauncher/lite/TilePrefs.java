package com.tvlauncher.lite;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Remembers which apps are hidden and the order of the tiles. */
final class TilePrefs {
    private static final String HIDDEN = "hidden";
    private static final String ORDER = "order";
    private static final String SEPARATOR = "\n";

    private final SharedPreferences prefs;

    TilePrefs(Context context) {
        prefs = context.getSharedPreferences("tiles", Context.MODE_PRIVATE);
    }

    boolean hasHidden() {
        return prefs.contains(HIDDEN);
    }

    Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet(HIDDEN, Collections.emptySet()));
    }

    void setHidden(Set<String> keys) {
        prefs.edit().putStringSet(HIDDEN, keys).apply();
    }

    /** Maps each saved tile key to its position. */
    Map<String, Integer> orderRanks() {
        Map<String, Integer> ranks = new HashMap<>();
        String saved = prefs.getString(ORDER, "");
        if (!saved.isEmpty()) {
            String[] keys = saved.split(SEPARATOR);
            for (int i = 0; i < keys.length; i++) {
                ranks.put(keys[i], i);
            }
        }
        return ranks;
    }

    void setOrder(List<AppEntry> entries) {
        List<String> keys = new ArrayList<>(entries.size());
        for (AppEntry entry : entries) {
            keys.add(entry.key);
        }
        prefs.edit().putString(ORDER, TextUtils.join(SEPARATOR, keys)).apply();
    }
}
