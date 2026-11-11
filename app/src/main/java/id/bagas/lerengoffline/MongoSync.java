package id.bagas.lerengoffline;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

/**
 * Sinkron otomatis ke endpoint HTTPS milik pengguna.
 *
 * Aplikasi tidak pernah menyimpan URI MongoDB. Yang disimpan hanya alamat
 * endpoint dan token; server yang memegang kredensial basis data. Sinkron
 * berjalan otomatis saat ada data baru, saat aplikasi dibuka, dan berkala
 * lewat JobScheduler. Tidak ada tombol kirim.
 *
 * Pengiriman bersifat inkremental: tanda air per proyek menyimpan ID lokal
 * terakhir yang sudah terkirim, sehingga ribuan titik lama tidak dikirim ulang.
 * Setiap dokumen tetap punya {@code _id} stabil, jadi pengiriman ulang aman.
 */
final class MongoSync {
    static final String PREFS = "lereng_sync";
    static final String ACTION_SYNC_STATUS = "id.bagas.lerengoffline.SYNC_STATUS";

    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_AUTO = "auto";
    private static final String KEY_STATUS = "last_status";
    private static final String KEY_STATUS_OK = "last_status_ok";
    private static final String KEY_STATUS_AT = "last_status_at";

    static final String SCHEMA = "lereng.mongo/1";
    static final int CHUNK_SIZE = 300;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 45000;

    static final class Config {
        final URL endpoint;
        final String token;

        Config(URL endpoint, String token) {
            this.endpoint = endpoint;
            this.token = token;
        }
    }

    private MongoSync() {}

    // ------------------------------------------------------------ pengaturan

    static Config load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String endpoint = prefs.getString(KEY_ENDPOINT, "");
        String token = prefs.getString(KEY_TOKEN, "");
        if (endpoint == null || endpoint.trim().isEmpty() || token == null || token.trim().isEmpty()) {
            return null;
        }
        try {
            return new Config(normalizeEndpoint(endpoint), token.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Config save(Context context, String rawEndpoint, String rawToken) {
        URL endpoint = normalizeEndpoint(rawEndpoint);
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.isEmpty()) throw new IllegalArgumentException("Token belum diisi.");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ENDPOINT, endpoint.toString())
                .putString(KEY_TOKEN, token)
                .apply();
        return new Config(endpoint, token);
    }

