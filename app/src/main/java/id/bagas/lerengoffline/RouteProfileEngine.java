package id.bagas.lerengoffline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RouteProfileEngine {
    private static final double EARTH_METERS = 6371008.8;
    private static final double MATCH_RADIUS_METERS = 45.0;
    private static final long MATCH_TIME_MILLIS = 180_000L;

    public static final class Station {
        public final double distanceMeters;
        public final double latitude;
        public final double longitude;
        public final double elevationMeters;
        public final double rawElevationMeters;
        public final boolean datasetCorrected;
        public final long timeMillis;
        public final int contributorCount;
        Station(double distanceMeters, SurveyDatabase.Point anchor, double elevationMeters, int contributorCount) {
            this(distanceMeters, anchor.lat, anchor.lon, elevationMeters, elevationMeters, anchor.time, contributorCount, false);
        }
        private Station(double distanceMeters, double latitude, double longitude, double elevationMeters,
                        double rawElevationMeters, long timeMillis, int contributorCount, boolean datasetCorrected) {
            this.distanceMeters = distanceMeters;
            this.latitude = latitude;
            this.longitude = longitude;
            this.elevationMeters = elevationMeters;
            this.rawElevationMeters = rawElevationMeters;
            this.datasetCorrected = datasetCorrected;
            this.timeMillis = timeMillis;
            this.contributorCount = contributorCount;
        }
        Station withElevation(double elevationMeters, boolean datasetCorrected) {
            return new Station(distanceMeters, latitude, longitude, elevationMeters, rawElevationMeters,
                    timeMillis, contributorCount, datasetCorrected);
        }
    }

    public static final class RouteWaypoint {
        public final double distanceMeters;
        public final Double elevationMeters;
        public final String label;
        public final String participantId;
        public final String participantAlias;
        public final long timeMillis;
        RouteWaypoint(double distanceMeters, Double elevationMeters, SurveyDatabase.Point point) {
            this.distanceMeters = distanceMeters;
            this.elevationMeters = point.gpsAltitude;
            this.label = point.label;
            this.participantId = point.participantId;
            this.participantAlias = point.participantAlias;
            this.timeMillis = point.time;
        }
    }

    public static final class Profile {
        public final List<Station> stations;
        public final List<RouteWaypoint> waypoints;
        public final double distanceMeters;
        public final double ascentMeters;
        public final double descentMeters;
        public final double minElevationMeters;
        public final double maxElevationMeters;
        public final int participantCount;
        public final int stationsWithMultipleContributors;
        public final String anchorParticipantId;
        public final String message;
        public final boolean datasetCorrectionApplied;
        public final String elevationDatasetName;
        public final String verticalDatum;
        public final int datasetCoveredStations;
        Profile(List<Station> stations, List<RouteWaypoint> waypoints, double distanceMeters,
                double ascentMeters, double descentMeters, double minElevationMeters,
                double maxElevationMeters, int participantCount, int stationsWithMultipleContributors,
                String anchorParticipantId, String message) {
            this(stations, waypoints, distanceMeters, ascentMeters, descentMeters, minElevationMeters,
                    maxElevationMeters, participantCount, stationsWithMultipleContributors, anchorParticipantId,
                    message, false, "", "", 0);
        }
        Profile(List<Station> stations, List<RouteWaypoint> waypoints, double distanceMeters,
                double ascentMeters, double descentMeters, double minElevationMeters,
                double maxElevationMeters, int participantCount, int stationsWithMultipleContributors,
                String anchorParticipantId, String message, boolean datasetCorrectionApplied,
                String elevationDatasetName, String verticalDatum, int datasetCoveredStations) {
            this.stations = stations;
            this.waypoints = waypoints;
            this.distanceMeters = distanceMeters;
            this.ascentMeters = ascentMeters;
            this.descentMeters = descentMeters;
            this.minElevationMeters = minElevationMeters;
            this.maxElevationMeters = maxElevationMeters;
            this.participantCount = participantCount;
            this.stationsWithMultipleContributors = stationsWithMultipleContributors;
            this.anchorParticipantId = anchorParticipantId;
            this.message = message;
            this.datasetCorrectionApplied = datasetCorrectionApplied;
            this.elevationDatasetName = elevationDatasetName == null ? "" : elevationDatasetName;
            this.verticalDatum = verticalDatum == null ? "" : verticalDatum;
            this.datasetCoveredStations = datasetCoveredStations;
        }
        Profile withDatasetStatus(String datasetName, String datum, int coveredStations,
                                  boolean correctionApplied, String updatedMessage) {
            return new Profile(stations, waypoints, distanceMeters, ascentMeters, descentMeters,
                    minElevationMeters, maxElevationMeters, participantCount, stationsWithMultipleContributors,
                    anchorParticipantId, updatedMessage, correctionApplied, datasetName, datum, coveredStations);
        }
    }

    private RouteProfileEngine() {}

    public static Profile build(List<SurveyDatabase.Point> input) {
        return build(input, false);
    }

    public static Profile build(List<SurveyDatabase.Point> input, boolean allowMissingElevation) {
        Map<String, List<SurveyDatabase.Point>> tracks = new LinkedHashMap<>();
        Set<String> participants = new HashSet<>();
        List<SurveyDatabase.Point> allElevations = new ArrayList<>();
        List<SurveyDatabase.Point> waypoints = new ArrayList<>();
        for (SurveyDatabase.Point p : input) {
            if (p.waypoint) { waypoints.add(p); continue; }
            if (p.participantId != null && !p.participantId.isEmpty()) participants.add(p.participantId);
            if (!validCoordinate(p)) continue;
            tracks.computeIfAbsent(nonEmptyId(p.participantId), k -> new ArrayList<>()).add(p);
            if (p.gpsAltitude != null && Double.isFinite(p.gpsAltitude)) allElevations.add(p);
        }
        if (tracks.isEmpty() || (allElevations.size() < 2 && !allowMissingElevation))
            return empty(participants.size(), "Butuh setidaknya 2 titik ber-elevasi atau dataset elevasi lokal yang mencakup rute.");

        String anchorId = null;
        List<SurveyDatabase.Point> anchor = null;
        int bestTrackCount = -1;
        for (Map.Entry<String, List<SurveyDatabase.Point>> entry : tracks.entrySet()) {
            int count = entry.getValue().size();
            if (count > bestTrackCount) { bestTrackCount = count; anchorId = entry.getKey(); anchor = entry.getValue(); }
        }
        if (anchor == null || bestTrackCount < 2) return empty(participants.size(), "Belum ada satu track peserta dengan dua titik valid.");
        anchor.sort(Comparator.comparingLong(p -> p.time));
        allElevations.sort(Comparator.comparingLong(p -> p.time));

        List<Station> stations = new ArrayList<>();
        double distance = 0;
        SurveyDatabase.Point previousAnchor = null;
        int joined = 0;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (SurveyDatabase.Point anchorPoint : anchor) {
            if (!validCoordinate(anchorPoint)) continue;
            if (previousAnchor != null) distance += distanceMeters(previousAnchor.lat, previousAnchor.lon, anchorPoint.lat, anchorPoint.lon);
            previousAnchor = anchorPoint;
            Map<String, SurveyDatabase.Point> nearestByParticipant = new HashMap<>();
            Map<String, Double> nearestScore = new HashMap<>();
            for (SurveyDatabase.Point p : allElevations) {
                if (!verticalDatumGroup(anchorPoint).equals(verticalDatumGroup(p))) continue;
                long dt = Math.abs(p.time - anchorPoint.time);
                if (dt > MATCH_TIME_MILLIS) continue;
                double d = distanceMeters(anchorPoint.lat, anchorPoint.lon, p.lat, p.lon);
                if (d > MATCH_RADIUS_METERS) continue;
                String pid = nonEmptyId(p.participantId);
                double score = d + (dt / (double)MATCH_TIME_MILLIS) * 10.0;
                if (!nearestScore.containsKey(pid) || score < nearestScore.get(pid)) {
                    nearestScore.put(pid, score);
                    nearestByParticipant.put(pid, p);
                }
            }
            List<Double> elevations = new ArrayList<>();
            for (SurveyDatabase.Point p : nearestByParticipant.values()) elevations.add(p.gpsAltitude);
            if (elevations.isEmpty()) {
                if (allowMissingElevation) stations.add(new Station(distance, anchorPoint, Double.NaN, 0));
                continue;
            }
            Collections.sort(elevations);
            double median = elevations.size() % 2 == 1 ? elevations.get(elevations.size() / 2)
                    : (elevations.get(elevations.size() / 2 - 1) + elevations.get(elevations.size() / 2)) / 2.0;
            stations.add(new Station(distance, anchorPoint, median, elevations.size()));
            if (elevations.size() >= 2) joined++;
            if (Double.isFinite(median)) {
                minZ = Math.min(minZ, median);
                maxZ = Math.max(maxZ, median);
            }
        }
        if (stations.size() < 2) return empty(participants.size(), "Track terlalu pendek atau memiliki kurang dari dua titik valid.");

        double ascent = 0, descent = 0;
        double filteredReference = stations.get(0).elevationMeters;
        for (int i = 1; i < stations.size(); i++) {
            double delta = stations.get(i).elevationMeters - filteredReference;
            if (delta >= 3.0) { ascent += delta; filteredReference = stations.get(i).elevationMeters; }
            else if (delta <= -3.0) { descent += -delta; filteredReference = stations.get(i).elevationMeters; }
        }

        List<RouteWaypoint> projectedWaypoints = new ArrayList<>();
        for (SurveyDatabase.Point waypoint : waypoints) {
            if (!validCoordinate(waypoint)) continue;
            double[] projection = projectOnRoute(waypoint.lat, waypoint.lon, stations);
            if (projection != null && projection[0] <= 120.0)
                projectedWaypoints.add(new RouteWaypoint(projection[1], waypoint.gpsAltitude, waypoint));
        }
        projectedWaypoints.sort(Comparator.comparingDouble(w -> w.distanceMeters));
        String message;
        if (participants.size() < 2) message = "Profil satu jalur • impor file track peserta lain untuk menggabungkan pengukuran rombongan.";
        else if (joined == 0) message = "Track satu jalur • tidak ada titik peserta lain yang cocok jarak/waktu/datumnya; elevasi track acuan saja.";
        else message = "Profil satu jalur • median elevasi peserta dekat dengan datum sejenis; bukan kontur area.";
        double safeMinZ = Double.isFinite(minZ) ? minZ : 0;
        double safeMaxZ = Double.isFinite(maxZ) ? maxZ : 0;
        return new Profile(stations, projectedWaypoints, distance, ascent, descent, safeMinZ, safeMaxZ,
                participants.size(), joined, anchorId, message);
    }

    private static boolean validCoordinate(SurveyDatabase.Point p) {
        return Double.isFinite(p.lat) && Double.isFinite(p.lon) && p.lat >= -90 && p.lat <= 90 && p.lon >= -180 && p.lon <= 180;
    }

    private static String nonEmptyId(String id) { return id == null || id.trim().isEmpty() ? "UNKNOWN" : id.trim(); }

    private static String verticalDatumGroup(SurveyDatabase.Point point) {
        String source = point.source == null ? "" : point.source.toLowerCase(java.util.Locale.ROOT);
        if (source.contains("core location") || source.contains("ios") || source.contains("mean sea level") ||
                source.contains("orthometric") || source.contains("egm")) return "orthometric-msl";
        if (source.contains("android") || source.contains("gps") || source.contains("gnss") ||
                source.contains("network") || source.contains("wgs84") || source.contains("ellipsoid")) return "wgs84-ellipsoid";
        return "unknown:" + nonEmptyId(point.participantId);
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }

    /** Returns {perpendicular distance to route, distance along route} using local-meter projection. */
    private static double[] projectOnRoute(double latitude, double longitude, List<Station> stations) {
        if (stations.isEmpty()) return null;
        double bestDistance = Double.POSITIVE_INFINITY, bestAlong = 0;
        double refLat = Math.toRadians(latitude), cosLat = Math.cos(refLat);
        double wx = Math.toRadians(longitude) * EARTH_METERS * cosLat;
        double wy = Math.toRadians(latitude) * EARTH_METERS;
        for (int i = 0; i < stations.size() - 1; i++) {
            Station a = stations.get(i), b = stations.get(i + 1);
            double ax = Math.toRadians(a.longitude) * EARTH_METERS * cosLat;
            double ay = Math.toRadians(a.latitude) * EARTH_METERS;
            double bx = Math.toRadians(b.longitude) * EARTH_METERS * cosLat;
            double by = Math.toRadians(b.latitude) * EARTH_METERS;
            double vx = bx - ax, vy = by - ay;
            double lengthSquared = vx * vx + vy * vy;
            double t = lengthSquared <= 0 ? 0 : ((wx - ax) * vx + (wy - ay) * vy) / lengthSquared;
            t = Math.max(0, Math.min(1, t));
            double dx = wx - (ax + t * vx), dy = wy - (ay + t * vy);
            double perpendicular = Math.sqrt(dx * dx + dy * dy);
            if (perpendicular < bestDistance) {
                bestDistance = perpendicular;
                bestAlong = a.distanceMeters + t * (b.distanceMeters - a.distanceMeters);
            }
        }
        if (stations.size() == 1) bestDistance = distanceMeters(latitude, longitude, stations.get(0).latitude, stations.get(0).longitude);
        return new double[]{bestDistance, bestAlong};
    }

    private static Profile empty(int participantCount, String message) {
        return new Profile(new ArrayList<>(), new ArrayList<>(), 0, 0, 0, 0, 0,
                participantCount, 0, "", message);
    }
}
