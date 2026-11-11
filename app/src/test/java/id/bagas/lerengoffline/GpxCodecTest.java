package id.bagas.lerengoffline;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class GpxCodecTest {
    private static final long T = Instant.parse("2026-11-08T01:02:03Z").toEpochMilli();

    @Test public void buildWritesWaypointsTrackAndEscapedLabels() {
        SurveyDatabase.Point track = point(T, -6.7000001, 106.9000001, 1200.5, false, "");
        SurveyDatabase.Point waypoint = point(T + 60_000, -6.701, 106.901, 1300.0, true,
                "Puncak <X> & \"Y\"");

        String xml = GpxCodec.build("Gede & Co", "Satu jalur", GpxCodec.DATUM_ELLIPSOID,
                Arrays.asList(waypoint, track), T + 1_000);

        assertEquals("one trkpt", 1, count(xml, "<trkpt "));
        assertEquals("one wpt", 1, count(xml, "<wpt "));
        assertEquals("both points carry ele", 2, count(xml, "<ele>"));
        assertTrue(xml.contains("lat=\"-6.7000001\" lon=\"106.9000001\""));
        assertTrue(xml.contains("<name>Gede &amp; Co</name>"));
        assertTrue(xml.contains("Puncak &lt;X&gt; &amp; &quot;Y&quot;"));
        assertTrue(xml.contains("<lereng:elevationReference>WGS84 ellipsoid</lereng:elevationReference>"));
        assertTrue(xml.endsWith("</gpx>\n"));
    }

    @Test public void withoutElevationOmitsEveryEleField() {
        String xml = GpxCodec.build("Jalur", null, GpxCodec.DATUM_NONE,
                Arrays.asList(point(T, -6.2, 106.8, 900.0, false, "")), T);
        assertFalse(xml.contains("<ele>"));
        assertTrue(xml.contains("<lereng:elevationReference>not recorded</lereng:elevationReference>"));
    }

    @Test public void parseRoundTripKeepsCoordinatesElevationTimeAndWaypointFlag() throws Exception {
        SurveyDatabase.Point track = point(T, -6.7000001, 106.9000001, 1200.5, false, "");
        SurveyDatabase.Point waypoint = point(T + 60_000, -6.701, 106.901, 1300.0, true, "Puncak");
        String xml = GpxCodec.build("Jalur", "desc", GpxCodec.DATUM_ELLIPSOID,
                Arrays.asList(track, waypoint), T);

        GpxCodec.Result result = GpxCodec.parse(stream(xml));

        assertEquals("two points, no skipped rows", 2, result.points.size());
        assertEquals(0, result.skipped);
        assertEquals("track first, then waypoint", 1, result.waypointCount());
        GpxCodec.ParsedPoint parsedTrack = result.points.get(0);
        assertFalse(parsedTrack.waypoint);
        assertEquals(-6.7000001, parsedTrack.latitude, 1e-9);
        assertEquals(106.9000001, parsedTrack.longitude, 1e-9);
        assertNotNull(parsedTrack.elevation);
        assertEquals(1200.5, parsedTrack.elevation, 0.001);
        assertEquals(Long.valueOf(T), parsedTrack.time);
        GpxCodec.ParsedPoint parsedWaypoint = result.points.get(1);
        assertTrue(parsedWaypoint.waypoint);
        assertEquals("Puncak", parsedWaypoint.name);
        assertTrue(result.hasElevation());
        assertTrue(result.hasTime());
        assertEquals("Lereng Offline", result.creator);
        assertEquals(GpxCodec.DATUM_ELLIPSOID, result.declaredReference);
    }

    @Test public void parseSkipsInvalidCoordinatesAndToleratesBrokenElevation() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<gpx version=\"1.1\" creator=\"test\" xmlns=\"http://www.topografix.com/GPX/1/1\">"
                + "<trk><trkseg>"
                + "<trkpt lat=\"999\" lon=\"0\"><ele>10</ele></trkpt>"
                + "<trkpt lat=\"-6.2\" lon=\"106.8\"><ele>not-a-number</ele>"
                + "<time>2026-11-08T00:00:00Z</time></trkpt>"
                + "<trkpt lat=\"-6.3\" lon=\"106.9\"><ele>950.25</ele></trkpt>"
                + "</trkseg></trk></gpx>";

        GpxCodec.Result result = GpxCodec.parse(stream(xml));

        assertEquals("two valid points", 2, result.points.size());
        assertEquals("one invalid coordinate skipped", 1, result.skipped);
        assertNull(result.points.get(0).elevation);
        assertNotNull(result.points.get(0).time);
        assertEquals(950.25, result.points.get(1).elevation, 0.001);
        assertNull(result.points.get(1).time);
    }

    @Test public void parseRejectsDoctypeToBlockExternalEntities() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE gpx [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>"
                + "<trkpt lat=\"0\" lon=\"0\"><name>&x;</name></trkpt>"
                + "</trkseg></trk></gpx>";
        try {
            GpxCodec.parse(stream(xml));
            fail("DOCTYPE must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("doctype"));
        } catch (Exception other) {
            fail("unexpected exception: " + other);
        }
    }

    @Test public void parseRejectsNonGpxXml() throws Exception {
        try {
            GpxCodec.parse(stream("<?xml version=\"1.0\"?><kml><Placemark/></kml>"));
            fail("non-GPX root must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bukan GPX"));
        }
    }

    @Test public void timeParserAcceptsUtcOffsetLocalAndEpoch() {
        assertEquals(Long.valueOf(T), GpxCodec.parseTime("2026-11-08T01:02:03Z"));
        assertEquals(Long.valueOf(T), GpxCodec.parseTime("2026-11-08T08:02:03+07:00"));
        assertEquals(Long.valueOf(123456789L), GpxCodec.parseTime("123456789"));
        assertNull(GpxCodec.parseTime(""));
        assertNull(GpxCodec.parseTime("kemarin"));
    }

    @Test public void timeWithoutOffsetIsReadAsUtcNotDeviceZone() {
        // GPX 1.1: <time> tanpa offset tetap UTC. Kalau dibaca sebagai zona
        // perangkat, seluruh jalur bergeser (7 jam di WIB) dan impor ulang
        // terlihat sebagai titik baru.
        assertEquals(Long.valueOf(T), GpxCodec.parseTime("2026-11-08T01:02:03"));
    }

    @Test public void parseAcceptsRoutePoints() throws Exception {
        String xml = "<?xml version=\"1.0\"?><gpx version=\"1.1\" creator=\"uji\">"
                + "<rte><rtept lat=\"-7.1\" lon=\"110.1\"><ele>900</ele><name>A</name></rtept>"
                + "<rtept lat=\"-7.2\" lon=\"110.2\"><ele>950</ele><name>B</name></rtept></rte></gpx>";
        GpxCodec.Result result = GpxCodec.parse(stream(xml));
        assertEquals(2, result.points.size());
        assertFalse("rtept adalah titik jalur, bukan waypoint", result.points.get(0).waypoint);
        assertEquals(-7.2, result.points.get(1).latitude, 1e-9);
    }

    private static SurveyDatabase.Point point(long time, double lat, double lon, Double elevation,
                                              boolean waypoint, String label) {
        return new SurveyDatabase.Point(1, time, lat, lon, elevation, 5f, 8f, null, null,
                "Android Location; WGS84 ellipsoid", label, "ABCD", "Alias", "GROUP1", waypoint);
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static int count(String text, String needle) {
        int found = 0, from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) { found++; from += needle.length(); }
        return found;
    }
}
