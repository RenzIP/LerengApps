package id.bagas.lerengoffline;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public final class RouteProfileEngineTest {
    private SurveyDatabase.Point point(String id, long time, double lat, double lon, double altitude, boolean waypoint, String label) {
        return new SurveyDatabase.Point(1, time, lat, lon, altitude, 5f, 4f, null, null,
                "GPS", label, id, id, "GEDE7K4PM2", waypoint);
    }

    private SurveyDatabase.Point point(String id, long time, double lat, double lon, double altitude,
                                       boolean waypoint, String label, String source) {
        return new SurveyDatabase.Point(1, time, lat, lon, altitude, 5f, 4f, null, null,
                source, label, id, id, "GEDE7K4PM2", waypoint);
    }

    @Test public void combinesNearbyParticipantsIntoOneRouteProfile() {
        List<SurveyDatabase.Point> points = new ArrayList<>();
        points.add(point("A1B2C3D4", 10_000, -6.75000, 107.64000, 100, false, ""));
        points.add(point("A1B2C3D4", 20_000, -6.75000, 107.64100, 110, false, ""));
        points.add(point("E5F6A7B8", 20_000, -6.75001, 107.64001, 102, false, ""));
        points.add(point("E5F6A7B8", 30_000, -6.75001, 107.64101, 112, false, ""));

        RouteProfileEngine.Profile profile = RouteProfileEngine.build(points);

        assertEquals(2, profile.stations.size());
        assertEquals(2, profile.participantCount);
        assertEquals(2, profile.stationsWithMultipleContributors);
        assertEquals(101.0, profile.stations.get(0).elevationMeters, 0.01);
        assertEquals(111.0, profile.stations.get(1).elevationMeters, 0.01);
        assertEquals(10.0, profile.ascentMeters, 0.1);
        assertTrue(profile.distanceMeters > 100 && profile.distanceMeters < 120);
    }

    @Test public void ignoresParticipantsOutsideMatchingRadius() {
        List<SurveyDatabase.Point> points = new ArrayList<>();
        points.add(point("A1B2C3D4", 10_000, -6.75000, 107.64000, 100, false, ""));
        points.add(point("A1B2C3D4", 20_000, -6.75000, 107.64100, 110, false, ""));
        points.add(point("E5F6A7B8", 10_000, -6.75000, 107.64200, 300, false, ""));
        points.add(point("E5F6A7B8", 20_000, -6.75000, 107.64300, 310, false, ""));

        RouteProfileEngine.Profile profile = RouteProfileEngine.build(points);

        assertEquals(2, profile.participantCount);
        assertEquals(0, profile.stationsWithMultipleContributors);
        assertEquals(100.0, profile.stations.get(0).elevationMeters, 0.01);
    }

    @Test public void projectsWaypointsOntoRouteWithoutTreatingThemAsTrackStations() {
        List<SurveyDatabase.Point> points = new ArrayList<>();
        points.add(point("A1B2C3D4", 10_000, -6.75000, 107.64000, 100, false, ""));
        points.add(point("A1B2C3D4", 20_000, -6.75000, 107.64100, 110, false, ""));
        points.add(point("A1B2C3D4", 15_000, -6.75000, 107.64050, 105, true, "Pos 1"));

        RouteProfileEngine.Profile profile = RouteProfileEngine.build(points);

        assertEquals(2, profile.stations.size());
        assertEquals(1, profile.waypoints.size());
        assertEquals("Pos 1", profile.waypoints.get(0).label);
        assertTrue(profile.waypoints.get(0).distanceMeters > 40 && profile.waypoints.get(0).distanceMeters < 70);
    }

    @Test public void blePayloadMatchesOnlyTheOutingAndUsesPseudonymousId() {
        byte[] payload = BleGroupCodec.createPayload("GEDE7K4PM2", "A1B2C3D4");
        assertNotNull(payload);
        assertEquals(10, payload.length);
        assertEquals("A1B2C3D4", BleGroupCodec.parsePeerId(payload, "GEDE7K4PM2"));
        assertNull(BleGroupCodec.parsePeerId(payload, "OTHER9CODE"));
        assertNull(BleGroupCodec.createPayload("SHORT", "A1B2C3D4"));
    }

    @Test public void generatedIdsAreOutingScopedHexAndGroupCodesAreRandomFormat() {
        String participant = BleGroupCodec.newParticipantId();
        String group = BleGroupCodec.newGroupCode();
        assertTrue(BleGroupCodec.isValidParticipantId(participant));
        assertTrue(BleGroupCodec.isValidGroupCode(group));
        assertNotEquals(participant, BleGroupCodec.newParticipantId());
    }

    @Test public void iosGattProtocolUsesSharedUuidAndKnownPayloadVector() {
        assertEquals(UUID.fromString("1a6cde71-9d0a-4c4e-a274-4c6572656e67"),
                BleGroupCodec.identityCharacteristicUuid());
        assertArrayEquals(new byte[]{(byte)0xA9, 0x03, (byte)0xC6, (byte)0xEF, (byte)0xB6, 0x32,
                        (byte)0xA1, (byte)0xB2, (byte)0xC3, (byte)0xD4},
                BleGroupCodec.createPayload("GEDE7K4PM2", "A1B2C3D4"));
    }

    @Test public void rawElevationsFromDifferentPlatformDatumsAreNotMerged() {
        List<SurveyDatabase.Point> points = new ArrayList<>();
        points.add(point("A1B2C3D4", 10_000, 0.10000, 0.10000, 100, false, "", "Android GPS/GNSS"));
        points.add(point("A1B2C3D4", 20_000, 0.10000, 0.10100, 110, false, "", "Android GPS/GNSS"));
        points.add(point("E5F6A7B8", 10_000, 0.10001, 0.10001, 500, false, "", "iOS Core Location"));
        points.add(point("E5F6A7B8", 20_000, 0.10001, 0.10101, 510, false, "", "iOS Core Location"));

        RouteProfileEngine.Profile profile = RouteProfileEngine.build(points);

        assertEquals(2, profile.stations.size());
        assertEquals(100.0, profile.stations.get(0).elevationMeters, 0.01);
        assertEquals(110.0, profile.stations.get(1).elevationMeters, 0.01);
        assertEquals(0, profile.stationsWithMultipleContributors);
    }
}
