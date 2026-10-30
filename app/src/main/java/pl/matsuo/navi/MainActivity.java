package pl.matsuo.navi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A minimal, always-black home screen for an aftermarket car head unit.
 *
 * <p>Every view is built in code, which keeps the APK tiny and dependency-free. The window behind
 * the content is always pure black; the brightness slider only fades text and icons on top of it.
 *
 * <p>One activity holds all five screens, each built on demand and added to {@code content}: the
 * home screen, the weather screen, the trip screen, the app list and the settings panel. Back and
 * HOME both return to the home screen, and nothing ever finishes the activity.
 *
 * <p>The rules live outside this class, so they can be tested without a device: {@link Wx} decides
 * what counts as a warning, {@link Trip} records the route, {@link Geo} does the arithmetic and
 * {@link Format} turns numbers into text.
 */
public class MainActivity extends Activity {

    static final String PREFS = "navi";

    // Intent extras honoured only by a debuggable build; see applyTestOverrides.
    static final String EXTRA_SCREEN = "navi.screen";
    static final String EXTRA_WEATHER = "navi.weather";
    static final String EXTRA_SIZE = "navi.size";
    static final String EXTRA_HOME_POINT = "navi.home";

    // Palette: everything muted, no white anywhere.
    static final int BG = 0xFF000000;
    static final int TILE = 0xFF0C0C0C;
    static final int TILE_EDGE = 0xFF1C1C1C;
    static final int TEXT = 0xFFBDBDBD;
    static final int TEXT_DIM = 0xFF6E6E6E;
    static final int GLYPH = 0xFF9A9A9A;
    static final int WARN = 0xFFD4A600; // muted yellow, used for warnings

    static final long REFRESH_MS = 3 * 60 * 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private int H; // screen height in px; all sizes scale from it

    private FrameLayout root; // opaque black, never dimmed
    private FrameLayout content; // every screen lives here, this is what dims
    private View homeView;
    private View weatherView;
    private View appsView;
    private View lookView;
    private View statsView;
    private RouteView routeView;
    private TextView statTime;
    private TextView statDistance;
    private TextView statHome;
    private TextView routeEmpty;
    private TextView tempView;
    private WxIcon wxIcon;
    private Glyph warnIcon;
    private TextView warnText;

    // Layout variants: clock scale / scale of everything else (temperature, tiles).
    static final String[] SIZE_NAMES = {"100%", "80%", "67%", "50%"};
    static final float[] CLOCK_SCALE = {1f, 0.8f, 2f / 3f, 0.5f};
    static final float[] OTHER_SCALE = {1f, 0.9f, 0.8f, 0.7f};
    private float cs = 1f;
    private float os = 1f;

    private LinearLayout weatherContent;
    private JSONObject lastWeather;
    private String placeName = "";

    /**
     * Applies the selected UI language before any view is created. An empty setting follows the
     * head unit's own locale; "en" or "pl" force a language, which also covers the clock's date
     * line and app-name sorting.
     */
    @Override
    protected void attachBaseContext(Context base) {
        String lang = base.getSharedPreferences(PREFS, MODE_PRIVATE).getString("lang", "");
        if (!lang.isEmpty()) {
            Locale locale = Locale.forLanguageTag(lang);
            Locale.setDefault(locale);
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            cfg.setLocale(locale);
            base = base.createConfigurationContext(cfg);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        H = Math.min(dm.widthPixels, dm.heightPixels);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        // Dimming is applied to this inner layer only. Fading the root instead
        // would make its black translucent and let the unit's wallpaper show.
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));

        loadScale();
        homeView = buildHome();
        content.addView(homeView);
        setContentView(root);
        applyNightDim();

        try {
            String cached = prefs.getString("weather", null);
            if (cached != null) {
                lastWeather = new JSONObject(cached);
                placeName = prefs.getString("place", "");
                showTemp();
            }
        } catch (Exception ignored) {
            // A damaged cache is simply ignored; the next fetch replaces it.
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            List<String> wanted = new ArrayList<>();
            wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Only needed so the recording notification is visible.
                wanted.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            requestPermissions(wanted.toArray(new String[0]), 1);
        } else {
            TripService.start(this);
        }

        applyTestOverrides(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        refreshLocationAndWeather();
        updateStats();
        ui.removeCallbacks(periodic);
        ui.postDelayed(periodic, REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Nothing is fetched while another app is in front. Route recording keeps
        // running: that is the foreground service, not this activity.
        ui.removeCallbacks(periodic);
        ui.removeCallbacks(statsTicker);
    }

    private final Runnable periodic =
            new Runnable() {
                @Override
                public void run() {
                    refreshLocationAndWeather();
                    ui.postDelayed(this, REFRESH_MS);
                }
            };

    /** Keeps the trip figures moving while the stats screen is open. */
    private final Runnable statsTicker =
            new Runnable() {
                @Override
                public void run() {
                    if (statsView == null) {
                        return;
                    }
                    updateStats();
                    ui.postDelayed(this, 10_000L);
                }
            };

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        refreshLocationAndWeather();
        TripService.start(this);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (!applyTestOverrides(i)) {
            showHome(); // the HOME key always returns to the black screen
        }
    }