    /** Menghapus alamat dan token, mematikan sinkron, tetapi mempertahankan ID pemasangan. */
    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_ENDPOINT)
                .remove(KEY_TOKEN)
                .putBoolean(KEY_AUTO, false)
                .apply();
    }

    /** Default aktif; tanpa endpoint/token, sinkron tidak melakukan apa pun. */
    static boolean isAutoEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO, true);
    }

    static void setAutoEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO, enabled)
                .apply();
    }

    static boolean isConfigured(Context context) {
        return load(context) != null;
    }

    /** ID pemasangan yang stabil, dibuat sekali dan tidak pernah dikirim ke pihak lain. */
    static String clientId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_CLIENT_ID, null);
        if (value != null && !value.isEmpty()) return value;
        value = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_CLIENT_ID, value).apply();
        return value;
    }

    /**
     * Hanya HTTPS. Alamat dasar tanpa path diarahkan ke /api/ingest supaya
     * pengguna cukup menempel alamat proyek Vercel.
     */
    static URL normalizeEndpoint(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Endpoint belum diisi.");
        URL url;
        try {
            url = new URL(value);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Alamat endpoint tidak valid.");
        }
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("Endpoint harus memakai HTTPS.");
        }
        if (url.getHost() == null || url.getHost().isEmpty()) {
            throw new IllegalArgumentException("Host endpoint tidak valid.");
        }
        if (url.getUserInfo() != null || url.getRef() != null || url.getQuery() != null) {
            throw new IllegalArgumentException("Endpoint tidak boleh memuat user, fragmen, atau query.");
        }
        String path = url.getPath() == null ? "" : url.getPath();
        if (path.isEmpty() || "/".equals(path)) {
            path = "/api/ingest";
        } else if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            return new URL("https", url.getHost(), url.getPort(), path);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Alamat endpoint tidak valid.");
        }
    }

    // ------------------------------------------------------------ status

    static String lastStatus(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STATUS, "Menunggu data pertama…");
    }

    static boolean lastStatusOk(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_STATUS_OK, true);
    }

    private static void recordStatus(Context context, String message, boolean ok) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_STATUS, message)
                .putBoolean(KEY_STATUS_OK, ok)
                .putLong(KEY_STATUS_AT, System.currentTimeMillis())
                .apply();
        Intent intent = new Intent(ACTION_SYNC_STATUS).setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    // ------------------------------------------------------------ dokumen

    /**
     * Satu dokumen per titik track, waypoint, event presence, dan metadata
     * proyek. Jalur pembanding GPX sengaja tidak pernah masuk ke sini.
     */
    static List<JSONObject> buildDocuments(String clientId, SurveyDatabase.Survey survey,
                                           String project, List<SurveyDatabase.Point> points,
                                           List<SurveyDatabase.Presence> presence,
                                           String appVersion, boolean includeProject) {
        List<JSONObject> documents = new ArrayList<>();
        String base = clientId + ":" + survey.id;

        if (includeProject) {
            try {
                JSONObject properties = new JSONObject();
                properties.put("schema", SCHEMA);
                properties.put("record_id", base + ":project");
                properties.put("record_type", "project");
                properties.put("project", project);
                properties.put("group_code", survey.groupCode);
                properties.put("participant_id", survey.participantId);
                properties.put("participant_alias", survey.participantAlias);
                properties.put("platform", "android");
                properties.put("app_version", appVersion);
                documents.add(feature(null, properties));
            } catch (Exception ignored) {}
        }

        for (SurveyDatabase.Point point : points) {
            if (!validCoordinate(point)) continue;
            try {
                JSONObject properties = new JSONObject();
                properties.put("schema", SCHEMA);
                properties.put("record_id", base + (point.waypoint ? ":waypoint:" : ":track:") + point.id);
                properties.put("record_type", point.waypoint ? "waypoint" : "track");
                properties.put("waypoint", point.waypoint);
                properties.put("project", project);
                properties.put("group_code", point.groupCode);
                properties.put("participant_id", point.participantId);
                properties.put("participant_alias", point.participantAlias);
                properties.put("time_iso", Instant.ofEpochMilli(point.time).toString());
                properties.put("time_ms", point.time);
                properties.put("source", point.source);
                properties.put("label", point.label);
                properties.put("platform", "android");
                if (point.gpsAltitude != null) {
                    properties.put("elevation_m", point.gpsAltitude);
                    properties.put("elevation_reference", elevationReference(point));
                }
                if (point.horizontalAccuracy != null)
                    properties.put("horizontal_accuracy_m", point.horizontalAccuracy);
                if (point.verticalAccuracy != null)
                    properties.put("vertical_accuracy_m", point.verticalAccuracy);
                if (point.pressureHpa != null) properties.put("pressure_hpa", point.pressureHpa);
                if (point.barometerRelativeAltitude != null)
                    properties.put("barometer_relative_elevation_m", point.barometerRelativeAltitude);

                JSONArray coordinates = new JSONArray().put(point.lon).put(point.lat);
                if (point.gpsAltitude != null) coordinates.put(point.gpsAltitude);
                JSONObject geometry = new JSONObject();
                geometry.put("type", "Point");
                geometry.put("coordinates", coordinates);
                documents.add(feature(geometry, properties));
            } catch (Exception ignored) {}
        }

        String localId = survey.participantId;
        String localAlias = survey.participantAlias;
        for (SurveyDatabase.Presence event : presence) {
            try {
                String tuple = event.time + "|" + event.observerParticipantId + "|" + event.participantId;
                JSONObject properties = new JSONObject();
                properties.put("schema", SCHEMA);
                properties.put("record_id", base + ":presence:" + shortHash(tuple));
                properties.put("record_type", "presence");
                properties.put("project", project);
                properties.put("group_code", event.groupCode);
                properties.put("participant_id", event.observerParticipantId);
                properties.put("participant_alias",
                        event.observerParticipantId.equals(localId) ? localAlias : "");
                properties.put("seen_participant_id", event.participantId);
                properties.put("time_iso", Instant.ofEpochMilli(event.time).toString());
                properties.put("time_ms", event.time);
                properties.put("source", "BLE");
                properties.put("label", "presence ID + timestamp");
                properties.put("platform", "android");
                documents.add(feature(null, properties));
            } catch (Exception ignored) {}
        }
        return documents;
    }

    private static JSONObject feature(JSONObject geometry, JSONObject properties) throws Exception {
        JSONObject feature = new JSONObject();
        feature.put("type", "Feature");
        feature.put("geometry", geometry == null ? JSONObject.NULL : geometry);
        feature.put("properties", properties);
        return feature;
    }

    private static boolean validCoordinate(SurveyDatabase.Point point) {
        return point != null && Double.isFinite(point.lat) && Double.isFinite(point.lon)
                && point.lat >= -90 && point.lat <= 90 && point.lon >= -180 && point.lon <= 180;
    }

    /** Sama dengan aturan ekspor: datum hanya keterangan, tidak pernah dikonversi. */
    private static String elevationReference(SurveyDatabase.Point point) {
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
        if (normalized.contains("core location") || normalized.contains("ios")
                || normalized.contains("orthometric") || normalized.contains("mean sea level")) {
            return "Core Location altitude; approximate mean sea level (orthometric)";
        }
        if ("csv".equalsIgnoreCase(source)) return "as imported; datum not recorded";
        return "Android Location altitude; WGS84 ellipsoid";
    }

    private static String shortHash(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(32);
        for (int i = 0; i < 16; i++) out.append(String.format(Locale.ROOT, "%02x", bytes[i]));
        return out.toString();
    }

    // ------------------------------------------------------------ sinkron

    /**
     * Sinkron semua proyek. Aman dipanggil dari JobService maupun aplikasi.
     * Mengembalikan {@code true} bila tidak ada kegagalan (termasuk saat
     * sinkron dimatikan atau belum dikonfigurasi).
     */
    static boolean syncAll(Context context) {
        if (!isAutoEnabled(context)) return true;
        Config config = load(context);
        if (config == null) return true;

        SurveyDatabase database = new SurveyDatabase(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            String clientId = clientId(context);
            String target = targetFingerprint(config.endpoint);
            List<SurveyDatabase.Survey> surveys = database.getSurveys();
            int total = 0;
            int projects = 0;

            for (SurveyDatabase.Survey survey : surveys) {
                long pointMark = prefs.getLong(pointKey(target, survey.id), 0);
                long presenceMark = prefs.getLong(presenceKey(target, survey.id), 0);
                long maxPoint = database.maxPointId(survey.id);
                long maxPresence = database.maxPresenceId(survey.id);
                // Database dibuat ulang: ID kembali kecil, jadi mulai dari nol.
                if (maxPoint > 0 && maxPoint < pointMark) pointMark = 0;
                if (maxPresence > 0 && maxPresence < presenceMark) presenceMark = 0;

                List<SurveyDatabase.Point> points = database.getPointsAfter(survey.id, pointMark);
                List<SurveyDatabase.Presence> presence = database.getPresenceAfter(survey.id, presenceMark);

                String signature = survey.name + "\u0000" + survey.groupCode + "\u0000"
                        + survey.participantId + "\u0000" + survey.participantAlias;
                boolean projectChanged = !signature.equals(prefs.getString(projectKey(target, survey.id), ""));
                if (!projectChanged && points.isEmpty() && presence.isEmpty()) continue;

                List<JSONObject> documents = buildDocuments(clientId, survey, survey.name,
                        points, presence, BuildConfig.VERSION_NAME, projectChanged);
                if (documents.isEmpty()) continue;
                sendBlocking(config, clientId, survey.name, survey.id, BuildConfig.VERSION_NAME, documents);

                long newPointMark = pointMark;
                for (SurveyDatabase.Point point : points) newPointMark = Math.max(newPointMark, point.id);
                long newPresenceMark = presenceMark;
                for (SurveyDatabase.Presence event : presence)
                    newPresenceMark = Math.max(newPresenceMark, event.id);
                prefs.edit()
                        .putLong(pointKey(target, survey.id), newPointMark)
                        .putLong(presenceKey(target, survey.id), newPresenceMark)
                        .putString(projectKey(target, survey.id), signature)
                        .apply();

                total += documents.size();
                projects++;
            }

            recordStatus(context, total == 0
                    ? "Sinkron otomatis aktif • semua data sudah terkirim"
                    : "Sinkron otomatis • " + total + " data dari " + projects + " proyek terkirim", true);
            return true;
        } catch (Exception e) {
            recordStatus(context, "Sinkron otomatis gagal: " + safeMessage(e) + ". Akan dicoba lagi.", false);
            return false;
        } finally {
            database.close();
        }
    }

    private static String targetFingerprint(URL endpoint) {
        try {
            return shortHash(endpoint.toString()).substring(0, 16);
        } catch (Exception e) {
            return "default";
        }
    }

    private static String pointKey(String target, long surveyId) {
        return "wp_" + target + "_" + surveyId;
    }

    private static String presenceKey(String target, long surveyId) {
        return "wr_" + target + "_" + surveyId;
    }

    private static String projectKey(String target, long surveyId) {
        return "pj_" + target + "_" + surveyId;
    }

    static int sendBlocking(Config config, String clientId, String project, long surveyId,
                            String appVersion, List<JSONObject> documents) throws Exception {
        int total = documents.size();
        int sent = 0;
        for (int start = 0; start < total; start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE, total);
            JSONArray chunk = new JSONArray();
            for (int i = start; i < end; i++) chunk.put(documents.get(i));

            JSONObject body = new JSONObject();
            body.put("schema", SCHEMA);
            body.put("client_id", clientId);
            body.put("platform", "android");
            body.put("app_version", appVersion);
            body.put("project", project);
            body.put("survey_id", surveyId);
            body.put("sent_at", Instant.ofEpochMilli(System.currentTimeMillis()).toString());
            body.put("features", chunk);

            post(config, body.toString());
            sent = end;
        }
        return sent;
    }

    private static void post(Config config, String body) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) config.endpoint.openConnection();
        try {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + config.token);
            connection.setRequestProperty("User-Agent", "LerengOffline-Android");

            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = read(stream);
            if (code < 200 || code >= 300) {
                throw new IOException("Server menolak (" + code + ")" + errorDetail(response));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static String errorDetail(String response) {
        if (response == null || response.isEmpty()) return "";
        try {
            JSONObject json = new JSONObject(response);
            String error = json.optString("error", "");
            String detail = json.optString("detail", "");
            String text = detail.isEmpty() ? error : error + ": " + detail;
            if (!text.isEmpty()) return " — " + truncate(text, 200);
        } catch (Exception ignored) {}
        return " — " + truncate(response, 200);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) return e.getClass().getSimpleName();
        return message;
    }
}
