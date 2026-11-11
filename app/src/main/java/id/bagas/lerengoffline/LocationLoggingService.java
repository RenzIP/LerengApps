package id.bagas.lerengoffline;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.Locale;

public final class LocationLoggingService extends Service implements LocationListener, SensorEventListener {
    public static final String ACTION_START = "id.bagas.lerengoffline.START_LOGGING";
    public static final String ACTION_STOP = "id.bagas.lerengoffline.STOP_LOGGING";
    public static final String ACTION_UPDATED = "id.bagas.lerengoffline.DATA_UPDATED";
    /** Dikirim ketika perekaman gagal dimulai (izin dicabut atau tidak ada provider). */
    public static final String ACTION_FAILED = "id.bagas.lerengoffline.RECORDING_FAILED";
    /** Dikirim ketika sudah lama tidak ada titik baru, supaya UI tidak diam saja. */
    public static final String ACTION_NO_FIX = "id.bagas.lerengoffline.NO_FIX";
    public static final String EXTRA_SURVEY_ID = "survey_id";
    public static final String EXTRA_GROUP_CODE = "group_code";
    public static final String EXTRA_PARTICIPANT_ID = "participant_id";
    public static final String EXTRA_PARTICIPANT_ALIAS = "participant_alias";
    public static final String EXTRA_BLE_ENABLED = "ble_enabled";
    public static volatile boolean isLogging = false;
    public static volatile long activeSurveyId = -1;
    private static final String CHANNEL_ID = "field_recording";
    private static final int NOTIFICATION_ID = 71;
    /** Provider sintetis dari Fused Location Provider (Google Play Services). */
    private static final String FUSED_PROVIDER = "fused";
    /** Titik dengan akurasi horizontal di atas nilai ini tidak disimpan. */
    static final float MAX_HORIZONTAL_ACCURACY_METERS = 30f;
    /** Jeda minimum antar titik tersimpan; lebih rapat dari 9 detik agar tikungan tidak terpotong. */
    private static final long SAVE_INTERVAL_MS = 5000L;

    private SurveyDatabase database;
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor pressureSensor;
    private BlePresenceController bleController;
    private long surveyId = -1;
    private String groupCode = "";
    private String participantId = "";
    private String participantAlias = "";
    private Float pressureHpa;
    private Float baselinePressureHpa;
    private Location latestGpsFix;
    private Location latestNetworkFix;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkCallbackRegistered;
    private boolean internetAvailable;
    private long lastSaveElapsed = 0;
    private long lastSavedLocationTime = 0;
    private boolean registered;
    private boolean saveScheduled;
    private long lastSyncRequestAt = 0;
    private FusedLocationProviderClient fusedClient;
    private boolean fusedActive;
    private final Handler locationHandler = new Handler(Looper.getMainLooper());
    /** Jeda pemeriksaan "apakah masih ada titik baru". */
    private static final long FIX_WATCHDOG_MS = 60_000L;
    /** Dianggap macet bila tidak ada titik tersimpan selama ini. */
    private static final long FIX_STALL_MS = 120_000L;

