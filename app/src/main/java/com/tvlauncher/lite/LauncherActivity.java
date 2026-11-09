package com.tvlauncher.lite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.TextClock;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LauncherActivity extends Activity {
    private static final int TILE_WIDTH_DP = 160;
    private static final int TILE_HEIGHT_DP = 90;
    private static final int TILE_GAP_DP = 16;
    private static final int ICON_DP = 48;
    /** Android TV banners are authored at 320x180, so larger thumbnails add no detail. */
    private static final int MAX_THUMB_WIDTH = 320;
    /** Enough for the rows on screen plus one row either side. */
    private static final int CACHED_TILES = 30;
    private static final long RELOAD_DELAY_MS = 300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> inFlight = new HashSet<>();
    private final Runnable loadApps = this::loadAppsInBackground;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            thumbs.evictAll();
            scheduleReload();
        }
    };

    /** Every app that can have a tile, in display order. */
    private List<AppEntry> allApps = Collections.emptyList();
    /** The subset of {@link #allApps} on the home screen. */
    private List<AppEntry> apps = new ArrayList<>();
    private TilePrefs prefs;
    private LruCache<String, TileView.Thumb> thumbs;
    private HandlerThread workerThread;
    private Handler worker;
    private PackageManager pm;
    private TileView.Style tileStyle;
    private TileAdapter adapter;
    private GridView grid;
    private String movingKey;
    private float density;
    private int tileColor;
    private int thumbWidth;
    private int thumbHeight;
    private int maxColumns;
    private int columns = 1;
    private boolean thumbsReleased;
    private boolean confirmHeld;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        prefs = new TilePrefs(this);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        density = metrics.density;
        tileColor = getResources().getColor(R.color.bg1);
        int tileWidth = dp(TILE_WIDTH_DP);
        tileStyle = new TileView.Style(getResources(), tileWidth, dp(TILE_HEIGHT_DP));
        thumbWidth = Math.min(tileWidth, MAX_THUMB_WIDTH);
        thumbHeight = thumbWidth * TILE_HEIGHT_DP / TILE_WIDTH_DP;
        int gap = dp(TILE_GAP_DP);
        maxColumns = Math.max(1, (metrics.widthPixels - 2 * safeSide() + gap) / (tileWidth + gap));

        // 16 bits per pixel: banners are opaque once composited onto the tile colour.
        int thumbBytes = thumbWidth * thumbHeight * 2;
        thumbs = new LruCache<String, TileView.Thumb>(thumbBytes * CACHED_TILES) {
            @Override
            protected int sizeOf(String key, TileView.Thumb value) {
                return value.bitmap.getAllocationByteCount();
            }
        };

        workerThread = new HandlerThread("tile-loader", Process.THREAD_PRIORITY_DEFAULT + 5);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        setContentView(buildContent());

        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        registerReceiver(packageReceiver, packages);
        // Apps on adopted or USB storage can appear after the launcher has started.
        IntentFilter storage = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        storage.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        registerReceiver(packageReceiver, storage);

        worker.post(loadApps);
    }

    private View buildContent() {
        int side = safeSide();
        int gap = dp(TILE_GAP_DP);

        TextClock clock = new TextClock(this);
        clock.setTextColor(getResources().getColor(R.color.gray));
        clock.setTextSize(20);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clockParams.setMargins(side, dp(27), side, 0);

        adapter = new TileAdapter();
        grid = new GridView(this);
        grid.setColumnWidth(tileStyle.width);
        grid.setStretchMode(GridView.NO_STRETCH);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setPadding(side, dp(8), side, dp(8));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> open(apps.get(position)));
        // Wrapped height plus centre gravity keeps a short grid in the middle of the screen.
        FrameLayout.LayoutParams gridParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        gridParams.setMargins(0, dp(64), 0, dp(64));

        FrameLayout root = new FrameLayout(this);
        root.addView(grid, gridParams);
        root.addView(clock, clockParams);
        return root;
    }

    /** TV safe area: 5% of a 960dp-wide screen. */
    private int safeSide() {
        return dp(48);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private void scheduleReload() {
        // One app update sends several broadcasts; handle them as a single reload.
        worker.removeCallbacks(loadApps);
        worker.postDelayed(loadApps, RELOAD_DELAY_MS);
    }

    private void loadAppsInBackground() {
        List<AppEntry> loaded = queryApps();
        main.post(() -> showApps(loaded));
    }

    private List<AppEntry> queryApps() {
        ResolveInfo settings = pm.resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);

        Set<String> systemPhoneApps = new HashSet<>();
        LinkedHashMap<String, AppEntry> byPackage = new LinkedHashMap<>();
        addApps(byPackage, Intent.CATEGORY_LEANBACK_LAUNCHER, true, systemPhoneApps);
        // Sideloaded phone apps only declare LAUNCHER; the stock TV home hides them.
        addApps(byPackage, Intent.CATEGORY_LAUNCHER, false, systemPhoneApps);
        byPackage.remove(getPackageName());
        if (settings != null) {
            byPackage.remove(settings.activityInfo.packageName);
        }
        if (!prefs.hasHidden()) {
            // First run: firmware utilities that aren't built for TV are clutter by default.
            prefs.setHidden(systemPhoneApps);
        }

        List<AppEntry> result = new ArrayList<>(byPackage.values());
        CharSequence settingsLabel = settings == null ? null : settings.loadLabel(pm);
        result.add(new AppEntry(
                TextUtils.isEmpty(settingsLabel) ? getString(R.string.settings) : settingsLabel.toString(),
                null, false));

        Map<String, Integer> ranks = prefs.orderRanks();
        Collections.sort(result, (a, b) -> {
            int rankA = rank(a, ranks);
            int rankB = rank(b, ranks);
            return rankA != rankB ? Integer.compare(rankA, rankB) : a.label.compareToIgnoreCase(b.label);
        });
        return result;
    }

    /** Apps without a saved position sort after those with one, and Settings comes last by default. */
    private static int rank(AppEntry entry, Map<String, Integer> ranks) {
        Integer saved = ranks.get(entry.key);
        if (saved != null) {
            return saved;
        }
        return entry.isSettings() ? Integer.MAX_VALUE : Integer.MAX_VALUE - 1;
    }

    private void addApps(LinkedHashMap<String, AppEntry> byPackage, String category, boolean leanback,
            Set<String> systemPhoneApps) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            String packageName = info.activityInfo.packageName;
            if (byPackage.containsKey(packageName)) {
                continue;
            }
            byPackage.put(packageName, new AppEntry(info.loadLabel(pm).toString(),
                    new ComponentName(packageName, info.activityInfo.name), leanback));
            if (!leanback && (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                systemPhoneApps.add(packageName);
            }
        }
    }

    private void showApps(List<AppEntry> loaded) {
        finishMove();
        String selectedKey = selectedKey();
        allApps = loaded;
        refreshGrid(selectedKey, 0);
    }

    /** Rebuilds the visible tiles, focusing {@code focusKey} or else the tile at {@code fallbackIndex}. */
    private void refreshGrid(String focusKey, int fallbackIndex) {
        Set<String> hidden = prefs.hidden();
        apps = new ArrayList<>();
        for (AppEntry entry : allApps) {
            if (!hidden.contains(entry.key)) {
                apps.add(entry);
            }
        }
        // Only as many columns as tiles, so that a short row sits centred.
        columns = Math.max(1, Math.min(maxColumns, apps.size()));
        grid.setNumColumns(columns);
        adapter.notifyDataSetChanged();

        int index = indexOf(focusKey);
        grid.setSelection(index >= 0 ? index : Math.max(0, Math.min(fallbackIndex, apps.size() - 1)));
        grid.requestFocus();
    }

    private String selectedKey() {
        int position = grid.getSelectedItemPosition();
        return position >= 0 && position < apps.size() ? apps.get(position).key : null;
    }

    private int indexOf(String key) {
        for (int i = 0; key != null && i < apps.size(); i++) {
            if (apps.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private void showMenu(AppEntry entry) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.move));
        actions.add(() -> startMove(entry));
        if (!entry.isSettings()) {
            labels.add(getString(R.string.hide));
            actions.add(() -> hide(entry));
        }
        labels.add(getString(R.string.choose_apps));
        actions.add(this::showChooser);
        if (!entry.isSettings()) {
            labels.add(getString(R.string.app_info));
            actions.add(() -> showAppInfo(entry));
        }
        new AlertDialog.Builder(this, R.style.MenuDialog)
                .setTitle(entry.label)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private void hide(AppEntry entry) {
        int position = indexOf(entry.key);
        Set<String> hidden = prefs.hidden();
        hidden.add(entry.key);
        prefs.setHidden(hidden);
        refreshGrid(null, position);
    }

    /** A checklist of every app. Changes save as they're made, and Back closes it. */
    private void showChooser() {
        List<AppEntry> choices = new ArrayList<>();
        for (AppEntry entry : allApps) {
            if (!entry.isSettings()) {
                choices.add(entry);
            }
        }
        Set<String> hidden = prefs.hidden();
        String[] labels = new String[choices.size()];
        boolean[] shown = new boolean[choices.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = choices.get(i).label;
            shown[i] = !hidden.contains(choices.get(i).key);
        }
        String focusKey = selectedKey();
        new AlertDialog.Builder(this, R.style.MenuDialog)
                .setTitle(R.string.choose_apps)
                .setMultiChoiceItems(labels, shown, (dialog, which, isChecked) -> {
                    if (isChecked) {
                        hidden.remove(choices.get(which).key);
                    } else {
                        hidden.add(choices.get(which).key);
                    }
                    prefs.setHidden(new HashSet<>(hidden));
                })
                .setOnDismissListener(dialog -> refreshGrid(focusKey, 0))
                .show();
    }

    private void startMove(AppEntry entry) {
        movingKey = entry.key;
        adapter.notifyDataSetChanged();
    }

    private void finishMove() {
        if (movingKey == null) {
            return;
        }
        movingKey = null;
        // Hidden apps go after the visible ones; only the visible order is meaningful.
        List<AppEntry> ordered = new ArrayList<>(apps);
        for (AppEntry entry : allApps) {
            if (!apps.contains(entry)) {
                ordered.add(entry);
            }
        }
        allApps = ordered;
        prefs.setOrder(allApps);
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (movingKey != null) {
            return handleMoveKey(event);
        }
        return handleHoldConfirm(event) || super.dispatchKeyEvent(event);
    }

    /**
     * Opens the tile menu when OK is held. Uses the framework's long-press flag, and swallows the
     * rest of that key press so that releasing OK doesn't also open the app.
     */
    private boolean handleHoldConfirm(KeyEvent event) {
        if (!isConfirmKey(event.getKeyCode())) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                confirmHeld = false;
            } else if (event.isLongPress()) {
                int position = grid.getSelectedItemPosition();
                if (position >= 0 && position < apps.size()) {
                    confirmHeld = true;
                    grid.setPressed(false);
                    showMenu(apps.get(position));
                }
            }
        }
        return confirmHeld;
    }

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private boolean handleMoveKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        int from = grid.getSelectedItemPosition();
        int to;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                to = from - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                to = from + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                to = from - columns;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                to = from + columns;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
                finishMove();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
        if (from >= 0 && to >= 0 && to < apps.size()) {
            apps.add(to, apps.remove(from));
            adapter.notifyDataSetChanged();
            grid.setSelection(to);
        }
        return true;
    }

    private void requestThumb(AppEntry entry) {
        if (!inFlight.add(entry.key)) {
            return;
        }
        worker.post(() -> {
            TileView.Thumb thumb = renderThumb(entry);
            main.post(() -> {
                inFlight.remove(entry.key);
                if (thumb != null) {
                    thumbs.put(entry.key, thumb);
                    deliverThumb(entry.key, thumb);
                }
            });
        });
    }

    /** Copies a banner or icon into a small bitmap so the full-size original can be freed. */
    private TileView.Thumb renderThumb(AppEntry entry) {
        Drawable banner = null;
        Drawable icon = null;
        try {
            if (entry.isSettings()) {
                icon = getDrawable(R.drawable.ic_settings);
            } else {
                banner = pm.getActivityBanner(entry.component);
                if (banner == null) {
                    icon = pm.getActivityIcon(entry.component);
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }

        Bitmap thumb = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(thumb);
        canvas.drawColor(tileColor);
        if (banner != null) {
            banner.setBounds(0, 0, thumbWidth, thumbHeight);
            banner.draw(canvas);
        } else if (icon != null) {
            int size = thumbWidth * ICON_DP / TILE_WIDTH_DP;
            int left = (thumbWidth - size) / 2;
            // Sits above centre to leave room for the label underneath.
            int top = (thumbHeight - size) / 2 - thumbHeight / 12;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
        }
        canvas.setBitmap(null);
        return new TileView.Thumb(thumb, banner == null);
    }

    private void deliverThumb(String key, TileView.Thumb thumb) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            TileView tile = (TileView) grid.getChildAt(i);
            if (tile.entry() != null && key.equals(tile.entry().key)) {
                tile.setThumb(thumb);
            }
        }
    }

    private void open(AppEntry entry) {
        Intent intent;
        if (entry.isSettings()) {
            intent = new Intent(Settings.ACTION_SETTINGS);
        } else {
            intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(entry.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                    .setComponent(entry.component)
                    .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        start(intent);
    }

    private void showAppInfo(AppEntry entry) {
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", entry.key, null)));
    }

    private void start(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            overridePendingTransition(0, 0);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finishMove();
        // Home pressed while already home: go back to the start of the grid.
        if (hasWindowFocus() && !apps.isEmpty()) {
            grid.setSelection(0);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (thumbsReleased) {
            thumbsReleased = false;
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onStop() {
        finishMove();
        super.onStop();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // While an app or TV input is showing, give the thumbnails back to the system.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            thumbs.evictAll();
            thumbsReleased = true;
            for (int i = 0; i < grid.getChildCount(); i++) {
                ((TileView) grid.getChildAt(i)).setThumb(null);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The manifest handles config changes without a restart, so refresh labels for a new locale.
        scheduleReload();
    }

    @Override
    public void onBackPressed() {
        // Home is the bottom of the stack; there is nothing to go back to.
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        main.removeCallbacksAndMessages(null);
        workerThread.quit();
        super.onDestroy();
    }

    private final class TileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TileView tile = convertView != null
                    ? (TileView) convertView
                    : new TileView(LauncherActivity.this, tileStyle);
            AppEntry entry = apps.get(position);
            TileView.Thumb thumb = thumbs.get(entry.key);
            tile.bind(entry, thumb);
            tile.setLifted(entry.key.equals(movingKey));
            if (thumb == null) {
                requestThumb(entry);
            }
            return tile;
        }
    }
}
