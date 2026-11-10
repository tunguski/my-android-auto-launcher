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
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.net.ConnectivityManager;
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
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
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
 * the content is always pure black; the brightness setting only fades text and icons on top of it.
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

    /**
     * Minutes to date the pinned reading back by, so that a test can see a stale one.
     *
     * <p>The alternative is a test that waits three hours.
     */
    static final String EXTRA_WEATHER_AGE_MIN = "navi.weather.age";

    static final String EXTRA_SIZE = "navi.size";
    static final String EXTRA_HOME_POINT = "navi.home";
    static final String EXTRA_NO_RECORDING = "navi.norecording";

    // Palette: everything muted, no white anywhere.
    static final int BG = 0xFF000000;
    static final int TILE = 0xFF0C0C0C;
    static final int TILE_EDGE = 0xFF1C1C1C;
    static final int TEXT = 0xFFBDBDBD;
    static final int TEXT_DIM = 0xFF6E6E6E;
    static final int GLYPH = 0xFF9A9A9A;
    static final int WARN = 0xFFD4A600; // muted yellow, used for warnings
    static final int OK = 0xFF4E9A4E; // muted green, used for "nothing to do here"

    static final long REFRESH_MS = 3 * 60 * 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private int H; // screen height in px; all sizes scale from it

    /** True when a test asked this run not to record; see {@link #applyTestOverrides}. */
    private boolean noRecording;

    private FrameLayout root; // opaque black, never dimmed
    private FrameLayout content; // every screen lives here, this is what dims
    private View homeView;
    private View weatherView;
    private View appsView;
    private View lookView;
    private View statsView;
    private View historyView;
    private View picView;
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

    /** The brightness steps offered, as percentages. */
    static final int[] BRIGHTNESS = {20, 40, 60, 80, 100};

    /**
     * The offered step nearest to what is stored.
     *
     * <p>Brightness was a slider until 2.5 and could hold any value from 15 to 100, so an upgraded
     * install arrives with something that is not on the list - and a row where nothing is picked
     * out would say the screen has no brightness at all.
     */
    static int nearestBrightness(int stored) {
        int best = BRIGHTNESS[0];
        for (int level : BRIGHTNESS) {
            if (Math.abs(level - stored) < Math.abs(best - stored)) {
                best = level;
            }
        }
        return best;
    }

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
                // Before the first fetch of this run has a chance to answer: a
                // unit switched on at dusk should come up dimmed, not come up
                // bright and correct itself once the network does.
                applyAutoNight();
            }
        } catch (Exception ignored) {
            // A damaged cache is simply ignored; the next fetch replaces it.
        }

        // Read before recording could start, so a test that asks for no recording
        // never gets a fix in the window between starting the service and the
        // overrides below being applied.
        noRecording =
                debuggable() && getIntent() != null && getIntent().hasExtra(EXTRA_NO_RECORDING);

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
            startRecording();
        }

        applyTestOverrides(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        refreshLocationAndWeather();
        // From what is already stored, without waiting for the fetch above to
        // come back - and again when it does.
        applyAutoNight();
        updateStats();
        ui.removeCallbacks(periodic);
        ui.postDelayed(periodic, REFRESH_MS);
        // And the trip screen's own ticker, if that is the screen being come
        // back to. onPause stops both; forgetting this one left the panel
        // standing still - showing the drive as it was when something else took
        // the screen, while the drive itself went on being recorded - until the
        // driver went out to the home screen and back in to have it rebuilt.
        keepStatsMoving();
        // Once a day at most, and each version is asked about once, so a release
        // the driver said no to does not come back tomorrow.
        if (Updater.autoCheckEnabled(prefs)
                && Updater.dueForCheck(prefs, System.currentTimeMillis())) {
            checkForUpdate(false);
        }
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
                    // Every three minutes, which is what makes the crossing
                    // land within three minutes of the sun itself. Offline it
                    // still runs, off the stored reading, until that reading is
                    // a week old.
                    applyAutoNight();
                    // And redrawn even when the fetch above brought nothing
                    // back, because a reading going stale is a change on screen
                    // that no fetch reports: without this, a unit that loses the
                    // network at noon would still be showing noon's warnings at
                    // four, having never been asked again.
                    showTemp();
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
        startRecording();
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

    /**
     * A swipe to the right anywhere on a panel goes back to the black screen.
     *
     * <p>The same thing the Back arrow in the corner does, reachable without aiming: on a head unit
     * the corner of the screen is the hardest place to hit while moving, and the ROM may have no
     * navigation bar at all.
     *
     * <p>In {@code dispatchTouchEvent} rather than a touch listener on the panel, because the
     * panels are full of children that claim the touch stream - a scrolling settings list, the app
     * grid - and a listener on the parent would never see the gesture. This sees every event first
     * and still hands it on, so nothing a panel does is disturbed.
     *
     * <p>It used to be off on the trip screen, because the strip of past drives along the foot of
     * that screen scrolls sideways and the two gestures are the same movement. That cost the whole
     * screen its way back for the sake of one row: the strip holds four chips and almost never has
     * anything to scroll. So the strip keeps the sideways drags that begin inside it - that is what
     * {@code draggedSideways} is for - and everywhere else on the screen the swipe works as it does
     * on every other panel.
     *
     * <p>Decided on release by how far the finger went, not by how fast. It used to ask Android's
     * gesture detector for a fling, which is a question about speed: an unhurried swipe - the kind
     * a driver makes while watching the road - travelled the whole screen and did nothing, and the
     * test for it failed about one run in ten for the same reason. Distance is what the driver
     * means, and it is the same every time.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (appsView == null
                && weatherView == null
                && lookView == null
                && historyView == null
                && statsView == null) {
            return super.dispatchTouchEvent(e);
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sideways = false;
                swipeBlocked = startedOn(draggedSideways, e);
                downX = e.getX();
                downY = e.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (swipeBlocked) {
                    break;
                }
                if (!sideways && isSideways(e)) {
                    sideways = true;
                    // Tell the children this touch is over. Without it a drag
                    // across the app grid never exceeds the grid's own vertical
                    // slop, so the cell under the finger stays pressed and fires
                    // on release: a sideways brush of the screen launches
                    // whatever app it happened to pass over.
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (sideways && wentBackwards(e)) {
                    backFromPanel();
                    return true;
                }
                break;
            default:
                break;
        }
        // Once it is a swipe it stays a swipe, in either direction. Only one of
        // them goes back, but neither may land as a tap.
        return sideways || super.dispatchTouchEvent(e);
    }

    /** Far enough to the right, and more sideways than up and down. */
    private boolean wentBackwards(MotionEvent e) {
        float dx = e.getX() - downX;
        return dx > H * SWIPE_MIN_W && dx > 2 * Math.abs(e.getY() - downY);
    }

    private boolean isSideways(MotionEvent e) {
        float dx = Math.abs(e.getX() - downX);
        return dx > H * SWIPE_SLOP && dx > 2 * Math.abs(e.getY() - downY);
    }

    /** How far sideways a finger travels before it stops being a tap. */
    private static final float SWIPE_SLOP = 0.04f;

    private boolean sideways;
    private boolean swipeBlocked;
    private float downX;
    private float downY;

    /** A child of the open panel that owns sideways drags for itself, or null. */
    private View draggedSideways;

    /** Whether a touch began inside the given view. */
    private boolean startedOn(View v, MotionEvent e) {
        if (v == null || v.getWindowToken() == null) {
            return false;
        }
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        float x = e.getRawX();
        float y = e.getRawY();
        return x >= at[0] && x <= at[0] + v.getWidth() && y >= at[1] && y <= at[1] + v.getHeight();
    }

    /** How far a swipe has to travel to count, as a fraction of the screen short side. */
    private static final float SWIPE_MIN_W = 0.12f;

    /**
     * Where going back from a panel lands.
     *
     * <p>The black screen, except from the history table: that is reached from the trip screen and
     * nowhere else, so going back from it means going back one step rather than all the way out.
     * Both the swipe and the Back arrow on that screen come here, because two ways back that land
     * in different places is worse than either one of them.
     */
    private void backFromPanel() {
        boolean fromHistory = historyView != null;
        showHome();
        if (fromHistory) {
            showStats();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideBars();
        }
    }

    /**
     * Asks for the whole screen: no status bar, no navigation bar, and no reappearance until the
     * user swipes a bar in deliberately.
     *
     * <p>This only governs the bars Android itself draws. A head unit whose ROM paints its own
     * toolbar as a system overlay keeps painting it; that has to be turned off on the unit.
     */
    @SuppressWarnings("deprecation")
    private void hideBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) {
                bars.hide(WindowInsets.Type.systemBars());
                bars.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                return;
            }
        }
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
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

        // Tapping the clock toggles the extra night dimming, and that choice
        // stands against the automatic switch until the sun next crosses.
        clockCol.setOnClickListener(v -> toggleNightDimByHand());
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

    /** Whether the screen follows the sun by itself. On unless it has been turned off. */
    static final String PREF_AUTO_NIGHT = "autonight";

    /** When the night dimming was last set by hand, so that the choice can be left standing. */
    static final String PREF_DIM_BY_HAND = "dim_by_hand";

    /** When the last weather reading that actually arrived did so. */
    static final String PREF_WEATHER_AT = "weather_at";

    /**
     * Sets the night dimming from the sun, unless the driver has had their say more recently.
     *
     * <p>Only ever from stored sun times, never from a clock alone: dusk is two hours apart between
     * June and December at this latitude, and a fixed hour would be wrong for most of the year.
     * When the reading carries no sun times, or is too old to trust, nothing happens at all and the
     * screen stays however it was last set - which is also what every canned reading the tests pin
     * screens with produces, so a test's screen is never dimmed out from under it.
     *
     * <p>A tap on the clock wins until the sun next crosses. Without that it would be undone within
     * three minutes by the periodic refresh, which is the whole reason the time of the tap is
     * recorded rather than just its outcome: the question is not whether the driver disagreed, but
     * whether anything has changed since they did.
     */
    private void applyAutoNight() {
        if (!prefs.getBoolean(PREF_AUTO_NIGHT, true) || lastWeather == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (Sun.tooOldToUse(prefs.getLong(PREF_WEATHER_AT, 0L), now)) {
            return;
        }
        Sun.Phase phase = Sun.phase(lastWeather, now);
        if (phase == Sun.Phase.UNKNOWN) {
            return;
        }
        if (Sun.lastChangeAtOrBefore(lastWeather, now) <= prefs.getLong(PREF_DIM_BY_HAND, 0L)) {
            return;
        }
        boolean night = phase == Sun.Phase.NIGHT;
        if (night != prefs.getBoolean("dim", false)) {
            prefs.edit().putBoolean("dim", night).apply();
            applyNightDim();
        }
    }

    /**
     * Turns the night dimming over by hand, and remembers when.
     *
     * <p>Both ways of doing it - the clock on the home screen and the button in the settings - go
     * through here, so that neither can be the one that forgets to stop the automatic switch from
     * answering back.
     */
    private void toggleNightDimByHand() {
        prefs.edit()
                .putBoolean("dim", !prefs.getBoolean("dim", false))
                .putLong(PREF_DIM_BY_HAND, System.currentTimeMillis())
                .apply();
        applyNightDim();
    }

    /** Dims only text and icons; the background stays pure black (#000). */
    private void applyNightDim() {
        // Through the same snapping the panel highlights by, so that an install
        // upgraded from the old slider is as bright as the picked-out step says
        // it is rather than five percent off it.
        float level = nearestBrightness(prefs.getInt("bright", 100)) / 100f;
        boolean dim = prefs.getBoolean("dim", false);
        content.setAlpha(Math.max(0.12f, level * (dim ? 0.45f : 1f)));
    }

    private void showHome() {
        // Whatever claimed sideways drags belonged to a panel that is going away.
        draggedSideways = null;
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
        if (historyView != null) {
            content.removeView(historyView);
            historyView = null;
        }
        if (picView != null) {
            content.removeView(picView);
            picView = null;
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

    /** Columns in the app grid, and the icon size that a column of that width allows. */
    private static final int APP_COLUMNS = 5;

    private static final float APP_ICON = 0.18f;

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
        // The handful wanted while driving first, then the rest sorted with the
        // collation rules of the active language.
        Collator collator = Collator.getInstance(Locale.getDefault());
        apps.sort(
                (x, y) -> {
                    int byRank =
                            Integer.compare(
                                    AppOrder.rank(x.cn.getPackageName()),
                                    AppOrder.rank(y.cn.getPackageName()));
                    return byRank != 0 ? byRank : collator.compare(x.label, y.label);
                });
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
        // Five fixed columns rather than as many as fit: a wider cell means a
        // bigger icon, and an icon aimed at from a moving car is worth more than
        // the extra app that would fit in a sixth column.
        grid.setNumColumns(APP_COLUMNS);
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
                        cell.addView(iv, new LinearLayout.LayoutParams(px(APP_ICON), px(APP_ICON)));
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
        return header(title, this::showHome);
    }

    /** A header whose Back arrow goes somewhere other than the black screen. */
    private View header(String title, Runnable onBack) {
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
        back.setOnClickListener(v -> onBack.run());
        h.addView(back);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        t.setPadding(px(0.05f), 0, 0, 0);
        h.addView(t);
        return h;
    }

    /**
     * The same header with the time in its far corner.
     *
     * <p>For the trip screen, which is the one a driver reads while the clock on the home screen is
     * not in front of them - how long the drive has taken is half an answer without what time it is
     * now.
     *
     * <p>A TextClock rather than a TextView and a ticker: it keeps itself right, including across a
     * change of time zone, which a unit driven west of here will meet. The same {@code HH:mm} for
     * both formats as the home screen, so the two clocks can never disagree about whether this unit
     * tells the time in twenty-four hours.
     */
    private View headerWithClock(String title) {
        LinearLayout h = (LinearLayout) header(title);
        // Takes the width that is left, which is what puts the clock against
        // the right edge without measuring anything.
        View gap = new View(this);
        h.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        TextClock now = new TextClock(this);
        now.setFormat24Hour("HH:mm");
        now.setFormat12Hour("HH:mm");
        now.setTextColor(TEXT);
        now.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        now.setPadding(0, 0, px(0.01f), 0);
        h.addView(now);
        return h;
    }

    // ───────────────────────────── TRIP STATS ─────────────────────────────

    /** Which trip the screen shows; null means the one being recorded. */
    /** The trip whose route is on screen, or null for the drive in progress. */
    private Long shownTrip;

    /** How many past trips the history table lists. */
    private static final int HISTORY_LIMIT = 50;

    /** How many of them the strip on the trip screen offers; the rest are in the table. */
    private static final int STRIP_LIMIT = 3;

    private void showStats() {
        if (statsView != null) {
            return;
        }
        shownTrip = null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BG);
        box.setPadding(px(0.04f), px(0.03f), px(0.04f), px(0.03f));
        box.addView(headerWithClock(getString(R.string.stats_title)));

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

        // Everything above keeps its place and the strip sits on the floor of the
        // screen, where the hand reaches without the eyes leaving the road.
        box.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));

        box.addView(
                text(getString(R.string.stats_history), 0.032f, TEXT_DIM), marginTop(px(0.025f)));

        // The strip along the foot of the screen, and the way to the rest of the
        // trips in the corner furthest from the driver's line of sight.
        TextView all = new TextView(this);
        all.setText(R.string.hist_title);
        all.setTextColor(TEXT);
        all.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.045f);
        all.setPadding(px(0.04f), px(0.025f), px(0.04f), px(0.025f));
        all.setBackground(tileBg());
        all.setOnClickListener(v -> showHistory());

        stripRow = new LinearLayout(this);
        stripRow.setOrientation(LinearLayout.HORIZONTAL);
        stripRow.setGravity(Gravity.BOTTOM);
        stripRow.addView(historyStrip(), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(-2, -2);
        allLp.setMargins(px(0.02f), 0, 0, 0);
        stripRow.addView(all, allLp);
        box.addView(stripRow);
        // The one thing on this screen that owns a sideways drag for itself.
        draggedSideways = stripRow;

        statsView = box;
        content.addView(statsView);
        homeView.setVisibility(View.INVISIBLE);
        updateStats();
        keepStatsMoving();
    }

    /**
     * Starts the trip screen's ticker, if that screen is up.
     *
     * <p>Called from both places that can leave the screen showing: building it, and coming back to
     * it after something else has had the display. It was only called from the first for a long
     * while, which is a mistake the one caller cannot see - hence one method, named for the
     * guarantee rather than for the posting.
     */
    private void keepStatsMoving() {
        if (statsView == null) {
            return;
        }
        ui.removeCallbacks(statsTicker);
        ui.postDelayed(statsTicker, 10_000L);
    }

    /** The trip being recorded plus the stored ones, newest first. */
    /**
     * Every finished drive as a table.
     *
     * <p>The strip on the trip screen is for the drive just made; this is for looking something up,
     * so it is a list with columns and it scrolls. A row opens that drive on the trip screen, which
     * is the only reason anyone reads a table of drives.
     *
     * <p>The drive in progress is not here: {@code recent} returns finished drives, and a drive
     * still being recorded has no duration to put in a column yet.
     */
    private void showHistory() {
        if (historyView != null) {
            return;
        }
        if (statsView != null) {
            content.removeView(statsView);
            statsView = null;
            ui.removeCallbacks(statsTicker);
            draggedSideways = null; // the strip went with it
        }
        List<TripDb.Row> rows = TripDb.of(this).recent(HISTORY_LIMIT);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BG);
        box.setPadding(px(0.04f), px(0.03f), px(0.04f), px(0.03f));

        LinearLayout hdr =
                (LinearLayout) header(getString(R.string.hist_title), this::backFromPanel);
        hdr.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        hdr.addView(text(getString(R.string.hist_count, rows.size()), 0.04f, TEXT_DIM));
        box.addView(hdr);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(px(0.02f), 0, px(0.02f), px(0.012f));
        columns.addView(cell(getString(R.string.hist_date), TEXT_DIM, 2f));
        columns.addView(cell(getString(R.string.hist_start), TEXT_DIM, 1f));
        columns.addView(cell(getString(R.string.stats_time), TEXT_DIM, 1f));
        columns.addView(cell(getString(R.string.stats_distance), TEXT_DIM, 1f));
        box.addView(columns, new LinearLayout.LayoutParams(-1, -2));

        View rule = new View(this);
        rule.setBackgroundColor(TILE_EDGE);
        box.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, px(0.004f))));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (rows.isEmpty()) {
            list.addView(text(getString(R.string.hist_none), 0.04f, TEXT_DIM));
        }
        SimpleDateFormat day = new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault());
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (TripDb.Row r : rows) {
            Date started = new Date(r.startedAt * 1000L);
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            // No background and no rules between the rows: a table whose columns
            // line up needs no borders to be read as one. The padding here is the
            // padding of the header above, or the columns would not line up.
            line.setPadding(px(0.02f), px(0.018f), px(0.02f), px(0.018f));
            line.addView(cell(day.format(started), TEXT, 2f));
            line.addView(cell(clock.format(started), TEXT_DIM, 1f));
            line.addView(cell(Format.duration(r.durationMillis()), TEXT, 1f));
            line.addView(cell(Format.distance(r.meters), TEXT, 1f));
            long id = r.id;
            line.setClickable(true);
            pressable(line);
            line.setOnClickListener(
                    v -> {
                        showHome();
                        showStats();
                        selectTrip(id);
                    });
            // Full width, or the weights would divide the width of the text in
            // the row rather than the width of the table, and no column would
            // sit under its heading.
            list.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        historyView = box;
        content.addView(historyView);
        homeView.setVisibility(View.INVISIBLE);
    }

    /** Dims a view while it is held, so a row with no background still answers a finger. */
    private void pressable(View v) {
        v.setOnTouchListener(
                (view, e) -> {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            view.setAlpha(0.5f);
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            view.setAlpha(1f);
                            break;
                        default:
                            break;
                    }
                    return false;
                });
    }

    /** One column of the history table. */
    private TextView cell(String s, int color, float weight) {
        TextView t = text(s, 0.036f, color);
        t.setSingleLine(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, weight));
        return t;
    }

    private View historyStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.addView(
                tripChip(null, getString(R.string.stats_current), Format.distance(Trip.meters)));
        // Three, and the rest behind the History button. A strip long enough to
        // scroll is a strip nobody reads at the wheel, and the drive in progress
        // may be among the rows, so one more is asked for than is shown.
        int shown = 0;
        for (TripDb.Row r : TripDb.of(this).recent(STRIP_LIMIT + 1)) {
            if (r.id == Trip.currentId()) {
                continue;
            }
            if (shown++ == STRIP_LIMIT) {
                break;
            }
            String when =
                    new SimpleDateFormat("d.MM HH:mm", Locale.getDefault())
                            .format(new Date(r.startedAt * 1000L));
            strip.addView(
                    tripChip(
                            r.id,
                            when,
                            Format.distance(r.meters)
                                    + "  ·  "
                                    + Format.duration(r.durationMillis())));
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(strip);
        return scroll;
    }

    /**
     * Puts a stored drive on the trip screen, as tapping its chip would.
     *
     * <p>The drive picked from the table is usually older than the three on the strip, so none of
     * them comes up highlighted. The route and the figures are what was asked for.
     */
    private void selectTrip(long id) {
        shownTrip = id;
        updateStats();
        refreshHistoryStrip();
    }

    private View tripChip(Long id, String title, String subtitle) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setBackground(tileBg());
        chip.setPadding(px(0.025f), px(0.015f), px(0.025f), px(0.015f));
        chip.addView(
                text(title, 0.035f, java.util.Objects.equals(id, shownTrip) ? TEXT : TEXT_DIM));
        chip.addView(text(subtitle, 0.03f, TEXT_DIM));
        chip.setOnClickListener(
                v -> {
                    shownTrip = id;
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
        if (statsView == null || stripRow == null) {
            return;
        }
        // Only the strip, which is the first of the two: the History button next
        // to it has nothing to redraw and would be lost with it.
        stripRow.removeViewAt(0);
        stripRow.addView(historyStrip(), 0, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    /** The strip of recent trips and the History button, along the foot of the trip screen. */
    private LinearLayout stripRow;

    private void updateStats() {
        if (statsView == null) {
            return;
        }
        List<double[]> route;
        TripDb db = TripDb.of(this);
        if (shownTrip == null) {
            statTime.setText(
                    Trip.startedAt == 0
                            ? "--"
                            : Format.duration(System.currentTimeMillis() - Trip.startedAt));
            statDistance.setText(Format.distance(Trip.meters));
            route = Trip.currentId() == 0 ? new ArrayList<>() : db.route(Trip.currentId());
        } else {
            TripDb.Row r = db.trip(shownTrip);
            statTime.setText(Format.duration(r == null ? 0 : r.durationMillis()));
            statDistance.setText(Format.distance(r == null ? 0 : r.meters));
            route = db.route(shownTrip);
        }
        routeView.set(route);
        // One fix is a dot, not a route; say so instead of showing an empty box.
        // Before the first drive the usual reason is that the receiver has not
        // found the sky yet, which is worth saying rather than leaving the driver
        // wondering whether anything is recording at all.
        routeEmpty.setVisibility(route.size() < 2 ? View.VISIBLE : View.GONE);
        boolean nothingYet = shownTrip == null && Trip.currentId() == 0;
        routeEmpty.setText(
                getString(nothingYet ? R.string.stats_waiting_gps : R.string.stats_no_route));
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
    /** Between one group of settings and the next. */
    private static final float GROUP_GAP = 0.05f;

    /** Between one setting and the next inside a group. */
    private static final float SETTING_GAP = 0.028f;

    /**
     * The settings panel.
     *
     * <p>Two columns, because the unit is 1280 wide and a single column down the middle left half
     * the screen empty while the panel had outgrown three screenfuls of scrolling. Scrolling costs
     * more than reading in a car.
     *
     * <p>Settings are grouped, each group a heading with a rule under it and a card holding its
     * controls. The card is what separates one group from the next: a boundary drawn with a
     * background rather than with empty space, which survives daylight on a glossy screen where
     * extra padding does not.
     *
     * <p>Headings and labels are {@link #TEXT}, the sentences under them {@link #TEXT_DIM}. It used
     * to be the other way round, the label dimmer than the value it labelled, 4.1:1 against 11.2:1
     * on black, so the thing being looked for was the hardest thing to see.
     *
     * <p>No capitals in the headings, however much they would help them read as headings: Android
     * renders them transformed but the tests read the text, so a label in capitals is a label
     * nothing can find.
     */
    private void showLook() {
        if (lookView != null) {
            content.removeView(lookView);
        }
        // A picture is left behind otherwise. Touching one calls this, and the
        // settings went on top of it rather than in place of it: three pictures
        // looked at in a row meant three full-screen views still in the tree,
        // three bitmaps still held, and three photographers' names stacked in
        // the corner, of which only the oldest was the one being shown.
        if (picView != null) {
            content.removeView(picView);
            picView = null;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BG);
        box.setPadding(px(0.04f), px(0.03f), px(0.04f), px(0.03f));
        LinearLayout hdr = (LinearLayout) header(getString(R.string.look_title));
        // Which build this is, in the corner, with no label on it. The Updates
        // group says it at length; this is for reading it at a glance.
        hdr.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        hdr.addView(text("v " + Updater.installedVersion(this), 0.04f, TEXT_DIM));
        box.addView(hdr);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = settingsColumn();
        LinearLayout right = settingsColumn();
        columns.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -2, 1f);
        rightLp.setMargins(px(0.045f), 0, 0, 0);
        columns.addView(right, rightLp);
        box.addView(columns);

        addAppearanceGroup(left);
        addHomePointGroup(left);
        addHomeRoleGroup(left);
        addPicturesGroup(left);
        addRecordingSection(group(right, getString(R.string.record_section)));
        addUpdateSection(group(right, getString(R.string.update_section)));
        addBackupSection(group(right, getString(R.string.backup_section)));
        addStorageSection(group(right, getString(R.string.storage_section)));

        box.addView(text(getString(R.string.look_hint), 0.032f, TEXT_DIM), marginTop(px(0.04f)));

        // Two columns halve it, but the settings still do not fit on 720 px.
        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(BG);
        scroller.addView(box, new FrameLayout.LayoutParams(-1, -2));
        lookView = scroller;
        content.addView(lookView);
        homeView.setVisibility(View.INVISIBLE);
    }

    private LinearLayout settingsColumn() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    /**
     * Starts a group of settings in a column.
     *
     * @return the card its controls go into
     */
    private LinearLayout group(LinearLayout column, String title) {
        TextView heading = text(title, 0.042f, TEXT);
        heading.setLetterSpacing(0.10f);
        column.addView(heading, marginTop(px(GROUP_GAP)));

        View rule = new View(this);
        rule.setBackgroundColor(TILE_EDGE);
        LinearLayout.LayoutParams ruleLp =
                new LinearLayout.LayoutParams(-1, Math.max(1, px(0.004f)));
        ruleLp.setMargins(0, px(0.008f), 0, px(0.012f));
        column.addView(rule, ruleLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(tileBg());
        card.setPadding(px(0.025f), px(0.022f), px(0.025f), px(0.022f));
        column.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /** The name of one setting: what the eye scans for, so the brighter of the two. */
    private TextView settingLabel(String s) {
        return text(s, 0.04f, TEXT);
    }

    /** The sentence under a setting that explains it. */
    private TextView settingNote(String s) {
        return text(s, 0.032f, TEXT_DIM);
    }

    /** The current value of a setting, on a line of its own rather than inside its label. */
    private TextView settingValue(String s) {
        return text(s, 0.048f, TEXT);
    }

    /** One setting from the next inside a card; every gap in the panel is this gap. */
    private void settingGap(LinearLayout card) {
        card.addView(new View(this), new LinearLayout.LayoutParams(-1, px(SETTING_GAP)));
    }

    private void addAppearanceGroup(LinearLayout column) {
        LinearLayout card = group(column, getString(R.string.look_appearance));

        card.addView(settingLabel(getString(R.string.look_clock_size)));
        LinearLayout sizes = new LinearLayout(this);
        sizes.setOrientation(LinearLayout.HORIZONTAL);
        int cur = prefs.getInt("size", 0);
        for (int i = 0; i < SIZE_NAMES.length; i++) {
            final int idx = i;
            TextView o = new TextView(this);
            o.setText(SIZE_NAMES[i]);
            o.setGravity(Gravity.CENTER);
            o.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.055f);
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(0.15f), 1f);
            lp.setMargins(i == 0 ? 0 : px(0.015f), px(0.012f), 0, 0);
            sizes.addView(o, lp);
        }
        card.addView(sizes);

        settingGap(card);
        card.addView(settingLabel(getString(R.string.look_brightness)));
        card.addView(settingNote(getString(R.string.look_brightness_note)));
        // Five steps rather than a slider. A slider wants a steady hand on a
        // surface that moves, and it was the one control on the panel whose own
        // gesture collided with the swipe back; a row of buttons is the same
        // shape as the layout sizes above it and can be hit without looking.
        LinearLayout brRow = new LinearLayout(this);
        brRow.setOrientation(LinearLayout.HORIZONTAL);
        int chosen = nearestBrightness(prefs.getInt("bright", 100));
        for (int i = 0; i < BRIGHTNESS.length; i++) {
            final int level = BRIGHTNESS[i];
            TextView t = text(level + "%", 0.045f, level == chosen ? TEXT : TEXT_DIM);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, px(0.025f), 0, px(0.025f));
            GradientDrawable bgd = tileBg();
            if (level == chosen) {
                bgd.setStroke(Math.max(2, px(0.006f)), 0xFF7A7A7A);
            }
            t.setBackground(bgd);
            t.setOnClickListener(
                    v -> {
                        prefs.edit().putInt("bright", level).apply();
                        applyNightDim();
                        showLook(); // redraw so the selection moves
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(i == 0 ? 0 : px(0.015f), px(0.012f), 0, 0);
            brRow.addView(t, lp);
        }
        card.addView(brRow);

        settingGap(card);
        boolean dim = prefs.getBoolean("dim", false);
        // Label and value stay in one button here: it is the control as much as
        // the label, and splitting them would leave nothing to press.
        TextView dimBtn = new TextView(this);
        dimBtn.setText(
                getString(
                        R.string.look_night,
                        getString(dim ? R.string.look_on : R.string.look_off)));
        dimBtn.setTextColor(TEXT);
        dimBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.045f);
        dimBtn.setPadding(px(0.035f), px(0.025f), px(0.035f), px(0.025f));
        dimBtn.setBackground(tileBg());
        dimBtn.setOnClickListener(
                v -> {
                    toggleNightDimByHand();
                    showLook();
                });
        card.addView(dimBtn, new LinearLayout.LayoutParams(-2, -2));
        card.addView(settingNote(getString(R.string.look_night_note)), marginTop(px(0.01f)));

        settingGap(card);
        boolean auto = prefs.getBoolean(PREF_AUTO_NIGHT, true);
        TextView autoBtn = new TextView(this);
        autoBtn.setText(
                getString(
                        R.string.look_autonight,
                        getString(auto ? R.string.look_on : R.string.look_off)));
        autoBtn.setTextColor(TEXT);
        autoBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.045f);
        autoBtn.setPadding(px(0.035f), px(0.025f), px(0.035f), px(0.025f));
        autoBtn.setBackground(tileBg());
        autoBtn.setOnClickListener(
                v -> {
                    // Turning it on hands the screen back to the sun at once,
                    // rather than at the next refresh: the driver has just said
                    // so, so the tap that said it must not also count as the
                    // manual choice that holds the sun off.
                    prefs.edit()
                            .putBoolean(PREF_AUTO_NIGHT, !prefs.getBoolean(PREF_AUTO_NIGHT, true))
                            .remove(PREF_DIM_BY_HAND)
                            .apply();
                    applyAutoNight();
                    showLook();
                });
        card.addView(autoBtn, new LinearLayout.LayoutParams(-2, -2));
        card.addView(settingNote(getString(R.string.look_autonight_note)), marginTop(px(0.01f)));

        settingGap(card);
        card.addView(settingLabel(getString(R.string.look_language)));
        // One button that opens a list, not three side by side. Three never fit
        // the width of a column: the last of them was squeezed until "Polski"
        // came out one letter per line, and a fourth language would be worse.
        card.addView(flatButton(languageName(prefs.getString("lang", "")), this::askLanguage));
    }

    /** Where "to home" is measured from. The heading names the setting, so the card shows it. */
    private void addHomePointGroup(LinearLayout column) {
        LinearLayout card = group(column, getString(R.string.home_section));
        card.addView(settingValue(homeLabel()));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(flatButton(getString(R.string.home_set_here), this::setHomeHere));
        row.addView(flatButton(getString(R.string.home_set_address), this::askHomeAddress));
        card.addView(row);
    }

    /**
     * Claiming the HOME role, which is a different thing from the home point and used to sit in the
     * same run of buttons as it.
     */
    private void addHomeRoleGroup(LinearLayout column) {
        LinearLayout card = group(column, getString(R.string.home_role_section));
        card.addView(flatButton(getString(R.string.home_default), this::requestHomeRole));
        card.addView(
                flatButton(
                        getString(R.string.home_picker),
                        () -> safeStart(new Intent(Settings.ACTION_HOME_SETTINGS))));
    }

    // ───────────────────────────── PICTURES ─────────────────────────────

    /**
     * Two buttons, and behind each one a full screen of something that is not a road.
     *
     * <p>The one thing in the settings that is there to be enjoyed rather than set. It is in the
     * settings and not on the home screen on purpose: the home row is for driving, and a picture is
     * for a car that has stopped.
     */
    private void addPicturesGroup(LinearLayout column) {
        LinearLayout card = group(column, getString(R.string.pics_section));
        card.addView(settingNote(getString(R.string.pics_note)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(flatButton(getString(R.string.pics_lego), () -> showPicture(Pics.LEGO)));
        row.addView(flatButton(getString(R.string.pics_kitty), () -> showPicture(Pics.KITTY)));
        card.addView(row);
        card.addView(
                flatButton(
                        getString(
                                unsplashKey().isEmpty()
                                        ? R.string.pics_key_unset
                                        : R.string.pics_key_set),
                        this::askUnsplashKey));
    }

    /**
     * Asks for an Unsplash access key, and keeps it.
     *
     * <p>Typed in rather than compiled in, so that a car running a build from the public source -
     * which carries no key, because the source is public - can still be given one without being
     * rebuilt. What is typed is never written to the log or shown again; the button afterwards says
     * only whether there is a key, not what it is. Emptying the field removes it and the pictures
     * go back to Openverse, which asks for nothing.
     */
    private void askUnsplashKey() {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(getString(R.string.pics_key_hint));
        field.setText(prefs.getString(PREF_UNSPLASH, ""));

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle(getString(R.string.pics_key_title))
                .setView(field)
                .setPositiveButton(
                        android.R.string.ok,
                        (d, which) -> {
                            prefs.edit()
                                    .putString(PREF_UNSPLASH, field.getText().toString().trim())
                                    .apply();
                            d.dismiss();
                            showLook();
                        })
                .setNegativeButton(android.R.string.cancel, (d, which) -> d.dismiss())
                .show();
    }

    /**
     * A picture of the given set, as large as the screen, gone at a touch.
     *
     * <p>Shown before it is fetched, saying so, because online it travels over whatever connection
     * a car has and a screen that does nothing for two seconds looks broken. Every touch anywhere
     * returns to the settings, including a touch during the wait: nothing here is worth being
     * trapped in.
     */
    private void showPicture(String set) {
        if (lookView != null) {
            content.removeView(lookView);
            lookView = null;
            draggedSideways = null;
        }
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF000000);

        ImageView shot = new ImageView(this);
        shot.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(shot, new FrameLayout.LayoutParams(-1, -1));

        TextView notice = text(getString(R.string.pics_loading), 0.045f, TEXT_DIM);
        notice.setGravity(Gravity.CENTER);
        frame.addView(notice, new FrameLayout.LayoutParams(-1, -1));

        // The ordinary text colour with a shadow under it, rather than the dim
        // grey used elsewhere: this line sits on a photograph, not on black, and
        // a credit that disappears into a pale corner is a credit not given. CC
        // BY asks for the name to be seen, and a photograph can be any colour.
        TextView credit = text("", 0.03f, TEXT);
        credit.setShadowLayer(px(0.008f), 0, px(0.002f), 0xCC000000);
        credit.setPadding(px(0.02f), px(0.01f), px(0.02f), px(0.01f));
        FrameLayout.LayoutParams creditLp = new FrameLayout.LayoutParams(-2, -2);
        creditLp.gravity = Gravity.BOTTOM | Gravity.START;
        frame.addView(credit, creditLp);

        frame.setOnClickListener(v -> showLook());
        picView = frame;
        content.addView(picView);
        homeView.setVisibility(View.INVISIBLE);
        fetchPicture(set, frame, shot, notice, credit);
    }

    /**
     * Puts a picture on screen, off the main thread, filling the cache if it is empty.
     *
     * <p>What is already on disk answers first and answers at once; only an empty cache makes the
     * screen wait for the network. Once something is showing, a cache holding fewer than {@link
     * Pics#WANTED} is topped up quietly behind it, so the waiting happens once and never again
     * until Android reclaims the room.
     *
     * <p>The views are touched only back on the launcher's own thread, and only if the screen is
     * still the one that asked - a driver who taps away during the wait must not have a cat land on
     * the settings afterwards.
     */
    private void fetchPicture(
            String set, View owner, ImageView shot, TextView notice, TextView credit) {
        new Thread(
                        () -> {
                            File dir = cacheFor(set);
                            String[] names = Pics.pictures(dir.list());
                            if (names.length == 0) {
                                fill(set, dir);
                                names = Pics.pictures(dir.list());
                            }
                            Bitmap bitmap = null;
                            String caption = "";
                            Integer last = lastPicture.get(set);
                            int at = Pics.pickOther(names.length, last == null ? -1 : last, random);
                            if (at >= 0) {
                                lastPicture.put(set, at);
                                bitmap = decode(readFile(new File(dir, names[at])));
                                Pics.Credit who =
                                        Pics.creditFor(
                                                text(new File(dir, Pics.CREDITS)), names[at]);
                                caption = who == null ? "" : credit(who.who, who.licence);
                            }
                            final Bitmap drawn = bitmap;
                            final String credited = caption;
                            final int held = names.length;
                            runOnUiThread(
                                    () -> {
                                        if (picView != owner) {
                                            return; // the driver has moved on
                                        }
                                        if (drawn == null) {
                                            notice.setText(getString(R.string.pics_none));
                                            return;
                                        }
                                        shot.setImageBitmap(drawn);
                                        notice.setVisibility(View.GONE);
                                        if (!credited.isEmpty()) {
                                            credit.setText(credited);
                                        }
                                    });
                            if (held > 0 && held < Pics.WANTED) {
                                // Behind the picture that is already showing.
                                fill(set, dir);
                            }
                        },
                        "pic")
                .start();
    }

    /**
     * Fetches pictures until the set holds {@link Pics#WANTED} of them, or until the source runs
     * out of answers.
     *
     * <p>One search for the whole refill rather than one per picture: Openverse allows an anonymous
     * caller twenty requests a minute, and asking it ten times for one picture each would be both
     * rude and slower. A dead address is skipped and the next candidate taken - roughly one link in
     * twenty no longer resolves at the far end - which is why more results are asked for than are
     * wanted.
     *
     * <p>Only one refill of a set runs at a time. Two taps in quick succession on an empty cache
     * would otherwise fetch everything twice and write over each other's files.
     */
    private void fill(String set, File dir) {
        synchronized (filling) {
            if (!filling.add(set)) {
                return;
            }
        }
        try {
            dir.mkdirs();
            int have = Pics.pictures(dir.list()).length;
            if (have >= Pics.WANTED) {
                return;
            }
            List<Pics.Shot> shots = Pics.spread(search(set), Pics.PER_CREATOR);
            StringBuilder credits = new StringBuilder();
            for (Pics.Shot found : shots) {
                if (have >= Pics.WANTED) {
                    break;
                }
                byte[] picture = download(found.url);
                if (picture == null || picture.length == 0) {
                    continue; // a dead link at the far end; take the next one
                }
                String name = Pics.fileName(have + 1);
                if (!write(new File(dir, name), picture)) {
                    continue;
                }
                credits.append(Pics.creditLine(name, found)).append('\n');
                have++;
            }
            if (credits.length() > 0) {
                append(new File(dir, Pics.CREDITS), credits.toString());
            }
        } catch (Throwable t) {
            // A diversion is never worth a crash. Whatever arrived is kept, and
            // the next tap tries again for the rest.
        } finally {
            synchronized (filling) {
                filling.remove(set);
            }
        }
    }

    /**
     * Asks a source for a page of pictures.
     *
     * <p>Unsplash when there is a key, because it is curated and deep; Openverse otherwise, because
     * it needs none. Unsplash failing - no signal, a dead key, a quota spent - falls through to
     * Openverse rather than leaving the screen empty, which is the whole reason there are two.
     */
    private List<Pics.Shot> search(String set) {
        if (!hasNetwork()) {
            return new ArrayList<>();
        }
        String key = unsplashKey();
        if (Pics.preferUnsplash(key, true)) {
            List<Pics.Shot> shots =
                    Pics.fromUnsplash(fetch(Pics.randomUrl(set, key, Pics.WANTED * 2)));
            if (!shots.isEmpty()) {
                return shots;
            }
        }
        // A later page each time, so a cache Android emptied does not come back
        // as the same ten pictures it held before.
        int page = prefs.getInt(PREF_PAGE + set, 0) + 1;
        prefs.edit().putInt(PREF_PAGE + set, page).apply();
        return Pics.fromOpenverse(fetch(Pics.openverseUrl(set, page, Pics.WANTED * 2)));
    }

    /** The Unsplash key, which only ever comes from the settings on this unit. */
    private String unsplashKey() {
        return Pics.key(prefs.getString(PREF_UNSPLASH, ""));
    }

    /** Whether anything is likely to answer; a wrong guess costs one wait, not a crash. */
    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            return cm != null && cm.getActiveNetwork() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Where a set's pictures are kept: the cache directory, which Android may empty at will. */
    private File cacheFor(String set) {
        return new File(getCacheDir(), Pics.cacheDir(set));
    }

    /** One request, as text, or the empty string. */
    private String fetch(String url) {
        byte[] body = download(url);
        if (body == null) {
            return "";
        }
        try {
            return new String(body, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** One request, as bytes, or null. */
    private byte[] download(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(PIC_TIMEOUT_MS);
                c.setReadTimeout(PIC_TIMEOUT_MS);
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() != 200) {
                    return null;
                }
                return readAll(c.getInputStream());
            } finally {
                c.disconnect();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** "Someone / CC0", in the corner of the picture. */
    private String credit(String who, String under) {
        return who == null || who.isEmpty() ? "" : getString(R.string.pics_credit, who, under);
    }

    private byte[] readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (Throwable t) {
            return null;
        }
    }

    private String text(File f) {
        byte[] body = readFile(f);
        if (body == null) {
            return null;
        }
        try {
            return new String(body, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean write(File f, byte[] body) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(body);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void append(File f, String line) {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(line.getBytes("UTF-8"));
        } catch (Throwable t) {
            // The picture is there; it will simply show without a name.
        }
    }

    /**
     * A bitmap no larger than it needs to be.
     *
     * <p>Measured before it is read: the head unit has little memory, and a photograph from the
     * open internet can be any size at all.
     */
    private Bitmap decode(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        int wanted = Math.max(getResources().getDisplayMetrics().widthPixels, 1);
        while (bounds.outWidth / opts.inSampleSize > 2 * wanted) {
            opts.inSampleSize *= 2;
        }
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }

    /** How long a picture is waited for, at either end of the fetch. */
    private static final int PIC_TIMEOUT_MS = 8000;

    private final java.util.Map<String, Integer> lastPicture = new java.util.HashMap<>();
    private final java.util.Random random = new java.util.Random();

    /** The sets whose cache is being filled right now; see {@link #fill}. */
    private final java.util.Set<String> filling = new java.util.HashSet<>();

    /** Where the Unsplash key typed into the settings is kept. */
    static final String PREF_UNSPLASH = "unsplash_key";

    /** Which page of Openverse results a set last took, so the next refill takes another. */
    static final String PREF_PAGE = "pics_page_";

    // ───────────────────────────── RECORDING ─────────────────────────────

    /**
     * How closely the route is followed, and whether the launcher claims the screen after a boot.
     *
     * <p>Both of these exist because of what a parked car does: it cold-boots into the ROM own
     * screen, and its receiver has forgotten where the satellites are. Coming up at boot starts the
     * GPS engine while the driver is still fastening the seatbelt, which is the only thing an app
     * can do about a cold receiver.
     */
    private void addRecordingSection(LinearLayout box) {
        box.addView(settingLabel(getString(R.string.record_interval)));

        int current = TripService.intervalMillis(prefs) / 1000;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int seconds : TripService.INTERVALS_S) {
            row.addView(intervalButton(seconds, current));
        }
        box.addView(row);

        boolean bootHome = prefs.getBoolean(BootReceiver.PREF_BOOT_HOME, true);
        box.addView(
                flatButton(
                        getString(
                                R.string.record_boot_home,
                                getString(bootHome ? R.string.look_on : R.string.look_off)),
                        () -> {
                            prefs.edit().putBoolean(BootReceiver.PREF_BOOT_HOME, !bootHome).apply();
                            showLook();
                        }));
    }

    private TextView intervalButton(int seconds, int current) {
        TextView t =
                text(
                        getString(R.string.record_seconds, seconds),
                        0.045f,
                        seconds == current ? TEXT : TEXT_DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(0.04f), px(0.025f), px(0.04f), px(0.025f));
        GradientDrawable bgd = tileBg();
        if (seconds == current) {
            bgd.setStroke(Math.max(2, px(0.006f)), 0xFF7A7A7A);
        }
        t.setBackground(bgd);
        t.setOnClickListener(
                v -> {
                    prefs.edit().putInt(TripService.PREF_INTERVAL, seconds).apply();
                    TripService.reapplyInterval(this);
                    showLook();
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, px(0.015f), px(0.02f), px(0.015f));
        t.setLayoutParams(lp);
        return t;
    }

    // ───────────────────────────── UPDATES ─────────────────────────────

    /**
     * Which version is installed, and whether a newer one has been published.
     *
     * <p>The app is on no store, so without this the owner finds out about a release by accident.
     */
    private void addUpdateSection(LinearLayout box) {
        LinearLayout installed = new LinearLayout(this);
        installed.setOrientation(LinearLayout.HORIZONTAL);
        installed.setGravity(Gravity.CENTER_VERTICAL);
        installed.addView(
                text(
                        getString(R.string.update_installed, Updater.installedVersion(this)),
                        0.035f,
                        TEXT_DIM));
        if (Updater.upToDate(this, prefs)) {
            // A tick beside the version, where the yellow "a newer one exists"
            // line would otherwise be. The two never show together.
            Glyph tick = new Glyph(this, Glyph.CHECK, OK);
            tick.setContentDescription(getString(R.string.update_up_to_date));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(0.045f), px(0.045f));
            lp.setMargins(px(0.02f), 0, 0, 0);
            installed.addView(tick, lp);
        }
        box.addView(installed);

        String found = prefs.getString(Updater.PREF_FOUND, "");
        if (!found.isEmpty()) {
            box.addView(text(getString(R.string.update_available, found), 0.04f, WARN));
        }
        String error = prefs.getString(Updater.PREF_LAST_ERROR, "");
        if (!error.isEmpty()) {
            box.addView(text(getString(R.string.update_check_failed, error), 0.032f, TEXT_DIM));
        }

        box.addView(flatButton(getString(R.string.update_check_now), () -> checkForUpdate(true)));

        boolean auto = Updater.autoCheckEnabled(prefs);
        box.addView(
                flatButton(
                        getString(
                                R.string.update_auto,
                                getString(auto ? R.string.look_on : R.string.look_off)),
                        () -> {
                            prefs.edit().putBoolean(Updater.PREF_AUTO, !auto).apply();
                            showLook();
                        }));

        if (!Updater.canInstall(this)) {
            box.addView(
                    flatButton(
                            getString(R.string.update_allow_installs),
                            () -> safeStart(Updater.unknownSourcesSettings(this))));
        }
    }

    /**
     * Asks GitHub whether there is a newer release.
     *
     * @param announce true when the driver asked, so say "up to date" as well as offering an update
     */
    private void checkForUpdate(boolean announce) {
        if (announce) {
            toast(getString(R.string.update_checking));
        }
        new Thread(
                        () -> {
                            try {
                                Update.Release release = Updater.check(this, prefs);
                                if (release == null) {
                                    if (announce) {
                                        ui.post(() -> toast(getString(R.string.update_up_to_date)));
                                    }
                                    return;
                                }
                                // Asked about once per version, so a release the
                                // driver said no to does not ask again every day.
                                if (!announce && Updater.alreadyOffered(prefs, release.version)) {
                                    return;
                                }
                                Updater.markOffered(prefs, release.version);
                                ui.post(() -> offerUpdate(release));
                            } catch (Throwable t) {
                                Updater.noteFailure(prefs, String.valueOf(t.getMessage()));
                                if (announce) {
                                    ui.post(
                                            () ->
                                                    toast(
                                                            getString(
                                                                    R.string.update_check_failed,
                                                                    String.valueOf(
                                                                            t.getMessage()))));
                                }
                            }
                        },
                        "update-check")
                .start();
    }

    /** The dialog that asks whether to install what was found. */
    private void offerUpdate(Update.Release release) {
        if (!release.installable()) {
            toast(getString(R.string.update_no_apk));
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.update_question, release.version))
                .setPositiveButton(
                        R.string.update_install, (d, which) -> downloadAndInstall(release))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    /**
     * Fetches the APK and hands it to the package installer.
     *
     * <p>A unit that refuses to let the launcher install anything is told about rather than left
     * silent: the file has been downloaded either way, and a file manager can finish the job.
     */
    private void downloadAndInstall(Update.Release release) {
        toast(getString(R.string.update_downloading, release.version));
        new Thread(
                        () -> {
                            try {
                                File apk = Updater.download(this, release);
                                Intent install = Updater.installIntent(this, apk);
                                ui.post(
                                        () -> {
                                            if (install == null || !Updater.canInstall(this)) {
                                                toast(
                                                        getString(
                                                                R.string.update_cannot_install,
                                                                apk.getParent()));
                                                return;
                                            }
                                            safeStart(install);
                                        });
                            } catch (Throwable t) {
                                ui.post(
                                        () ->
                                                toast(
                                                        getString(
                                                                R.string.update_download_failed,
                                                                String.valueOf(t.getMessage()))));
                            }
                        },
                        "update-download")
                .start();
    }

    // ───────────────────────────── BACKUP AND STORAGE ─────────────────────────────

    /**
     * Where trips are copied to, if anywhere.
     *
     * <p>Off by default, and off means off: nothing leaves the unit. Choosing a backend only
     * changes where finished trips are copied; recording to the database is unaffected by any of
     * it, which is the point of keeping the whole thing behind {@link BackupRunner}.
     */
    private void addBackupSection(LinearLayout box) {

        String mode = BackupRunner.mode(prefs);
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.addView(backupModeButton(BackupRunner.MODE_OFF, R.string.backup_off, mode));
        modes.addView(backupModeButton(BackupRunner.MODE_FOLDER, R.string.backup_folder, mode));
        modes.addView(backupModeButton(BackupRunner.MODE_DRIVE, R.string.backup_drive, mode));
        box.addView(modes);

        if (BackupRunner.MODE_DRIVE.equals(mode)) {
            DriveBackup drive = new DriveBackup(this, prefs);
            if (!drive.hasClient()) {
                box.addView(text(getString(R.string.backup_no_client), 0.035f, WARN));
            } else if (drive.signedIn()) {
                box.addView(text(getString(R.string.backup_signed_in), 0.035f, TEXT_DIM));
                box.addView(
                        flatButton(
                                getString(R.string.backup_sign_out),
                                () -> {
                                    drive.signOut();
                                    showLook();
                                }));
            } else {
                box.addView(flatButton(getString(R.string.backup_sign_in), this::startDriveSignIn));
            }
        }
        if (BackupRunner.MODE_FOLDER.equals(mode)) {
            box.addView(
                    text(
                            new FolderBackup(getExternalFilesDir(null)).root().getAbsolutePath(),
                            0.03f,
                            TEXT_DIM));
        }
        if (!BackupRunner.MODE_OFF.equals(mode)) {
            box.addView(text(backupStatus(), 0.035f, TEXT_DIM));
        }
    }

    /** One line saying whether the backup is keeping up, and why not. */
    private String backupStatus() {
        int waiting = BackupRunner.waiting(TripDb.of(this));
        String error = prefs.getString(BackupRunner.PREF_LAST_ERROR, "");
        if (!error.isEmpty()) {
            return getString(R.string.backup_last_error, error);
        }
        if (waiting > 0) {
            return getString(R.string.backup_waiting, waiting);
        }
        long ok = prefs.getLong(BackupRunner.PREF_LAST_OK, 0);
        if (ok == 0) {
            return getString(R.string.backup_all_done);
        }
        return getString(
                R.string.backup_last_ok,
                new SimpleDateFormat("d.MM HH:mm", Locale.getDefault()).format(new Date(ok)));
    }

    private TextView backupModeButton(String mode, int labelRes, String current) {
        TextView t = text(getString(labelRes), 0.045f, mode.equals(current) ? TEXT : TEXT_DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(0.03f), px(0.025f), px(0.03f), px(0.025f));
        GradientDrawable bgd = tileBg();
        if (mode.equals(current)) {
            bgd.setStroke(Math.max(2, px(0.006f)), 0xFF7A7A7A);
        }
        t.setBackground(bgd);
        t.setOnClickListener(
                v -> {
                    prefs.edit().putString(BackupRunner.PREF_MODE, mode).apply();
                    BackupRunner.resetBackoff(prefs);
                    BackupRunner.trigger(this, prefs);
                    showLook();
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, px(0.015f), px(0.02f), px(0.015f));
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * Signs in to Google with the limited-input device flow.
     *
     * <p>The unit shows a short code and the owner types it on their phone, because a head unit has
     * no keyboard worth using and may have no browser at all. Everything happens on a background
     * thread; a failure puts a message on this screen and changes nothing else.
     */
    private void startDriveSignIn() {
        final DriveBackup drive = new DriveBackup(this, prefs);
        final TextView status = text(getString(R.string.backup_waiting_for_sign_in), 0.04f, TEXT);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setView(status)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        dialog.show();

        new Thread(
                        () -> {
                            try {
                                DriveApi.DeviceCode code = drive.requestCode();
                                ui.post(
                                        () ->
                                                status.setText(
                                                        getString(R.string.backup_code_title)
                                                                + "\n"
                                                                + code.verificationUrl
                                                                + "\n\n"
                                                                + code.userCode
                                                                + "\n\n"
                                                                + getString(
                                                                        R.string
                                                                                .backup_code_hint)));
                                pollForSignIn(drive, code, dialog, status);
                            } catch (Throwable t) {
                                signInFailed(dialog, String.valueOf(t.getMessage()));
                            }
                        },
                        "drive-sign-in")
                .start();
    }

    private void pollForSignIn(
            DriveBackup drive, DriveApi.DeviceCode code, AlertDialog dialog, TextView status) {
        long deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L;
        long waitMs = Math.max(1, code.intervalSeconds) * 1000L;
        while (System.currentTimeMillis() < deadline && dialog.isShowing()) {
            try {
                Thread.sleep(waitMs);
                DriveApi.Poll state = drive.poll(code.deviceCode);
                if (state == null) {
                    ui.post(
                            () -> {
                                dialog.dismiss();
                                toast(getString(R.string.backup_sign_in_done));
                                BackupRunner.resetBackoff(prefs);
                                BackupRunner.trigger(this, prefs);
                                showLook();
                            });
                    return;
                }
                if (state == DriveApi.Poll.SLOW_DOWN) {
                    waitMs += 5000L;
                } else if (state == DriveApi.Poll.DENIED || state == DriveApi.Poll.EXPIRED) {
                    signInFailed(dialog, state.name().toLowerCase(Locale.US));
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                signInFailed(dialog, String.valueOf(t.getMessage()));
                return;
            }
        }
    }

    private void signInFailed(AlertDialog dialog, String reason) {
        ui.post(
                () -> {
                    dialog.dismiss();
                    toast(getString(R.string.backup_sign_in_failed, reason));
                });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** What the trips take up, and the leftovers of the CSV import. */
    private void addStorageSection(LinearLayout box) {

        TripDb db = TripDb.of(this);
        File dbFile = getDatabasePath(TripDb.NAME);
        box.addView(
                text(
                        getString(R.string.storage_trips, db.count(), bytes(dbFile.length())),
                        0.035f,
                        TEXT_DIM));
        box.addView(flatButton(getString(R.string.backup_export_now), this::exportEverything));

        long imported = Migration.importedBytes(Migration.csvDir(this));
        if (imported > 0) {
            box.addView(
                    text(getString(R.string.storage_imported, bytes(imported)), 0.035f, TEXT_DIM));
            box.addView(
                    flatButton(
                            getString(R.string.storage_delete_imported),
                            () -> {
                                int n = Migration.deleteImported(Migration.csvDir(this));
                                toast(getString(R.string.storage_deleted, n));
                                showLook();
                            }));
        }
    }

    /** A size a person can read. */
    static String bytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", n / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", n / (1024.0 * 1024));
    }

    /** Writes every stored trip into the export folder, whatever the backup mode is. */
    private void exportEverything() {
        toast(getString(R.string.backup_exporting));
        final Context app = getApplicationContext();
        new Thread(
                        () -> {
                            // Through BackupRunner, which holds the lock the
                            // automatic backup holds: both write the same folder.
                            final int exported = BackupRunner.exportAll(app);
                            ui.post(() -> toast(getString(R.string.backup_exported, exported)));
                        },
                        "export")
                .start();
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

    /** The languages on offer; the empty code means whatever the head unit is set to. */
    private static final String[] LANGUAGES = {"", "en", "pl"};

    /** What a language code is called, in its own language where it has one. */
    private String languageName(String lang) {
        if ("en".equals(lang)) {
            return getString(R.string.lang_en);
        }
        if ("pl".equals(lang)) {
            return getString(R.string.lang_pl);
        }
        return getString(R.string.lang_system);
    }

    /** The list behind the language button; picking one restarts the activity in it. */
    private void askLanguage() {
        String current = prefs.getString("lang", "");
        String[] names = new String[LANGUAGES.length];
        int checked = -1;
        for (int i = 0; i < LANGUAGES.length; i++) {
            names[i] = languageName(LANGUAGES[i]);
            if (LANGUAGES[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle(R.string.look_language)
                .setSingleChoiceItems(
                        names,
                        checked,
                        (d, which) -> {
                            d.dismiss();
                            prefs.edit().putString("lang", LANGUAGES[which]).apply();
                            recreate(); // attachBaseContext applies the new locale
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
        if (Wx.isStale(prefs.getLong(PREF_WEATHER_AT, 0L), System.currentTimeMillis())) {
            // The forecast is not shown alongside this. Three hours of silence
            // from the network is a thing the driver needs to know in one
            // glance, and a screen that says "no connection" over a full
            // forecast is a screen that says the forecast is current.
            weatherContent.addView(text(getString(R.string.weather_offline), 0.045f, TEXT_DIM));
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
            // The first slot after this hour, however stale the reading is. A
            // forecast whose hours are all behind us says nothing about the drive
            // ahead, so it gets no strip rather than a strip of the wrong hours.
            int start = Wx.nextHourIndex(ht, cur.getString("time"));
            LinearLayout hours = new LinearLayout(this);
            hours.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = start; i >= 0 && i < Math.min(ht.length(), start + 12); i++) {
                LinearLayout c = new LinearLayout(this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setGravity(Gravity.CENTER_HORIZONTAL);
                c.setPadding(px(0.025f), px(0.015f), px(0.025f), px(0.015f));
                c.addView(centred(text(ht.getString(i).substring(11, 13), 0.035f, TEXT_DIM)));
                c.addView(centred(text(Math.round(hv.getDouble(i)) + "°", 0.05f, TEXT)));
                int pr = hp.isNull(i) ? 0 : hp.getInt(i);
                c.addView(
                        centred(
                                text(
                                        pr >= 20 ? pr + "%" : shortDescribe(hc.getInt(i)),
                                        0.03f,
                                        TEXT_DIM)));
                hours.addView(c);
            }
            if (hours.getChildCount() > 0) {
                HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                hs.addView(hours);
                LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(-1, -2);
                hsLp.setMargins(0, px(0.03f), 0, px(0.03f));
                weatherContent.addView(hs, hsLp);
            }

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
                c.setPadding(px(0.025f), px(0.02f), px(0.025f), px(0.02f));
                c.addView(centred(text(name, 0.04f, TEXT_DIM)));
                c.addView(
                        centred(
                                text(
                                        Math.round(dmax.getDouble(i))
                                                + "° / "
                                                + Math.round(dmin.getDouble(i))
                                                + "°",
                                        0.045f,
                                        TEXT)));
                int pr = dp.isNull(i) ? 0 : dp.getInt(i);
                c.addView(
                        centred(
                                text(
                                        shortDescribe(dc.getInt(i))
                                                + (pr >= 20 ? "  " + pr + "%" : ""),
                                        0.032f,
                                        TEXT_DIM)));
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

    /**
     * Centres a line of text inside its column.
     *
     * <p>A vertical LinearLayout hands its children MATCH_PARENT width, so a TextView fills the
     * column and draws its text hard against the left edge however the container gravity is set.
     * Setting the gravity on the TextView itself is what actually centres it. This has caught out
     * the tile labels, the date line and the forecast strips in turn.
     */
    private TextView centred(TextView t) {
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    /** Puts the current reading on the home screen: temperature, icon, warning line. */
    private void showTemp() {
        if (Wx.isStale(prefs.getLong(PREF_WEATHER_AT, 0L), System.currentTimeMillis())) {
            // Nothing from the sky for three hours, so the home screen says so
            // by saying nothing: a dash where the temperature was, no weather
            // icon, and above all no warnings. A gale warning fetched this
            // morning is worse than a blank space - a driver who reads it
            // decides something about a road on evidence that has expired.
            //
            // The dash is the same thing the launcher shows before its first
            // reading ever arrives, so this introduces no new sign to learn.
            // The box stays tappable, and the weather screen behind it is where
            // the reason is written.
            tempView.setText("--°");
            tempView.setTextColor(TEXT_DIM);
            wxIcon.setVisibility(View.INVISIBLE);
            warnIcon.setVisibility(View.GONE);
            warnText.setVisibility(View.GONE);
            fillWeather();
            return;
        }
        wxIcon.setVisibility(View.VISIBLE);
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
                                                    + "precipitation_probability_max,sunrise,sunset"
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
                                // The time of the last reading that actually
                                // arrived, which is what decides whether the sun
                                // times in it may still be switched by. A failed
                                // fetch falls through to the catch below and
                                // leaves this alone, so a unit that has been out
                                // of range for a week knows that it has.
                                prefs.edit()
                                        .putString("weather", j.toString())
                                        .putString("place", fp)
                                        .putLong(PREF_WEATHER_AT, System.currentTimeMillis())
                                        .apply();
                                ui.post(
                                        () -> {
                                            lastWeather = j;
                                            placeName = fp;
                                            showTemp();
                                            applyAutoNight();
                                        });
                            } catch (Exception e) {
                                // Offline: the last stored reading stays on screen.
                            } finally {
                                fetching = false;
                            }
                        })
                .start();
    }

    /**
     * Starts recording, unless a test asked this run not to.
     *
     * <p>A test that asserts what the screen shows before the first fix cannot have a live receiver
     * feeding it one; see {@link #EXTRA_NO_RECORDING}.
     */
    private void startRecording() {
        if (!noRecording) {
            TripService.start(this);
        }
    }

    private int px(float fractionOfHeight) {
        return Math.round(H * fractionOfHeight);
    }

    // ─────────────────────────── TEST HOOKS (DEBUG ONLY) ───────────────────────────

    /**
     * Lets a test or the screenshot script put the launcher into an exact state: which screen is
     * open, which canned weather reading is shown, which size variant and which home point are
     * stored, and whether this run records at all.
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

        if (i.hasExtra(EXTRA_NO_RECORDING)) {
            // Before anything is drawn: a drive picked up here would change what the
            // trip screen says. Not marked as handled - it steers recording, not
            // which screen is shown.
            noRecording = true;
            try {
                stopService(new Intent(this, TripService.class));
            } catch (Throwable ignored) {
                // Not running, or the ROM refused; forgetting the drive is what matters.
            }
            Trip.forget();
        }

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
            handled |= loadCannedWeather(i.getStringExtra(EXTRA_WEATHER), ageMinutes(i));
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

    /**
     * How many minutes old the pinned reading should pretend to be, from the intent.
     *
     * <p>Zero unless asked for, and unreadable values count as zero: a hook that fails loudly here
     * would fail a test for the wrong reason.
     */
    private int ageMinutes(Intent i) {
        try {
            return Integer.parseInt(i.getStringExtra(EXTRA_WEATHER_AGE_MIN));
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Replaces the live reading with a stored one, so warnings are reproducible. */
    private boolean loadCannedWeather(String name, int ageMinutes) {
        File f = new File(new File(getExternalFilesDir(null), "wx"), name + ".json");
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            lastWeather = new JSONObject(sb.toString());
            placeName = lastWeather.optString("place", placeName);
            // Pinning a reading means "this one just arrived", so it is dated
            // now unless the caller asks for it to be older. Without this every
            // canned reading would be three hours stale the moment it was
            // pinned, and every screen pinned with one would show a dash and a
            // no-connection notice instead of the weather the test or the
            // screenshot was asking about. The age is how a test reaches the
            // other case, which otherwise takes three hours to arrive.
            prefs.edit()
                    .putLong(PREF_WEATHER_AT, System.currentTimeMillis() - ageMinutes * 60_000L)
                    .apply();
            weatherPinned = true;
            showTemp();
            return true;
        } catch (Exception ignored) {
            return false; // no such reading: leave the live one alone
        }
    }
}