    /**
     * Peringatan diam-diam adalah bug: kalau GPS tidak memberi fix (di dalam
     * tenda, GPS dimatikan, izin dicabut), pengguna tidak tahu apa pun sampai
     * pulang. Pengawas ini memberi tahu setelah dua menit tanpa titik baru.
     */
    private final Runnable fixWatchdog = new Runnable() {
        @Override public void run() {
            if (!isLogging) return;
            long sinceLastSave = lastSaveElapsed == 0
                    ? Long.MAX_VALUE
                    : SystemClock.elapsedRealtime() - lastSaveElapsed;
            if (sinceLastSave > FIX_STALL_MS) {
                updateNotification("Belum ada titik baru lebih dari 2 menit. Periksa GPS dan ruang terbuka.");
                sendBroadcast(new Intent(ACTION_NO_FIX).setPackage(getPackageName()));
            }
            locationHandler.postDelayed(this, FIX_WATCHDOG_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        database = new SurveyDatabase(this);
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        connectivityManager = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        pressureSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (pressureSensor != null) sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopLogging();
            return START_NOT_STICKY;
        }
        surveyId = intent.getLongExtra(EXTRA_SURVEY_ID, -1);
        if (surveyId < 0) surveyId = database.ensureDefaultSurvey();
        SurveyDatabase.Survey survey = database.getSurvey(surveyId);
        groupCode = intent.getStringExtra(EXTRA_GROUP_CODE);
        if (groupCode == null && survey != null) groupCode = survey.groupCode;
        if (groupCode == null) groupCode = "";
        participantId = intent.getStringExtra(EXTRA_PARTICIPANT_ID);
        if (participantId == null && survey != null) participantId = survey.participantId;
        if (participantId == null) participantId = "";
        participantAlias = intent.getStringExtra(EXTRA_PARTICIPANT_ALIAS);
        if (participantAlias == null && survey != null) participantAlias = survey.participantAlias;
        if (participantAlias == null) participantAlias = "";
        createNotificationChannel();
        Notification notification = buildNotification("Menunggu provider lokasi Android…");
        if (Build.VERSION.SDK_INT >= 29) {
            int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            if (intent.getBooleanExtra(EXTRA_BLE_ENABLED, false))
                serviceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            startForeground(NOTIFICATION_ID, notification, serviceTypes);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        activeSurveyId = surveyId;
        registerConnectivityMonitor();
        beginLocationUpdates();
        if (!registered) {
            // Jangan mengaku merekam tanpa sumber lokasi. Dulu layanan tetap
            // menyalakan notifikasi "sedang merekam" padahal tidak ada titik
            // yang akan pernah tersimpan.
            isLogging = false;
            activeSurveyId = -1;
            unregisterConnectivityMonitor();
            sendBroadcast(new Intent(ACTION_FAILED).setPackage(getPackageName()));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        isLogging = true;
        locationHandler.postDelayed(fixWatchdog, FIX_WATCHDOG_MS);
        boolean bleEnabled = intent.getBooleanExtra(EXTRA_BLE_ENABLED, false)
                && BleGroupCodec.isValidGroupCode(groupCode);
        if (bleController != null) {
            // Perintah mulai kedua tidak boleh meninggalkan pemindai lama hidup:
            // dulu presence tercatat ganda dan baterai terpakai dua kali.
            bleController.stop();
            bleController = null;
        }
        if (bleEnabled) {
            bleController = new BlePresenceController(this, database, surveyId, groupCode, participantId,
                    new BlePresenceController.Listener() {
                        @Override public void onStatus(String status) { updateNotification(status); }
                        @Override public void onPeerSeen(String id) {
                            sendBroadcast(new Intent(ACTION_UPDATED).setPackage(getPackageName()));
                            scheduleSync(20000);
                            updateNotification("Lokasi aktif • ID peserta terlihat; BLE tidak mengukur jarak atau elevasi.");
                        }
                    });
            bleController.start();
        }
        return START_NOT_STICKY;
    }

    private void beginLocationUpdates() {
        if (locationManager == null || registered) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Izin lokasi presisi diperlukan untuk pencatatan.");
            return;
        }
        if (startFusedUpdates()) return;
        startPlatformUpdates();
    }

    /**
     * Fused Location Provider menggabungkan GNSS, Wi-Fi, dan sel seperti yang dipakai aplikasi
     * olahraga populer. Hasilnya titik awal lebih cepat dan lebih stabil di bawah kanopi, tetapi
     * lokasi melewati layanan Google Play; aplikasi ini tetap tidak mengunggah apa pun.
     * Bila Play Services tidak tersedia, pemanggil memakai LocationManager biasa.
     */
    @SuppressLint("MissingPermission")
    private boolean startFusedUpdates() {
        try {
            int availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
            if (availability != ConnectionResult.SUCCESS) return false;
            fusedClient = LocationServices.getFusedLocationProviderClient(this);
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                    .setMinUpdateIntervalMillis(2000L)
                    .setMaxUpdateDelayMillis(0L)
                    .setWaitForAccurateLocation(false)
                    .build();
            fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper());
            fusedActive = true;
            registered = true;
            updateNotification("Lokasi presisi (Google Play Services) diminta; GNSS tetap sumber utama di luar jaringan.");
            return true;
        } catch (RuntimeException e) {
            fusedClient = null;
            fusedActive = false;
            return false;
        }
    }

    private final LocationCallback fusedCallback = new LocationCallback() {
        @Override public void onLocationResult(LocationResult result) {
            if (result == null) return;
            for (Location location : result.getLocations()) {
                if (location != null) onLocationChanged(location);
            }
        }
    };

    private void startPlatformUpdates() {
        boolean gnssStarted = false;
        boolean networkStarted = false;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!locationManager.getAllProviders().contains(provider)) continue;
                locationManager.requestLocationUpdates(provider, 10000L, 0f, this);
                if (LocationManager.GPS_PROVIDER.equals(provider)) gnssStarted = true;
                else networkStarted = true;
            } catch (SecurityException | IllegalArgumentException ignored) {
                // A missing or disabled provider does not stop the other source.
            }
        }
        registered = gnssStarted || networkStarted;
        String status = !registered
                ? "Provider lokasi tidak tersedia; periksa layanan lokasi perangkat."
                : gnssStarted && networkStarted
                    ? "Provider Android Network + GNSS diminta; fix terbaik dipilih otomatis."
                    : gnssStarted
                        ? "GNSS diminta; provider jaringan tidak tersedia, rekaman tetap lokal."
                        : "Provider jaringan diminta; GNSS tidak tersedia pada perangkat ini.";
        updateNotification(status);
    }

    private void registerConnectivityMonitor() {
        if (connectivityManager == null) return;
        if (networkCallback == null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                private void changed() { updateInternetAvailability(); }
                @Override public void onAvailable(Network network) { changed(); }
                @Override public void onLost(Network network) { changed(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { changed(); }
            };
        }
        if (!networkCallbackRegistered) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
                networkCallbackRegistered = true;
            } catch (RuntimeException ignored) {}
        }
        updateInternetAvailability();
    }

    private void updateInternetAvailability() {
        boolean connected = false;
        if (connectivityManager != null) {
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = active == null ? null : connectivityManager.getNetworkCapabilities(active);
            connected = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        final boolean validated = connected;
        locationHandler.post(() -> {
            if (internetAvailable == validated) return;
            internetAvailable = validated;
            if (!internetAvailable) latestNetworkFix = null;
            if (isLogging) updateNotification(internetAvailable
                    ? "Internet tersedia • provider jaringan dapat membantu; GNSS tetap diminta bila tersedia."
                    : "Internet putus • GNSS tetap diminta bila tersedia; rekaman tidak dijeda.");
        });
    }

    private void unregisterConnectivityMonitor() {
        if (connectivityManager != null && networkCallback != null && networkCallbackRegistered) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) {}
            networkCallbackRegistered = false;
        }
    }

    @Override public void onLocationChanged(Location location) {
        if (location == null || surveyId < 0) return;
        String provider = location.getProvider();
        if (FUSED_PROVIDER.equals(provider)) {
            if (fusedActive) latestGpsFix = new Location(location);
        } else if (LocationManager.GPS_PROVIDER.equals(provider)) latestGpsFix = new Location(location);
        else if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            if (!internetAvailable) return;
            latestNetworkFix = new Location(location);
        }
        else return;
        // Jeda antar titik memakai jam monoton. Kalau memakai jam dinding,
        // perubahan zona waktu atau koreksi NTP bisa membekukan penyimpanan
        // sampai jam perangkat mengejar nilai lama.
        if (SystemClock.elapsedRealtime() - lastSaveElapsed < SAVE_INTERVAL_MS || saveScheduled) return;
        saveScheduled = true;
        locationHandler.postDelayed(this::saveBestFix, 800L);
    }

    private void saveBestFix() { saveBestFix(false); }

    private void saveBestFix(boolean force) {
        saveScheduled = false;
        if (!isLogging || surveyId < 0) return;
        long now = System.currentTimeMillis();
        if (!force && SystemClock.elapsedRealtime() - lastSaveElapsed < SAVE_INTERVAL_MS) return;
        Location location = chooseBestFreshFix(now);
        if (location == null) return;
        if (location.hasAccuracy() && location.getAccuracy() > MAX_HORIZONTAL_ACCURACY_METERS) {
            // Titik buruk tidak disimpan supaya jalur tidak melompat; titik berikutnya tetap dicoba.
            updateNotification(String.format(Locale.getDefault(),
                    "Titik dilewati: akurasi ±%d m di atas batas %d m.",
                    Math.round(location.getAccuracy()), Math.round(MAX_HORIZONTAL_ACCURACY_METERS)));
            return;
        }
        Double altitude = location.hasAltitude() ? location.getAltitude() : null;
        Float verticalAccuracy = location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : null;
        String source = sourceForProvider(location.getProvider());
        String note = "Rekaman otomatis • " + source;
        if (!location.hasAltitude()) note += " • elevasi tidak tersedia";
        SurveyDatabase.Point p = new SurveyDatabase.Point(surveyId, location.getTime(),
                location.getLatitude(), location.getLongitude(), altitude,
                location.hasAccuracy() ? location.getAccuracy() : null,
                verticalAccuracy, pressureHpa, getRelativeAltitude(), source, note,
                participantId, participantAlias, groupCode, false);
        try {
            boolean saved = database.addPoint(p);
            if (!saved) {
                // Duplikat atau proyek sudah dihapus: jangan memperbarui tanda
                // waktu seolah titik baru tersimpan.
                updateNotification("Titik ini sudah tersimpan; dilewati.");
                return;
            }
            lastSaveElapsed = SystemClock.elapsedRealtime();
            lastSavedLocationTime = location.getTime();
            sendBroadcast(new Intent(ACTION_UPDATED).setPackage(getPackageName()));
            scheduleSync(20000);
            updateNotification(String.format(Locale.getDefault(), "Titik %s • akurasi horizontal %s",
                    source, location.hasAccuracy() ? "±" + Math.round(location.getAccuracy()) + " m" : "tidak diketahui"));
        } catch (RuntimeException ignored) {
            updateNotification("Penyimpanan lokal gagal. Hentikan lalu coba lagi.");
        }
    }

    private Location chooseBestFreshFix(long now) {
        Location best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Location candidate : new Location[]{latestGpsFix, latestNetworkFix}) {
            if (candidate == null || candidate.getTime() <= lastSavedLocationTime) continue;
            long age = Math.max(0, now - candidate.getTime());
            if (age > 20000) continue;
            double accuracy = candidate.hasAccuracy() ? candidate.getAccuracy()
                    : (LocationManager.GPS_PROVIDER.equals(candidate.getProvider()) ? 100.0 : 1000.0);
            double score = accuracy + age / 1000.0 * 2.0;
            if (LocationManager.GPS_PROVIDER.equals(candidate.getProvider())
                    || FUSED_PROVIDER.equals(candidate.getProvider())) score -= 0.01;
            if (score < bestScore) { best = candidate; bestScore = score; }
        }
        return best;
    }

    private String sourceForProvider(String provider) {
        if (FUSED_PROVIDER.equals(provider)) return "Android Fused (Google Play Services)";
        if (LocationManager.GPS_PROVIDER.equals(provider)) return "Android GPS/GNSS";
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) return "Android Network Location";
        return "Android location";
    }

    private Double getRelativeAltitude() {
        if (pressureHpa == null || baselinePressureHpa == null || pressureHpa <= 0 || baselinePressureHpa <= 0) return null;
        return 44330.0 * (1.0 - Math.pow(pressureHpa / baselinePressureHpa, 0.190294957));
    }

    /**
     * Minta sinkron otomatis, tetapi tidak lebih sering dari sekali per menit.
     * Tanpa batas ini, setiap titik akan menumpuk permintaan jaringan.
     */
    private void scheduleSync(long delayMs) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSyncRequestAt < 60000L) return;
        lastSyncRequestAt = now;
        MongoSyncScheduler.requestSync(this, delayMs);
    }

    private void stopLogging() {
        // Simpan fix terakhir sebelum berhenti. Tanpa ini, titik paling akhir
        // (sering kali puncak) hilang karena antrean simpan dibatalkan.
        if (isLogging) saveBestFix(true);
        locationHandler.removeCallbacksAndMessages(null);
        saveScheduled = false;
        // Kirim sisa data segera; rekaman berhenti tetapi data tetap tersimpan lokal.
        MongoSyncScheduler.requestSync(this, 0);
        if (bleController != null) {
            bleController.stop();
            bleController = null;
        }
        unregisterConnectivityMonitor();
        if (fusedClient != null) {
            try { fusedClient.removeLocationUpdates(fusedCallback); } catch (RuntimeException ignored) {}
            fusedClient = null;
        }
        fusedActive = false;
        if (registered && locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) {}
        }
        registered = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        isLogging = false;
        activeSurveyId = -1;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Perekaman lapangan", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Menunjukkan saat provider lokasi sedang merekam titik lapangan");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        return builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Lereng Offline sedang merekam")
                .setContentText(text).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_PRESSURE || event.values.length == 0) return;
        pressureHpa = event.values[0];
        if (baselinePressureHpa == null) baselinePressureHpa = pressureHpa;
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public void onProviderEnabled(String provider) {
        updateNotification("Provider " + provider + " aktif; sumber lokasi tetap dipilih otomatis.");
    }
    @Override public void onProviderDisabled(String provider) {
        updateNotification(LocationManager.GPS_PROVIDER.equals(provider)
                ? "GNSS nonaktif; provider network tetap dapat merekam."
                : "Network provider tidak aktif; GNSS tetap berjalan tanpa jeda.");
    }
    @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

    @Override public void onDestroy() {
        locationHandler.removeCallbacksAndMessages(null);
        unregisterConnectivityMonitor();
        if (bleController != null) { bleController.stop(); bleController = null; }
        // Fused request harus dilepas di sini juga, bukan hanya di stopLogging:
        // layanan bisa dihentikan sistem tanpa melewati jalur itu, dan permintaan
        // lokasi yang tertinggal terus membangunkan perangkat.
        if (fusedClient != null) {
            try { fusedClient.removeLocationUpdates(fusedCallback); } catch (RuntimeException ignored) {}
            fusedClient = null;
        }
        fusedActive = false;
        if (registered && locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) {}
        }
        registered = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        isLogging = false;
        activeSurveyId = -1;
        if (database != null) database.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
