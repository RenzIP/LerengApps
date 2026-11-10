package id.bagas.lerengoffline;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional update check against a static JSON manifest.
 *
 * <p>The application still uploads nothing: it issues a single HTTPS GET for a small public JSON
 * file and compares the published {@code versionCode} with the installed one. Every failure
 * (offline, DNS, TLS, HTTP error, malformed manifest) is reported as an error string and the app
 * keeps working. A manifest may declare an update as mandatory, or make it mandatory by raising
 * {@code minSupportedVersionCode} above the installed build.</p>
 */
final class UpdateChecker {

    static final String SCHEMA = "lereng.update/1";

    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int TIMEOUT_MS = 7000;

    private static ExecutorService executor;

    private static Handler mainHandler;

    private static synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "lereng-update-check");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    private static synchronized Handler mainHandler() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    private UpdateChecker() {}

    interface Callback {
        /** {@code info} is null when the installed build is current; {@code error} is null on success. */
        void onFinished(UpdateInfo info, String error);
    }

    static final class UpdateInfo {
        final int versionCode;
        final String versionName;
        final boolean mandatory;
        final String apkUrl;
        final String notes;
        final long sizeBytes;
        final String sha256;

        UpdateInfo(int versionCode, String versionName, boolean mandatory, String apkUrl,
                   String notes, long sizeBytes, String sha256) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.mandatory = mandatory;
            this.apkUrl = apkUrl;
            this.notes = notes;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }

        String title() {
            return mandatory ? "Pembaruan wajib" : "Pembaruan tersedia";
        }

        String message() {
            StringBuilder text = new StringBuilder(256);
            text.append("Terpasang: v").append(BuildConfig.VERSION_NAME)
                    .append(" (kode ").append(BuildConfig.VERSION_CODE).append(")\n");
            text.append("Tersedia: v").append(versionName)
                    .append(" (kode ").append(versionCode).append(")");
            if (sizeBytes > 0) text.append(" • ").append(sizeLabel());
            if (notes != null && !notes.isEmpty()) text.append("\n\n").append(notes);
            if (mandatory) {
                text.append("\n\nPembaruan ini wajib. Aplikasi dapat dipakai kembali setelah versi baru terpasang.");
            } else {
                text.append("\n\nPembaruan bersifat opsional; data yang sudah terekam tetap aman.");
            }
            return text.toString();
        }

        private String sizeLabel() {
            if (sizeBytes >= 1024 * 1024) {
                return String.format(Locale.ROOT, "%.1f MB", sizeBytes / (1024.0 * 1024.0));
            }
            return String.format(Locale.ROOT, "%d KB", Math.max(1, sizeBytes / 1024));
        }
    }

    static void check(String manifestUrl, int currentVersionCode, Callback callback) {
        String url = manifestUrl == null ? "" : manifestUrl.trim();
        if (url.isEmpty()) {
            callback.onFinished(null, "URL manifes pembaruan belum dikonfigurasi pada build ini.");
            return;
        }
        executor().execute(() -> {
            UpdateInfo info = null;
            String error = null;
            try {
                info = parse(fetch(url), currentVersionCode);
            } catch (Exception exc) {
                error = describe(exc);
            }
            final UpdateInfo result = info;
            final String reason = error;
            mainHandler().post(() -> callback.onFinished(result, reason));
        });
    }

    static UpdateInfo parse(String json, int currentVersionCode) throws Exception {
        JSONObject root = new JSONObject(json);
        String schema = root.optString("schema", "").trim();
        if (!SCHEMA.equals(schema)) {
            throw new IllegalStateException("skema manifes tidak dikenal (" + schema + ")");
        }
        int versionCode = root.optInt("versionCode", -1);
        if (versionCode <= 0) throw new IllegalStateException("versionCode tidak valid");

        String apkUrl = root.optString("apkUrl", "").trim();
        if (!apkUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalStateException("apkUrl harus memakai HTTPS");
        }
        if (versionCode <= currentVersionCode) return null;

        String versionName = root.optString("versionName", "").trim();
        if (versionName.isEmpty()) versionName = String.valueOf(versionCode);

        int minSupported = root.optInt("minSupportedVersionCode", 0);
        boolean mandatory = root.optBoolean("mandatory", false) || currentVersionCode < minSupported;

        return new UpdateInfo(versionCode, versionName, mandatory, apkUrl,
                readNotes(root.opt("notes")), root.optLong("sizeBytes", 0L),
                root.optString("sha256", ""));
    }

    private static String readNotes(Object raw) {
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String line = array.optString(i, "").trim();
                if (line.isEmpty()) continue;
                if (text.length() > 0) text.append('\n');
                text.append("• ").append(line);
            }
            return text.toString();
        }
        if (raw instanceof String) return ((String) raw).trim();
        return "";
    }

    private static String fetch(String manifestUrl) throws Exception {
        URL url = new URL(manifestUrl);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("manifes harus memakai HTTPS");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("User-Agent", "LerengOffline/" + BuildConfig.VERSION_NAME);

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("server menjawab HTTP " + status);
            }
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IllegalStateException("pengalihan bukan HTTPS");
            }
            if (connection.getContentLengthLong() > MAX_MANIFEST_BYTES) {
                throw new IllegalStateException("manifes terlalu besar");
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_MANIFEST_BYTES) {
                        throw new IllegalStateException("manifes terlalu besar");
                    }
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String describe(Exception exc) {
        String message = exc.getMessage();
        String type = exc.getClass().getSimpleName();
        String text = message == null || message.trim().isEmpty() ? type : type + ": " + message.trim();
        return text.length() > 180 ? text.substring(0, 180) + "…" : text;
    }
}
