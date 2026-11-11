package id.bagas.lerengoffline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SurveyDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "lereng_offline.db";
    private static final int DB_VERSION = 4;

    public static final class Survey {
        public final long id;
        public final String name;
        public final String groupCode;
        public final String participantId;
        public final String participantAlias;
        public Survey(long id, String name, String groupCode, String participantId, String participantAlias) {
            this.id = id;
            this.name = name;
            this.groupCode = safe(groupCode);
            this.participantId = safe(participantId);
            this.participantAlias = safe(participantAlias);
        }
        @Override public String toString() { return name; }
    }

    public static final class Point {
        public long id;
        public long surveyId;
        public long time;
        public double lat;
        public double lon;
        public Double gpsAltitude;
        public Float horizontalAccuracy;
        public Float verticalAccuracy;
        public Float pressureHpa;
        public Double barometerRelativeAltitude;
        public String source;
        public String label;
        public String participantId;
        public String participantAlias;
        public String groupCode;
        public boolean waypoint;

        public Point(long surveyId, long time, double lat, double lon, Double gpsAltitude,
                     Float horizontalAccuracy, Float verticalAccuracy, Float pressureHpa,
                     Double barometerRelativeAltitude, String source, String label) {
            this(surveyId, time, lat, lon, gpsAltitude, horizontalAccuracy, verticalAccuracy,
                    pressureHpa, barometerRelativeAltitude, source, label, "", "", "", false);
        }

        public Point(long surveyId, long time, double lat, double lon, Double gpsAltitude,
                     Float horizontalAccuracy, Float verticalAccuracy, Float pressureHpa,
                     Double barometerRelativeAltitude, String source, String label,
                     String participantId, String participantAlias, String groupCode, boolean waypoint) {
            this.surveyId = surveyId;
            this.time = time;
            this.lat = lat;
            this.lon = lon;
            this.gpsAltitude = gpsAltitude;
            this.horizontalAccuracy = horizontalAccuracy;
            this.verticalAccuracy = verticalAccuracy;
            this.pressureHpa = pressureHpa;
            this.barometerRelativeAltitude = barometerRelativeAltitude;
            this.source = source == null ? "GPS" : source;
            this.label = safe(label);
            this.participantId = safe(participantId);
            this.participantAlias = safe(participantAlias);
            this.groupCode = safe(groupCode);
            this.waypoint = waypoint;
        }
    }

    public static final class Presence {
        public final long time;
        public final String observerParticipantId;
        public final String participantId;
        public final String groupCode;
        public Presence(long time, String observerParticipantId, String participantId, String groupCode) {
            this.time = time;
            this.observerParticipantId = safe(observerParticipantId);
            this.participantId = safe(participantId);
            this.groupCode = safe(groupCode);
        }
    }

    public static final class ElevationDataset {
        public final long id;
        public final String name;
        public final String verticalDatum;
        public final int sampleCount;
        public final long importedAt;
        ElevationDataset(long id, String name, String verticalDatum, int sampleCount, long importedAt) {
            this.id = id;
            this.name = safe(name);
            this.verticalDatum = safe(verticalDatum);
            this.sampleCount = sampleCount;
            this.importedAt = importedAt;
        }
        @Override public String toString() { return name + " • " + sampleCount + " sampel • datum " + verticalDatum; }
    }

    public SurveyDatabase(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createElevationTables(db);
        db.execSQL("CREATE TABLE surveys (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL, group_code TEXT, participant_id TEXT, participant_alias TEXT, elevation_dataset_id INTEGER)");
        db.execSQL("CREATE TABLE points (_id INTEGER PRIMARY KEY AUTOINCREMENT, survey_id INTEGER NOT NULL, time_ms INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, gps_alt REAL, h_acc REAL, v_acc REAL, pressure_hpa REAL, baro_rel_alt REAL, source TEXT NOT NULL, label TEXT, participant_id TEXT, participant_alias TEXT, group_code TEXT, is_waypoint INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(survey_id) REFERENCES surveys(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX points_by_survey_time ON points(survey_id, time_ms)");
        createPresenceTable(db);
        createReferenceTables(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE surveys ADD COLUMN group_code TEXT");
            db.execSQL("ALTER TABLE surveys ADD COLUMN participant_id TEXT");
            db.execSQL("ALTER TABLE surveys ADD COLUMN participant_alias TEXT");
            db.execSQL("ALTER TABLE points ADD COLUMN participant_id TEXT");
            db.execSQL("ALTER TABLE points ADD COLUMN participant_alias TEXT");
            db.execSQL("ALTER TABLE points ADD COLUMN group_code TEXT");
            db.execSQL("ALTER TABLE points ADD COLUMN is_waypoint INTEGER NOT NULL DEFAULT 0");
            createPresenceTable(db);
        }
        if (oldVersion < 3) {
            createElevationTables(db);
            db.execSQL("ALTER TABLE surveys ADD COLUMN elevation_dataset_id INTEGER");
        }
        if (oldVersion < 4) createReferenceTables(db);
    }

    private void createElevationTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS elevation_datasets (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, vertical_datum TEXT NOT NULL, imported_at INTEGER NOT NULL, sample_count INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS elevation_samples (_id INTEGER PRIMARY KEY AUTOINCREMENT, dataset_id INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, elevation_m REAL NOT NULL, FOREIGN KEY(dataset_id) REFERENCES elevation_datasets(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS elevation_samples_by_dataset ON elevation_samples(dataset_id)");
    }

    private void createPresenceTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS presence (_id INTEGER PRIMARY KEY AUTOINCREMENT, survey_id INTEGER NOT NULL, time_ms INTEGER NOT NULL, observer_participant_id TEXT NOT NULL, seen_participant_id TEXT NOT NULL, group_code TEXT NOT NULL, bucket_30s INTEGER NOT NULL, FOREIGN KEY(survey_id) REFERENCES surveys(_id) ON DELETE CASCADE, UNIQUE(survey_id, observer_participant_id, seen_participant_id, bucket_30s))");
        db.execSQL("CREATE INDEX IF NOT EXISTS presence_by_survey_time ON presence(survey_id, time_ms)");
    }

    /**
     * Jalur pembanding (mis. hasil ekspor GPX dari aplikasi lain) disimpan terpisah supaya tidak
     * pernah tercampur dengan titik yang direkam aplikasi ini.
     */
    private void createReferenceTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reference_tracks (_id INTEGER PRIMARY KEY AUTOINCREMENT, survey_id INTEGER NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL, imported_at INTEGER NOT NULL, point_count INTEGER NOT NULL, FOREIGN KEY(survey_id) REFERENCES surveys(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE IF NOT EXISTS reference_points (_id INTEGER PRIMARY KEY AUTOINCREMENT, track_id INTEGER NOT NULL, seq INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, time_ms INTEGER, FOREIGN KEY(track_id) REFERENCES reference_tracks(_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS reference_points_by_track ON reference_points(track_id, seq)");
        db.execSQL("CREATE INDEX IF NOT EXISTS reference_tracks_by_survey ON reference_tracks(survey_id)");
    }

    public static final class ReferencePoint {
        public final double lat;
        public final double lon;
        public final Double elevation;
        public final Long time;
        public ReferencePoint(double lat, double lon, Double elevation, Long time) {
            this.lat = lat; this.lon = lon; this.elevation = elevation; this.time = time;
        }
    }

    public static final class ReferenceTrack {
        public final long id;
        public final String name;
        public final String source;
        public final long importedAt;
        public final int pointCount;
        ReferenceTrack(long id, String name, String source, long importedAt, int pointCount) {
            this.id = id; this.name = name; this.source = source;
            this.importedAt = importedAt; this.pointCount = pointCount;
        }
    }

    /** One reference track per project: importing again replaces the previous comparison file. */
    public synchronized long replaceReferenceTrack(long surveyId, String name, String source,
                                                   List<ReferencePoint> points) {
        if (points == null || points.isEmpty()) return -1;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("reference_tracks", "survey_id=?", new String[]{String.valueOf(surveyId)});
            ContentValues track = new ContentValues();
            track.put("survey_id", surveyId);
            track.put("name", safe(name).trim().isEmpty() ? "Jalur pembanding" : safe(name).trim());
            track.put("source", safe(source));
            track.put("imported_at", System.currentTimeMillis());
            track.put("point_count", points.size());
            long trackId = db.insertOrThrow("reference_tracks", null, track);
            android.database.sqlite.SQLiteStatement insert = db.compileStatement(
                    "INSERT INTO reference_points (track_id, seq, lat, lon, ele, time_ms) VALUES (?, ?, ?, ?, ?, ?)");
            try {
                int seq = 0;
                for (ReferencePoint point : points) {
                    if (point == null) continue;
                    insert.bindLong(1, trackId);
                    insert.bindLong(2, seq++);
                    insert.bindDouble(3, point.lat);
                    insert.bindDouble(4, point.lon);
                    if (point.elevation == null) insert.bindNull(5);
                    else insert.bindDouble(5, point.elevation);
                    if (point.time == null) insert.bindNull(6);
                    else insert.bindLong(6, point.time);
                    insert.executeInsert();
                }
            } finally {
                insert.close();
            }
            db.setTransactionSuccessful();
            return trackId;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized ReferenceTrack getReferenceTrack(long surveyId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT _id, name, source, imported_at, point_count FROM reference_tracks WHERE survey_id=? ORDER BY _id DESC LIMIT 1",
                new String[]{String.valueOf(surveyId)})) {
            return c.moveToFirst()
                    ? new ReferenceTrack(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4))
                    : null;
        }
    }

    /** Coordinates in file order; only lat/lon, because the map draws geometry, not elevation. */
    public synchronized List<double[]> getReferenceCoordinates(long surveyId) {
        List<double[]> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT p.lat, p.lon FROM reference_points p JOIN reference_tracks t ON t._id = p.track_id "
                        + "WHERE t.survey_id=? ORDER BY p.track_id DESC, p.seq ASC",
                new String[]{String.valueOf(surveyId)})) {
            while (c.moveToNext()) result.add(new double[]{c.getDouble(0), c.getDouble(1)});
        }
        return result;
    }

    public synchronized int deleteReferenceTrack(long surveyId) {
        return getWritableDatabase().delete("reference_tracks", "survey_id=?",
                new String[]{String.valueOf(surveyId)});
    }

    public synchronized long ensureDefaultSurvey() {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT _id FROM surveys ORDER BY _id LIMIT 1", null)) {
            if (c.moveToFirst()) return c.getLong(0);
        }
        ContentValues values = new ContentValues();
        values.put("name", "Pendakian saya");
        values.put("created_at", System.currentTimeMillis());
        return db.insertOrThrow("surveys", null, values);
    }

    public synchronized long createSurvey(String name) {
        ContentValues values = new ContentValues();
        values.put("name", name.trim());
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("surveys", null, values);
    }

    /** Renames a project; blank names are rejected so the header never shows an empty title. */
    public synchronized boolean renameSurvey(long surveyId, String name) {
        String clean = safe(name).trim();
        if (clean.isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("name", clean);
        return getWritableDatabase().update("surveys", values, "_id=?",
                new String[]{String.valueOf(surveyId)}) > 0;
    }

    public synchronized List<Survey> getSurveys() {
        List<Survey> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT _id, name, group_code, participant_id, participant_alias FROM surveys ORDER BY created_at DESC", null)) {
            while (c.moveToNext()) result.add(new Survey(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)));
        }
        return result;
    }

    public synchronized Survey getSurvey(long surveyId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT _id, name, group_code, participant_id, participant_alias FROM surveys WHERE _id=?", new String[]{String.valueOf(surveyId)})) {
            return c.moveToFirst() ? new Survey(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)) : null;
        }
    }

    public synchronized String ensureParticipantId(long surveyId, String candidateId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("participant_id", candidateId);
        db.update("surveys", v, "_id=? AND (participant_id IS NULL OR participant_id='')", new String[]{String.valueOf(surveyId)});
        Survey s = getSurvey(surveyId);
        return s == null ? candidateId : (s.participantId.isEmpty() ? candidateId : s.participantId);
    }

    public synchronized void setGroupProfile(long surveyId, String groupCode, String participantId, String participantAlias) {
        ContentValues v = new ContentValues();
        v.put("group_code", safe(groupCode).trim().toUpperCase(Locale.ROOT));
        v.put("participant_id", safe(participantId));
        v.put("participant_alias", safe(participantAlias).trim());
        getWritableDatabase().update("surveys", v, "_id=?", new String[]{String.valueOf(surveyId)});
        ContentValues codeOnly = new ContentValues();
        codeOnly.put("group_code", safe(groupCode).trim().toUpperCase(Locale.ROOT));
        getWritableDatabase().update("points", codeOnly, "survey_id=?", new String[]{String.valueOf(surveyId)});
        getWritableDatabase().update("presence", codeOnly, "survey_id=?", new String[]{String.valueOf(surveyId)});
        ContentValues legacy = new ContentValues();
        legacy.put("participant_id", safe(participantId));
        legacy.put("participant_alias", safe(participantAlias).trim());
        getWritableDatabase().update("points", legacy, "survey_id=? AND (participant_id IS NULL OR participant_id='')", new String[]{String.valueOf(surveyId)});
    }

    public synchronized long getSurveyElevationDatasetId(long surveyId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(elevation_dataset_id,-1) FROM surveys WHERE _id=?", new String[]{String.valueOf(surveyId)})) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    public synchronized long importElevationDataset(String name, String verticalDatum,
            List<LocalElevationEngine.Sample> samples) {
        if (samples == null || samples.size() < 4 || samples.size() > ElevationCsvImporter.MAX_SAMPLES)
            throw new IllegalArgumentException("Jumlah sampel dataset tidak valid.");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        long datasetId;
        try {
            ContentValues metadata = new ContentValues();
            metadata.put("name", safe(name).trim());
            metadata.put("vertical_datum", safe(verticalDatum).trim());
            metadata.put("imported_at", System.currentTimeMillis());
            metadata.put("sample_count", samples.size());
            datasetId = db.insertOrThrow("elevation_datasets", null, metadata);
            try (SQLiteStatement insert = db.compileStatement("INSERT INTO elevation_samples(dataset_id,lat,lon,elevation_m) VALUES(?,?,?,?)")) {
                for (LocalElevationEngine.Sample sample : samples) {
                    insert.clearBindings();
                    insert.bindLong(1, datasetId);
                    insert.bindDouble(2, sample.latitude);
                    insert.bindDouble(3, sample.longitude);
                    insert.bindDouble(4, sample.elevationMeters);
                    insert.executeInsert();
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return datasetId;
    }

    public synchronized List<ElevationDataset> getElevationDatasets() {
        List<ElevationDataset> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT _id,name,vertical_datum,sample_count,imported_at FROM elevation_datasets ORDER BY imported_at DESC", null)) {
            while (c.moveToNext()) result.add(new ElevationDataset(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4)));
        }
        return result;
    }

    public synchronized ElevationDataset getElevationDataset(long datasetId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT _id,name,vertical_datum,sample_count,imported_at FROM elevation_datasets WHERE _id=?", new String[]{String.valueOf(datasetId)})) {
            return c.moveToFirst() ? new ElevationDataset(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4)) : null;
        }
    }

    public synchronized List<LocalElevationEngine.Sample> getElevationSamples(long datasetId) {
        List<LocalElevationEngine.Sample> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT lat,lon,elevation_m FROM elevation_samples WHERE dataset_id=? ORDER BY _id", new String[]{String.valueOf(datasetId)})) {
            while (c.moveToNext()) result.add(new LocalElevationEngine.Sample(c.getDouble(0), c.getDouble(1), c.getDouble(2)));
        }
        return result;
    }

    public synchronized void setSurveyElevationDataset(long surveyId, long datasetId) {
        ContentValues values = new ContentValues();
        if (datasetId < 0) values.putNull("elevation_dataset_id"); else values.put("elevation_dataset_id", datasetId);
        getWritableDatabase().update("surveys", values, "_id=?", new String[]{String.valueOf(surveyId)});
    }

    public synchronized int deleteElevationDataset(long datasetId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues clear = new ContentValues();
            clear.putNull("elevation_dataset_id");
            db.update("surveys", clear, "elevation_dataset_id=?", new String[]{String.valueOf(datasetId)});
            db.delete("elevation_samples", "dataset_id=?", new String[]{String.valueOf(datasetId)});
            int deleted = db.delete("elevation_datasets", "_id=?", new String[]{String.valueOf(datasetId)});
            db.setTransactionSuccessful();
            return deleted;
        } finally { db.endTransaction(); }
    }

    public synchronized boolean addPoint(Point point) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT _id FROM points WHERE survey_id=? AND participant_id=? AND time_ms=? AND lat=? AND lon=? AND is_waypoint=? LIMIT 1",
                new String[]{String.valueOf(point.surveyId), point.participantId, String.valueOf(point.time),
                        String.valueOf(point.lat), String.valueOf(point.lon), point.waypoint ? "1" : "0"})) {
            if (c.moveToFirst()) return false;
        }
        ContentValues v = new ContentValues();
        v.put("survey_id", point.surveyId);
        v.put("time_ms", point.time);
        v.put("lat", point.lat);
        v.put("lon", point.lon);
        if (point.gpsAltitude != null) v.put("gps_alt", point.gpsAltitude);
        if (point.horizontalAccuracy != null) v.put("h_acc", point.horizontalAccuracy);
        if (point.verticalAccuracy != null) v.put("v_acc", point.verticalAccuracy);
        if (point.pressureHpa != null) v.put("pressure_hpa", point.pressureHpa);
        if (point.barometerRelativeAltitude != null) v.put("baro_rel_alt", point.barometerRelativeAltitude);
        v.put("source", point.source);
        v.put("label", point.label);
        v.put("participant_id", point.participantId);
        v.put("participant_alias", point.participantAlias);
        v.put("group_code", point.groupCode);
        v.put("is_waypoint", point.waypoint ? 1 : 0);
        long id = getWritableDatabase().insertWithOnConflict("points", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id == -1) return false;
        point.id = id;
        return true;
    }

    public synchronized List<Point> getPoints(long surveyId) {
        List<Point> result = new ArrayList<>();
        String sql = "SELECT p._id,p.survey_id,p.time_ms,p.lat,p.lon,p.gps_alt,p.h_acc,p.v_acc,p.pressure_hpa,p.baro_rel_alt,p.source,p.label,COALESCE(NULLIF(p.participant_id,''),s.participant_id,'LEGACY'),COALESCE(NULLIF(p.participant_alias,''),s.participant_alias,'Peserta lama'),COALESCE(NULLIF(p.group_code,''),s.group_code,''),p.is_waypoint FROM points p LEFT JOIN surveys s ON s._id=p.survey_id WHERE p.survey_id=? ORDER BY p.time_ms";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(surveyId)})) {
            while (c.moveToNext()) {
                Double alt = c.isNull(5) ? null : c.getDouble(5);
                Float hacc = c.isNull(6) ? null : c.getFloat(6);
                Float vacc = c.isNull(7) ? null : c.getFloat(7);
                Float pressure = c.isNull(8) ? null : c.getFloat(8);
                Double baro = c.isNull(9) ? null : c.getDouble(9);
                Point p = new Point(c.getLong(1), c.getLong(2), c.getDouble(3), c.getDouble(4), alt,
                        hacc, vacc, pressure, baro, c.getString(10), c.getString(11),
                        c.getString(12), c.getString(13), c.getString(14), c.getInt(15) != 0);
                p.id = c.getLong(0);
                result.add(p);
            }
        }
        return result;
    }

    public synchronized boolean recordPresence(long surveyId, String groupCode, String observerId, String participantId, long timeMs) {
        if (safe(groupCode).isEmpty() || safe(observerId).isEmpty() || safe(participantId).isEmpty()) return false;
        ContentValues v = new ContentValues();
        v.put("survey_id", surveyId);
        v.put("time_ms", timeMs);
        v.put("observer_participant_id", observerId);
        v.put("seen_participant_id", participantId);
        v.put("group_code", groupCode);
        v.put("bucket_30s", timeMs / 30000L);
        return getWritableDatabase().insertWithOnConflict("presence", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public synchronized List<Presence> getPresence(long surveyId) {
        List<Presence> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT time_ms,observer_participant_id,seen_participant_id,group_code FROM presence WHERE survey_id=? ORDER BY time_ms", new String[]{String.valueOf(surveyId)})) {
            while (c.moveToNext()) result.add(new Presence(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)));
        }
        return result;
    }

    public synchronized int countWaypoints(long surveyId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM points WHERE survey_id=? AND is_waypoint=1", new String[]{String.valueOf(surveyId)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized int countParticipants(long surveyId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(DISTINCT participant_id) FROM points WHERE survey_id=? AND is_waypoint=0 AND participant_id IS NOT NULL AND participant_id!=''", new String[]{String.valueOf(surveyId)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized int deleteSurvey(long surveyId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("presence", "survey_id=?", new String[]{String.valueOf(surveyId)});
            db.delete("points", "survey_id=?", new String[]{String.valueOf(surveyId)});
            int n = db.delete("surveys", "_id=?", new String[]{String.valueOf(surveyId)});
            db.setTransactionSuccessful();
            return n;
        } finally { db.endTransaction(); }
    }

    /** Deletes a single stored point; profile/terrain are recomputed by the caller. */
    public synchronized boolean deletePoint(long pointId) {
        return getWritableDatabase().delete("points", "_id=?",
                new String[]{String.valueOf(pointId)}) > 0;
    }

    public synchronized int countPoints(long surveyId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM points WHERE survey_id=?", new String[]{String.valueOf(surveyId)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
