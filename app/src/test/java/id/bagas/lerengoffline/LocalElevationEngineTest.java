package id.bagas.lerengoffline;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class LocalElevationEngineTest {
    private SurveyDatabase.Point point(long time, double lat, double lon, Double elevation) {
        return new SurveyDatabase.Point(1, time, lat, lon, elevation, 5f, 4f, null, null,
                "Android GPS/GNSS", "", "P01", "", "", false);
    }

    private List<LocalElevationEngine.Sample> cluster(double lat, double lon, double baseElevation) {
        List<LocalElevationEngine.Sample> samples = new ArrayList<>();
        samples.add(new LocalElevationEngine.Sample(lat + 0.00010, lon, baseElevation - 1));
        samples.add(new LocalElevationEngine.Sample(lat - 0.00010, lon, baseElevation + 1));
        samples.add(new LocalElevationEngine.Sample(lat, lon + 0.00010, baseElevation + 2));
        samples.add(new LocalElevationEngine.Sample(lat, lon - 0.00010, baseElevation - 2));
        return samples;
    }

    private RouteProfileEngine.Profile rawProfile() {
        List<SurveyDatabase.Point> points = new ArrayList<>();
        points.add(point(1000, 10.00000, 20.00000, 100.0));
        points.add(point(2000, 10.00000, 20.01000, 110.0));
        return RouteProfileEngine.build(points);
    }

    private List<SurveyDatabase.Point> routeWithoutGpsElevation() {
        List<SurveyDatabase.Point> points = new ArrayList<>();
        points.add(point(1000, 10.00000, 20.00000, null));
        points.add(point(2000, 10.00000, 20.01000, null));
        return points;
    }

    @Test public void nearbyLocalSamplesCorrectWholeRouteAndRecalculateAscent() {
        List<LocalElevationEngine.Sample> samples = new ArrayList<>();
        samples.addAll(cluster(10.00000, 20.00000, 1000));
        samples.addAll(cluster(10.00000, 20.01000, 1200));

        RouteProfileEngine.Profile corrected = LocalElevationEngine.correctProfile(rawProfile(),
                "DEM milik saya", "EGM96", new LocalElevationEngine.Index(samples));

        assertTrue(corrected.datasetCorrectionApplied);
        assertEquals("DEM milik saya", corrected.elevationDatasetName);
        assertEquals("EGM96", corrected.verticalDatum);
        assertEquals(2, corrected.datasetCoveredStations);
        assertEquals(1000, corrected.stations.get(0).elevationMeters, 2.1);
        assertEquals(1200, corrected.stations.get(1).elevationMeters, 2.1);
        assertEquals(200, corrected.ascentMeters, 5);
        assertEquals(100, corrected.stations.get(0).rawElevationMeters, 0.01);
        assertTrue(corrected.stations.get(0).datasetCorrected);
    }

    @Test public void partialCoverageLeavesEntireRawProfileUnchanged() {
        List<LocalElevationEngine.Sample> samples = cluster(10.00000, 20.00000, 1000);

        RouteProfileEngine.Profile result = LocalElevationEngine.correctProfile(rawProfile(),
                "Dataset parsial", "datum lain", new LocalElevationEngine.Index(samples));

        assertFalse(result.datasetCorrectionApplied);
        assertEquals(1, result.datasetCoveredStations);
        assertEquals(100, result.stations.get(0).elevationMeters, 0.01);
        assertEquals(110, result.stations.get(1).elevationMeters, 0.01);
        assertEquals(10, result.ascentMeters, 0.01);
        assertTrue(result.message.contains("dipertahankan"));
    }

    @Test public void sparseSamplesTooFarFromRouteDoNotProduceAnEstimate() {
        LocalElevationEngine.Index index = new LocalElevationEngine.Index(cluster(10.00000, 20.00000, 1000));
        assertNull(index.estimate(10.01000, 20.00000));
    }

    @Test public void fullLocalDatasetBuildsProfileWhenGpsAltitudeIsMissing() {
        List<LocalElevationEngine.Sample> samples = new ArrayList<>();
        samples.addAll(cluster(10.00000, 20.00000, 1000));
        samples.addAll(cluster(10.00000, 20.01000, 1200));
        RouteProfileEngine.Profile route = RouteProfileEngine.build(routeWithoutGpsElevation(), true);

        RouteProfileEngine.Profile corrected = LocalElevationEngine.correctProfile(route,
                "DEM lokal", "EGM96", new LocalElevationEngine.Index(samples));

        assertTrue(corrected.datasetCorrectionApplied);
        assertEquals(2, corrected.stations.size());
        assertEquals(200, corrected.ascentMeters, 5);
    }

    @Test public void partialDatasetWithoutGpsAltitudeDoesNotExposeNanStations() {
        List<LocalElevationEngine.Sample> samples = cluster(10.00000, 20.00000, 1000);
        RouteProfileEngine.Profile route = RouteProfileEngine.build(routeWithoutGpsElevation(), true);

        RouteProfileEngine.Profile result = LocalElevationEngine.correctProfile(route,
                "DEM parsial", "EGM96", new LocalElevationEngine.Index(samples));

        assertFalse(result.datasetCorrectionApplied);
        assertEquals(0, result.stations.size());
        assertTrue(result.message.contains("Elevasi GPS tidak tersedia lengkap"));
    }
}
