package id.bagas.lerengoffline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MongoSyncTest {
    private SurveyDatabase.Survey survey() {
        return new SurveyDatabase.Survey(7, "Gunung Uji", "ROMBONGAN1", "P001", "Bagas");
    }

    private SurveyDatabase.Point point(long id, boolean waypoint) {
        SurveyDatabase.Point point = new SurveyDatabase.Point(7, 1760000000000L + id,
                -7.7828, 110.3695, 1200.5, 8f, 12f, 900f, 15.5,
                "fused", "uji", "P001", "Bagas", "ROMBONGAN1", waypoint);
        point.id = id;
        return point;
    }

    @Test public void endpointBaseMenjadiApiIngest() throws Exception {
        URL url = MongoSync.normalizeEndpoint("https://proyek.vercel.app");
        assertEquals("https://proyek.vercel.app/api/ingest", url.toString());
    }

    @Test public void endpointLengkapDipertahankan() throws Exception {
        URL url = MongoSync.normalizeEndpoint(" https://proyek.vercel.app/api/ingest/ ");
        assertEquals("https://proyek.vercel.app/api/ingest", url.toString());
    }

    @Test public void endpointHttpDitolak() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MongoSync.normalizeEndpoint("http://proyek.vercel.app/api/ingest"));
        assertTrue(error.getMessage().contains("HTTPS"));
    }

    @Test public void endpointDenganKredensialDitolak() {
        assertThrows(IllegalArgumentException.class,
                () -> MongoSync.normalizeEndpoint("https://user:pass@proyek.vercel.app/api/ingest"));
    }

    @Test public void dokumenBerisiSemuaJenisDanIdStabil() throws Exception {
        List<SurveyDatabase.Point> points = new ArrayList<>();
        points.add(point(1, false));
        points.add(point(2, true));
        List<SurveyDatabase.Presence> presence = new ArrayList<>();
        presence.add(new SurveyDatabase.Presence(1760000005000L, "P001", "P002", "ROMBONGAN1"));

        List<JSONObject> first = MongoSync.buildDocuments(
                "client-a", survey(), "Gunung Uji", points, presence, "2.5.4");
        List<JSONObject> second = MongoSync.buildDocuments(
                "client-a", survey(), "Gunung Uji", points, presence, "2.5.4");

        assertEquals(4, first.size());
        assertEquals(4, second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(
                    first.get(i).getJSONObject("properties").getString("record_id"),
                    second.get(i).getJSONObject("properties").getString("record_id"));
        }

        assertEquals("project", first.get(0).getJSONObject("properties").getString("record_type"));
        assertTrue(first.get(0).isNull("geometry"));
        assertEquals("track", first.get(1).getJSONObject("properties").getString("record_type"));
        assertEquals("waypoint", first.get(2).getJSONObject("properties").getString("record_type"));
        assertEquals("presence", first.get(3).getJSONObject("properties").getString("record_type"));
        assertTrue(first.get(3).isNull("geometry"));

        JSONObject geometry = first.get(1).getJSONObject("geometry");
        assertEquals("Point", geometry.getString("type"));
        assertEquals(3, geometry.getJSONArray("coordinates").length());
        assertTrue(first.get(1).getJSONObject("properties")
                .getString("elevation_reference").contains("WGS84"));
    }

    @Test public void idTrackDanWaypointBerbeda() throws Exception {
        List<SurveyDatabase.Point> points = new ArrayList<>();
        points.add(point(1, false));
        points.add(point(1, true));
        List<JSONObject> documents = MongoSync.buildDocuments(
                "client-a", survey(), "Gunung Uji", points, new ArrayList<>(), "2.5.4");
        assertNotEquals(
                documents.get(1).getJSONObject("properties").getString("record_id"),
                documents.get(2).getJSONObject("properties").getString("record_id"));
    }
}
