package id.bagas.lerengoffline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** On-device interpolation of a user-imported point dataset. */
public final class LocalElevationEngine {
    public static final double MAX_SEARCH_RADIUS_METERS = 150.0;
    private static final double CELL_DEGREES = 0.001;
    private static final int MIN_NEIGHBORS = 3;
    private static final int MAX_NEIGHBORS = 4;

    public static final class Sample {
        public final double latitude;
        public final double longitude;
        public final double elevationMeters;
        public Sample(double latitude, double longitude, double elevationMeters) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.elevationMeters = elevationMeters;
        }
    }

    public static final class Estimate {
        public final double elevationMeters;
        public final int sampleCount;
        public final double nearestSampleMeters;
        Estimate(double elevationMeters, int sampleCount, double nearestSampleMeters) {
            this.elevationMeters = elevationMeters;
            this.sampleCount = sampleCount;
            this.nearestSampleMeters = nearestSampleMeters;
        }
    }

    public static final class Index {
        private static final class Neighbor {
            final Sample sample;
            final double distanceMeters;
            Neighbor(Sample sample, double distanceMeters) { this.sample = sample; this.distanceMeters = distanceMeters; }
        }

        private final Map<Long, List<Sample>> cells = new HashMap<>();
        public final int sampleCount;

        public Index(List<Sample> samples) {
            int count = 0;
            if (samples != null) for (Sample sample : samples) {
                if (sample == null || !validCoordinate(sample.latitude, sample.longitude) || !Double.isFinite(sample.elevationMeters)) continue;
                cells.computeIfAbsent(key(cell(sample.latitude), cell(sample.longitude)), ignored -> new ArrayList<>()).add(sample);
                count++;
            }
            sampleCount = count;
        }

        public Estimate estimate(double latitude, double longitude) {
            if (sampleCount == 0 || !validCoordinate(latitude, longitude)) return null;
            int latCell = cell(latitude), lonCell = cell(longitude);
            int latRadius = (int)Math.ceil((MAX_SEARCH_RADIUS_METERS / 111_320.0) / CELL_DEGREES) + 1;
            double cosine = Math.max(0.01, Math.abs(Math.cos(Math.toRadians(latitude))));
            int lonRadius = (int)Math.ceil((MAX_SEARCH_RADIUS_METERS / (111_320.0 * cosine)) / CELL_DEGREES) + 1;
            List<Neighbor> neighbors = new ArrayList<>();
            for (int y = latCell - latRadius; y <= latCell + latRadius; y++) {
                for (int x = lonCell - lonRadius; x <= lonCell + lonRadius; x++) {
                    List<Sample> bucket = cells.get(key(y, x));
                    if (bucket == null) continue;
                    for (Sample sample : bucket) {
                        double distance = RouteProfileEngine.distanceMeters(latitude, longitude, sample.latitude, sample.longitude);
                        if (distance <= MAX_SEARCH_RADIUS_METERS) neighbors.add(new Neighbor(sample, distance));
                    }
                }
            }
            if (neighbors.isEmpty()) return null;
            neighbors.sort(Comparator.comparingDouble(n -> n.distanceMeters));
            Neighbor nearest = neighbors.get(0);
            if (nearest.distanceMeters <= 0.5)
                return new Estimate(nearest.sample.elevationMeters, 1, nearest.distanceMeters);
            if (neighbors.size() < MIN_NEIGHBORS) return null;
            int used = Math.min(MAX_NEIGHBORS, neighbors.size());
            double weightedElevation = 0, totalWeight = 0;
            for (int i = 0; i < used; i++) {
                Neighbor neighbor = neighbors.get(i);
                double weight = 1.0 / (neighbor.distanceMeters * neighbor.distanceMeters);
                weightedElevation += neighbor.sample.elevationMeters * weight;
                totalWeight += weight;
            }
            return totalWeight <= 0 ? null : new Estimate(weightedElevation / totalWeight, used, nearest.distanceMeters);
        }
    }

    private LocalElevationEngine() {}

    public static RouteProfileEngine.Profile correctProfile(RouteProfileEngine.Profile raw,
            String datasetName, String verticalDatum, Index index) {
        if (raw == null) return null;
        String name = datasetName == null || datasetName.trim().isEmpty() ? "Dataset lokal" : datasetName.trim();
        String datum = verticalDatum == null || verticalDatum.trim().isEmpty() ? "tidak diketahui" : verticalDatum.trim();
        if (index == null || raw.stations.size() < 2) return raw.withDatasetStatus(name, datum, 0, false,
                raw.message + " Dataset elevasi belum dapat diterapkan karena profil belum memiliki cukup stasiun.");

        List<Estimate> estimates = new ArrayList<>(raw.stations.size());
        int covered = 0;
        for (RouteProfileEngine.Station station : raw.stations) {
            Estimate estimate = index.estimate(station.latitude, station.longitude);
            estimates.add(estimate);
            if (estimate != null) covered++;
        }
        if (covered != raw.stations.size()) {
            String message = raw.message + " Dataset “" + name + "” hanya mencakup " + covered + "/" + raw.stations.size()
                    + " stasiun dalam radius " + Math.round(MAX_SEARCH_RADIUS_METERS)
                    + " m; impor area/kerapatan sampel yang mencakup seluruh rute.";
            boolean rawElevationsComplete = true;
            for (RouteProfileEngine.Station station : raw.stations)
                if (!Double.isFinite(station.rawElevationMeters)) { rawElevationsComplete = false; break; }
            if (rawElevationsComplete)
                return raw.withDatasetStatus(name, datum, covered, false,
                        message + " Profil GPS asli dipertahankan agar datum berbeda tidak tercampur.");
            return new RouteProfileEngine.Profile(new ArrayList<>(), raw.waypoints, raw.distanceMeters,
                    0, 0, 0, 0, raw.participantCount, raw.stationsWithMultipleContributors,
                    raw.anchorParticipantId, message + " Elevasi GPS tidak tersedia lengkap; profil tidak ditampilkan daripada membuat koreksi parsial.",
                    false, name, datum, covered);
        }

        List<RouteProfileEngine.Station> corrected = new ArrayList<>(raw.stations.size());
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < raw.stations.size(); i++) {
            RouteProfileEngine.Station source = raw.stations.get(i);
            double elevation = estimates.get(i).elevationMeters;
            corrected.add(source.withElevation(elevation, true));
            min = Math.min(min, elevation);
            max = Math.max(max, elevation);
        }
        double ascent = 0, descent = 0;
        double reference = corrected.get(0).elevationMeters;
        for (int i = 1; i < corrected.size(); i++) {
            double delta = corrected.get(i).elevationMeters - reference;
            if (delta >= 3.0) { ascent += delta; reference = corrected.get(i).elevationMeters; }
            else if (delta <= -3.0) { descent += -delta; reference = corrected.get(i).elevationMeters; }
        }
        String message = "Profil dikoreksi penuh dari dataset lokal “" + name + "” • datum: " + datum
                + " • " + covered + "/" + corrected.size() + " stasiun cocok; data GPS mentah tetap tersimpan.";
        return new RouteProfileEngine.Profile(corrected, raw.waypoints, raw.distanceMeters, ascent, descent,
                min, max, raw.participantCount, raw.stationsWithMultipleContributors, raw.anchorParticipantId,
                message, true, name, datum, covered);
    }

    private static boolean validCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude) && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }

    private static int cell(double coordinate) { return (int)Math.floor(coordinate / CELL_DEGREES); }
    private static long key(int latitudeCell, int longitudeCell) {
        return (((long)latitudeCell) << 32) | (longitudeCell & 0xffffffffL);
    }
}
