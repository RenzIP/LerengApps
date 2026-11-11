package id.bagas.lerengoffline;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity implements LocationListener, SensorEventListener {
    private static final int REQ_LOCATION = 101;
    private static final int REQ_IMPORT = 102;
    private static final int REQ_EXPORT_CSV = 103;
    private static final int REQ_EXPORT_GEOJSON = 104;
    private static final int REQ_BLE = 105;
    private static final int REQ_BACKGROUND_LOCATION = 106;
    private static final int REQ_IMPORT_ELEVATION = 107;
    private static final int REQ_EXPORT_GPX = 108;
    private static final int REQ_IMPORT_GPX = 109;
    private static final int REQ_EXPORT_JSONL = 110;
    private static final int REQ_IMPORT_REFERENCE_GPX = 111;
    private static final String PREFS_EXPORT = "lereng_export";
    private static final String PREFS_UPDATE = "lereng_update";
    private static final long UPDATE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final long UPDATE_SNOOZE_MS = 24L * 60L * 60L * 1000L;

    private SurveyDatabase database;
    private List<SurveyDatabase.Survey> surveys = new ArrayList<>();
    private long selectedSurveyId = -1;

    // Hero / project header
    private LinearLayout projectRow;
    private TextView projectNameText, projectMetaText;
    private TextView projectDataName, projectDataMeta;

    // Tabs
    private FrameLayout tabHost;
    private final List<ScrollView> tabPages = new ArrayList<>();
    private final List<LinearLayout> tabItems = new ArrayList<>();
    private final List<ImageView> tabIcons = new ArrayList<>();
    private final List<TextView> tabLabels = new ArrayList<>();
    private int activeTab;

    // Status chips
    private UiKit.Chip gpsChip, netChip, baroChip, bleChip;

    // Stats
    private UiKit.Stat statDistance, statAscent, statDescent, statParticipants;

    // Bottom recording bar
    private View recordDot;
    private TextView bottomStatus, bottomDetail;
    private Button bottomPrimary;
    private ImageView bottomWaypoint;
    private ValueAnimator pulseAnimator;
    private int lastTrackCount = -1;

    private TextView gpsText, networkText, pressureText, recordText, countText, elevationCountText;
    private TextView groupText, presenceText, profileText, waypointText, recentText;
    private TextView elevationDatasetText;
    private TextView exportStatusText, exportStatusMeta;
    private EditText syncEndpointInput, syncTokenInput;
    private TextView syncStatusText;
    private Switch syncToggle;
    private boolean suppressSyncToggle;
    private boolean syncReceiverRegistered;
    private SharedPreferences exportPrefs;
    private SharedPreferences updatePrefs;
    private AlertDialog updateDialog;
    private UpdateChecker.UpdateInfo offeredUpdate;
    private boolean updateCheckInFlight;
    /** Datum chosen before GPX export; empty means “export without elevation”. */
    private String pendingGpxDatum = GpxCodec.DATUM_ELLIPSOID;
    private Button permissionButton, startButton, stopButton, groupButton;
    private Button importElevationButton, selectElevationButton, deleteElevationButton;
    private RouteProfileView profileView;
    private TerrainView terrainView;
    private RouteMapView mapView;
    private TextView mapStatus;
    private TextView referenceStatus;
    private Button referenceDeleteButton;
    private TextView terrainStatus, terrain2dTab, terrain3dTab;
    private boolean terrain3d;
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor pressureSensor;
    private Location latestFix;
    private Location latestGpsFix;
    private Location latestNetworkFix;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkCallbackRegistered;
    private Float pressureHpa;
    private Float baselinePressureHpa;
    private boolean receiverRegistered;
    private boolean pendingStartAfterLocation;
    private boolean pendingStartAfterBle;
    /** True between the start command and the service flipping {@link LocationLoggingService#isLogging}. */
    private boolean startingRecording;
    private final ExecutorService profileExecutor = Executors.newSingleThreadExecutor();
    private long cachedElevationDatasetId = -1;
    private SurveyDatabase.ElevationDataset cachedElevationDataset;
    private LocalElevationEngine.Index cachedElevationIndex;

    private final BroadcastReceiver dataReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshAll(); }
    };

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshSyncStatus(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        database = new SurveyDatabase(this);
        exportPrefs = getSharedPreferences(PREFS_EXPORT, MODE_PRIVATE);
        updatePrefs = getSharedPreferences(PREFS_UPDATE, MODE_PRIVATE);
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        connectivityManager = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        pressureSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        Window window = getWindow();
        boolean nightMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        window.setStatusBarColor(getColor(R.color.hero_top));
        window.setNavigationBarColor(getColor(R.color.bg));
        window.getDecorView().setSystemUiVisibility(
                nightMode ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        buildScreen();
        selectedSurveyId = database.ensureDefaultSurvey();
        if (LocationLoggingService.isLogging && LocationLoggingService.activeSurveyId > 0)
            selectedSurveyId = LocationLoggingService.activeSurveyId;
        reloadSurveys(selectedSurveyId);
        MongoSyncScheduler.configure(this);
        refreshAll();
        updatePermissionState();
        checkForUpdate(false);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.bg));
        root.addView(buildHero());
        root.addView(buildTabBar());

        tabHost = new FrameLayout(this);
        root.addView(tabHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addPage(buildRecordPage());
        addPage(buildAnalysisPage());
        addPage(buildDataPage());

        View bar = buildBottomBar();
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        barParams.gravity = Gravity.BOTTOM;
        tabHost.addView(bar, barParams);

        setContentView(root);
        setActiveTab(0, false);
    }

    // ---------------------------------------------------------------- hero + tabs

    private View buildHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(16), dp(18), dp(14));
        hero.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{UiKit.color(this, R.color.hero_top), UiKit.color(this, R.color.hero_bottom)}));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_mountain);
        mark.setColorFilter(UiKit.color(this, R.color.hero_text));
        mark.setPadding(dp(9), dp(9), dp(9), dp(9));
        GradientDrawable markBg = new GradientDrawable();
        markBg.setColor(0x1FFFFFFF);
        markBg.setCornerRadius(dp(13));
        markBg.setStroke(dp(1), 0x33FFFFFF);
        mark.setBackground(markBg);
        brand.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, dp(8), 0);
        titles.addView(UiKit.text(this, "Lereng Offline", 21, R.color.hero_text, Typeface.BOLD));
        TextView tagline = UiKit.text(this, "Survei jalur & elevasi — data di perangkat",
                11.5f, R.color.hero_muted, Typeface.NORMAL);
        tagline.setPadding(0, dp(2), 0, 0);
        titles.addView(tagline);
        brand.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView local = UiKit.text(this, "100% LOKAL", 9.5f, R.color.hero_text, Typeface.BOLD);
        local.setLetterSpacing(0.08f);
        GradientDrawable localBg = new GradientDrawable();
        localBg.setColor(0x1FFFFFFF);
        localBg.setCornerRadius(dp(20));
        local.setBackground(localBg);
        local.setPadding(dp(9), dp(5), dp(9), dp(5));
        brand.addView(local);
        hero.addView(brand);

        projectRow = new LinearLayout(this);
        projectRow.setOrientation(LinearLayout.HORIZONTAL);
        projectRow.setGravity(Gravity.CENTER_VERTICAL);
        projectRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        projectRow.setBackground(UiKit.rippleFill(this, 0x1AFFFFFF, 14, true));
        projectRow.setClickable(true);
        projectRow.setFocusable(true);
        projectRow.setContentDescription("Pilih proyek outing");

        ImageView folder = new ImageView(this);
        folder.setImageResource(R.drawable.ic_folder);
        folder.setColorFilter(UiKit.color(this, R.color.hero_muted));
        projectRow.addView(folder, new LinearLayout.LayoutParams(dp(18), dp(18)));

        LinearLayout projectCol = new LinearLayout(this);
        projectCol.setOrientation(LinearLayout.VERTICAL);
        projectCol.setPadding(dp(10), 0, dp(8), 0);
        projectNameText = UiKit.text(this, "Memuat proyek…", 15, R.color.hero_text, Typeface.BOLD);
        projectNameText.setSingleLine(true);
        projectNameText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        projectMetaText = UiKit.text(this, "", 11, R.color.hero_muted, Typeface.NORMAL);
        projectMetaText.setSingleLine(true);
        projectMetaText.setPadding(0, dp(2), 0, 0);
        projectCol.addView(projectNameText);
        projectCol.addView(projectMetaText);
        projectRow.addView(projectCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        chevron.setColorFilter(UiKit.color(this, R.color.hero_muted));
        projectRow.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
        projectRow.setOnClickListener(v -> showProjectPicker());

        hero.addView(projectRow, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        return hero;
    }

    private View buildTabBar() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(UiKit.color(this, R.color.surface));
        wrapper.setPadding(dp(8), dp(6), dp(8), dp(6));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addTab(row, 0, R.drawable.ic_record, "Rekam");
        addTab(row, 1, R.drawable.ic_chart, "Analisis");
        addTab(row, 2, R.drawable.ic_database, "Data");
        wrapper.addView(row);

        View hairline = new View(this);
        hairline.setBackgroundColor(UiKit.color(this, R.color.outline));
        wrapper.addView(hairline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return wrapper;
    }

    private void addTab(LinearLayout parent, int index, int iconRes, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(8), 0, dp(8));
        item.setMinimumHeight(dp(52));
        item.setClickable(true);
        item.setFocusable(true);
        item.setBackground(UiKit.rippleFill(this, Color.TRANSPARENT, 14, false));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        item.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView text = UiKit.text(this, label, 11, R.color.text_muted, Typeface.BOLD);
        text.setPadding(0, dp(3), 0, 0);
        item.addView(text);

        item.setOnClickListener(v -> setActiveTab(index, true));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(item, params);
        tabItems.add(item);
        tabIcons.add(icon);
        tabLabels.add(text);
    }

    private void setActiveTab(int index, boolean animate) {
        if (index < 0 || index >= tabItems.size()) return;
        activeTab = index;
        for (int i = 0; i < tabItems.size(); i++) {
            boolean active = i == index;
            tabItems.get(i).setBackground(UiKit.rippleFill(this,
                    active ? UiKit.color(this, R.color.primary_container) : Color.TRANSPARENT, 14, false));
            int tint = UiKit.color(this, active ? R.color.on_primary_container : R.color.text_faint);
            tabIcons.get(i).setColorFilter(tint);
            tabLabels.get(i).setTextColor(tint);
            tabPages.get(i).setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (animate) {
            View page = tabPages.get(index);
            page.setAlpha(0f);
            page.animate().alpha(1f).setDuration(150).start();
        }
    }

    private void addPage(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        tabHost.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        tabPages.add(scroll);
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(116));
        return page;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------------- record tab

    private LinearLayout buildRecordPage() {
        LinearLayout page = page();

        LinearLayout statusCard = UiKit.card(this);
        statusCard.addView(UiKit.cardHeader(this, R.drawable.ic_gps, "Status lapangan", null));

        gpsChip = new UiKit.Chip(this);
        netChip = new UiKit.Chip(this);
        baroChip = new UiKit.Chip(this);
        bleChip = new UiKit.Chip(this);
        gpsChip.icon.setImageResource(R.drawable.ic_gps);
        netChip.icon.setImageResource(R.drawable.ic_wifi);
        baroChip.icon.setImageResource(R.drawable.ic_gauge);
        bleChip.icon.setImageResource(R.drawable.ic_bluetooth);
        gpsChip.set(UiKit.TONE_NEUTRAL, "GPS…");
        netChip.set(UiKit.TONE_NEUTRAL, "Cek jaringan");
        baroChip.set(UiKit.TONE_NEUTRAL, "Baro…");
        bleChip.set(UiKit.TONE_NEUTRAL, "BLE nonaktif");

        LinearLayout chipRows = new LinearLayout(this);
        chipRows.setOrientation(LinearLayout.VERTICAL);
        chipRows.setPadding(0, dp(11), 0, 0);
        chipRows.addView(chipRow(gpsChip, netChip));
        LinearLayout secondRow = chipRow(baroChip, bleChip);
        secondRow.setPadding(0, dp(6), 0, 0);
        chipRows.addView(secondRow);
        statusCard.addView(chipRows);

        gpsText = UiKit.text(this, "Menunggu fix dari Android network location atau GPS/GNSS.",
                14, R.color.text, Typeface.NORMAL);
        gpsText.setLineSpacing(dp(2), 1f);
        gpsText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusCard.addView(gpsText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 11, 0, 0));

        networkText = UiKit.caption(this, "Memeriksa koneksi…");
        statusCard.addView(networkText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 5, 0, 0));

        permissionButton = UiKit.button(this, "Aktifkan lokasi", UiKit.STYLE_SECONDARY, R.drawable.ic_gps);
        permissionButton.setOnClickListener(v -> requestLocationAccess());
        statusCard.addView(permissionButton, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48), 0, 11, 0, 0));
        UiKit.addCard(page, statusCard, 0);

        LinearLayout recordCard = UiKit.card(this);
        recordCard.addView(UiKit.cardHeader(this, R.drawable.ic_record, "Perekaman", null));
        recordText = UiKit.body(this, "Perekaman berhenti. Titik lokasi disimpan lokal kira-kira tiap 10 detik.");
        recordCard.addView(recordText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 9, 0, 0));

        LinearLayout recordActions = new LinearLayout(this);
        recordActions.setOrientation(LinearLayout.HORIZONTAL);
        startButton = UiKit.button(this, "Mulai rekam", UiKit.STYLE_PRIMARY, R.drawable.ic_record);
        stopButton = UiKit.button(this, "Hentikan", UiKit.STYLE_DANGER, R.drawable.ic_stop);
        startButton.setOnClickListener(v -> startRecording());
        stopButton.setOnClickListener(v -> stopRecording());
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        startParams.rightMargin = dp(8);
        recordActions.addView(startButton, startParams);
        recordActions.addView(stopButton, new LinearLayout.LayoutParams(0, dp(50), 1f));
        recordCard.addView(recordActions, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 13, 0, 0));

        pressureText = UiKit.caption(this, "Memeriksa sensor tekanan…");
        recordCard.addView(pressureText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 11, 0, 0));
        UiKit.addCard(page, recordCard, 12);

        LinearLayout captureCard = UiKit.card(this);
        captureCard.addView(UiKit.cardHeader(this, R.drawable.ic_flag, "Titik & waypoint", null));
        captureCard.addView(UiKit.caption(this,
                "Titik ukur masuk ke track. Waypoint adalah lokasi berlabel—puncak, pos, atau persimpangan."),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        LinearLayout captureActions = new LinearLayout(this);
        captureActions.setOrientation(LinearLayout.HORIZONTAL);
        Button captureButton = UiKit.button(this, "Titik ukur", UiKit.STYLE_SECONDARY, R.drawable.ic_gps);
        Button waypointButton = UiKit.button(this, "Waypoint", UiKit.STYLE_ACCENT, R.drawable.ic_flag);
        captureButton.setOnClickListener(v -> captureManualPoint());
        waypointButton.setOnClickListener(v -> captureWaypoint());
        LinearLayout.LayoutParams captureParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        captureParams.rightMargin = dp(8);
        captureActions.addView(captureButton, captureParams);
        captureActions.addView(waypointButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        captureCard.addView(captureActions, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        UiKit.addCard(page, captureCard, 12);

        LinearLayout groupCard = UiKit.card(this);
        groupCard.addView(UiKit.cardHeader(this, R.drawable.ic_bluetooth, "Rombongan", "BLE OPSIONAL"));
        groupText = UiKit.text(this, "Kode outing belum diatur.", 13.5f, R.color.text, Typeface.BOLD);
        groupText.setLineSpacing(dp(2), 1f);
        groupCard.addView(groupText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 9, 0, 0));
        groupButton = UiKit.button(this, "Atur kode & alias", UiKit.STYLE_SECONDARY, R.drawable.ic_settings);
        groupButton.setOnClickListener(v -> showGroupDialog());
        groupCard.addView(groupButton, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48), 0, 11, 0, 0));
        presenceText = UiKit.caption(this, "Belum ada deteksi BLE tersimpan.");
        groupCard.addView(presenceText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        UiKit.addCard(page, groupCard, 12);

        LinearLayout recentCard = UiKit.card(this);
        recentCard.addView(UiKit.cardHeader(this, R.drawable.ic_route, "Catatan terbaru", null));
        countText = UiKit.text(this, "0 catatan", 13.5f, R.color.text, Typeface.BOLD);
        elevationCountText = UiKit.caption(this, "0 elevasi • 0 waypoint");
        recentCard.addView(countText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 9, 0, 0));
        recentCard.addView(elevationCountText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 3, 0, 0));
        recentText = UiKit.mono(this, "Belum ada titik.");
        recentCard.addView(recentText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));

        LinearLayout recentActions = new LinearLayout(this);
        recentActions.setOrientation(LinearLayout.HORIZONTAL);
        Button viewAllButton = UiKit.button(this, "Lihat semua", UiKit.STYLE_GHOST, R.drawable.ic_route);
        Button deleteLastButton = UiKit.button(this, "Hapus terakhir", UiKit.STYLE_GHOST, R.drawable.ic_trash);
        viewAllButton.setOnClickListener(v -> showPointList());
        deleteLastButton.setOnClickListener(v -> deleteLastTrackPoint());
        LinearLayout.LayoutParams viewAllParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        viewAllParams.rightMargin = dp(8);
        recentActions.addView(viewAllButton, viewAllParams);
        recentActions.addView(deleteLastButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        recentCard.addView(recentActions, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        UiKit.addCard(page, recentCard, 12);

        return page;
    }

    private LinearLayout chipRow(UiKit.Chip left, UiKit.Chip right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        leftParams.rightMargin = dp(6);
        row.addView(left.root, leftParams);
        row.addView(right.root, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    // ---------------------------------------------------------------- analysis tab

    private LinearLayout buildAnalysisPage() {
        LinearLayout page = page();

        LinearLayout mapCard = UiKit.card(this);
        mapCard.addView(UiKit.cardHeader(this, R.drawable.ic_mountain, "Peta jalur", "OFFLINE"));
        mapCard.addView(UiKit.caption(this,
                "Digambar dari titik tersimpan. Tanpa ubin, tanpa unduhan peta; area kosong berarti belum terukur."),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        mapView = new RouteMapView(this);
        mapCard.addView(mapView, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(282), 0, 12, 0, 0));
        mapStatus = UiKit.caption(this, "Belum ada jalur untuk dipetakan.");
        mapStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        mapCard.addView(mapStatus, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 11, 0, 0));
        Button resetMapButton = UiKit.button(this, "Reset tampilan peta", UiKit.STYLE_GHOST, R.drawable.ic_layers);
        resetMapButton.setOnClickListener(v -> mapView.resetView());
        mapCard.addView(resetMapButton, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44), 0, 6, 0, 0));
        UiKit.addCard(page, mapCard, 0);

        LinearLayout referenceCard = UiKit.card(this);
        referenceCard.addView(UiKit.cardHeader(this, R.drawable.ic_route, "Jalur pembanding", "GPX"));
        referenceCard.addView(UiKit.caption(this,
                "Impor GPX dari aplikasi lain (mis. ekspor Strava) untuk dibandingkan dengan rekaman sendiri. "
                        + "Garis putus-putus; tidak digabung ke titik rekaman dan tidak ikut diekspor."),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        referenceStatus = UiKit.caption(this, "Belum ada jalur pembanding.");
        referenceStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        referenceCard.addView(referenceStatus, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 9, 0, 0));

        LinearLayout referenceActions = new LinearLayout(this);
        referenceActions.setOrientation(LinearLayout.HORIZONTAL);
        Button referenceImportButton = UiKit.button(this, "Impor GPX", UiKit.STYLE_GHOST, R.drawable.ic_route);
        referenceDeleteButton = UiKit.button(this, "Hapus", UiKit.STYLE_GHOST, R.drawable.ic_trash);
        referenceImportButton.setOnClickListener(v -> importReferenceGpx());
        referenceDeleteButton.setOnClickListener(v -> confirmDeleteReferenceTrack());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        importParams.rightMargin = dp(8);
        referenceActions.addView(referenceImportButton, importParams);
        referenceActions.addView(referenceDeleteButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        referenceCard.addView(referenceActions, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        UiKit.addCard(page, referenceCard, 12);

        LinearLayout profileCard = UiKit.card(this);
        profileCard.addView(UiKit.cardHeader(this, R.drawable.ic_chart, "Profil elevasi", "SATU JALUR"));

        statDistance = new UiKit.Stat(this, "Jarak");
        statAscent = new UiKit.Stat(this, "Naik");
        statDescent = new UiKit.Stat(this, "Turun");
        statParticipants = new UiKit.Stat(this, "Peserta");
        LinearLayout statTop = UiKit.statRow(this, statDistance, statAscent, 8);
        statTop.setPadding(0, dp(12), 0, 0);
        profileCard.addView(statTop);
        LinearLayout statBottom = UiKit.statRow(this, statDescent, statParticipants, 8);
        statBottom.setPadding(0, dp(8), 0, 0);
        profileCard.addView(statBottom);

        profileView = new RouteProfileView(this);
        profileView.setPlaceholder("Belum ada track\nRekam atau impor CSV peserta");
        profileCard.addView(profileView, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(232), 0, 13, 0, 0));

        profileText = UiKit.body(this, "Tambahkan titik GPS ber-elevasi untuk membuat profil.");
        profileCard.addView(profileText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 11, 0, 0));
        UiKit.addCard(page, profileCard, 12);

        LinearLayout waypointCard = UiKit.card(this);
        waypointCard.addView(UiKit.cardHeader(this, R.drawable.ic_flag, "Waypoint", null));
        waypointText = UiKit.caption(this, "Waypoint belum ditandai.");
        waypointCard.addView(waypointText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 9, 0, 0));
        UiKit.addCard(page, waypointCard, 12);

        LinearLayout terrainCard = UiKit.card(this);
        LinearLayout terrainHeader = new LinearLayout(this);
        terrainHeader.setOrientation(LinearLayout.HORIZONTAL);
        terrainHeader.setGravity(Gravity.CENTER_VERTICAL);
        terrainHeader.addView(UiKit.cardHeader(this, R.drawable.ic_terrain, "Permukaan terrain", null),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        terrainHeader.addView(buildTerrainSegment());
        terrainCard.addView(terrainHeader);

        terrainView = new TerrainView(this);
        terrainCard.addView(terrainView, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(278), 0, 12, 0, 0));

        terrainStatus = UiKit.caption(this, "Coverage belum cukup. Blank = belum ada coverage.");
        terrainStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        terrainCard.addView(terrainStatus, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 11, 0, 0));

        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.setPadding(0, dp(9), 0, 0);
        legend.addView(legendDot(R.color.terrain_low), new LinearLayout.LayoutParams(dp(9), dp(9)));
        legend.addView(UiKit.caption(this, "rendah"), UiKit.lp(this, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 5, 0, 12, 0));
        legend.addView(legendDot(R.color.terrain_peak), new LinearLayout.LayoutParams(dp(9), dp(9)));
        legend.addView(UiKit.caption(this, "tinggi"), UiKit.lp(this, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 5, 0, 0, 0));
        terrainCard.addView(legend);
        UiKit.addCard(page, terrainCard, 12);

        return page;
    }

    private View legendDot(int colorRes) {
        View dot = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(UiKit.color(this, colorRes));
        dot.setBackground(bg);
        return dot;
    }

    private LinearLayout buildTerrainSegment() {
        LinearLayout segment = new LinearLayout(this);
        segment.setOrientation(LinearLayout.HORIZONTAL);
        segment.setPadding(dp(3), dp(3), dp(3), dp(3));
        segment.setBackground(UiKit.fill(this, R.color.surface_alt, 12));

        terrain2dTab = UiKit.text(this, "2D", 12, R.color.text, Typeface.BOLD);
        terrain2dTab.setGravity(Gravity.CENTER);
        terrain2dTab.setMinWidth(dp(52));
        terrain2dTab.setPadding(dp(10), dp(6), dp(10), dp(6));
        terrain3dTab = UiKit.text(this, "3D", 12, R.color.text, Typeface.BOLD);
        terrain3dTab.setGravity(Gravity.CENTER);
        terrain3dTab.setMinWidth(dp(52));
        terrain3dTab.setPadding(dp(10), dp(6), dp(10), dp(6));
        terrain2dTab.setOnClickListener(v -> setTerrainMode(false));
        terrain3dTab.setOnClickListener(v -> setTerrainMode(true));
        segment.addView(terrain2dTab);
        segment.addView(terrain3dTab);
        setTerrainMode(false);
        return segment;
    }

    private void setTerrainMode(boolean is3d) {
        terrain3d = is3d;
        if (terrainView != null) terrainView.setSurface3d(is3d);
        if (terrain2dTab == null || terrain3dTab == null) return;
        terrain2dTab.setBackground(UiKit.rippleFill(this,
                !is3d ? UiKit.color(this, R.color.surface) : Color.TRANSPARENT, 10, false));
        terrain3dTab.setBackground(UiKit.rippleFill(this,
                is3d ? UiKit.color(this, R.color.surface) : Color.TRANSPARENT, 10, false));
        terrain2dTab.setTextColor(UiKit.color(this, !is3d ? R.color.primary : R.color.text_faint));
        terrain3dTab.setTextColor(UiKit.color(this, is3d ? R.color.primary : R.color.text_faint));
    }

    // ---------------------------------------------------------------- data tab

    private LinearLayout buildDataPage() {
        LinearLayout page = page();

        LinearLayout exportCard = UiKit.card(this);
        exportCard.addView(UiKit.cardHeader(this, R.drawable.ic_export, "Gabungkan & ekspor", null));
        exportCard.addView(UiKit.caption(this,
                "CSV menggabungkan peserta dan event BLE. GPX mencadangkan jalur + waypoint. GeoJSON = satu LineString + waypoint; JSONL = satu dokumen per titik untuk MongoDB. Ekspor sendiri tidak mengunggah; unggahan ke MongoDB berjalan otomatis di latar belakang saat endpoint dan token sudah diisi."),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

        Button importButton = UiKit.button(this, "Impor CSV peserta", UiKit.STYLE_SECONDARY, R.drawable.ic_import);
        Button exportButton = UiKit.button(this, "Ekspor CSV grup", UiKit.STYLE_PRIMARY, R.drawable.ic_export);
        importButton.setOnClickListener(v -> importCsv());
        exportButton.setOnClickListener(v -> beginExport(false));
        LinearLayout exportRow = new LinearLayout(this);
        exportRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        importParams.rightMargin = dp(8);
        exportRow.addView(importButton, importParams);
        exportRow.addView(exportButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        exportCard.addView(exportRow, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));

        Button gpxExportButton = UiKit.button(this, "Ekspor GPX", UiKit.STYLE_SECONDARY, R.drawable.ic_route);
        Button gpxImportButton = UiKit.button(this, "Impor GPX", UiKit.STYLE_SECONDARY, R.drawable.ic_import);
        gpxExportButton.setOnClickListener(v -> beginGpxExport());
        gpxImportButton.setOnClickListener(v -> importGpx());
        LinearLayout gpxRow = new LinearLayout(this);
        gpxRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gpxExportParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        gpxExportParams.rightMargin = dp(8);
        gpxRow.addView(gpxExportButton, gpxExportParams);
        gpxRow.addView(gpxImportButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        exportCard.addView(gpxRow, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

        Button geojsonButton = UiKit.button(this, "Ekspor jalur sebagai GeoJSON",
                UiKit.STYLE_GHOST, R.drawable.ic_route);
        geojsonButton.setOnClickListener(v -> beginExport(true));
        exportCard.addView(geojsonButton, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46), 0, 8, 0, 0));

        Button jsonlButton = UiKit.button(this, "Ekspor JSONL untuk MongoDB",
                UiKit.STYLE_GHOST, R.drawable.ic_database);
        jsonlButton.setOnClickListener(v -> beginJsonLinesExport());
        exportCard.addView(jsonlButton, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46), 0, 8, 0, 0));

        exportCard.addView(UiKit.divider(this), UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1), 0, 14, 0, 0));
        exportStatusText = UiKit.text(this, "Belum pernah diekspor", 13.5f, R.color.warning, Typeface.BOLD);
        exportStatusText.setLineSpacing(dp(2), 1f);
        exportStatusMeta = UiKit.caption(this, "Penyimpanan hanya di perangkat ini.");
        exportStatusMeta.setLineSpacing(dp(2), 1f);
        exportCard.addView(exportStatusText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        exportCard.addView(exportStatusMeta, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        UiKit.addCard(page, exportCard, 0);

        UiKit.addCard(page, buildSyncCard(), 12);

        LinearLayout datasetCard = UiKit.card(this);
        datasetCard.addView(UiKit.cardHeader(this, R.drawable.ic_database, "Dataset elevasi lokal", null));
        elevationDatasetText = UiKit.text(this, "Belum ada dataset aktif.", 13.5f, R.color.text, Typeface.BOLD);
        elevationDatasetText.setLineSpacing(dp(2), 1f);
        datasetCard.addView(elevationDatasetText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 9, 0, 0));
        datasetCard.addView(UiKit.caption(this,
                "CSV latitude, longitude, elevation_m dalam WGS84 dan meter. Datum dicatat sebagai keterangan, tidak dikonversi."),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));

        importElevationButton = UiKit.button(this, "Impor CSV elevasi", UiKit.STYLE_PRIMARY, R.drawable.ic_import);
        selectElevationButton = UiKit.button(this, "Pilih dataset", UiKit.STYLE_SECONDARY, R.drawable.ic_folder);
        importElevationButton.setOnClickListener(v -> importElevationCsv());
        selectElevationButton.setOnClickListener(v -> chooseElevationDataset());
        LinearLayout datasetRow = new LinearLayout(this);
        datasetRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams importElevationParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        importElevationParams.rightMargin = dp(8);
        datasetRow.addView(importElevationButton, importElevationParams);
        datasetRow.addView(selectElevationButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        datasetCard.addView(datasetRow, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));

        deleteElevationButton = UiKit.button(this, "Hapus dataset aktif", UiKit.STYLE_GHOST, R.drawable.ic_trash);
        deleteElevationButton.setTextColor(getColor(R.color.danger));
        Drawable[] deleteDrawables = deleteElevationButton.getCompoundDrawablesRelative();
        if (deleteDrawables[0] != null) deleteDrawables[0].setTint(getColor(R.color.danger));
        deleteElevationButton.setOnClickListener(v -> deleteActiveElevationDataset());
        datasetCard.addView(deleteElevationButton, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46), 0, 8, 0, 0));
        UiKit.addCard(page, datasetCard, 12);

        LinearLayout projectCard = UiKit.card(this);
        projectCard.addView(UiKit.cardHeader(this, R.drawable.ic_folder, "Proyek outing", "KELOLA"));
        projectDataName = UiKit.text(this, "Belum ada proyek", 15.5f, R.color.text, Typeface.BOLD);
        projectDataMeta = UiKit.caption(this, "—");
        projectCard.addView(projectDataName, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 9, 0, 0));
        projectCard.addView(projectDataMeta, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));

        Button renameButton = UiKit.button(this, "Ganti nama", UiKit.STYLE_SECONDARY, R.drawable.ic_settings);
        Button deleteProjectButton = UiKit.button(this, "Hapus proyek", UiKit.STYLE_GHOST, R.drawable.ic_trash);
        deleteProjectButton.setTextColor(getColor(R.color.danger));
        Drawable[] projectDeleteDrawables = deleteProjectButton.getCompoundDrawablesRelative();
        if (projectDeleteDrawables[0] != null) projectDeleteDrawables[0].setTint(getColor(R.color.danger));
        renameButton.setOnClickListener(v -> showRenameSurveyDialog());
        deleteProjectButton.setOnClickListener(v -> confirmDeleteSurvey());
        LinearLayout projectActions = new LinearLayout(this);
        projectActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams renameParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        renameParams.rightMargin = dp(8);
        projectActions.addView(renameButton, renameParams);
        projectActions.addView(deleteProjectButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        projectCard.addView(projectActions, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        UiKit.addCard(page, projectCard, 12);

        addDisclosure(page, R.drawable.ic_info, "Catatan & batasan",
                "LOKASI\n"
                + "Provider jaringan Android dan GNSS diminta bersamaan. Saat internet putus, fix jaringan lama dibuang dan GNSS tetap diminta; perekaman tidak dijeda. Ketersediaan bergantung perangkat dan layanan lokasi Android. Track dikirim otomatis ke endpoint HTTPS milikmu saat ada data baru dan saat internet tersedia.\n\n"
                + "ELEVASI\n"
                + "Elevasi Location Android adalah meter di atas ellipsoid WGS84, bukan otomatis tinggi permukaan laut. Akurasi horizontal bukan ukuran akurasi elevasi. Profil dan naik/turun bersifat indikatif, bukan survei resmi.\n\n"
                + "DATASET LOKAL\n"
                + "Koreksi hanya diterapkan bila seluruh stasiun rute tercakup. Jika parsial, profil GPS asli dipertahankan; jika GPS juga tidak lengkap, profil tidak ditampilkan. Datum berbeda tidak pernah digabung. Nama datum hanya keterangan—tanpa konversi.\n\n"
                + "TERRAIN\n"
                + "Permukaan dan kontur adalah interpolasi lokal di dalam dukungan sampel, bukan DEM area luas. Sel di bawah 25% coverage, kurang dari 8 sel valid, atau terpecah tidak digambar. Area kosong berarti belum terukur.\n\n"
                + "BLE\n"
                + "BLE hanya mencatat ID samaran dan waktu terlihat; tidak menyimpan MAC/RSSI, tidak mengukur jarak, posisi, elevasi, atau triangulasi. iOS dapat membatasi scan/advertising di background. BLE bukan alat keselamatan.\n\n"
                + "PENYIMPANAN\n"
                + "Track, event, dan dataset tersimpan di SQLite privat perangkat. Tidak ada pencadangan otomatis. Ekspor GPX/CSV/GeoJSON hanya melalui pemilih file Android; file ekspor tidak diunggah. Jika sinkron otomatis aktif dan endpoint serta token sudah diisi, data baru dikirim sendiri ke endpoint HTTPS milikmu yang menulis ke MongoDB. Token disimpan di penyimpanan privat aplikasi, bukan URI MongoDB. Tanpa internet, data menunggu dan dikirim saat koneksi kembali.\n\n"
                + "BATERAI\n"
                + "Beberapa produsen membatasi proses background. Jika rekaman terputus saat layar mati, izinkan aktivitas background atau nonaktifkan optimasi baterai untuk Lereng Offline. Izin lokasi “Saat digunakan” juga dapat menghentikan rekaman di background pada sebagian perangkat.",
                12);

        LinearLayout aboutCard = UiKit.card(this);
        aboutCard.addView(UiKit.cardHeader(this, R.drawable.ic_info, "Tentang aplikasi", null));
        aboutCard.addView(UiKit.caption(this,
                "Lereng Offline v" + BuildConfig.VERSION_NAME + " • satu jalur per proyek\n"
                + "Data tetap di perangkat sampai sinkron otomatis mengirimkannya ke endpoint HTTPS milikmu. Pemeriksaan pembaruan hanya mengambil satu file JSON publik."),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 9, 0, 0));

        Button updateButton = UiKit.button(this, "Cek pembaruan",
                UiKit.STYLE_GHOST, R.drawable.ic_update);
        updateButton.setOnClickListener(v -> checkForUpdate(true));
        aboutCard.addView(updateButton, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46), 0, 10, 0, 0));

        UiKit.addCard(page, aboutCard, 12);

        TextView footer = UiKit.caption(this, "Data tetap di perangkat sampai kamu mengirimnya.");
        footer.setGravity(Gravity.CENTER);
        page.addView(footer, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        return page;
    }

    private LinearLayout buildSyncCard() {
        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.cardHeader(this, R.drawable.ic_database, "Sinkron otomatis", "MONGODB"));
        card.addView(UiKit.caption(this,
                "Semua proyek dikirim otomatis saat ada data baru, saat aplikasi dibuka, dan berkala di background. "
                + "Endpoint HTTPS milikmu yang menyimpan URI MongoDB; aplikasi hanya menyimpan alamat dan token. "
                + "Jalur pembanding GPX tidak ikut."),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

        card.addView(UiKit.sectionLabel(this, "Endpoint HTTPS"),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        syncEndpointInput = UiKit.input(this, "https://proyek.vercel.app/api/ingest");
        syncEndpointInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        syncEndpointInput.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        card.addView(syncEndpointInput, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));

        card.addView(UiKit.sectionLabel(this, "Token"),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        syncTokenInput = UiKit.input(this, "Token yang sama dengan INGEST_TOKEN");
        syncTokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        syncTokenInput.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        card.addView(syncTokenInput, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));

        syncToggle = new Switch(this);
        syncToggle.setText("Sinkron otomatis");
        syncToggle.setTextSize(14.5f);
        syncToggle.setTextColor(getColor(R.color.text));
        syncToggle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        syncToggle.setPadding(0, dp(4), 0, dp(4));
        syncToggle.setChecked(MongoSync.isAutoEnabled(this));
        syncToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSyncToggle) return;
            MongoSync.setAutoEnabled(this, isChecked);
            if (isChecked) {
                if (MongoSync.isConfigured(this)) {
                    MongoSyncScheduler.configure(this);
                    MongoSyncScheduler.requestSync(this, 0);
                    setSyncStatus("Sinkron otomatis aktif. Data baru akan dikirim sendiri.", true);
                } else {
                    setSyncStatus("Sinkron otomatis aktif. Isi endpoint dan token untuk mulai.", false);
                }
            } else {
                MongoSyncScheduler.cancelAll(this);
                setSyncStatus("Sinkron otomatis dijeda. Data tetap tersimpan lokal.", false);
            }
        });
        card.addView(syncToggle, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));

        Button saveButton = UiKit.button(this, "Simpan pengaturan", UiKit.STYLE_SECONDARY, R.drawable.ic_settings);
        saveButton.setOnClickListener(v -> saveSyncSettings());
        card.addView(saveButton, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46), 0, 10, 0, 0));

        syncStatusText = UiKit.text(this, "Belum diatur.", 13.5f, R.color.warning, Typeface.BOLD);
        syncStatusText.setLineSpacing(dp(2), 1f);
        card.addView(syncStatusText, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        card.addView(UiKit.caption(this,
                "Token disimpan di penyimpanan privat aplikasi. Tidak ada tombol kirim: sinkron berjalan sendiri "
                + "selama sakelar aktif dan endpoint terisi. Matikan sakelar untuk berhenti; data tetap tersimpan lokal."),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));

        Button clearButton = UiKit.button(this, "Hapus alamat & token", UiKit.STYLE_GHOST, R.drawable.ic_trash);
        clearButton.setTextColor(getColor(R.color.danger));
        Drawable[] clearDrawables = clearButton.getCompoundDrawablesRelative();
        if (clearDrawables[0] != null) clearDrawables[0].setTint(getColor(R.color.danger));
        clearButton.setOnClickListener(v -> {
            MongoSync.clear(this);
            MongoSyncScheduler.cancelAll(this);
            syncEndpointInput.setText("");
            syncTokenInput.setText("");
            suppressSyncToggle = true;
            syncToggle.setChecked(false);
            suppressSyncToggle = false;
            setSyncStatus("Alamat dan token dihapus. Sinkron otomatis dimatikan.", false);
        });
        card.addView(clearButton, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46), 0, 6, 0, 0));

        MongoSync.Config config = MongoSync.load(this);
        if (config != null) {
            syncEndpointInput.setText(config.endpoint.toString());
            syncTokenInput.setText(config.token);
            refreshSyncStatus();
        } else {
            setSyncStatus("Belum diatur. Isi endpoint dan token.", false);
        }
        return card;
    }

    private void saveSyncSettings() {
        try {
            MongoSync.Config config = MongoSync.save(this,
                    syncEndpointInput.getText().toString(), syncTokenInput.getText().toString());
            syncEndpointInput.setText(config.endpoint.toString());
            if (MongoSync.isAutoEnabled(this)) {
                MongoSyncScheduler.configure(this);
                MongoSyncScheduler.requestSync(this, 0);
                setSyncStatus("Pengaturan tersimpan. Sinkron otomatis aktif.", true);
            } else {
                setSyncStatus("Pengaturan tersimpan. Sinkron otomatis sedang dijeda.", false);
            }
            hideKeyboard();
        } catch (IllegalArgumentException e) {
            showSyncError(e.getMessage());
        }
    }

    private void refreshSyncStatus() {
        if (syncStatusText == null) return;
        boolean ok = MongoSync.lastStatusOk(this);
        syncStatusText.setTextColor(getColor(ok ? R.color.success : R.color.danger));
        syncStatusText.setText(MongoSync.lastStatus(this));
    }

    private void setSyncStatus(String message, boolean ok) {
        if (syncStatusText == null) return;
        syncStatusText.setTextColor(getColor(ok ? R.color.success : R.color.warning));
        syncStatusText.setText(message);
    }

    private void showSyncError(String message) {
        if (syncStatusText == null) return;
        syncStatusText.setTextColor(getColor(R.color.danger));
        syncStatusText.setText(message == null ? "Pengaturan sinkron tidak valid." : message);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private void addDisclosure(LinearLayout page, int iconRes, String title, String bodyText, float topMargin) {
        LinearLayout card = UiKit.card(this);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        header.setClickable(true);
        header.setFocusable(true);
        header.setContentDescription(title + ", ketuk untuk membuka");

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(UiKit.color(this, R.color.primary));
        header.addView(icon, new LinearLayout.LayoutParams(dp(19), dp(19)));

        TextView label = UiKit.text(this, title, 15.5f, R.color.text, Typeface.BOLD);
        label.setPadding(dp(9), 0, dp(8), 0);
        header.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        chevron.setColorFilter(UiKit.color(this, R.color.text_faint));
        header.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView content = UiKit.body(this, bodyText);
        content.setVisibility(View.GONE);
        card.addView(header);
        card.addView(content, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));

        header.setOnClickListener(v -> {
            boolean show = content.getVisibility() != View.VISIBLE;
            content.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(show ? 180f : 0f).setDuration(160).start();
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        });
        UiKit.addCard(page, card, topMargin);
    }

    // ---------------------------------------------------------------- bottom bar

    private View buildBottomBar() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.color(this, R.color.surface));
        shell.setElevation(dp(14));

        View hairline = new View(this);
        hairline.setBackgroundColor(UiKit.color(this, R.color.outline));
        shell.addView(hairline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(14), dp(10));

        recordDot = new View(this);
        recordDot.setBackground(UiKit.circle(this, R.color.text_faint));
        bar.addView(recordDot, new LinearLayout.LayoutParams(dp(10), dp(10)));

        LinearLayout statusCol = new LinearLayout(this);
        statusCol.setOrientation(LinearLayout.VERTICAL);
        statusCol.setPadding(dp(10), 0, dp(8), 0);
        bottomStatus = UiKit.text(this, "Siap merekam", 13, R.color.text, Typeface.BOLD);
        bottomDetail = UiKit.text(this, "Tekan mulai untuk merekam jalur ini", 11, R.color.text_faint, Typeface.NORMAL);
        bottomDetail.setSingleLine(true);
        bottomDetail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusCol.addView(bottomStatus);
        statusCol.addView(bottomDetail);
        bar.addView(statusCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        bottomWaypoint = UiKit.iconButton(this, R.drawable.ic_flag, "Tandai waypoint", R.color.accent);
        bottomWaypoint.setOnClickListener(v -> captureWaypoint());
        bar.addView(bottomWaypoint, new LinearLayout.LayoutParams(dp(48), dp(48)));

        bottomPrimary = UiKit.button(this, "Mulai", UiKit.STYLE_PRIMARY, R.drawable.ic_record);
        bottomPrimary.setOnClickListener(v -> {
            if (LocationLoggingService.isLogging) stopRecording();
            else startRecording();
        });
        bar.addView(bottomPrimary, UiKit.lp(this, dp(128), dp(48), 6, 0, 0, 0));

        shell.addView(bar);
        return shell;
    }

    private void showProjectPicker() {
        if (LocationLoggingService.isLogging) {
            Toast.makeText(this, "Hentikan rekaman sebelum berpindah proyek.", Toast.LENGTH_SHORT).show();
            return;
        }
        surveys = database.getSurveys();
        if (surveys.isEmpty()) {
            showNewSurveyDialog();
            return;
        }
        CharSequence[] items = new CharSequence[surveys.size()];
        int selected = 0;
        for (int i = 0; i < surveys.size(); i++) {
            SurveyDatabase.Survey survey = surveys.get(i);
            String prefix = survey.id == selectedSurveyId ? "●  " : "○  ";
            items[i] = prefix + survey.name + "   •   " + database.countPoints(survey.id) + " titik";
            if (survey.id == selectedSurveyId) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Proyek outing")
                .setSingleChoiceItems(items, selected, (dialog, which) -> {
                    long choice = surveys.get(which).id;
                    dialog.dismiss();
                    if (choice == selectedSurveyId) return;
                    selectedSurveyId = choice;
                    profileView.setProfile(null);
                    profileView.setPlaceholder("Profil proyek ini belum dibuat");
                    refreshAll();
                })
                .setNeutralButton("+ Proyek baru", (dialog, which) -> showNewSurveyDialog())
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void updateProjectHeader() {
        if (projectNameText == null || projectMetaText == null) return;
        SurveyDatabase.Survey survey = currentSurvey();
        if (survey == null) {
            projectNameText.setText("Pilih proyek");
            projectMetaText.setText("Belum ada proyek");
            if (projectDataName != null) projectDataName.setText("Belum ada proyek");
            if (projectDataMeta != null) projectDataMeta.setText("Buat proyek baru dari header atau pemilih proyek.");
            if (projectRow != null) projectRow.setContentDescription("Pilih atau buat proyek outing");
            return;
        }
        projectNameText.setText(survey.name);
        int points = database.countPoints(survey.id);
        int people = database.countParticipants(survey.id);
        String meta = points + " titik • " + people + " peserta • "
                + (survey.groupCode.isEmpty() ? "BLE nonaktif" : survey.groupCode);
        projectMetaText.setText(meta);
        if (projectDataName != null) projectDataName.setText(survey.name);
        if (projectDataMeta != null)
            projectDataMeta.setText(points + " titik • " + database.countWaypoints(survey.id)
                    + " waypoint • " + people + " peserta");
        if (projectRow != null)
            projectRow.setContentDescription("Proyek " + survey.name + ". Ketuk untuk mengganti proyek outing.");
    }

    private void reloadSurveys(long preferredId) {
        if (LocationLoggingService.isLogging && LocationLoggingService.activeSurveyId > 0)
            preferredId = LocationLoggingService.activeSurveyId;
        surveys = database.getSurveys();
        if (surveys.isEmpty()) {
            selectedSurveyId = database.ensureDefaultSurvey();
            surveys = database.getSurveys();
        }
        if (surveys.isEmpty()) return;
        selectedSurveyId = surveys.get(0).id;
        for (SurveyDatabase.Survey survey : surveys) {
            if (survey.id == preferredId) {
                selectedSurveyId = survey.id;
                break;
            }
        }
        updateProjectHeader();
    }

    private void showNewSurveyDialog() {
        EditText input = UiKit.input(this, "Contoh: Gunung Gede • Nov 2026");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);
        form.addView(input);
        new AlertDialog.Builder(this).setTitle("Proyek outing baru")
                .setMessage("Satu proyek untuk satu jalur/outing. ID samaran peserta dibuat terpisah untuk proyek ini.")
                .setView(form).setNegativeButton("Batal", null)
                .setPositiveButton("Buat", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "Pendakian " + java.time.LocalDate.now();
                    long id = database.createSurvey(name);
                    reloadSurveys(id);
                    refreshAll();
                    Toast.makeText(this, "Proyek “" + name + "” dibuat.", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private SurveyDatabase.Survey currentSurvey() { return database == null ? null : database.getSurvey(selectedSurveyId); }

    // ---------------------------------------------------------------- project & point management

    private void showRenameSurveyDialog() {
        SurveyDatabase.Survey survey = currentSurvey();
        if (survey == null) return;
        EditText input = UiKit.input(this, "Nama proyek");
        input.setText(survey.name);
        input.setSelection(input.getText().length());
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);
        form.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        new AlertDialog.Builder(this)
                .setTitle("Ganti nama proyek")
                .setMessage("Nama hanya mengubah label proyek; track, waypoint, dan dataset tidak berubah.")
                .setView(form)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Nama proyek tidak boleh kosong.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    database.renameSurvey(survey.id, name);
                    reloadSurveys(survey.id);
                    refreshAll();
                    Toast.makeText(this, "Proyek diganti nama menjadi “" + name + "”.", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void confirmDeleteSurvey() {
        SurveyDatabase.Survey survey = currentSurvey();
        if (survey == null) return;
        if (LocationLoggingService.isLogging && LocationLoggingService.activeSurveyId == survey.id) {
            Toast.makeText(this, "Hentikan perekaman sebelum menghapus proyek ini.", Toast.LENGTH_LONG).show();
            return;
        }
        int points = database.countPoints(survey.id);
        int waypoints = database.countWaypoints(survey.id);
        int people = database.countParticipants(survey.id);
        new AlertDialog.Builder(this)
                .setTitle("Hapus proyek ini?")
                .setMessage("“" + survey.name + "” beserta " + points + " titik (" + waypoints
                        + " waypoint) dan data " + people + " peserta akan dihapus permanen dari perangkat.\n\n"
                        + "Dataset elevasi global tidak ikut dihapus. Tindakan ini tidak bisa dibatalkan.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus proyek", (dialog, which) -> deleteProject(survey.id))
                .show();
    }

    private void deleteProject(long surveyId) {
        int deleted = database.deleteSurvey(surveyId);
        if (deleted <= 0) {
            Toast.makeText(this, "Proyek tidak ditemukan.", Toast.LENGTH_SHORT).show();
            return;
        }
        surveys = database.getSurveys();
        if (surveys.isEmpty()) {
            selectedSurveyId = database.ensureDefaultSurvey();
        } else {
            selectedSurveyId = surveys.get(0).id;
        }
        reloadSurveys(selectedSurveyId);
        profileView.setProfile(null);
        profileView.setPlaceholder("Belum ada track\nRekam atau impor CSV peserta");
        refreshAll();
        Toast.makeText(this, "Proyek dihapus. Proyek lain tetap utuh.", Toast.LENGTH_SHORT).show();
    }

    private void showPointList() {
        List<SurveyDatabase.Point> points = database.getPoints(selectedSurveyId);
        if (points.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Belum ada titik")
                    .setMessage("Mulai rekam atau impor CSV peserta untuk mengisi proyek ini. Titik yang tersimpan akan muncul di sini.")
                    .setPositiveButton("Tutup", null)
                    .show();
            return;
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = points.size() - 1; i >= 0; i--) list.addView(pointRow(points.get(i)));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(list);
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        int measured = dp(64) * Math.min(points.size(), 8) + dp(8);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(8), dp(8), dp(8), dp(2));
        shell.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.min(maxHeight, measured)));
        new AlertDialog.Builder(this)
                .setTitle("Semua titik (" + points.size() + ")")
                .setView(shell)
                .setNegativeButton("Tutup", null)
                .show();
    }

    private View pointRow(SurveyDatabase.Point point) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(UiKit.rippleFill(this, Color.TRANSPARENT, 12, false));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription((point.waypoint ? "Waypoint " : "Titik ") + point.label);

        ImageView icon = new ImageView(this);
        icon.setImageResource(point.waypoint ? R.drawable.ic_flag : R.drawable.ic_route);
        icon.setColorFilter(UiKit.color(this, point.waypoint ? R.color.accent : R.color.primary));
        row.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(10), 0, 0, 0);
        TextView title = UiKit.text(this,
                (point.waypoint ? "Waypoint" : "Titik") + (point.label.isEmpty() ? "" : " • " + point.label),
                13.5f, R.color.text, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView detail = UiKit.mono(this, pointTime(point) + "  " + pointCoords(point));
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(title);
        column.addView(detail);
        row.addView(column, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> showPointDetail(point));
        return row;
    }

    private void showPointDetail(SurveyDatabase.Point point) {
        StringBuilder body = new StringBuilder();
        body.append("Waktu      : ").append(pointTime(point)).append('\n');
        body.append("Koordinat : ").append(pointCoords(point)).append('\n');
        body.append("Elevasi   : ").append(point.gpsAltitude == null
                ? "tidak tersedia" : String.format(Locale.getDefault(), "%.1f m", point.gpsAltitude)).append('\n');
        if (point.horizontalAccuracy != null)
            body.append("Akurasi H : ").append(String.format(Locale.getDefault(), "±%.0f m", point.horizontalAccuracy)).append('\n');
        if (point.verticalAccuracy != null)
            body.append("Akurasi V : ").append(String.format(Locale.getDefault(), "±%.0f m", point.verticalAccuracy)).append('\n');
        if (point.pressureHpa != null)
            body.append("Tekanan   : ").append(String.format(Locale.getDefault(), "%.1f hPa", point.pressureHpa)).append('\n');
        body.append("Sumber    : ").append(point.source).append('\n');
        body.append("Peserta   : ").append(point.participantAlias.isEmpty()
                ? point.participantId : point.participantAlias);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(point.waypoint ? "Detail waypoint" : "Detail titik")
                .setMessage(body.toString())
                .setNegativeButton("Tutup", null)
                .setNeutralButton("Hapus titik", (d, w) -> confirmDeletePoint(point))
                .create();
        dialog.show();
    }

    private void confirmDeletePoint(SurveyDatabase.Point point) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus titik ini?")
                .setMessage("Titik " + pointTime(point) + " • " + pointCoords(point)
                        + " akan dihapus permanen. Profil dan terrain dihitung ulang.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (dialog, which) -> {
                    boolean deleted = database.deletePoint(point.id);
                    refreshAll();
                    Toast.makeText(this, deleted ? "Titik dihapus." : "Titik sudah tidak ada.",
                            Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void deleteLastTrackPoint() {
        List<SurveyDatabase.Point> points = database.getPoints(selectedSurveyId);
        SurveyDatabase.Point target = null;
        for (int i = points.size() - 1; i >= 0; i--) {
            if (!points.get(i).waypoint) { target = points.get(i); break; }
        }
        if (target == null) {
            Toast.makeText(this, "Belum ada titik track yang bisa dihapus.", Toast.LENGTH_SHORT).show();
            return;
        }
        SurveyDatabase.Point point = target;
        new AlertDialog.Builder(this)
                .setTitle("Hapus titik track terakhir?")
                .setMessage(pointTime(point) + " • " + pointCoords(point)
                        + "\n\nHanya titik ini yang dihapus; waypoint dan titik lain tetap tersimpan.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (dialog, which) -> {
                    database.deletePoint(point.id);
                    refreshAll();
                    Toast.makeText(this, "Titik track terakhir dihapus.", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private String pointTime(SurveyDatabase.Point point) {
        return java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(point.time));
    }

    private String pointCoords(SurveyDatabase.Point point) {
        return String.format(Locale.getDefault(), "%.6f, %.6f", point.lat, point.lon);
    }

    private SurveyDatabase.Survey ensureLocalIdentity() {
        SurveyDatabase.Survey s = currentSurvey();
        if (s == null) return null;
        String id = s.participantId;
        if (!BleGroupCodec.isValidParticipantId(id)) id = database.ensureParticipantId(s.id, BleGroupCodec.newParticipantId());
        String alias = s.participantAlias;
        if (alias == null || alias.trim().isEmpty()) alias = "Peserta-" + id.substring(Math.max(0, id.length() - 4));
        database.setGroupProfile(s.id, s.groupCode, id, alias);
        return database.getSurvey(s.id);
    }

    private void showGroupDialog() {
        if (LocationLoggingService.isLogging) {
            Toast.makeText(this, "Hentikan rekaman sebelum mengubah profil outing.", Toast.LENGTH_LONG).show();
            return;
        }
        SurveyDatabase.Survey s = ensureLocalIdentity();
        if (s == null) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(6), dp(20), 0);
        TextView codeLabel = UiKit.caption(this, "Kode outing 8–16 huruf/angka, sama di semua ponsel");
        form.addView(codeLabel);
        EditText code = UiKit.input(this, "Contoh: GEDE7K4PM2");
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setText(s.groupCode);
        form.addView(code, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        TextView aliasLabel = UiKit.caption(this, "Alias peserta (hanya untuk file lokal/ekspor)");
        form.addView(aliasLabel, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        EditText alias = UiKit.input(this, "Contoh: Pendaki 1");
        alias.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        alias.setText(s.participantAlias);
        form.addView(alias, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, dp(50), 0, 6, 0, 0));
        form.addView(UiKit.caption(this, "ID samaran per outing: " + s.participantId
                        + ". BLE menyiarkan tag hash kode + ID acak saja; bukan nama atau data track."),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Profil rombongan")
                .setMessage("Gunakan kode yang sama pada semua peserta agar BLE hanya mencatat perangkat dalam outing ini. Mengubah kode akan memberi kode baru pada seluruh data di proyek ini; buat proyek baru untuk outing lain. Kode kosong berarti GPS lokal tanpa BLE.")
                .setView(form).setNegativeButton("Batal", null)
                .setNeutralButton("Buat kode acak", null)
                .setPositiveButton("Simpan", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> code.setText(BleGroupCodec.newGroupCode()));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String groupCode = BleGroupCodec.normalizeGroupCode(code.getText().toString());
                if (!groupCode.isEmpty() && !BleGroupCodec.isValidGroupCode(groupCode)) {
                    code.setError("Gunakan 8–16 karakter A–Z atau 0–9.");
                    return;
                }
                String participantAlias = alias.getText().toString().trim();
                if (participantAlias.isEmpty()) participantAlias = "Peserta-" + s.participantId.substring(4);
                database.setGroupProfile(s.id, groupCode, s.participantId, participantAlias);
                dialog.dismiss();
                refreshAll();
                Toast.makeText(this, groupCode.isEmpty() ? "BLE rombongan dinonaktifkan; GPS tetap bisa digunakan." : "Profil outing tersimpan lokal.", Toast.LENGTH_SHORT).show();
            });
        });
        dialog.show();
    }

    private void requestLocationAccess() {
        List<String> requests = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) requests.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) requests.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requests.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!requests.isEmpty()) requestPermissions(requests.toArray(new String[0]), REQ_LOCATION);
        else updatePermissionState();
    }

    private boolean hasPreciseLocation() { return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }

    private void updatePermissionState() {
        if (permissionButton == null) return;
        boolean precise = hasPreciseLocation();
        permissionButton.setVisibility(precise ? View.GONE : View.VISIBLE);
        if (precise) {
            gpsText.setText(!hasFreshFix() ? "Menunggu fix Android network/GNSS. GNSS tetap dicari meski internet tidak tersedia." : locationSummary(latestFix));
        } else {
            permissionButton.setText("Aktifkan lokasi");
            boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            gpsText.setText(coarse ? "Lokasi kasar saja aktif. Izinkan lokasi presisi untuk merekam track." : "Lokasi belum diizinkan. Provider jaringan/GNSS Android memerlukan izin lokasi.");
        }
        if (gpsChip != null) {
            if (precise) gpsChip.set(hasFreshFix() ? UiKit.TONE_SUCCESS : UiKit.TONE_WARNING,
                    hasFreshFix() ? "GPS siap" : "Menunggu fix");
            else gpsChip.set(UiKit.TONE_DANGER, "Izin lokasi");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            updatePermissionState();
            if (pendingStartAfterLocation && hasPreciseLocation()) {
                pendingStartAfterLocation = false;
                startRecording();
            } else if (hasPreciseLocation()) beginLiveLocation();
            else {
                pendingStartAfterLocation = false;
                Toast.makeText(this, "Perekaman memerlukan izin lokasi presisi.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_BLE && pendingStartAfterBle) {
            pendingStartAfterBle = false;
            if (hasBleRuntimePermissions()) startRecordingWithOptionalBackgroundPermission();
            else {
                Toast.makeText(this, "Izin BLE tidak diberikan; perekaman GPS lokal tetap dimulai tanpa deteksi rombongan.", Toast.LENGTH_LONG).show();
                startRecordingNow(false);
            }
        } else if (requestCode == REQ_BACKGROUND_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) startRecordingNow(true);
            else {
                Toast.makeText(this, "Izin lokasi latar belakang tidak diberikan; GPS dimulai tanpa scan BLE latar belakang.", Toast.LENGTH_LONG).show();
                startRecordingNow(false);
            }
        }
    }

    private boolean hasBleRuntimePermissions() {
        return Build.VERSION.SDK_INT < 31 ||
                (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                 checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                 checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED);
    }

    private void startRecording() {
        if (!hasPreciseLocation()) {
            pendingStartAfterLocation = true;
            requestLocationAccess();
            return;
        }
        if (LocationLoggingService.isLogging) {
            Toast.makeText(this, "Perekaman sudah aktif. Hentikan dulu sebelum memulai proyek lain.", Toast.LENGTH_LONG).show();
            return;
        }
        if (startingRecording) return;
        SurveyDatabase.Survey s = ensureLocalIdentity();
        if (s != null && BleGroupCodec.isValidGroupCode(s.groupCode)) {
            if (Build.VERSION.SDK_INT >= 31 && !hasBleRuntimePermissions()) {
                pendingStartAfterBle = true;
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLE);
                return;
            }
            startRecordingWithOptionalBackgroundPermission();
        } else startRecordingNow(false);
    }

    private void startRecordingWithOptionalBackgroundPermission() {
        if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT <= 30 &&
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this).setTitle("Izin BLE saat layar terkunci")
                    .setMessage("Pada Android 10–11, pemindaian BLE saat aplikasi tidak terlihat dapat memerlukan izin lokasi 'Sepanjang waktu'. Jika tidak ingin memberi izin ini, pilih tanpa BLE; lokasi jaringan/GNSS tetap direkam.")
                    .setNegativeButton("Tanpa BLE", (d, w) -> startRecordingNow(false))
                    .setPositiveButton("Minta izin", (d, w) -> requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BACKGROUND_LOCATION))
                    .show();
        } else startRecordingNow(true);
    }

    private void startRecordingNow(boolean bleEnabled) {
        if (LocationLoggingService.isLogging || startingRecording) return;
        SurveyDatabase.Survey s = ensureLocalIdentity();
        if (s == null) return;
        startingRecording = true;
        Intent intent = new Intent(this, LocationLoggingService.class);
        intent.setAction(LocationLoggingService.ACTION_START);
        intent.putExtra(LocationLoggingService.EXTRA_SURVEY_ID, selectedSurveyId);
        intent.putExtra(LocationLoggingService.EXTRA_GROUP_CODE, s.groupCode);
        intent.putExtra(LocationLoggingService.EXTRA_PARTICIPANT_ID, s.participantId);
        intent.putExtra(LocationLoggingService.EXTRA_PARTICIPANT_ALIAS, s.participantAlias);
        intent.putExtra(LocationLoggingService.EXTRA_BLE_ENABLED, bleEnabled);
        startForegroundService(intent);
        recordText.setText(bleEnabled ? "Provider Android network/GNSS aktif; BLE mencatat presence ID secara lokal." : "Provider Android network/GNSS aktif; BLE tidak aktif pada sesi ini.");
        syncRecordingControls();
        // Safety net: if the service never reports back, re-enable the action instead of locking the UI.
        if (bottomPrimary != null)
            bottomPrimary.postDelayed(() -> {
                if (!LocationLoggingService.isLogging) startingRecording = false;
                syncRecordingControls();
            }, 2500);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bottomPrimary.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Toast.makeText(this, "Perekaman dimulai. Pantau notifikasi saat layar terkunci.", Toast.LENGTH_SHORT).show();
    }

    private void stopRecording() {
        if (!LocationLoggingService.isLogging) {
            Toast.makeText(this, "Tidak ada perekaman aktif.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, LocationLoggingService.class);
        intent.setAction(LocationLoggingService.ACTION_STOP);
        startService(intent);
        recordText.setText("Perekaman dihentikan. Data yang sudah tersimpan tetap ada di proyek ini.");
        syncRecordingControls();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bottomPrimary.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        Toast.makeText(this, "Perekaman dihentikan.", Toast.LENGTH_SHORT).show();
        maybePromptBackup();
    }

    /** Reflects logging state on the in-card buttons and the sticky bottom bar. */
    private void syncRecordingControls() {
        boolean logging = LocationLoggingService.isLogging || startingRecording;
        if (startButton != null) startButton.setEnabled(!logging);
        if (stopButton != null) stopButton.setEnabled(logging);
        if (groupButton != null) groupButton.setEnabled(!logging);

        if (bottomPrimary != null) {
            bottomPrimary.setText(logging ? "Stop" : "Mulai");
            bottomPrimary.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    logging ? R.drawable.ic_stop : R.drawable.ic_record, 0, 0, 0);
            UiKit.styleButton(this, bottomPrimary, logging ? UiKit.STYLE_DANGER : UiKit.STYLE_PRIMARY);
        }
        if (bottomStatus != null) {
            boolean live = LocationLoggingService.isLogging;
            bottomStatus.setText(live ? "Merekam" : (startingRecording ? "Memulai…" : "Siap merekam"));
        }
        if (recordDot != null)
            recordDot.setBackground(UiKit.circle(this, logging ? R.color.danger : R.color.text_faint));

        if (logging) startPulse(); else stopPulse();
    }

    private void startPulse() {
        if (recordDot == null || pulseAnimator != null) return;
        pulseAnimator = ValueAnimator.ofFloat(1f, 0.35f, 1f);
        pulseAnimator.setDuration(1500);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.addUpdateListener(a -> {
            if (recordDot != null) recordDot.setAlpha((float) a.getAnimatedValue());
        });
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        if (recordDot != null) recordDot.setAlpha(1f);
    }

    private void beginLiveLocation() {
        if (locationManager == null || !hasPreciseLocation()) return;
        int requested = 0;
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
            try {
                if (!locationManager.getAllProviders().contains(provider)) continue;
                Location last = locationManager.getLastKnownLocation(provider);
                if (last != null) rememberActivityFix(last);
                locationManager.requestLocationUpdates(provider, 3000L, 0f, this);
                requested++;
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Keep listening to the other provider when this one is unavailable.
            }
        }
        if (requested == 0 && !hasFreshFix()) gpsText.setText("Provider lokasi jaringan/GNSS tidak tersedia. Periksa layanan lokasi perangkat.");
    }

    private void rememberActivityFix(Location location) {
        if (location == null) return;
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) latestGpsFix = new Location(location);
        else if (LocationManager.NETWORK_PROVIDER.equals(location.getProvider())) {
            if (!hasValidatedInternet()) return;
            latestNetworkFix = new Location(location);
        } else return;
        latestFix = chooseBestActivityFix(System.currentTimeMillis());
        if (latestFix != null && gpsText != null) gpsText.setText(locationSummary(latestFix));
    }

    private Location chooseBestActivityFix(long now) {
        Location best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Location candidate : new Location[]{latestGpsFix, latestNetworkFix}) {
            if (candidate == null) continue;
            long age = now - candidate.getTime();
            if (age < -60000 || age > 60000) continue;
            double accuracy = candidate.hasAccuracy() ? candidate.getAccuracy()
                    : (LocationManager.GPS_PROVIDER.equals(candidate.getProvider()) ? 100.0 : 1000.0);
            double score = accuracy + Math.max(0, age) / 1000.0 * 2.0;
            if (LocationManager.GPS_PROVIDER.equals(candidate.getProvider())) score -= 0.01;
            if (score < bestScore) { best = candidate; bestScore = score; }
        }
        return best;
    }

    private boolean hasFreshFix() {
        if (latestFix == null) return false;
        long age = System.currentTimeMillis() - latestFix.getTime();
        return age >= -60000 && age <= 60000;
    }

    private String sourceForLocation(Location location) {
        if (location == null) return "Android location";
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) return "Android GPS/GNSS";
        if (LocationManager.NETWORK_PROVIDER.equals(location.getProvider())) return "Android Network Location";
        return "Android location";
    }

    private String locationSummary(Location location) {
        String horizontal = location.hasAccuracy() ? "±" + Math.round(location.getAccuracy()) + " m" : "akurasi horizontal tidak diketahui";
        String altitude = location.hasAltitude() ? String.format(Locale.getDefault(), " • elevasi %.1f m", location.getAltitude()) : " • elevasi belum tersedia";
        String vertical = location.hasVerticalAccuracy() ? String.format(Locale.getDefault(), " (±%.1f m vertikal)", location.getVerticalAccuracyMeters()) : " (akurasi vertikal tidak disediakan Android)";
        return "Fix " + sourceForLocation(location) + " • horizontal " + horizontal + altitude + vertical;
    }

    @Override public void onLocationChanged(Location location) {
        rememberActivityFix(location);
    }
    @Override public void onProviderEnabled(String provider) { if (gpsText != null) gpsText.setText("Provider " + provider + " aktif; sumber fix dipilih otomatis."); }
    @Override public void onProviderDisabled(String provider) {
        if (gpsText != null) gpsText.setText(LocationManager.GPS_PROVIDER.equals(provider)
                ? "GNSS tidak tersedia; Android network location tetap dapat memberi fix."
                : "Network location tidak tersedia; GNSS tetap dicari tanpa menghentikan rekaman.");
    }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    private void captureManualPoint() {
        capturePoint(false, "Titik ukur");
    }

    private void captureWaypoint() {
        if (!hasPreciseLocation()) { requestLocationAccess(); return; }
        if (!hasFreshFix()) {
            beginLiveLocation();
            Toast.makeText(this, "Belum ada fix lokasi baru. Tunggu provider Android network/GNSS lalu coba lagi.", Toast.LENGTH_LONG).show();
            return;
        }
        EditText input = UiKit.input(this, "Contoh: Puncak, pos, persimpangan");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);
        form.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        new AlertDialog.Builder(this).setTitle("Label waypoint")
                .setMessage("Waypoint akan ditandai pada profil jalur dan diekspor bersama track.")
                .setView(form).setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (dialog, which) -> {
                    String label = input.getText().toString().trim();
                    if (label.isEmpty()) label = "Waypoint";
                    capturePoint(true, label);
                }).show();
    }

    private void capturePoint(boolean waypoint, String label) {
        if (!hasPreciseLocation()) { requestLocationAccess(); return; }
        if (!hasFreshFix()) {
            beginLiveLocation();
            Toast.makeText(this, "Belum ada fix lokasi baru. Tunggu sebentar lalu coba lagi.", Toast.LENGTH_LONG).show();
            return;
        }
        SurveyDatabase.Survey s = ensureLocalIdentity();
        if (s == null) return;
        Double altitude = latestFix.hasAltitude() ? latestFix.getAltitude() : null;
        Float vacc = latestFix.hasVerticalAccuracy() ? latestFix.getVerticalAccuracyMeters() : null;
        Double baroRelative = pressureHpa != null && baselinePressureHpa != null
                ? 44330.0 * (1.0 - Math.pow(pressureHpa / baselinePressureHpa, 0.190294957)) : null;
        if (altitude == null && waypoint) label += " • elevasi tidak tersedia";
        SurveyDatabase.Point point = new SurveyDatabase.Point(selectedSurveyId, latestFix.getTime(),
                latestFix.getLatitude(), latestFix.getLongitude(), altitude,
                latestFix.hasAccuracy() ? latestFix.getAccuracy() : null, vacc,
                pressureHpa, baroRelative, sourceForLocation(latestFix), label, s.participantId, s.participantAlias, s.groupCode, waypoint);
        boolean added = database.addPoint(point);
        refreshAll();
        Toast.makeText(this, added ? (waypoint ? "Waypoint tersimpan lokal." : "Titik lokasi tersimpan lokal.") : "Titik yang sama sudah ada.", Toast.LENGTH_SHORT).show();
    }

    private void refreshAll() {
        if (selectedSurveyId < 0 || countText == null) return;
        List<SurveyDatabase.Point> points = database.getPoints(selectedSurveyId);
        int withElevation = 0, trackCount = 0, waypointCount = 0;
        for (SurveyDatabase.Point p : points) {
            if (p.gpsAltitude != null) withElevation++;
            if (p.waypoint) waypointCount++; else trackCount++;
        }
        countText.setText(points.size() + " catatan • " + database.countParticipants(selectedSurveyId) + " peserta");
        elevationCountText.setText(withElevation + " titik elevasi • " + waypointCount + " waypoint");
        StringBuilder recent = new StringBuilder();
        int start = Math.max(0, points.size() - 5);
        for (int i = points.size() - 1; i >= start; i--) {
            SurveyDatabase.Point p = points.get(i);
            recent.append(p.waypoint ? "◆ " : "• ");
            recent.append(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.getDefault()).withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(p.time)));
            recent.append("  ").append(String.format(Locale.getDefault(), "%.5f, %.5f", p.lat, p.lon));
            if (p.gpsAltitude != null) recent.append(String.format(Locale.getDefault(), " • %.1f m", p.gpsAltitude));
            recent.append(" • ").append(p.participantAlias.isEmpty() ? p.participantId : p.participantAlias);
            if (!p.label.isEmpty()) recent.append(" • ").append(p.label);
            recent.append('\n');
        }
        recentText.setText(recent.length() == 0 ? "Belum ada titik." : recent.toString().trim());
        updateProjectHeader();
        updateStatusChips();
        updateGroupUi();
        updateElevationDatasetUi();
        updateExportStatus();
        updateWaypointList(points);
        updatePressureUi();
        updatePermissionState();
        updateRecordingUi(trackCount);
        if (mapView != null) mapView.setRoute(points, selectedSurveyId);
        refreshReferenceTrack();
        refreshRouteProfile(points, selectedSurveyId);
        refreshTerrain(points, selectedSurveyId);
        // Data baru memicu sinkron otomatis; ID job tetap membuat permintaan beruntun digabung.
        MongoSyncScheduler.requestSync(this, 12000);
    }

    /** Refreshes GPS / internet / barometer / BLE chips from the current runtime state. */
    private void updateStatusChips() {
        if (gpsChip == null) return;
        boolean precise = hasPreciseLocation();
        if (!precise) gpsChip.set(UiKit.TONE_DANGER, "Izin lokasi");
        else if (hasFreshFix()) gpsChip.set(UiKit.TONE_SUCCESS, "GPS aktif");
        else gpsChip.set(UiKit.TONE_WARNING, "Menunggu fix");

        boolean online = false;
        if (connectivityManager != null) {
            Network network = connectivityManager.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : connectivityManager.getNetworkCapabilities(network);
            online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        if (online) netChip.set(UiKit.TONE_SUCCESS, "Jaringan ada");
        else netChip.set(UiKit.TONE_NEUTRAL, "Offline");

        if (pressureSensor == null) baroChip.set(UiKit.TONE_NEUTRAL, "Tanpa barometer");
        else if (pressureHpa == null) baroChip.set(UiKit.TONE_WARNING, "Baro menunggu");
        else baroChip.set(UiKit.TONE_SUCCESS, String.format(Locale.getDefault(), "%.0f hPa", pressureHpa));

        SurveyDatabase.Survey s = currentSurvey();
        boolean bleOn = s != null && !s.groupCode.isEmpty();
        bleChip.set(bleOn ? (LocationLoggingService.isLogging ? UiKit.TONE_SUCCESS : UiKit.TONE_PRIMARY)
                        : UiKit.TONE_NEUTRAL,
                bleOn ? (LocationLoggingService.isLogging ? "BLE mencatat" : "BLE siap") : "BLE nonaktif");
    }

    private void updateGroupUi() {
        SurveyDatabase.Survey s = currentSurvey();
        if (s == null) return;
        if (s.participantId.isEmpty()) groupText.setText("ID peserta belum dibuat; akan dibuat otomatis untuk proyek ini.");
        else if (s.groupCode.isEmpty()) groupText.setText("BLE nonaktif • ID peserta outing: " + s.participantId + " • alias: " + (s.participantAlias.isEmpty() ? "belum diatur" : s.participantAlias));
        else groupText.setText("Kode outing: " + s.groupCode + " • ID samaran: " + s.participantId + " • alias: " + s.participantAlias);
        groupButton.setEnabled(!LocationLoggingService.isLogging);
        List<SurveyDatabase.Presence> events = database.getPresence(selectedSurveyId);        Set<String> peers = new HashSet<>();
        long latest = 0;
        for (SurveyDatabase.Presence event : events) {
            if (!event.participantId.equalsIgnoreCase(s.participantId)) peers.add(event.participantId);
            latest = Math.max(latest, event.time);
        }
        if (peers.isEmpty()) presenceText.setText("Belum ada ID peserta rombongan terdeteksi. Deteksi hanya event ID + waktu; tidak ada jarak.");
        else {
            String latestText = latest > 0 ? java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.getDefault()).withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(latest)) : "—";
            presenceText.setText(peers.size() + " ID samaran berbeda terlihat • " + events.size() + " event lokal • terakhir " + latestText + ". Tidak menyimpan MAC/RSSI/jarak.");
        }
    }

    private void updateElevationDatasetUi() {
        if (elevationDatasetText == null || selectedSurveyId < 0) return;
        long datasetId = database.getSurveyElevationDatasetId(selectedSurveyId);
        SurveyDatabase.ElevationDataset dataset = datasetId < 0 ? null : database.getElevationDataset(datasetId);
        if (datasetId >= 0 && dataset == null) {
            database.setSurveyElevationDataset(selectedSurveyId, -1);
            datasetId = -1;
        }
        if (dataset == null) {
            elevationDatasetText.setText("Belum ada dataset aktif • profil memakai elevasi rekaman GPS/rombongan.");
            deleteElevationButton.setEnabled(false);
        } else {
            String datumNote = TerrainEngine.datumGroup(dataset.verticalDatum).startsWith("UNKNOWN:")
                    ? "\nDatum tidak diketahui • tidak digabung dengan elevasi track/peserta lain."
                    : "";
            elevationDatasetText.setText("Aktif: " + dataset.name + "\n" + dataset.sampleCount + " sampel • datum vertikal: " + dataset.verticalDatum + datumNote);
            deleteElevationButton.setEnabled(true);
        }
        selectElevationButton.setEnabled(!database.getElevationDatasets().isEmpty());
    }

    private void chooseElevationDataset() {
        List<SurveyDatabase.ElevationDataset> datasets = database.getElevationDatasets();
        if (datasets.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Belum ada dataset")
                    .setMessage("Impor dulu CSV elevasi milikmu. Data disimpan lokal dan hanya bisa mengoreksi profil bila mencakup seluruh rute.")
                    .setPositiveButton("Tutup", null).show();
            return;
        }
        long surveyId = selectedSurveyId;
        CharSequence[] items = new CharSequence[datasets.size() + 1];
        items[0] = "Tanpa koreksi • elevasi rekaman GPS/rombongan";
        for (int i = 0; i < datasets.size(); i++) items[i + 1] = datasets.get(i).toString();
        new AlertDialog.Builder(this).setTitle("Dataset untuk proyek ini")
                .setItems(items, (dialog, which) -> {
                    database.setSurveyElevationDataset(surveyId, which == 0 ? -1 : datasets.get(which - 1).id);
                    if (selectedSurveyId == surveyId) refreshAll();
                }).setNegativeButton("Batal", null).show();
    }

    private void deleteActiveElevationDataset() {
        long surveyId = selectedSurveyId;
        long datasetId = database.getSurveyElevationDatasetId(surveyId);
        SurveyDatabase.ElevationDataset dataset = database.getElevationDataset(datasetId);
        if (dataset == null) return;
        new AlertDialog.Builder(this).setTitle("Hapus dataset dari perangkat?")
                .setMessage("“" + dataset.name + "” dan " + dataset.sampleCount + " sampelnya akan dihapus dari penyimpanan lokal dan dilepas dari semua proyek. Track GPS mentah tidak dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus dataset", (dialog, which) -> {
                    database.deleteElevationDataset(datasetId);
                    if (selectedSurveyId == surveyId) refreshAll();
                }).show();
    }

    private void updateWaypointList(List<SurveyDatabase.Point> points) {
        int total = 0;
        for (SurveyDatabase.Point p : points) if (p.waypoint) total++;
        if (total == 0) { waypointText.setText("Waypoint belum ditandai."); return; }
        StringBuilder b = new StringBuilder("Waypoint (" + total + ")\n");
        int shown = 0;
        for (int i = points.size() - 1; i >= 0 && shown < 10; i--) {
            SurveyDatabase.Point p = points.get(i);
            if (!p.waypoint) continue;
            b.append("◆ ").append(p.label.isEmpty() ? "Waypoint" : p.label)
                    .append(" • ").append(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.getDefault()).withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(p.time)));
            if (p.gpsAltitude != null) b.append(String.format(Locale.getDefault(), " • %.0f m", p.gpsAltitude));
            b.append(" • ").append(p.participantAlias.isEmpty() ? p.participantId : p.participantAlias).append('\n');
            shown++;
        }
        if (total > shown) b.append("… dan ").append(total - shown).append(" waypoint lain");
        waypointText.setText(b.toString().trim());
    }

    private void updatePressureUi() {
        if (pressureText == null) return;
        if (pressureSensor == null) pressureText.setText("Sensor barometer tidak tersedia. GPS dan impor tetap dapat digunakan.");
        else if (pressureHpa == null) pressureText.setText("Sensor barometer tersedia; menunggu pembacaan tekanan.");
        else {
            String rel = baselinePressureHpa == null ? "" : String.format(Locale.getDefault(), " • perubahan relatif %.1f m", 44330.0 * (1 - Math.pow(pressureHpa / baselinePressureHpa, 0.190294957)));
            pressureText.setText(String.format(Locale.getDefault(), "Tekanan %.1f hPa%s", pressureHpa, rel));
        }
    }

    private void updateRecordingUi(int trackCount) {
        if (recordText == null) return;
        boolean recording = LocationLoggingService.isLogging;
        if (recording) startingRecording = false;
        if (recording) {
            recordText.setText("Perekaman GPS aktif untuk proyek ini • " + trackCount + " catatan track tersimpan. Jika outing disetel, BLE presence berjalan lokal; pantau notifikasi.");
        } else {
            recordText.setText("Perekaman berhenti. Track dan event yang tersimpan tetap ada di proyek ini.");
        }
        if (bottomDetail != null) {
            if (recording)
                bottomDetail.setText(trackCount + " titik tersimpan • berjalan di latar belakang");
            else
                bottomDetail.setText(trackCount == 0
                        ? "Tekan mulai untuk merekam jalur ini"
                        : trackCount + " titik pada proyek ini");
        }
        if (lastTrackCount >= 0 && trackCount > lastTrackCount && bottomStatus != null) {
            bottomStatus.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
                    .withEndAction(() -> bottomStatus.animate()
                            .scaleX(1f).scaleY(1f).setDuration(130).start())
                    .start();
        }
        lastTrackCount = trackCount;
        syncRecordingControls();
    }

    private void refreshTerrain(List<SurveyDatabase.Point> snapshot, long surveyId) {
        if (terrainExecutor().isShutdown() || terrainStatus == null) return;
        terrainStatus.setText("Menghitung coverage terrain lokal…");
        long datasetId = database.getSurveyElevationDatasetId(surveyId);
        profileExecutor.execute(() -> {
            List<TerrainEngine.Sample> samples = new ArrayList<>();
            String datum = "unknown";
            if (datasetId >= 0) {
                SurveyDatabase.ElevationDataset d = database.getElevationDataset(datasetId);
                if (d != null) {
                    datum = d.verticalDatum;
                    for (LocalElevationEngine.Sample x : database.getElevationSamples(datasetId)) samples.add(new TerrainEngine.Sample(x.latitude,x.longitude,x.elevationMeters,d.verticalDatum));
                    if (!TerrainEngine.datumGroup(d.verticalDatum).startsWith("UNKNOWN:")) {
                        for (SurveyDatabase.Point p : snapshot) if (p.gpsAltitude != null)
                            if (TerrainEngine.compatibleDatum(p.source, d.verticalDatum))
                                samples.add(new TerrainEngine.Sample(p.lat,p.lon,p.gpsAltitude,p.source));
                    }
                }
            } else {
                java.util.Map<String, List<SurveyDatabase.Point>> byDatum = new java.util.HashMap<>();
                for (SurveyDatabase.Point p : snapshot) if (p.gpsAltitude != null) {
                    String group = TerrainEngine.datumGroup(p.source);
                    if (group.startsWith("UNKNOWN:")) group += ":" + p.participantId;
                    byDatum.computeIfAbsent(group, ignored -> new ArrayList<>()).add(p);
                }
                String selectedGroup = null;
                int selectedCount = 0;
                for (String group : new java.util.TreeSet<>(byDatum.keySet())) {
                    int count = byDatum.get(group).size();
                    if (count > selectedCount) { selectedGroup = group; selectedCount = count; }
                }
                if (selectedGroup != null) {
                    datum = selectedGroup;
                    for (SurveyDatabase.Point p : byDatum.get(selectedGroup))
                        samples.add(new TerrainEngine.Sample(p.lat,p.lon,p.gpsAltitude,selectedGroup));
                }
            }
            final TerrainEngine.Result r = TerrainEngine.build(samples, datum, 0);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || selectedSurveyId != surveyId) return;
                terrainView.setResult(r);
                if (mapView != null) mapView.setTerrain(r);
                if (mapStatus != null) {
                    int track = 0;
                    for (SurveyDatabase.Point point : snapshot) if (!point.waypoint) track++;
                    if (track == 0 && r.samples.isEmpty()) {
                        mapStatus.setText("Belum ada jalur untuk dipetakan.");
                    } else if (r.shouldRenderSurface) {
                        mapStatus.setText(String.format(Locale.getDefault(),
                                "%d titik jalur • kontur interpolasi lokal %.1f m • tanpa ubin daring",
                                track, r.contourIntervalMeters));
                    } else {
                        mapStatus.setText(track + " titik jalur • kontur belum cukup coverage; jalur & waypoint tetap tampil tanpa ubin daring.");
                    }
                }
                String visibleDatum = r.datumGroup.startsWith("UNKNOWN:")
                        ? "datum tidak diketahui • sumber/peserta tidak digabung"
                        : r.datumGroup.replace('_', ' ');
                String quality = r.shouldRenderSurface
                        ? String.format(Locale.getDefault(), "interval kontur %.1f m", r.contourIntervalMeters)
                        : "kontur/permukaan disembunyikan; titik asli saja";
                terrainStatus.setText(String.format(Locale.getDefault(),
                        "%d posisi elevasi unik • area didukung %d/%d sel (%.0f%%) • %s\n%s\n%s",
                        r.distinctSampleCount, r.validCellCount, r.totalCellCount, r.coveragePercent,
                        quality, visibleDatum, r.status));
            });
        });
    }
    private ExecutorService terrainExecutor() { return profileExecutor; }

    private void refreshRouteProfile(List<SurveyDatabase.Point> snapshot, long surveyId) {
        if (profileExecutor.isShutdown()) return;
        profileText.setText("Menghitung profil jalur satu rute…");
        long datasetId = database.getSurveyElevationDatasetId(surveyId);
        profileExecutor.execute(() -> {
            RouteProfileEngine.Profile profile = RouteProfileEngine.build(snapshot, datasetId >= 0);
            if (datasetId >= 0) {
                if (cachedElevationDatasetId != datasetId) {
                    cachedElevationDataset = database.getElevationDataset(datasetId);
                    cachedElevationIndex = cachedElevationDataset == null ? null
                            : new LocalElevationEngine.Index(database.getElevationSamples(datasetId));
                    cachedElevationDatasetId = datasetId;
                }
                if (cachedElevationDataset != null) {
                    profile = LocalElevationEngine.correctProfile(profile, cachedElevationDataset.name,
                            cachedElevationDataset.verticalDatum, cachedElevationIndex);
                }
            }
            final RouteProfileEngine.Profile completedProfile = profile;
            int trackPoints = 0, gpsElevationPoints = 0;
            for (SurveyDatabase.Point point : snapshot) {
                if (point.waypoint || !Double.isFinite(point.lat) || !Double.isFinite(point.lon)) continue;
                trackPoints++;
                if (point.gpsAltitude != null && Double.isFinite(point.gpsAltitude)) gpsElevationPoints++;
            }
            final int routePointCount = trackPoints, routeElevationCount = gpsElevationPoints;
            final SurveyDatabase.ElevationDataset diagnosticDataset = datasetId >= 0 ? database.getElevationDataset(datasetId) : null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || selectedSurveyId != surveyId) return;
                profileView.setProfile(completedProfile);
                boolean hasProfile = completedProfile.stations.size() >= 2;
                if (statDistance != null) {
                    statDistance.set(hasProfile
                            ? String.format(Locale.getDefault(), "%.2f km", completedProfile.distanceMeters / 1000.0) : "—");
                    statAscent.set(hasProfile ? String.format(Locale.getDefault(), "%.0f m", completedProfile.ascentMeters) : "—");
                    statDescent.set(hasProfile ? String.format(Locale.getDefault(), "%.0f m", completedProfile.descentMeters) : "—");
                    statParticipants.set(hasProfile ? String.valueOf(completedProfile.participantCount) : "—");
                }
                if (!hasProfile) {
                    String detail = routePointCount < 2
                            ? "Belum ada cukup track jalur: " + routePointCount + " titik track • " + routeElevationCount
                                + " titik dengan elevasi GPS. Sampel dataset elevasi bukan track; mulai rekam atau impor CSV track."
                            : "Track jalur: " + routePointCount + " titik • elevasi GPS tersedia pada " + routeElevationCount + " titik. "
                                + completedProfile.message;
                    if (diagnosticDataset != null && routePointCount < 2)
                        detail += " Dataset “" + diagnosticDataset.name + "” memiliki " + diagnosticDataset.sampleCount
                                + " sampel terrain, tetapi belum ada jalur yang bisa dikoreksi.";
                    else if (diagnosticDataset != null && !completedProfile.message.contains(diagnosticDataset.name))
                        detail += " Dataset “" + diagnosticDataset.name + "” hanya mengoreksi profil bila seluruh jalur tercakup.";
                    profileText.setText(detail);
                    profileView.setPlaceholder(routePointCount < 2 ? "Belum ada track jalur" :
                            (datasetId >= 0 ? "Dataset belum menutup semua titik rute" : "Perlu ≥2 elevasi valid"));
                } else {
                    profileText.setText(String.format(Locale.getDefault(),
                            "Jarak track acuan %.2f km • naik ~%.0f m • turun ~%.0f m\n%d peserta • %d stasiun digabung dari ≥2 peserta\n%s",
                            completedProfile.distanceMeters / 1000.0, completedProfile.ascentMeters, completedProfile.descentMeters,
                            completedProfile.participantCount, completedProfile.stationsWithMultipleContributors, completedProfile.message));
                }
            });
        });
    }

    private void importElevationCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/*");
        startActivityForResult(i, REQ_IMPORT_ELEVATION);
    }

    private String documentBaseName(Uri uri) {
        String name = "Dataset elevasi lokal";
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String value = c.getString(0);
                if (value != null && !value.trim().isEmpty()) name = value.trim().replaceFirst("(?i)\\.csv$", "");
            }
        } catch (Exception ignored) {}
        return name;
    }

    private void showElevationImportDetails(Uri uri) {
        long targetSurveyId = selectedSurveyId;
        EditText nameInput = UiKit.input(this, "Nama dataset / wilayah");
        nameInput.setText(documentBaseName(uri));
        EditText datumInput = UiKit.input(this, "Datum vertikal, mis. EGM96");
        datumInput.setText("Tidak diketahui");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);
        form.addView(UiKit.caption(this, "Nama dataset"));
        form.addView(nameInput, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, dp(50), 0, 6, 0, 0));
        form.addView(UiKit.caption(this, "Datum elevasi dari sumber (tidak dikonversi)"),
                UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        form.addView(datumInput, UiKit.lp(this, LinearLayout.LayoutParams.MATCH_PARENT, dp(50), 0, 6, 0, 0));
        new AlertDialog.Builder(this).setTitle("Impor dataset elevasi milikmu")
                .setMessage("CSV harus memiliki kolom latitude, longitude, elevation_m. Sampel akan disalin ke database lokal perangkat; file tidak diunggah.")
                .setView(form).setNegativeButton("Batal", null)
                .setPositiveButton("Impor & gunakan", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String datum = datumInput.getText().toString().trim();
                    if (name.isEmpty()) name = "Dataset elevasi lokal";
                    if (datum.isEmpty()) datum = "Tidak diketahui";
                    importElevationFile(uri, targetSurveyId, name, datum);
                }).show();
    }

    private void importElevationFile(Uri uri, long surveyId, String name, String datum) {
        importElevationButton.setEnabled(false);
        elevationDatasetText.setText("Mengimpor dataset lokal… data belum diterapkan sampai impor selesai.");
        profileExecutor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalArgumentException("File kosong atau tidak dapat dibuka.");
                ElevationCsvImporter.Result result = ElevationCsvImporter.parse(
                        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)));
                long datasetId = database.importElevationDataset(name, datum, result.samples);
                database.setSurveyElevationDataset(surveyId, datasetId);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    importElevationButton.setEnabled(true);
                    if (selectedSurveyId == surveyId) refreshAll();
                    String message = result.samples.size() + " sampel elevasi disimpan lokal sebagai “" + name + "” (datum: " + datum + ").";
                    if (result.skippedRows > 0) message += "\n" + result.skippedRows + " baris tidak valid dilewati.";
                    message += "\n\nProfil akan dikoreksi hanya jika semua stasiun rute memiliki dukungan sampel lokal yang cukup; jika tidak, profil asli tetap dipakai.";
                    new AlertDialog.Builder(this).setTitle("Dataset siap di perangkat").setMessage(message)
                            .setPositiveButton("Selesai", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    importElevationButton.setEnabled(true);
                    updateElevationDatasetUi();
                    new AlertDialog.Builder(this).setTitle("Impor elevasi gagal")
                            .setMessage(safeMessage(e)).setPositiveButton("Tutup", null).show();
                });
            }
        });
    }

    private void importCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void beginExport(boolean geojson) {
        if (database.getPoints(selectedSurveyId).isEmpty() && database.getPresence(selectedSurveyId).isEmpty()) {
            Toast.makeText(this, "Belum ada data untuk diekspor.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(geojson ? "application/geo+json" : "text/csv");
        String safeName = selectedSurveyName().replaceAll("[^A-Za-z0-9_-]+", "_");
        i.putExtra(Intent.EXTRA_TITLE, safeName + (geojson ? "_jalur.geojson" : "_rombongan.csv"));
        startActivityForResult(i, geojson ? REQ_EXPORT_GEOJSON : REQ_EXPORT_CSV);
    }

    /** One GeoJSON Feature per line, ready for {@code mongoimport --type json}. */
    private void beginJsonLinesExport() {
        if (database.getPoints(selectedSurveyId).isEmpty() && database.getPresence(selectedSurveyId).isEmpty()) {
            Toast.makeText(this, "Belum ada data untuk diekspor.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/x-ndjson");
        String safeName = selectedSurveyName().replaceAll("[^A-Za-z0-9_-]+", "_");
        i.putExtra(Intent.EXTRA_TITLE, safeName + "_mongo.jsonl");
        startActivityForResult(i, REQ_EXPORT_JSONL);
    }

    /**
     * GPX carries one {@code <ele>} per point and no datum attribute, so the export asks which
     * reference to write when the project holds more than one. Points from other references are
     * exported without elevation instead of being silently relabelled.
     */
    private void beginGpxExport() {
        List<SurveyDatabase.Point> points = database.getPoints(selectedSurveyId);
        if (points.isEmpty()) {
            Toast.makeText(this, "Belum ada titik untuk diekspor sebagai GPX.", Toast.LENGTH_SHORT).show();
            return;
        }
        LinkedHashSet<String> references = new LinkedHashSet<>();
        for (SurveyDatabase.Point point : points) {
            if (point.gpsAltitude != null) references.add(elevationReferenceFor(point));
        }
        if (references.isEmpty()) {
            launchGpxExport(GpxCodec.DATUM_NONE);
            return;
        }
        if (references.size() == 1) {
            launchGpxExport(references.iterator().next());
            return;
        }
        final String[] options = references.toArray(new String[0]);
        final String[] labels = new String[options.length + 1];
        for (int i = 0; i < options.length; i++) labels[i] = "Elevasi: " + options[i];
        labels[options.length] = "Tanpa elevasi (hanya koordinat & waktu)";
        new AlertDialog.Builder(this)
                .setTitle("Pilih referensi elevasi GPX")
                .setMessage("Proyek berisi elevasi dengan lebih dari satu referensi. GPX hanya punya satu medan <ele>, dan aplikasi tidak mengonversi datum.\n\n"
                        + "Pilih satu referensi; titik dengan referensi lain diekspor tanpa elevasi agar tidak menyesatkan.")
                .setItems(labels, (dialog, which) -> launchGpxExport(
                        which < options.length ? options[which] : GpxCodec.DATUM_NONE))
                .show();
    }

    private void launchGpxExport(String datumLabel) {
        pendingGpxDatum = datumLabel == null ? GpxCodec.DATUM_NONE : datumLabel;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/gpx+xml");
        String safeName = selectedSurveyName().replaceAll("[^A-Za-z0-9_-]+", "_");
        i.putExtra(Intent.EXTRA_TITLE, safeName + "_jalur.gpx");
        startActivityForResult(i, REQ_EXPORT_GPX);
    }

    private void importGpx() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/gpx+xml", "application/xml", "text/xml", "text/plain", "application/octet-stream"});
        startActivityForResult(i, REQ_IMPORT_GPX);
    }

    // ---------------------------------------------------------------- jalur pembanding

    private void refreshReferenceTrack() {
        if (mapView == null) return;
        SurveyDatabase.ReferenceTrack track = database.getReferenceTrack(selectedSurveyId);
        List<double[]> coordinates = track == null
                ? new ArrayList<>() : database.getReferenceCoordinates(selectedSurveyId);
        mapView.setReferenceTrack(coordinates, track == null ? "" : track.name);
        if (referenceStatus != null) {
            referenceStatus.setText(track == null
                    ? "Belum ada jalur pembanding."
                    : track.name + " • " + track.pointCount + " titik • diimpor "
                        + java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.getDefault())
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(track.importedAt)));
        }
        if (referenceDeleteButton != null) referenceDeleteButton.setEnabled(track != null);
    }

    private void importReferenceGpx() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/gpx+xml", "application/xml", "text/xml", "text/plain", "application/octet-stream"});
        startActivityForResult(i, REQ_IMPORT_REFERENCE_GPX);
    }

    private String referenceDocumentName(Uri uri) {
        String name = "Jalur pembanding";
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String value = c.getString(0);
                if (value != null && !value.trim().isEmpty())
                    name = value.trim().replaceFirst("(?i)\\.gpx$", "");
            }
        } catch (Exception ignored) {}
        return name;
    }

    private void prepareReferenceGpxImport(Uri uri) {
        final long targetSurveyId = selectedSurveyId;
        final String name = referenceDocumentName(uri);
        Toast.makeText(this, "Membaca GPX pembanding…", Toast.LENGTH_SHORT).show();
        profileExecutor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                final GpxCodec.Result result = GpxCodec.parse(input);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    final List<SurveyDatabase.ReferencePoint> points = new ArrayList<>();
                    for (GpxCodec.ParsedPoint parsed : result.points) {
                        if (parsed.waypoint) continue;
                        points.add(new SurveyDatabase.ReferencePoint(parsed.latitude, parsed.longitude,
                                parsed.elevation, parsed.time));
                    }
                    if (points.isEmpty()) {
                        new AlertDialog.Builder(this)
                                .setTitle("GPX tanpa titik track")
                                .setMessage("File terbaca, tetapi tidak ada <trkpt> dengan koordinat valid."
                                        + (result.skipped > 0 ? "\n\n" + result.skipped + " baris dilewati karena koordinat tidak valid." : ""))
                                .setPositiveButton("Tutup", null).show();
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Pakai sebagai jalur pembanding?")
                            .setMessage(points.size() + " titik track dibaca"
                                    + (result.creator.isEmpty() ? "" : " (dibuat oleh " + result.creator + ")")
                                    + ".\n\nJalur ini disimpan terpisah: hanya digambar putus-putus di peta, "
                                    + "tidak digabung ke titik rekaman, dan tidak ikut diekspor. Elevasi file diabaikan "
                                    + "karena pembanding hanya memakai geometri.\n\nJalur pembanding sebelumnya pada proyek ini akan diganti.")
                            .setNegativeButton("Batal", null)
                            .setPositiveButton("Pakai", (dialog, which) -> {
                                long id = database.replaceReferenceTrack(targetSurveyId, name, result.creator, points);
                                if (id < 0) {
                                    Toast.makeText(this, "Jalur pembanding gagal disimpan.", Toast.LENGTH_LONG).show();
                                } else {
                                    Toast.makeText(this, "Jalur pembanding tersimpan (" + points.size() + " titik).", Toast.LENGTH_LONG).show();
                                    refreshAll();
                                }
                            })
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("Impor GPX pembanding gagal")
                            .setMessage("Pilih file GPX 1.1 yang valid (akar <gpx>). Tidak ada data yang diubah.\n\n" + safeMessage(e))
                            .setPositiveButton("Tutup", null).show();
                });
            }
        });
    }

    private void confirmDeleteReferenceTrack() {
        new AlertDialog.Builder(this)
                .setTitle("Hapus jalur pembanding?")
                .setMessage("Hanya jalur pembanding yang dihapus. Titik rekaman aplikasi tidak terpengaruh.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (dialog, which) -> {
                    database.deleteReferenceTrack(selectedSurveyId);
                    Toast.makeText(this, "Jalur pembanding dihapus.", Toast.LENGTH_SHORT).show();
                    refreshAll();
                })
                .show();
    }

    /** Reads the file off the UI thread, then asks for the datum before writing anything. */
    private void prepareGpxImport(Uri uri) {
        Toast.makeText(this, "Membaca file GPX…", Toast.LENGTH_SHORT).show();
        profileExecutor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                final GpxCodec.Result result = GpxCodec.parse(input);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (result.points.isEmpty()) {
                        new AlertDialog.Builder(this)
                                .setTitle("GPX tanpa titik")
                                .setMessage("File terbaca, tetapi tidak ada <trkpt> atau <wpt> dengan koordinat valid."
                                        + (result.skipped > 0 ? "\n\n" + result.skipped + " baris dilewati karena koordinat tidak valid." : ""))
                                .setPositiveButton("Tutup", null).show();
                        return;
                    }
                    askGpxDatum(result);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("Impor GPX gagal")
                            .setMessage("Pilih file GPX 1.1 yang valid (akar <gpx>). Tidak ada data yang diubah.\n\n" + safeMessage(e))
                            .setPositiveButton("Tutup", null).show();
                });
            }
        });
    }

    private void askGpxDatum(GpxCodec.Result result) {
        if (!result.hasElevation()) {
            confirmGpxImport(result, GpxCodec.DATUM_NONE);
            return;
        }
        final String[] datumOptions = {
                GpxCodec.DATUM_ELLIPSOID,
                GpxCodec.DATUM_EGM96,
                GpxCodec.DATUM_MSL,
                GpxCodec.DATUM_NONE
        };
        final String[] labels = {
                "WGS84 ellipsoid (Location/GNSS Android)",
                "EGM96 (geoid)",
                "Mean sea level / orthometric (mis. iOS)",
                "Abaikan elevasi (buang <ele>)"
        };
        String declared = result.declaredReference.trim();
        String declaredLine = declared.isEmpty()
                || GpxCodec.DATUM_UNRECORDED.equalsIgnoreCase(declared)
                ? "" : "\n\nFile mencantumkan referensi: \"" + declared + "\". Verifikasi sebelum memilih.";
        new AlertDialog.Builder(this)
                .setTitle("Referensi elevasi GPX")
                .setMessage(result.points.size() + " titik dibaca. Ketuk datum yang dipakai file ini.\n\n"
                        + "Aplikasi tidak mengonversi elevasi; label ini hanya dicatat agar profil dan terrain tidak mencampur datum."
                        + declaredLine)
                .setItems(labels, (dialog, which) -> confirmGpxImport(result, datumOptions[which]))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void confirmGpxImport(GpxCodec.Result result, String datumLabel) {
        int waypoints = result.waypointCount();
        int track = result.points.size() - waypoints;
        String datumText = datumLabel.isEmpty() ? "tanpa elevasi (<ele> diabaikan)" : datumLabel;
        StringBuilder message = new StringBuilder();
        message.append(track).append(" titik track dan ").append(waypoints).append(" waypoint akan ditambahkan ke proyek “")
                .append(selectedSurveyName()).append("”.\n\n");
        message.append("Referensi elevasi: ").append(datumText).append(".\n");
        if (result.skipped > 0) message.append(result.skipped).append(" baris dilewati karena koordinat tidak valid.\n");
        if (!result.hasTime()) message.append("File tanpa waktu: urutan file dipakai sebagai waktu relatif.\n");
        message.append("\nDuplikat (waktu + koordinat sama) diabaikan. GPX dianggap satu jalur; gunakan CSV grup untuk menggabungkan peserta.");
        new AlertDialog.Builder(this)
                .setTitle("Impor GPX ke proyek ini?")
                .setMessage(message.toString())
                .setNegativeButton("Batal", null)
                .setPositiveButton("Impor", (dialog, which) -> performGpxImport(result, datumLabel))
                .show();
    }

    private void performGpxImport(GpxCodec.Result result, String datumLabel) {
        SurveyDatabase.Survey identity = ensureLocalIdentity();
        if (identity == null) {
            Toast.makeText(this, "Proyek aktif tidak ditemukan.", Toast.LENGTH_SHORT).show();
            return;
        }
        final long surveyId = identity.id;
        final String source = datumLabel.isEmpty()
                ? "GPX import"
                : "GPX import [elevation reference: " + datumLabel + "]";
        final long importStart = System.currentTimeMillis();
        profileExecutor.execute(() -> {
            int inserted = 0, duplicates = 0;
            Long lastTime = null;
            int sequence = 0;
            try {
                // GPX coordinates are rounded on export, so exact-equality dedupe is not enough.
                // Match on exact time + participant + kind, then allow a few metres of drift.
                Map<String, List<SurveyDatabase.Point>> index = new HashMap<>();
                for (SurveyDatabase.Point existing : database.getPoints(surveyId)) {
                    index.computeIfAbsent(gpxDedupeKey(existing.time, existing.participantId,
                            existing.waypoint), ignored -> new ArrayList<>()).add(existing);
                }
                for (GpxCodec.ParsedPoint parsed : result.points) {
                    long time = parsed.time != null ? parsed.time
                            : (lastTime != null ? lastTime + 1000 : importStart + sequence);
                    lastTime = time;
                    sequence++;
                    String key = gpxDedupeKey(time, identity.participantId, parsed.waypoint);
                    List<SurveyDatabase.Point> candidates = index.get(key);
                    boolean duplicate = false;
                    if (candidates != null) {
                        for (SurveyDatabase.Point candidate : candidates) {
                            if (RouteProfileEngine.distanceMeters(candidate.lat, candidate.lon,
                                    parsed.latitude, parsed.longitude) <= 2.0) {
                                duplicate = true;
                                break;
                            }
                        }
                    }
                    if (duplicate) { duplicates++; continue; }
                    Double altitude = datumLabel.isEmpty() ? null : parsed.elevation;
                    String label = parsed.name;
                    if (label.length() > 80) label = label.substring(0, 80);
                    SurveyDatabase.Point point = new SurveyDatabase.Point(surveyId, time,
                            parsed.latitude, parsed.longitude, altitude, null, null, null, null,
                            source, label, identity.participantId, identity.participantAlias,
                            identity.groupCode, parsed.waypoint);
                    if (database.addPoint(point)) {
                        inserted++;
                        index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(point);
                    } else duplicates++;
                }
                final int insertedCount = inserted, duplicateCount = duplicates;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (selectedSurveyId == surveyId) refreshAll();
                    StringBuilder message = new StringBuilder(insertedCount + " titik ditambahkan ke “"
                            + selectedSurveyName() + "”.");
                    if (duplicateCount > 0) message.append("\n").append(duplicateCount).append(" duplikat diabaikan.");
                    if (result.skipped > 0) message.append("\n").append(result.skipped).append(" baris tidak valid dilewati.");
                    message.append("\n\nData tetap lokal. Ekspor ulang setelah impor untuk membuat cadangan.");
                    new AlertDialog.Builder(this).setTitle("Impor GPX selesai").setMessage(message.toString())
                            .setPositiveButton("Selesai", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this).setTitle("Impor GPX gagal")
                            .setMessage(safeMessage(e)).setPositiveButton("Tutup", null).show();
                });
            }
        });
    }

    private String selectedSurveyName() {
        for (SurveyDatabase.Survey s : surveys) if (s.id == selectedSurveyId) return s.name;
        return "pendakian";
    }

    // ---------------------------------------------------------------- export reminder

    private String exportTimeKey(long surveyId) { return "last_export_" + surveyId; }
    private String exportKindKey(long surveyId) { return "last_export_kind_" + surveyId; }

    /** Records a successful export so the Data tab can show how stale the last backup is. */
    private void markExported(String kind) {
        if (selectedSurveyId < 0 || exportPrefs == null) return;
        long now = System.currentTimeMillis();
        exportPrefs.edit()
                .putLong(exportTimeKey(selectedSurveyId), now)
                .putString(exportKindKey(selectedSurveyId), kind)
                .apply();
        updateExportStatus();
    }

    private void updateExportStatus() {
        if (exportStatusText == null || exportStatusMeta == null || selectedSurveyId < 0) return;
        int points = database.countPoints(selectedSurveyId);
        long last = exportPrefs == null ? 0 : exportPrefs.getLong(exportTimeKey(selectedSurveyId), 0L);
        String kind = exportPrefs == null ? "" : exportPrefs.getString(exportKindKey(selectedSurveyId), "");
        if (points == 0) {
            exportStatusText.setText("Belum ada data untuk dicadangkan");
            exportStatusText.setTextColor(getColor(R.color.text_faint));
            exportStatusMeta.setText("Ekspor aktif setelah ada titik tersimpan di proyek ini.");
            return;
        }
        if (last <= 0) {
            exportStatusText.setText("Belum pernah diekspor");
            exportStatusText.setTextColor(getColor(R.color.warning));
            exportStatusMeta.setText(points + " titik hanya ada di perangkat ini. Ekspor GPX/CSV sebagai cadangan; menghapus aplikasi atau data akan menghilangkannya.");
            return;
        }
        long days = Math.max(0, System.currentTimeMillis() - last) / 86_400_000L;
        String when = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(last));
        boolean stale = days >= 7;
        exportStatusText.setText("Cadangan terakhir " + when + (kind == null || kind.isEmpty() ? "" : " • " + kind));
        exportStatusText.setTextColor(getColor(stale ? R.color.warning : R.color.success));
        exportStatusMeta.setText(stale
                ? points + " titik • sudah " + days + " hari sejak ekspor. Ekspor ulang agar cadangan terbaru."
                : points + " titik • cadangan tercatat di perangkat ini; file tetap perlu disalin ke luar ponsel.");
    }

    /** After stopping a long recording, nudge once per day when the project has no recent backup. */
    private void maybePromptBackup() {
        if (selectedSurveyId < 0 || exportPrefs == null) return;
        int points = database.countPoints(selectedSurveyId);
        if (points < 20) return;
        long now = System.currentTimeMillis();
        long last = exportPrefs.getLong(exportTimeKey(selectedSurveyId), 0L);
        if (last > 0 && now - last < 7L * 86_400_000L) return;
        String throttleKey = "last_prompt_" + selectedSurveyId;
        if (now - exportPrefs.getLong(throttleKey, 0L) < 86_400_000L) return;
        exportPrefs.edit().putLong(throttleKey, now).apply();
        String detail = last <= 0 ? "Proyek ini belum pernah diekspor."
                : "Ekspor terakhir lebih dari 7 hari lalu.";
        new AlertDialog.Builder(this)
                .setTitle("Cadangkan proyek ini?")
                .setMessage(points + " titik tersimpan hanya di perangkat ini. " + detail
                        + "\n\nTidak ada pencadangan otomatis atau cloud. GPX menyimpan jalur/waypoint; CSV juga menyimpan event BLE dan identitas peserta.")
                .setNegativeButton("Nanti", null)
                .setNeutralButton("Ekspor CSV", (dialog, which) -> beginExport(false))
                .setPositiveButton("Ekspor GPX", (dialog, which) -> beginGpxExport())
                .show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_ELEVATION) {
            showElevationImportDetails(uri);
        } else if (requestCode == REQ_IMPORT) {
            try { importCsvFile(uri); }
            catch (Exception e) {
                new AlertDialog.Builder(this).setTitle("Impor gagal")
                        .setMessage("File tidak dapat dibaca. Pilih CSV hasil ekspor Lereng Offline atau tabel dengan latitude, longitude, elevation_m.\n\n" + safeMessage(e))
                        .setPositiveButton("Tutup", null).show();
            }
        } else if (requestCode == REQ_IMPORT_GPX) {
            prepareGpxImport(uri);
        } else if (requestCode == REQ_IMPORT_REFERENCE_GPX) {
            prepareReferenceGpxImport(uri);
        } else if (requestCode == REQ_EXPORT_CSV || requestCode == REQ_EXPORT_GEOJSON
                || requestCode == REQ_EXPORT_GPX || requestCode == REQ_EXPORT_JSONL) {
            try {
                String content = requestCode == REQ_EXPORT_GEOJSON ? buildGeoJson()
                        : requestCode == REQ_EXPORT_GPX ? buildGpx()
                        : requestCode == REQ_EXPORT_JSONL ? buildJsonLines() : buildCsv();
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IllegalStateException("Lokasi file tidak dapat ditulis");
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                String kind = requestCode == REQ_EXPORT_GEOJSON ? "GeoJSON"
                        : requestCode == REQ_EXPORT_GPX ? "GPX"
                        : requestCode == REQ_EXPORT_JSONL ? "JSONL" : "CSV";
                markExported(kind);
                Toast.makeText(this, "Cadangan " + kind + " berhasil diekspor.", Toast.LENGTH_LONG).show();
            } catch (Exception e) { Toast.makeText(this, "Ekspor gagal: " + safeMessage(e), Toast.LENGTH_LONG).show(); }
        }
    }

    private void importCsvFile(Uri uri) throws Exception {
        SurveyDatabase.Survey profile = ensureLocalIdentity();
        if (profile == null) throw new IllegalStateException("Proyek aktif tidak ditemukan");
        String fallbackId = BleGroupCodec.newParticipantId();
        String fallbackAlias = importFileAlias(uri, fallbackId);
        int inserted = 0, presenceInserted = 0, invalid = 0, wrongGroup = 0, duplicates = 0;
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new IllegalArgumentException("File kosong atau tidak dapat dibuka");
        try (InputStream in = input; BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int rowNumber = 0;
            int typeCol = -1, groupCol = -1, pidCol = -1, aliasCol = -1, latCol = -1, lonCol = -1, altCol = -1;
            int haccCol = -1, vaccCol = -1, pressureCol = -1, baroCol = -1, labelCol = -1, timeCol = -1, sourceCol = -1, peerCol = -1, waypointCol = -1, datumCol = -1;
            boolean headerChecked = false;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.trim().isEmpty()) continue;
                List<String> cells = parseCsvLine(line);
                if (!headerChecked) {
                    headerChecked = true;
                    if (!isNumber(cell(cells, 0))) {
                        for (int j = 0; j < cells.size(); j++) {
                            String h = cells.get(j).trim().replace("\uFEFF", "").toLowerCase(Locale.ROOT).replace(" ", "_");
                            if (h.equals("record_type")) typeCol = j;
                            else if (h.equals("group_code")) groupCol = j;
                            else if (h.equals("participant_id")) pidCol = j;
                            else if (h.equals("participant_alias")) aliasCol = j;
                            else if (h.equals("lat") || h.equals("latitude")) latCol = j;
                            else if (h.equals("lon") || h.equals("lng") || h.equals("longitude")) lonCol = j;
                            else if (h.equals("elevation_m") || h.equals("elevation") || h.equals("altitude") || h.equals("alt") || h.equals("ele") || h.equals("height_m")) altCol = j;
                            else if (h.equals("accuracy_m") || h.equals("h_acc") || h.equals("horizontal_accuracy")) haccCol = j;
                            else if (h.equals("vertical_accuracy_m") || h.equals("v_acc")) vaccCol = j;
                            else if (h.equals("pressure_hpa")) pressureCol = j;
                            else if (h.equals("barometer_relative_elevation_m")) baroCol = j;
                            else if (h.equals("label") || h.equals("name")) labelCol = j;
                            else if (h.equals("time_iso") || h.equals("timestamp") || h.equals("time")) timeCol = j;
                            else if (h.equals("source")) sourceCol = j;
                            else if (h.equals("elevation_reference") || h.equals("vertical_datum")) datumCol = j;
                            else if (h.equals("peer_id") || h.equals("seen_participant_id")) peerCol = j;
                            else if (h.equals("is_waypoint")) waypointCol = j;
                        }
                        continue;
                    } else {
                        latCol = 0; lonCol = 1; altCol = 2; haccCol = 3; labelCol = 4;
                    }
                }
                try {
                    String recordType = cell(cells, typeCol).trim().toLowerCase(Locale.ROOT);
                    if (recordType.isEmpty()) recordType = "track";
                    String rowGroup = BleGroupCodec.normalizeGroupCode(cell(cells, groupCol));
                    if (!rowGroup.isEmpty() && (profile.groupCode.isEmpty() || !profile.groupCode.equalsIgnoreCase(rowGroup))) { wrongGroup++; continue; }
                    String effectiveGroup = rowGroup.isEmpty() ? profile.groupCode : rowGroup;
                    String participantId = cell(cells, pidCol).trim();
                    if (participantId.isEmpty()) participantId = fallbackId;
                    if (!participantId.matches("[A-Za-z0-9_-]{4,32}")) throw new IllegalArgumentException("ID peserta tidak valid");
                    String participantAlias = cell(cells, aliasCol).trim();
                    if (participantAlias.isEmpty()) participantAlias = fallbackAlias;
                    long time = parseTime(cell(cells, timeCol));
                    if (recordType.equals("presence")) {
                        String peerId = cell(cells, peerCol).trim();
                        if (peerId.isEmpty() || !peerId.matches("[A-Za-z0-9_-]{4,32}") || effectiveGroup.isEmpty()) throw new IllegalArgumentException("event presence tidak lengkap");
                        if (database.recordPresence(selectedSurveyId, effectiveGroup, participantId, peerId, time)) presenceInserted++;
                        else duplicates++;
                        continue;
                    }
                    if (!recordType.equals("track") && !recordType.equals("waypoint")) throw new IllegalArgumentException("jenis baris tidak dikenal");
                    double lat = Double.parseDouble(cell(cells, latCol).trim());
                    double lon = Double.parseDouble(cell(cells, lonCol).trim());
                    if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) throw new IllegalArgumentException("koordinat di luar rentang");
                    Double alt = parseNullableDouble(cell(cells, altCol));
                    if (alt != null && (!Double.isFinite(alt) || alt < -12000 || alt > 100000)) throw new IllegalArgumentException("elevasi di luar rentang");
                    Float hacc = parseNullableFloat(cell(cells, haccCol));
                    Float vacc = parseNullableFloat(cell(cells, vaccCol));
                    Float pressure = parseNullableFloat(cell(cells, pressureCol));
                    Double baro = parseNullableDouble(cell(cells, baroCol));
                    String label = cell(cells, labelCol).trim();
                    String source = cell(cells, sourceCol).trim();
                    if (source.isEmpty()) source = "CSV";
                    String importedDatum = cell(cells, datumCol).trim();
                    if (importedDatum.length() > 80) importedDatum = importedDatum.substring(0, 80);
                    if (!importedDatum.isEmpty() && !source.toLowerCase(Locale.ROOT).contains(importedDatum.toLowerCase(Locale.ROOT)))
                        source += " [elevation reference: " + importedDatum + "]";
                    boolean waypoint = recordType.equals("waypoint") || "true".equalsIgnoreCase(cell(cells, waypointCol)) || "1".equals(cell(cells, waypointCol));
                    SurveyDatabase.Point point = new SurveyDatabase.Point(selectedSurveyId, time, lat, lon, alt, hacc, vacc, pressure, baro, source,
                            label.isEmpty() ? "Impor CSV • baris " + rowNumber : label, participantId, participantAlias, effectiveGroup, waypoint);
                    if (database.addPoint(point)) inserted++; else duplicates++;
                } catch (Exception e) { invalid++; }
            }
        }
        refreshAll();
        StringBuilder message = new StringBuilder(inserted + " titik track/waypoint dan " + presenceInserted + " event presence ditambahkan ke proyek “" + selectedSurveyName() + "”.");
        if (duplicates > 0) message.append("\n").append(duplicates).append(" baris duplikat diabaikan.");
        if (wrongGroup > 0) message.append("\n").append(wrongGroup).append(" baris outing lain/tanpa kode yang cocok dilewati. Atur kode rombongan yang sama sebelum mengimpor.");
        if (invalid > 0) message.append("\n").append(invalid).append(" baris dilewati karena format/koordinat tidak valid.");
        message.append("\n\nData tetap tersimpan lokal. Track peserta digabung untuk satu profil jalur, bukan kontur area.");
        new AlertDialog.Builder(this).setTitle("Impor selesai").setMessage(message.toString()).setPositiveButton("Selesai", null).show();
    }

    private static String gpxDedupeKey(long time, String participantId, boolean waypoint) {
        return time + "|" + (waypoint ? "w" : "t") + "|"
                + (participantId == null ? "" : participantId);
    }

    private String importFileAlias(Uri uri, String fallbackId) {
        String name = "Peserta impor-" + fallbackId.substring(4);
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.trim().isEmpty()) name = n.trim().replaceFirst("(?i)\\.csv$", "");
            }
        } catch (Exception ignored) {}
        return name.length() > 32 ? name.substring(0, 32) : name;
    }

    private long parseTime(String value) {
        String timeValue = value == null ? "" : value.trim();
        if (timeValue.isEmpty()) return System.currentTimeMillis();
        try { return Long.parseLong(timeValue); }
        catch (NumberFormatException ignored) {
            try { return Instant.parse(timeValue).toEpochMilli(); }
            catch (DateTimeParseException ignoredAgain) { return System.currentTimeMillis(); }
        }
    }

    private List<String> parseCsvLine(String line) {
        return CsvCodec.parseLine(line);
    }

    private String cell(List<String> cells, int col) { return col >= 0 && col < cells.size() ? cells.get(col) : ""; }
    private boolean isNumber(String value) { try { Double.parseDouble(value.trim()); return true; } catch (Exception e) { return false; } }
    private Double parseNullableDouble(String value) { if (value == null || value.trim().isEmpty()) return null; return Double.parseDouble(value.trim()); }
    private Float parseNullableFloat(String value) { if (value == null || value.trim().isEmpty()) return null; return Float.parseFloat(value.trim()); }

    private String buildCsv() {
        List<SurveyDatabase.Point> points = database.getPoints(selectedSurveyId);
        List<SurveyDatabase.Presence> events = database.getPresence(selectedSurveyId);
        SurveyDatabase.Survey s = currentSurvey();
        String localId = s == null ? "" : s.participantId;
        String localAlias = s == null ? "" : s.participantAlias;
        StringBuilder b = new StringBuilder();
        CsvCodec.appendRow(b, "record_type", "group_code", "participant_id", "participant_alias", "latitude", "longitude",
                "elevation_m", "accuracy_m", "vertical_accuracy_m", "pressure_hpa", "barometer_relative_elevation_m",
                "time_iso", "source", "elevation_reference", "label", "peer_id");
        for (SurveyDatabase.Point p : points) {
            CsvCodec.appendRow(b,
                    p.waypoint ? "waypoint" : "track", p.groupCode, p.participantId, p.participantAlias,
                    String.valueOf(p.lat), String.valueOf(p.lon), p.gpsAltitude == null ? "" : String.valueOf(p.gpsAltitude),
                    p.horizontalAccuracy == null ? "" : String.valueOf(p.horizontalAccuracy),
                    p.verticalAccuracy == null ? "" : String.valueOf(p.verticalAccuracy),
                    p.pressureHpa == null ? "" : String.valueOf(p.pressureHpa),
                    p.barometerRelativeAltitude == null ? "" : String.valueOf(p.barometerRelativeAltitude),
                    Instant.ofEpochMilli(p.time).toString(), p.source,
                    elevationReferenceFor(p),
                    p.label, "");
        }
        for (SurveyDatabase.Presence event : events) {
            String alias = event.observerParticipantId.equals(localId) ? localAlias : "";
            CsvCodec.appendRow(b, "presence", event.groupCode, event.observerParticipantId, alias,
                    "", "", "", "", "", "", "", Instant.ofEpochMilli(event.time).toString(),
                    "BLE", "", "presence ID + timestamp", event.participantId);
        }
        return b.toString();
    }

    private String buildGpx() {
        List<SurveyDatabase.Point> points = database.getPoints(selectedSurveyId);
        boolean withElevation = pendingGpxDatum != null && !pendingGpxDatum.isEmpty();
        List<SurveyDatabase.Point> export = new ArrayList<>(points.size());
        for (SurveyDatabase.Point point : points) {
            if (withElevation && point.gpsAltitude != null
                    && !pendingGpxDatum.equals(elevationReferenceFor(point))) {
                export.add(GpxCodec.withoutElevation(point));
            } else {
                export.add(point);
            }
        }
        String note = "Satu jalur per proyek; track bukan kontur area."
                + (withElevation
                ? " Referensi elevasi: " + pendingGpxDatum + ". Elevasi tidak dikonversi."
                : " Ekspor tanpa elevasi; hanya koordinat dan waktu.");
        return GpxCodec.build(selectedSurveyName(), note, pendingGpxDatum, export,
                System.currentTimeMillis());
    }

    private String elevationReferenceFor(SurveyDatabase.Point point) {
        if (point.gpsAltitude == null) return "";
        String source = point.source == null ? "" : point.source;
        String marker = "[elevation reference:";
        int start = source.toLowerCase(Locale.ROOT).indexOf(marker);
        if (start >= 0) {
            start += marker.length();
            int end = source.indexOf("]", start);
            if (end > start) return source.substring(start, end).trim();
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        if (normalized.contains("core location") || normalized.contains("ios") || normalized.contains("orthometric") || normalized.contains("mean sea level"))
            return "Core Location altitude; approximate mean sea level (orthometric)";
        if ("csv".equalsIgnoreCase(source)) return "as imported; datum not recorded";
        return "Android Location altitude; WGS84 ellipsoid";
    }

    private String buildGeoJson() throws Exception {
        List<SurveyDatabase.Point> points = database.getPoints(selectedSurveyId);
        SurveyDatabase.Survey survey = currentSurvey();
        String project = selectedSurveyName();
        JSONArray features = new JSONArray();

        List<SurveyDatabase.Point> track = new ArrayList<>();
        for (SurveyDatabase.Point point : points) {
            if (!point.waypoint && validCoordinate(point)) track.add(point);
        }

        if (!track.isEmpty()) {
            LinkedHashSet<String> references = new LinkedHashSet<>();
            boolean allElevated = true;
            double distance = 0;
            for (int i = 0; i < track.size(); i++) {
                SurveyDatabase.Point point = track.get(i);
                if (point.gpsAltitude == null) allElevated = false;
                else references.add(elevationReferenceFor(point));
                if (i > 0) {
                    SurveyDatabase.Point previous = track.get(i - 1);
                    distance += RouteProfileEngine.distanceMeters(previous.lat, previous.lon, point.lat, point.lon);
                }
            }

            // A GeoJSON position must not mix datums silently, so elevation only enters the
            // geometry when every track point has one unambiguous reference. Otherwise the line
            // stays 2D and the per-point JSONL/CSV keeps the elevations.
            boolean uniform3d = allElevated && references.size() == 1;
            JSONArray coordinates = new JSONArray();
            JSONArray times = new JSONArray();
            for (SurveyDatabase.Point point : track) {
                JSONArray position = new JSONArray().put(point.lon).put(point.lat);
                if (uniform3d) position.put(point.gpsAltitude);
                coordinates.put(position);
                times.put(Instant.ofEpochMilli(point.time).toString());
            }

            JSONObject geometry = new JSONObject();
            geometry.put("type", track.size() == 1 ? "Point" : "LineString");
            geometry.put("coordinates", track.size() == 1 ? coordinates.get(0) : coordinates);

            JSONObject properties = new JSONObject();
            properties.put("record_type", "route");
            properties.put("project", project);
            properties.put("point_count", track.size());
            properties.put("distance_m", Math.round(distance * 10.0) / 10.0);
            properties.put("start_time_iso", Instant.ofEpochMilli(track.get(0).time).toString());
            properties.put("end_time_iso", Instant.ofEpochMilli(track.get(track.size() - 1).time).toString());
            properties.put("times_iso", times);
            properties.put("elevation_reference", uniform3d ? references.iterator().next()
                    : references.isEmpty() ? "none"
                    : "mixed/omitted from route geometry; see JSONL or CSV");
            if (survey != null) {
                properties.put("group_code", survey.groupCode);
                properties.put("participant_id", survey.participantId);
                properties.put("participant_alias", survey.participantAlias);
            }

            JSONObject feature = new JSONObject();
            feature.put("type", "Feature");
            feature.put("geometry", geometry);
            feature.put("properties", properties);
            features.put(feature);
        }

        for (SurveyDatabase.Point point : points) {
            if (!point.waypoint || !validCoordinate(point)) continue;
            JSONObject feature = new JSONObject();
            feature.put("type", "Feature");
            JSONObject geometry = new JSONObject();
            geometry.put("type", "Point");
            JSONArray coordinates = new JSONArray().put(point.lon).put(point.lat);
            if (point.gpsAltitude != null) coordinates.put(point.gpsAltitude);
            geometry.put("coordinates", coordinates);
            feature.put("geometry", geometry);

            JSONObject properties = new JSONObject();
            properties.put("record_type", "waypoint");
            properties.put("project", project);
            properties.put("group_code", point.groupCode);
            properties.put("participant_id", point.participantId);
            properties.put("participant_alias", point.participantAlias);
            properties.put("time_iso", Instant.ofEpochMilli(point.time).toString());
            properties.put("time_ms", point.time);
            properties.put("source", point.source);
            properties.put("label", point.label);
            if (point.gpsAltitude != null) {
                properties.put("elevation_m", point.gpsAltitude);
                properties.put("elevation_reference", elevationReferenceFor(point));
            }
            if (point.horizontalAccuracy != null) properties.put("horizontal_accuracy_m", point.horizontalAccuracy);
            if (point.verticalAccuracy != null) properties.put("vertical_accuracy_m", point.verticalAccuracy);
            if (point.pressureHpa != null) properties.put("pressure_hpa", point.pressureHpa);
            if (point.barometerRelativeAltitude != null)
                properties.put("barometer_relative_elevation_m", point.barometerRelativeAltitude);
            feature.put("properties", properties);
            features.put(feature);
        }

        JSONObject meta = new JSONObject();
        meta.put("app", "Lereng Offline");
        meta.put("version", BuildConfig.VERSION_NAME);
        meta.put("schema", "lereng.geojson/1");
        meta.put("project", project);
        meta.put("exported_at", Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        meta.put("scope", "single route only; not area-wide terrain contours; no basemap tiles");
        if (survey != null) {
            meta.put("group_code", survey.groupCode);
            meta.put("participant_id", survey.participantId);
            meta.put("participant_alias", survey.participantAlias);
        }

        JSONObject collection = new JSONObject();
        collection.put("type", "FeatureCollection");
        collection.put("name", project);
        collection.put("scope", "single route only; not area-wide terrain contours");
        collection.put("lereng", meta);
        collection.put("features", features);
        return collection.toString(2) + "\n";
    }

    /**
     * Newline-delimited GeoJSON Features (one document per stored record) for
     * {@code mongoimport} or the bundled {@code tools/geojson_to_mongodb.py}.
     */
    private String buildJsonLines() throws Exception {
        List<SurveyDatabase.Point> points = database.getPoints(selectedSurveyId);
        List<SurveyDatabase.Presence> events = database.getPresence(selectedSurveyId);
        SurveyDatabase.Survey survey = currentSurvey();
        String project = selectedSurveyName();
        String exportedAt = Instant.ofEpochMilli(System.currentTimeMillis()).toString();
        StringBuilder out = new StringBuilder(1024 + points.size() * 420);

        for (SurveyDatabase.Point point : points) {
            if (!validCoordinate(point)) continue;
            JSONObject feature = new JSONObject();
            feature.put("type", "Feature");
            JSONObject geometry = new JSONObject();
            geometry.put("type", "Point");
            JSONArray coordinates = new JSONArray().put(point.lon).put(point.lat);
            if (point.gpsAltitude != null) coordinates.put(point.gpsAltitude);
            geometry.put("coordinates", coordinates);
            feature.put("geometry", geometry);

            JSONObject properties = new JSONObject();
            properties.put("schema", "lereng.mongo/1");
            properties.put("project", project);
            properties.put("record_type", point.waypoint ? "waypoint" : "track");
            properties.put("waypoint", point.waypoint);
            properties.put("group_code", point.groupCode);
            properties.put("participant_id", point.participantId);
            properties.put("participant_alias", point.participantAlias);
            properties.put("time_iso", Instant.ofEpochMilli(point.time).toString());
            properties.put("time_ms", point.time);
            properties.put("source", point.source);
            properties.put("label", point.label);
            properties.put("exported_at", exportedAt);
            if (point.gpsAltitude != null) {
                properties.put("elevation_m", point.gpsAltitude);
                properties.put("elevation_reference", elevationReferenceFor(point));
            }
            if (point.horizontalAccuracy != null) properties.put("horizontal_accuracy_m", point.horizontalAccuracy);
            if (point.verticalAccuracy != null) properties.put("vertical_accuracy_m", point.verticalAccuracy);
            if (point.pressureHpa != null) properties.put("pressure_hpa", point.pressureHpa);
            if (point.barometerRelativeAltitude != null)
                properties.put("barometer_relative_elevation_m", point.barometerRelativeAltitude);
            feature.put("properties", properties);
            out.append(feature.toString()).append('\n');
        }

        String localId = survey == null ? "" : survey.participantId;
        String localAlias = survey == null ? "" : survey.participantAlias;
        for (SurveyDatabase.Presence event : events) {
            JSONObject feature = new JSONObject();
            feature.put("type", "Feature");
            feature.put("geometry", JSONObject.NULL);
            JSONObject properties = new JSONObject();
            properties.put("schema", "lereng.mongo/1");
            properties.put("project", project);
            properties.put("record_type", "presence");
            properties.put("group_code", event.groupCode);
            properties.put("participant_id", event.observerParticipantId);
            properties.put("participant_alias",
                    event.observerParticipantId.equals(localId) ? localAlias : "");
            properties.put("seen_participant_id", event.participantId);
            properties.put("time_iso", Instant.ofEpochMilli(event.time).toString());
            properties.put("time_ms", event.time);
            properties.put("source", "BLE");
            properties.put("label", "presence ID + timestamp");
            properties.put("exported_at", exportedAt);
            feature.put("properties", properties);
            out.append(feature.toString()).append('\n');
        }
        return out.toString();
    }

    private boolean validCoordinate(SurveyDatabase.Point point) {
        return point != null && Double.isFinite(point.lat) && Double.isFinite(point.lon)
                && point.lat >= -90 && point.lat <= 90 && point.lon >= -180 && point.lon <= 180;
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_PRESSURE || event.values.length == 0) return;
        pressureHpa = event.values[0];
        if (baselinePressureHpa == null) baselinePressureHpa = pressureHpa;
        if (pressureText != null) updatePressureUi();
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void registerNetworkMonitor() {
        if (connectivityManager == null) return;
        if (networkCallback == null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                private void update() {
                    runOnUiThread(() -> {
                        if (!hasValidatedInternet()) {
                            latestNetworkFix = null;
                            latestFix = chooseBestActivityFix(System.currentTimeMillis());
                            if (latestFix != null && gpsText != null) gpsText.setText(locationSummary(latestFix));
                        }
                        refreshNetworkStatus();
                    });
                }
                @Override public void onAvailable(Network network) { update(); }
                @Override public void onLost(Network network) { update(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { update(); }
            };
        }
        if (!networkCallbackRegistered) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
                networkCallbackRegistered = true;
            } catch (RuntimeException ignored) {}
        }
        refreshNetworkStatus();
    }

    private boolean hasValidatedInternet() {
        if (connectivityManager == null) return false;
        Network active = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : connectivityManager.getNetworkCapabilities(active);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void refreshNetworkStatus() {
        if (networkText == null) return;
        if (connectivityManager == null) {
            networkText.setText("Status internet tidak dapat dipantau; provider GNSS tetap diminta.");
        } else if (hasValidatedInternet()) {
            networkText.setText("Internet tervalidasi • provider jaringan Android dapat membantu; GNSS tetap diminta bila tersedia. Track tidak diunggah otomatis.");
        } else {
            networkText.setText("Internet tidak tersedia/tervalidasi • GNSS tetap diminta bila tersedia; rekaman tidak berhenti.");
        }
        if (netChip != null) {
            if (connectivityManager != null && hasValidatedInternet())
                netChip.set(UiKit.TONE_SUCCESS, "Internet ada");
            else if (connectivityManager != null && connectivityManager.getActiveNetwork() != null)
                netChip.set(UiKit.TONE_WARNING, "Jaringan mentah");
            else netChip.set(UiKit.TONE_NEUTRAL, "Offline");
        }
    }

    private void unregisterNetworkMonitor() {
        if (connectivityManager != null && networkCallback != null && networkCallbackRegistered) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) {}
            networkCallbackRegistered = false;
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    // ---------------------------------------------------------------- pembaruan aplikasi

    private void checkForUpdate(boolean manual) {
        if (updateCheckInFlight) {
            if (manual) Toast.makeText(this, "Pemeriksaan sedang berjalan…", Toast.LENGTH_SHORT).show();
            return;
        }
        String manifest = BuildConfig.UPDATE_MANIFEST_URL == null
                ? "" : BuildConfig.UPDATE_MANIFEST_URL.trim();
        if (manifest.isEmpty()) {
            if (manual) showUpdateMessage("Pembaruan belum dikonfigurasi",
                    "Build ini tidak memuat URL manifes pembaruan, jadi pemeriksaan otomatis nonaktif. "
                    + "Aplikasi tetap berjalan normal dan tidak menghubungi server mana pun.");
            return;
        }
        long now = System.currentTimeMillis();
        if (!manual && now - updatePrefs.getLong("last_check_ms", 0L) < UPDATE_CHECK_INTERVAL_MS) return;

        updateCheckInFlight = true;
        if (manual) Toast.makeText(this, "Memeriksa pembaruan…", Toast.LENGTH_SHORT).show();
        UpdateChecker.check(manifest, BuildConfig.VERSION_CODE, (info, error) -> {
            updateCheckInFlight = false;
            updatePrefs.edit().putLong("last_check_ms", System.currentTimeMillis()).apply();
            if (error != null) {
                if (manual) showUpdateMessage("Gagal memeriksa pembaruan",
                        error + "\n\nTidak ada data pengguna yang dikirim. Aplikasi tetap berjalan normal; "
                        + "coba lagi saat koneksi stabil.");
                return;
            }
            if (info == null) {
                offeredUpdate = null;
                if (manual) showUpdateMessage("Sudah versi terbaru",
                        "Terpasang v" + BuildConfig.VERSION_NAME
                        + " (kode " + BuildConfig.VERSION_CODE + ").");
                return;
            }
            offeredUpdate = info;
            if (info.mandatory) showUpdateDialog(info);
            else if (manual || !updateSnoozed()) showUpdateDialog(info);
        });
    }

    private void showUpdateDialog(UpdateChecker.UpdateInfo info) {
        if (isFinishing() || isDestroyed()) return;
        if (updateDialog != null && updateDialog.isShowing()) updateDialog.dismiss();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(info.title())
                .setMessage(info.message())
                .setCancelable(!info.mandatory)
                .setPositiveButton("Perbarui sekarang", (dialog, which) -> openUpdateLink(info));
        if (info.mandatory) builder.setNegativeButton("Keluar", (dialog, which) -> finishAffinity());
        else builder.setNegativeButton("Nanti", (dialog, which) -> snoozeUpdate());
        updateDialog = builder.create();
        updateDialog.setCanceledOnTouchOutside(!info.mandatory);
        updateDialog.show();
    }

    private void openUpdateLink(UpdateChecker.UpdateInfo info) {
        String url = info.apkUrl == null ? "" : info.apkUrl;
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            showUpdateMessage("Tautan tidak aman",
                    "URL unduhan bukan HTTPS, jadi tidak dibuka. Hubungi pembuat build untuk manifes yang benar.");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception exc) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Unduh Lereng Offline", url));
            }
            Toast.makeText(this, "Tidak ada browser. Tautan unduhan disalin ke clipboard.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void snoozeUpdate() {
        updatePrefs.edit().putLong("snooze_until_ms",
                System.currentTimeMillis() + UPDATE_SNOOZE_MS).apply();
        Toast.makeText(this, "Pengingat ditunda 24 jam.", Toast.LENGTH_SHORT).show();
    }

    private boolean updateSnoozed() {
        return System.currentTimeMillis() < updatePrefs.getLong("snooze_until_ms", 0L);
    }

    private void showUpdateMessage(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Tutup", null).show();
    }

    // RECEIVER_NOT_EXPORTED baru dikenal sejak API 33. Pada API 26–32 overload dua argumen
    // sudah aman karena LocationLoggingService mengirim broadcast dengan setPackage(nama paket).
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(LocationLoggingService.ACTION_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dataReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(dataReceiver, filter);
        receiverRegistered = true;
        IntentFilter syncFilter = new IntentFilter(MongoSync.ACTION_SYNC_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(syncReceiver, syncFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(syncReceiver, syncFilter);
        syncReceiverRegistered = true;
        if (pressureSensor != null && sensorManager != null) sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
        registerNetworkMonitor();
        if (hasPreciseLocation()) beginLiveLocation();
        lastTrackCount = -1;
        refreshAll();
        syncRecordingControls();
        setActiveTab(activeTab, false);
        if (offeredUpdate != null && offeredUpdate.mandatory) showUpdateDialog(offeredUpdate);
        else checkForUpdate(false);
    }

    @Override protected void onPause() {
        if (receiverRegistered) { unregisterReceiver(dataReceiver); receiverRegistered = false; }
        if (syncReceiverRegistered) { unregisterReceiver(syncReceiver); syncReceiverRegistered = false; }
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (locationManager != null && hasPreciseLocation()) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) {}
        }
        unregisterNetworkMonitor();
        stopPulse();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopPulse();
        if (bottomPrimary != null) bottomPrimary.animate().cancel();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        profileExecutor.shutdownNow();
        if (database != null) database.close();
        super.onDestroy();
    }
}