    @Override
    public void onBackPressed() {
        showHome(); // the launcher can never be "closed"
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideBars();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void loadScale() {
        int i = Math.max(0, Math.min(3, prefs.getInt("size", 0)));
        cs = CLOCK_SCALE[i];
        os = OTHER_SCALE[i];
    }

    private void rebuildHome() {
        loadScale();
        content.removeView(homeView);
        homeView = buildHome();
        homeView.setVisibility(View.INVISIBLE);
        content.addView(homeView, 0);
        if (lastWeather != null) {
            showTemp();
        }
    }

    // ───────────────────────────── HOME SCREEN ─────────────────────────────

    private View buildHome() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(px(0.05f), px(0.06f), px(0.05f), px(0.06f));

        // Upper half: clock and temperature side by side.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER);
        col.addView(top, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.CENTER);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(TEXT);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.30f * cs);
        clock.setIncludeFontPadding(false);
        // The column hands children MATCH_PARENT width, so each line needs its
        // own horizontal gravity to stay centred under the clock.
        clock.setGravity(Gravity.CENTER_HORIZONTAL);
        clockCol.addView(clock);
        TextClock date = new TextClock(this);
        String dateFormat = getString(R.string.clock_date_format);
        date.setFormat24Hour(dateFormat);
        date.setFormat12Hour(dateFormat);
        date.setTextColor(TEXT_DIM);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.045f * Math.max(cs, 0.8f));
        date.setGravity(Gravity.CENTER_HORIZONTAL);
        clockCol.addView(date);
        top.addView(clockCol);

        // Tapping the clock toggles the extra night dimming.
        clockCol.setOnClickListener(
                v -> {
                    prefs.edit().putBoolean("dim", !prefs.getBoolean("dim", false)).apply();
                    applyNightDim();
                });
        // Holding the clock opens the layout settings.
        clockCol.setOnLongClickListener(
                v -> {
                    showLook();
                    return true;
                });

        View sep = new View(this);
        sep.setBackgroundColor(0xFF262626);
        LinearLayout.LayoutParams sepLp =
                new LinearLayout.LayoutParams(Math.max(1, px(0.004f)), px(0.30f * (cs + os) / 2f));
        int sepM = px(0.09f * (cs + os) / 2f);
        sepLp.setMargins(sepM, 0, sepM, 0);
        top.addView(sep, sepLp);

        LinearLayout tempCol = new LinearLayout(this);
        tempCol.setOrientation(LinearLayout.HORIZONTAL);
        tempCol.setGravity(Gravity.CENTER_VERTICAL);
        wxIcon = new WxIcon(this, TEXT, BG);
        int ic = px(0.20f * os * 0.78f);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(ic, ic);
        icLp.setMargins(0, 0, px(0.035f * os), 0);
        tempCol.addView(wxIcon, icLp);
        tempView = new TextView(this);
        tempView.setText("--°");
        tempView.setTextColor(TEXT);
        tempView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        tempView.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.20f * os);
        tempView.setIncludeFontPadding(false);
        tempCol.addView(tempView);
        warnIcon = new Glyph(this, Glyph.WARN, WARN);
        int wi = px(0.20f * os * 0.62f);
        LinearLayout.LayoutParams wiLp = new LinearLayout.LayoutParams(wi, wi);
        wiLp.setMargins(px(0.03f * os), 0, 0, 0);
        warnIcon.setVisibility(View.GONE);
        tempCol.addView(warnIcon, wiLp);

        LinearLayout tempBox = new LinearLayout(this);
        tempBox.setOrientation(LinearLayout.VERTICAL);
        tempBox.setGravity(Gravity.CENTER);
        tempBox.addView(tempCol);
        warnText = new TextView(this);
        warnText.setTextColor(WARN);
        warnText.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.042f * Math.max(os, 0.8f));
        warnText.setGravity(Gravity.CENTER);
        warnText.setPadding(0, px(0.02f), 0, 0);
        warnText.setVisibility(View.GONE);
        tempBox.addView(warnText);
        tempBox.setOnClickListener(v -> showWeather());
        top.addView(tempBox);

        // Lower half: five large tiles.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(row, new LinearLayout.LayoutParams(-1, px(0.30f * os)));

        row.addView(tile(Glyph.NAV, getString(R.string.tile_nav), v -> openMaps()), tileLp());
        row.addView(
                tile(Glyph.MUSIC, getString(R.string.tile_music), v -> openSpotify()), tileLp());
        row.addView(tile(Glyph.ROUTE, getString(R.string.tile_trip), v -> showStats()), tileLp());
        row.addView(
                tile(Glyph.WEATHER, getString(R.string.tile_weather), v -> showWeather()),
                tileLp());
        row.addView(tile(Glyph.APPS, getString(R.string.tile_apps), v -> showApps()), tileLp());
        return col;
    }

    private LinearLayout.LayoutParams tileLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        int m = px(0.018f);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }

    private View tile(int glyph, String label, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(tileBg());
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setOnTouchListener(
                (v, e) -> {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            v.setAlpha(0.5f);
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.setAlpha(1f);
                            break;
                        default:
                            break;
                    }
                    return false;
                });

        Glyph g = new Glyph(this, glyph, GLYPH);
        t.addView(g, new LinearLayout.LayoutParams(px(0.12f * os), px(0.12f * os)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(TEXT_DIM);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.040f * Math.max(os, 0.8f));
        tv.setPadding(0, px(0.02f * os), 0, 0);
        tv.setSingleLine(true);
        // A vertical LinearLayout hands children MATCH_PARENT width, so without
        // this the label would sit against the left edge of the tile.
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        t.addView(tv);
        return t;
    }

    private GradientDrawable tileBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(TILE);
        d.setCornerRadius(H * 0.03f);
        d.setStroke(Math.max(1, px(0.003f)), TILE_EDGE);
        return d;
    }

    /** Dims only text and icons; the background stays pure black (#000). */
    private void applyNightDim() {
        float level = prefs.getInt("bright", 100) / 100f;
        boolean dim = prefs.getBoolean("dim", false);
        content.setAlpha(Math.max(0.12f, level * (dim ? 0.45f : 1f)));
    }

    private void showHome() {
        if (weatherView != null) {
            content.removeView(weatherView);
            weatherView = null;
        }
        if (appsView != null) {
            content.removeView(appsView);
            appsView = null;
        }
        if (lookView != null) {
            content.removeView(lookView);
            lookView = null;
        }
        if (statsView != null) {
            content.removeView(statsView);
            statsView = null;
        }
        homeView.setVisibility(View.VISIBLE);
    }

    // ────────────────────────── LAUNCHING OTHER APPS ──────────────────────────

    private void openMaps() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");
        if (i == null) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"));
        }
        safeStart(i);
    }

    private void openSpotify() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.spotify.music");
        if (i == null) {
            Toast.makeText(this, R.string.msg_spotify_missing, Toast.LENGTH_SHORT).show();
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.spotify.music"));
        }
        safeStart(i);
    }

    private void safeStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.msg_cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    // ───────────────────────────── ALL APPS ─────────────────────────────

    static class AppEntry {
        String label;
        ComponentName cn;
        Drawable icon;
        boolean isHome;
    }

    private void showApps() {
        if (appsView != null) {
            return;
        }
        PackageManager pm = getPackageManager();
        List<AppEntry> list = new ArrayList<>();

        // The head unit's own home screens come first: the escape hatch back to
        // Radio, DAB+ and the car settings.
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) {
                continue;
            }
            if (ri.activityInfo.packageName.equals("com.android.settings")) {
                continue; // FallbackHome, not a real launcher
            }
            AppEntry a = new AppEntry();
            a.label = getString(R.string.apps_original_home, ri.loadLabel(pm));
            a.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            a.icon = ri.loadIcon(pm);
            a.isHome = true;
            list.add(a);
        }

        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppEntry> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) {
                continue;
            }
            AppEntry a = new AppEntry();
            a.label = String.valueOf(ri.loadLabel(pm));
            a.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            a.icon = ri.loadIcon(pm);
            apps.add(a);
        }
        // Sorted with the collation rules of the active language.
        Collator collator = Collator.getInstance(Locale.getDefault());
        apps.sort((x, y) -> collator.compare(x.label, y.label));
        list.addAll(apps);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BG);
        box.setPadding(px(0.04f), px(0.03f), px(0.04f), 0);

        LinearLayout hdr = (LinearLayout) header(getString(R.string.apps_title));
        TextView look = new TextView(this);
        look.setText(R.string.look_title);
        look.setTextColor(TEXT);
        look.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.045f);
        look.setPadding(px(0.04f), px(0.02f), px(0.04f), px(0.02f));
        look.setBackground(tileBg());
        look.setOnClickListener(
                v -> {
                    content.removeView(appsView);
                    appsView = null;
                    showLook();
                });
        hdr.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        hdr.addView(look);
        box.addView(hdr);

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(px(0.27f));
        grid.setVerticalSpacing(px(0.03f));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(0));
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0.35f); // washed-out icon colours
        ColorMatrixColorFilter muted = new ColorMatrixColorFilter(cm);
        grid.setAdapter(
                new BaseAdapter() {
                    @Override
                    public int getCount() {
                        return list.size();
                    }

                    @Override
                    public Object getItem(int i) {
                        return list.get(i);
                    }

                    @Override
                    public long getItemId(int i) {
                        return i;
                    }

                    @Override
                    public View getView(int i, View cv, ViewGroup parent) {
                        AppEntry a = list.get(i);
                        LinearLayout cell = new LinearLayout(MainActivity.this);
                        cell.setOrientation(LinearLayout.VERTICAL);
                        cell.setGravity(Gravity.CENTER_HORIZONTAL);
                        ImageView iv = new ImageView(MainActivity.this);
                        iv.setImageDrawable(a.icon);
                        iv.setColorFilter(muted);
                        iv.setAlpha(0.8f);
                        cell.addView(iv, new LinearLayout.LayoutParams(px(0.13f), px(0.13f)));
                        TextView tv = new TextView(MainActivity.this);
                        tv.setText(a.label);
                        tv.setTextColor(a.isHome ? TEXT : TEXT_DIM);
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.034f);
                        tv.setGravity(Gravity.CENTER);
                        tv.setMaxLines(2);
                        tv.setPadding(0, px(0.01f), 0, 0);
                        cell.addView(tv);
                        return cell;
                    }
                });
        grid.setOnItemClickListener(
                (p, v, pos, id) -> {
                    AppEntry a = list.get(pos);
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(a.isHome ? Intent.CATEGORY_HOME : Intent.CATEGORY_LAUNCHER);
                    i.setComponent(a.cn);
                    i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    safeStart(i);
                });
        // Long press opens the app-info page.
        grid.setOnItemLongClickListener(
                (p, v, pos, id) -> {
                    safeStart(
                            new Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + list.get(pos).cn.getPackageName())));
                    return true;
                });
        box.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        appsView = box;
        content.addView(appsView);
        homeView.setVisibility(View.INVISIBLE);
    }

    private View header(String title) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, 0, 0, px(0.03f));
        TextView back = new TextView(this);
        back.setText(R.string.nav_back);
        back.setTextColor(TEXT);
        back.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        back.setPadding(px(0.03f), px(0.02f), px(0.05f), px(0.02f));
        back.setBackground(tileBg());
        back.setOnClickListener(v -> showHome());
        h.addView(back);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        t.setPadding(px(0.05f), 0, 0, 0);
        h.addView(t);
        return h;
    }

    // ───────────────────────────── TRIP STATS ─────────────────────────────

    /** Which trip the screen shows; null means the one being recorded. */
    private File shownTrip;

    private void showStats() {
        if (statsView != null) {
            return;
        }
        shownTrip = null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BG);
        box.setPadding(px(0.04f), px(0.03f), px(0.04f), px(0.03f));
        box.addView(header(getString(R.string.stats_title)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        routeView = new RouteView(this, GLYPH, WARN, Math.max(2, px(0.006f)));
        // Also what the on-device tests identify the route area by.
        routeView.setContentDescription(getString(R.string.stats_route_desc));
        // Before the first drive the area would otherwise just be black.
        routeEmpty = text(getString(R.string.stats_no_route), 0.04f, TEXT_DIM);
        routeEmpty.setGravity(Gravity.CENTER);
        FrameLayout routeBox = new FrameLayout(this);
        routeBox.addView(routeView, new FrameLayout.LayoutParams(-1, -1));
        routeBox.addView(routeEmpty, new FrameLayout.LayoutParams(-1, -1));
        row.addView(routeBox, new LinearLayout.LayoutParams(0, px(0.44f), 1f));

        LinearLayout figures = new LinearLayout(this);
        figures.setOrientation(LinearLayout.VERTICAL);
        figures.setGravity(Gravity.CENTER_VERTICAL);
        figures.setPadding(px(0.06f), 0, 0, 0);
        figures.addView(text(getString(R.string.stats_time), 0.032f, TEXT_DIM));
        statTime = text("--", 0.07f, TEXT);
        figures.addView(statTime);
        figures.addView(
                text(getString(R.string.stats_distance), 0.032f, TEXT_DIM), marginTop(px(0.02f)));
        statDistance = text("--", 0.07f, TEXT);
        figures.addView(statDistance);
        figures.addView(
                text(getString(R.string.stats_to_home), 0.032f, TEXT_DIM), marginTop(px(0.02f)));
        statHome = text("--", 0.07f, TEXT);
        figures.addView(statHome);
        row.addView(
                figures,
                new LinearLayout.LayoutParams(px(0.52f), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(row);

        box.addView(
                text(getString(R.string.stats_history), 0.032f, TEXT_DIM), marginTop(px(0.025f)));
        box.addView(historyStrip());

        statsView = box;
        content.addView(statsView);
        homeView.setVisibility(View.INVISIBLE);
        updateStats();
        ui.removeCallbacks(statsTicker);
        ui.postDelayed(statsTicker, 10_000L);
    }

    /** The trip being recorded plus the stored ones, newest first. */
    private View historyStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.addView(
                tripChip(null, getString(R.string.stats_current), Format.distance(Trip.meters)));
        for (File f : Trip.list(this)) {
            if (f.equals(Trip.currentFile())) {
                continue;
            }
            Trip.Summary s = Trip.summarize(f);
            if (s.points < 2) {
                continue; // a single fix has no shape worth showing
            }
            String when =
                    new SimpleDateFormat("d.MM HH:mm", Locale.getDefault())
                            .format(new Date(s.startedAt));
            strip.addView(
                    tripChip(
                            f,
                            when,
                            Format.distance(s.meters)
                                    + "  ·  "
                                    + Format.duration(s.endedAt - s.startedAt)));
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(strip);
        return scroll;
    }

    private View tripChip(File f, String title, String subtitle) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setBackground(tileBg());
        chip.setPadding(px(0.025f), px(0.015f), px(0.025f), px(0.015f));
        chip.addView(text(title, 0.035f, java.util.Objects.equals(f, shownTrip) ? TEXT : TEXT_DIM));
        chip.addView(text(subtitle, 0.03f, TEXT_DIM));
        chip.setOnClickListener(
                v -> {
                    shownTrip = f;
                    updateStats();
                    refreshHistoryStrip();
                });
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, px(0.01f), px(0.02f), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void refreshHistoryStrip() {
        if (statsView == null) {
            return;
        }
        LinearLayout box = (LinearLayout) statsView;
        box.removeViewAt(box.getChildCount() - 1);
        box.addView(historyStrip());
    }

    private void updateStats() {
        if (statsView == null) {
            return;
        }
        List<double[]> route;
        if (shownTrip == null) {
            statTime.setText(
                    Trip.startedAt == 0
                            ? "--"
                            : Format.duration(System.currentTimeMillis() - Trip.startedAt));
            statDistance.setText(Format.distance(Trip.meters));
            route = Trip.read(Trip.currentFile());
        } else {
            Trip.Summary s = Trip.summarize(shownTrip);
            statTime.setText(Format.duration(s.endedAt - s.startedAt));
            statDistance.setText(Format.distance(s.meters));
            route = Trip.read(shownTrip);
        }
        routeView.set(route);
        // One fix is a dot, not a route; say so instead of showing an empty box.
        routeEmpty.setVisibility(route.size() < 2 ? View.VISIBLE : View.GONE);
        // The "not set" note is a sentence, not a figure: it gets the small type.
        boolean homeSet = !Float.isNaN(prefs.getFloat("home_lat", Float.NaN));
        statHome.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * (homeSet ? 0.07f : 0.035f));
        statHome.setTextColor(homeSet ? TEXT : TEXT_DIM);
        statHome.setText(distanceToHome());
    }

    /** Straight-line distance to the configured home point. */
    private String distanceToHome() {
        float homeLat = prefs.getFloat("home_lat", Float.NaN);
        float homeLon = prefs.getFloat("home_lon", Float.NaN);
        if (Float.isNaN(homeLat)) {
            return getString(R.string.stats_no_home);
        }
        double lat = !Double.isNaN(Trip.lastLat) ? Trip.lastLat : prefs.getFloat("lat", Float.NaN);
        double lon = !Double.isNaN(Trip.lastLon) ? Trip.lastLon : prefs.getFloat("lon", Float.NaN);
        if (Double.isNaN(lat)) {
            return "--";
        }
        return Format.distance(Geo.distance(lat, lon, homeLat, homeLon));
    }

    // ───────────────────────────── HOME POINT ─────────────────────────────

    private void setHomeHere() {
        double lat = !Double.isNaN(Trip.lastLat) ? Trip.lastLat : prefs.getFloat("lat", Float.NaN);
        double lon = !Double.isNaN(Trip.lastLon) ? Trip.lastLon : prefs.getFloat("lon", Float.NaN);
        if (Double.isNaN(lat)) {
            Toast.makeText(this, R.string.home_no_fix, Toast.LENGTH_SHORT).show();
            return;
        }
        saveHome(lat, lon, null);
    }

    private void askHomeAddress() {
        final EditText input = new EditText(this);
        input.setHint(R.string.home_set_address);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle(R.string.home_section)
                .setView(input)
                .setPositiveButton(
                        android.R.string.ok, (d, which) -> geocodeHome(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void geocodeHome(String query) {
        if (query.trim().isEmpty()) {
            return;
        }
        final Locale geoLocale = Locale.getDefault();
        new Thread(
                        () -> {
                            Address found = null;
                            try {
                                @SuppressWarnings("deprecation")
                                List<Address> list =
                                        new Geocoder(this, geoLocale).getFromLocationName(query, 1);
                                if (list != null && !list.isEmpty()) {
                                    found = list.get(0);
                                }
                            } catch (Exception ignored) {
                                // No network or no geocoder backend.
                            }
                            final Address a = found;
                            ui.post(
                                    () -> {
                                        if (a == null) {
                                            Toast.makeText(
                                                            this,
                                                            R.string.home_not_found,
                                                            Toast.LENGTH_SHORT)
                                                    .show();
                                        } else {
                                            saveHome(a.getLatitude(), a.getLongitude(), query);
                                        }
                                    });
                        })
                .start();
    }

    private void saveHome(double lat, double lon, String name) {
        prefs.edit()
                .putFloat("home_lat", (float) lat)
                .putFloat("home_lon", (float) lon)
                .putString("home_name", name == null ? "" : name)
                .apply();
        Toast.makeText(this, R.string.home_saved, Toast.LENGTH_SHORT).show();
        showLook();
    }

    private String homeLabel() {
        float lat = prefs.getFloat("home_lat", Float.NaN);
        if (Float.isNaN(lat)) {
            return getString(R.string.stats_no_home);
        }
        String name = prefs.getString("home_name", "");
        if (!name.isEmpty()) {
            return name;
        }
        return String.format(
                Locale.getDefault(), "%.4f, %.4f", lat, prefs.getFloat("home_lon", Float.NaN));
    }

    // ───────────────────────────── SCREEN LAYOUT ─────────────────────────────

    private void showLook() {
        if (lookView != null) {
            content.removeView(lookView);
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BG);
        box.setPadding(px(0.04f), px(0.03f), px(0.04f), px(0.03f));
        box.addView(header(getString(R.string.look_title)));

        box.addView(text(getString(R.string.look_clock_size), 0.045f, TEXT_DIM));
        LinearLayout sizes = new LinearLayout(this);
        sizes.setOrientation(LinearLayout.HORIZONTAL);
        int cur = prefs.getInt("size", 0);
        for (int i = 0; i < SIZE_NAMES.length; i++) {
            final int idx = i;
            TextView o = new TextView(this);
            o.setText(SIZE_NAMES[i]);
            o.setGravity(Gravity.CENTER);
            o.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.06f);
            o.setTextColor(i == cur ? TEXT : TEXT_DIM);
            GradientDrawable bgd = tileBg();
            if (i == cur) {
                bgd.setStroke(Math.max(2, px(0.006f)), 0xFF7A7A7A);
            }
            o.setBackground(bgd);
            o.setOnClickListener(
                    v -> {
                        prefs.edit().putInt("size", idx).apply();
                        rebuildHome();
                        showLook(); // redraw so the selection moves
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(0.18f), 1f);
            lp.setMargins(px(0.015f), px(0.02f), px(0.015f), px(0.05f));
            sizes.addView(o, lp);
        }
        box.addView(sizes);

        box.addView(text(getString(R.string.look_brightness), 0.045f, TEXT_DIM));
        LinearLayout brRow = new LinearLayout(this);
        brRow.setOrientation(LinearLayout.HORIZONTAL);
        brRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView brVal = text(prefs.getInt("bright", 100) + "%", 0.05f, TEXT);
        SeekBar sb = new SeekBar(this);
        sb.setMax(85); // maps to 15..100 percent
        sb.setProgress(prefs.getInt("bright", 100) - 15);
        ColorStateList grey = ColorStateList.valueOf(0xFF8A8A8A);
        sb.setProgressTintList(grey);
        sb.setThumbTintList(grey);
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2A2A2A));
        sb.setMinimumHeight(px(0.1f));
        sb.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar s, int v, boolean user) {
                        int val = v + 15;
                        prefs.edit().putInt("bright", val).apply();
                        brVal.setText(val + "%");
                        applyNightDim();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar s) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar s) {}
                });
        brRow.addView(sb, new LinearLayout.LayoutParams(0, px(0.12f), 1f));
        brRow.addView(brVal, new LinearLayout.LayoutParams(px(0.16f), -2));
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, -2);
        brLp.setMargins(0, 0, 0, px(0.03f));
        box.addView(brRow, brLp);

        boolean dim = prefs.getBoolean("dim", false);
        TextView dimBtn = new TextView(this);
        dimBtn.setText(
                getString(
                        R.string.look_night,
                        getString(dim ? R.string.look_on : R.string.look_off)));
        dimBtn.setTextColor(TEXT);
        dimBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        dimBtn.setPadding(px(0.04f), px(0.03f), px(0.04f), px(0.03f));
        dimBtn.setBackground(tileBg());
        dimBtn.setOnClickListener(
                v -> {
                    prefs.edit().putBoolean("dim", !prefs.getBoolean("dim", false)).apply();
                    applyNightDim();
                    showLook();
                });
        box.addView(dimBtn, new LinearLayout.LayoutParams(-2, -2));

        box.addView(
                text(getString(R.string.home_section) + ":  " + homeLabel(), 0.045f, TEXT_DIM),
                marginTop(px(0.03f)));
        LinearLayout homePoint = new LinearLayout(this);
        homePoint.setOrientation(LinearLayout.HORIZONTAL);
        homePoint.addView(flatButton(getString(R.string.home_set_here), this::setHomeHere));
        homePoint.addView(flatButton(getString(R.string.home_set_address), this::askHomeAddress));
        box.addView(homePoint);

        box.addView(
                text(getString(R.string.look_language), 0.045f, TEXT_DIM), marginTop(px(0.03f)));
        LinearLayout langs = new LinearLayout(this);
        langs.setOrientation(LinearLayout.HORIZONTAL);
        String curLang = prefs.getString("lang", "");
        langs.addView(langButton("", R.string.lang_system, curLang));
        langs.addView(langButton("en", R.string.lang_en, curLang));
        langs.addView(langButton("pl", R.string.lang_pl, curLang));
        box.addView(langs);

        TextView homeBtn = new TextView(this);
        homeBtn.setText(R.string.home_default);
        homeBtn.setTextColor(TEXT);
        homeBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.045f);
        homeBtn.setPadding(px(0.04f), px(0.03f), px(0.04f), px(0.03f));
        homeBtn.setBackground(tileBg());
        homeBtn.setOnClickListener(v -> requestHomeRole());
        box.addView(homeBtn, marginTop(px(0.03f)));

        TextView pickerBtn = new TextView(this);
        pickerBtn.setText(R.string.home_picker);
        pickerBtn.setTextColor(TEXT_DIM);
        pickerBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.045f);
        pickerBtn.setPadding(px(0.04f), px(0.03f), px(0.04f), px(0.03f));
        pickerBtn.setBackground(tileBg());
        pickerBtn.setOnClickListener(v -> safeStart(new Intent(Settings.ACTION_HOME_SETTINGS)));
        box.addView(pickerBtn, marginTop(px(0.02f)));

        box.addView(text(getString(R.string.look_hint), 0.035f, TEXT_DIM), marginTop(px(0.04f)));

        // The settings do not fit on a 720 px screen, so this screen scrolls.
        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(BG);
        scroller.addView(box, new FrameLayout.LayoutParams(-1, -2));
        lookView = scroller;
        content.addView(lookView);
        homeView.setVisibility(View.INVISIBLE);
    }

    /**
     * Asks Android to make this app the default home screen. From Android 10 on that is a one-tap
     * system dialog; older versions only offer the picker in Settings.
     */
    private void requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    Toast.makeText(this, R.string.msg_already_default, Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 2);
                    return;
                } catch (Exception ignored) {
                    // Some head-unit ROMs ship no role dialog; fall through.
                }
            }
        }
        safeStart(new Intent(Settings.ACTION_HOME_SETTINGS));
    }

    /** A plain bordered button used by the settings screen. */
    private TextView flatButton(String label, Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.045f);
        t.setPadding(px(0.04f), px(0.025f), px(0.04f), px(0.025f));
        t.setBackground(tileBg());
        t.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, px(0.015f), px(0.02f), px(0.01f));
        t.setLayoutParams(lp);
        return t;
    }

    /** One language option; picking it restarts the activity in that language. */
    private TextView langButton(String lang, int labelRes, String current) {
        boolean selected = lang.equals(current);
        TextView t = new TextView(this);
        t.setText(labelRes);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(selected ? TEXT : TEXT_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        t.setPadding(px(0.04f), px(0.025f), px(0.04f), px(0.025f));
        GradientDrawable bgd = tileBg();
        if (selected) {
            bgd.setStroke(Math.max(2, px(0.006f)), 0xFF7A7A7A);
        }
        t.setBackground(bgd);
        t.setOnClickListener(
                v -> {
                    prefs.edit().putString("lang", lang).apply();
                    recreate(); // attachBaseContext applies the new locale
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(px(0.015f), px(0.02f), px(0.015f), px(0.03f));
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout.LayoutParams marginTop(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, m, 0, 0);
        return lp;
    }

    // ───────────────────────────── WEATHER ─────────────────────────────

    private void showWeather() {
        if (weatherView != null) {
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BG);
        box.setPadding(px(0.04f), px(0.03f), px(0.04f), px(0.03f));
        box.addView(header(getString(R.string.weather_title)));
        weatherContent = new LinearLayout(this);
        weatherContent.setOrientation(LinearLayout.VERTICAL);
        box.addView(weatherContent, new LinearLayout.LayoutParams(-1, 0, 1f));
        weatherView = box;
        content.addView(weatherView);
        homeView.setVisibility(View.INVISIBLE);
        fillWeather();
        refreshLocationAndWeather();
    }

    private void fillWeather() {
        if (weatherContent == null) {
            return;
        }
        weatherContent.removeAllViews();
        if (lastWeather == null) {
            weatherContent.addView(
                    text(
                            getString(
                                    hasLocation()
                                            ? R.string.weather_loading
                                            : R.string.weather_no_location),
                            0.045f,
                            TEXT_DIM));
            return;
        }
        try {
            JSONObject cur = lastWeather.getJSONObject("current");
            JSONObject hourly = lastWeather.getJSONObject("hourly");
            JSONObject daily = lastWeather.getJSONObject("daily");

            LinearLayout nowRow = new LinearLayout(this);
            nowRow.setOrientation(LinearLayout.HORIZONTAL);
            nowRow.setGravity(Gravity.CENTER_VERTICAL);
            WxIcon wi = new WxIcon(this, TEXT, BG);
            wi.set(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
            LinearLayout.LayoutParams wiLp = new LinearLayout.LayoutParams(px(0.15f), px(0.15f));
            wiLp.setMargins(0, 0, px(0.04f), 0);
            nowRow.addView(wi, wiLp);
            TextView big = text(Math.round(cur.getDouble("temperature_2m")) + "°", 0.18f, TEXT);
            big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            nowRow.addView(big);

            LinearLayout desc = new LinearLayout(this);
            desc.setOrientation(LinearLayout.VERTICAL);
            desc.setPadding(px(0.06f), 0, 0, 0);
            String description = describe(cur.getInt("weather_code"));
            desc.addView(
                    text(
                            description + (placeName.isEmpty() ? "" : "  ·  " + placeName),
                            0.05f,
                            TEXT));
            desc.addView(
                    text(
                            getString(
                                    R.string.weather_feels,
                                    Math.round(cur.getDouble("apparent_temperature")),
                                    Math.round(cur.getDouble("wind_speed_10m"))),
                            0.04f,
                            TEXT_DIM));
            nowRow.addView(desc);
            weatherContent.addView(nowRow);

            List<String> ws = warnings(lastWeather);
            if (!ws.isEmpty()) {
                LinearLayout warnRow = new LinearLayout(this);
                warnRow.setOrientation(LinearLayout.HORIZONTAL);
                warnRow.setGravity(Gravity.CENTER_VERTICAL);
                warnRow.addView(
                        new Glyph(this, Glyph.WARN, WARN),
                        new LinearLayout.LayoutParams(px(0.06f), px(0.06f)));
                warnRow.addView(
                        text(
                                "  " + getString(R.string.warn_prefix, String.join(", ", ws)),
                                0.045f,
                                WARN));
                weatherContent.addView(warnRow, marginTop(px(0.02f)));
            }

            // The coming hours.
            JSONArray ht = hourly.getJSONArray("time");
            JSONArray hv = hourly.getJSONArray("temperature_2m");
            JSONArray hc = hourly.getJSONArray("weather_code");
            JSONArray hp = hourly.getJSONArray("precipitation_probability");
            String nowIso = cur.getString("time").substring(0, 13);
            int start = 0;
            for (int i = 0; i < ht.length(); i++) {
                if (ht.getString(i).startsWith(nowIso)) {
                    start = i;
                    break;
                }
            }
            LinearLayout hours = new LinearLayout(this);
            hours.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = start + 1; i < Math.min(ht.length(), start + 13); i++) {
                LinearLayout c = new LinearLayout(this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setGravity(Gravity.CENTER_HORIZONTAL);
                c.setPadding(px(0.025f), px(0.015f), px(0.025f), px(0.015f));
                c.addView(text(ht.getString(i).substring(11, 13), 0.035f, TEXT_DIM));
                c.addView(text(Math.round(hv.getDouble(i)) + "°", 0.05f, TEXT));
                int pr = hp.isNull(i) ? 0 : hp.getInt(i);
                c.addView(text(pr >= 20 ? pr + "%" : shortDescribe(hc.getInt(i)), 0.03f, TEXT_DIM));
                hours.addView(c);
            }
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(hours);
            LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(-1, -2);
            hsLp.setMargins(0, px(0.03f), 0, px(0.03f));
            weatherContent.addView(hs, hsLp);

            // The following days.
            JSONArray dt = daily.getJSONArray("time");
            JSONArray dmax = daily.getJSONArray("temperature_2m_max");
            JSONArray dmin = daily.getJSONArray("temperature_2m_min");
            JSONArray dc = daily.getJSONArray("weather_code");
            JSONArray dp = daily.getJSONArray("precipitation_probability_max");
            LinearLayout days = new LinearLayout(this);
            days.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < dt.length(); i++) {
                String name = i == 0 ? getString(R.string.weather_today) : dayName(dt.getString(i));
                LinearLayout c = new LinearLayout(this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setGravity(Gravity.CENTER_HORIZONTAL);
                c.setBackground(tileBg());
                c.setPadding(0, px(0.02f), 0, px(0.02f));
                c.addView(text(name, 0.04f, TEXT_DIM));
                c.addView(
                        text(
                                Math.round(dmax.getDouble(i))
                                        + "° / "
                                        + Math.round(dmin.getDouble(i))
                                        + "°",
                                0.045f,
                                TEXT));
                int pr = dp.isNull(i) ? 0 : dp.getInt(i);
                c.addView(
                        text(
                                shortDescribe(dc.getInt(i)) + (pr >= 20 ? "  " + pr + "%" : ""),
                                0.032f,
                                TEXT_DIM));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(px(0.012f), 0, px(0.012f), 0);
                days.addView(c, lp);
            }
            weatherContent.addView(days);
        } catch (Exception e) {
            weatherContent.addView(text(getString(R.string.weather_error), 0.045f, TEXT_DIM));
        }
    }

    /** Short weekday name in the active language. */
    private String dayName(String isoDate) {
        try {
            return LocalDate.parse(isoDate)
                    .getDayOfWeek()
                    .getDisplayName(TextStyle.SHORT, Locale.getDefault());
        } catch (Exception e) {
            return isoDate.substring(5);
        }
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * size);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Puts the current reading on the home screen: temperature, icon, warning line. */
    private void showTemp() {
        try {
            JSONObject cur = lastWeather.getJSONObject("current");
            tempView.setText(Math.round(cur.getDouble("temperature_2m")) + "°");
            wxIcon.set(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
            boolean ice = Wx.iceRisk(lastWeather);
            List<String> ws = warnings(lastWeather);
            // Black ice is the only condition that recolours the temperature.
            tempView.setTextColor(ice ? WARN : TEXT);
            warnIcon.setVisibility(ws.isEmpty() ? View.GONE : View.VISIBLE);
            warnText.setText(String.join("  ·  ", ws));
            warnText.setVisibility(ws.isEmpty() ? View.GONE : View.VISIBLE);
        } catch (Exception ignored) {
            // Incomplete reading: the previous values stay on screen.
        }
        fillWeather();
    }

    /** Warning labels for the current reading, in the active language. */
    private List<String> warnings(JSONObject w) {
        List<String> out = new ArrayList<>();
        for (int res : Wx.warnings(w)) {
            out.add(getString(res));
        }
        return out;
    }

    /** WMO weather code as a sentence. */
    private String describe(int c) {
        int res = Wx.describe(c);
        return res == 0 ? "" : getString(res);
    }

    /** WMO weather code as one short word for the hourly and daily strips. */
    private String shortDescribe(int c) {
        int res = Wx.describeShort(c);
        return res == 0 ? "" : getString(res);
    }

    // ────────────────────────── LOCATION AND FETCHING ──────────────────────────

    private boolean hasLocation() {
        return prefs.contains("lat");
    }

    private LocationListener oneShot;

    @SuppressWarnings("MissingPermission")
    private void refreshLocationAndWeather() {
        boolean perm =
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        if (perm) {
            final LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String prov : lm.getProviders(true)) {
                try {
                    Location l = lm.getLastKnownLocation(prov);
                    if (l != null && (best == null || l.getTime() > best.getTime())) {
                        best = l;
                    }
                } catch (Exception ignored) {
                    // A provider can refuse; the others still count.
                }
            }
            if (best != null) {
                saveLocation(best);
            }
            if (oneShot == null) {
                // An anonymous class, not a lambda: on API 26 LocationListener has
                // no default methods yet, so a lambda would fail at runtime.
                oneShot =
                        new LocationListener() {
                            @Override
                            public void onLocationChanged(Location l) {
                                saveLocation(l);
                                try {
                                    lm.removeUpdates(this);
                                } catch (Exception ignored) {
                                    // Already removed.
                                }
                                oneShot = null;
                                fetchWeather();
                            }

                            @Override
                            public void onProviderEnabled(String p) {}

                            @Override
                            public void onProviderDisabled(String p) {}

                            @Override
                            public void onStatusChanged(String p, int s, Bundle b) {}
                        };
                for (String prov :
                        new String[] {
                            LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER
                        }) {
                    try {
                        if (lm.isProviderEnabled(prov)) {
                            lm.requestLocationUpdates(prov, 0, 0, oneShot, Looper.getMainLooper());
                        }
                    } catch (Exception ignored) {
                        // Provider unavailable on this unit.
                    }
                }
            }
        }
        fetchWeather();
    }

    private void saveLocation(Location l) {
        prefs.edit()
                .putFloat("lat", (float) l.getLatitude())
                .putFloat("lon", (float) l.getLongitude())
                .apply();
    }

    private volatile boolean fetching = false;

    /**
     * Set by the debug hook. A canned reading is there to make a screen reproducible, so the live
     * fetch must not land on top of it a second later.
     */
    private boolean weatherPinned;

    private void fetchWeather() {
        if (weatherPinned) {
            return;
        }
        if (!hasLocation() || fetching) {
            if (!hasLocation()) {
                fillWeather();
            }
            return;
        }
        fetching = true;
        final float lat = prefs.getFloat("lat", 0);
        final float lon = prefs.getFloat("lon", 0);
        final Locale geoLocale = Locale.getDefault();
        new Thread(
                        () -> {
                            try {
                                // Open-Meteo: free, no API key, CC BY 4.0.
                                String url =
                                        String.format(
                                                Locale.US,
                                                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                                                    + "&current=temperature_2m,apparent_temperature,weather_code,"
                                                    + "wind_speed_10m,wind_gusts_10m,is_day"
                                                    + "&hourly=temperature_2m,weather_code,precipitation_probability,"
                                                    + "wind_gusts_10m,visibility,snowfall"
                                                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                                                    + "precipitation_probability_max"
                                                    + "&timezone=auto&forecast_days=6",
                                                lat,
                                                lon);
                                HttpURLConnection c =
                                        (HttpURLConnection) new URL(url).openConnection();
                                c.setConnectTimeout(10000);
                                c.setReadTimeout(10000);
                                StringBuilder sb = new StringBuilder();
                                try (BufferedReader r =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        c.getInputStream(), "UTF-8"))) {
                                    String line;
                                    while ((line = r.readLine()) != null) {
                                        sb.append(line);
                                    }
                                }
                                JSONObject j = new JSONObject(sb.toString());

                                String place = placeName;
                                try {
                                    if (Geocoder.isPresent()) {
                                        @SuppressWarnings("deprecation")
                                        List<Address> a =
                                                new Geocoder(this, geoLocale)
                                                        .getFromLocation(lat, lon, 1);
                                        if (a != null
                                                && !a.isEmpty()
                                                && a.get(0).getLocality() != null) {
                                            place = a.get(0).getLocality();
                                        }
                                    }
                                } catch (Exception ignored) {
                                    // The place name is not critical.
                                }
                                final String fp = place;
                                prefs.edit()
                                        .putString("weather", j.toString())
                                        .putString("place", fp)
                                        .apply();
                                ui.post(
                                        () -> {
                                            lastWeather = j;
                                            placeName = fp;
                                            showTemp();
                                        });
                            } catch (Exception e) {
                                // Offline: the last stored reading stays on screen.
                            } finally {
                                fetching = false;
                            }
                        })
                .start();
    }

    private int px(float fractionOfHeight) {
        return Math.round(H * fractionOfHeight);
    }

    // ─────────────────────────── TEST HOOKS (DEBUG ONLY) ───────────────────────────

    /**
     * Lets a test or the screenshot script put the launcher into an exact state: which screen is
     * open, which canned weather reading is shown, which size variant and which home point are
     * stored.
     *
     * <p>Every extra is ignored unless the APK is debuggable, so a release build cannot be steered
     * from outside. Weather readings are read from {@code <externalFilesDir>/wx/<name>.json}, a
     * folder adb can write to without any storage permission.
     *
     * @return true when the intent carried an override, so the caller should not fall back to
     *     showing the home screen
     */
    boolean applyTestOverrides(Intent i) {
        if (i == null || !debuggable() || i.getExtras() == null) {
            return false;
        }
        boolean handled = false;

        if (i.hasExtra(EXTRA_HOME_POINT)) {
            String[] parts = i.getStringExtra(EXTRA_HOME_POINT).split(",", 3);
            if (parts.length >= 2) {
                prefs.edit()
                        .putFloat("home_lat", Float.parseFloat(parts[0]))
                        .putFloat("home_lon", Float.parseFloat(parts[1]))
                        .putString("home_name", parts.length > 2 ? parts[2] : "")
                        .apply();
                handled = true;
            }
        }
        if (i.hasExtra(EXTRA_SIZE)) {
            prefs.edit().putInt("size", i.getIntExtra(EXTRA_SIZE, 0)).apply();
            rebuildHome();
            handled = true;
        }
        if (i.hasExtra(EXTRA_WEATHER)) {
            handled |= loadCannedWeather(i.getStringExtra(EXTRA_WEATHER));
        }
        if (i.hasExtra(EXTRA_SCREEN)) {
            String screen = i.getStringExtra(EXTRA_SCREEN);
            if ("weather".equals(screen)) {
                showWeather();
            } else if ("trip".equals(screen)) {
                showStats();
            } else if ("apps".equals(screen)) {
                showApps();
            } else if ("look".equals(screen)) {
                showLook();
            } else {
                showHome();
            }
            handled = true;
        }
        return handled;
    }

    /** True for a debug build; the test hooks do nothing in a release APK. */
    private boolean debuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** Replaces the live reading with a stored one, so warnings are reproducible. */
    private boolean loadCannedWeather(String name) {
        File f = new File(new File(getExternalFilesDir(null), "wx"), name + ".json");
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            lastWeather = new JSONObject(sb.toString());
            placeName = lastWeather.optString("place", placeName);
            weatherPinned = true;
            showTemp();
            return true;
        } catch (Exception ignored) {
            return false; // no such reading: leave the live one alone
        }
    }
}
