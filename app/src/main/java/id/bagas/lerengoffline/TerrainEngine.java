package id.bagas.lerengoffline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded, local-only terrain surface. It never extrapolates beyond measured support. */
public final class TerrainEngine {
    public static final double MAX_RADIUS_METERS = 250.0;
    public static final int MAX_NODES = 56;
    public static final int MIN_DISTINCT_SAMPLES = 12;
    public static final double MIN_RENDER_COVERAGE_PERCENT = 25.0;
    private static final int MIN_RENDER_CELLS = 8;
    private static final double MIN_CONNECTED_FRACTION = 0.60;
    private static final int MAX_NEIGHBORS = 8;
    private static final double MIN_SPAN_METERS = 2.0;
    private static final double AGGREGATE_CELL_METERS = 5.0;

    public static final class Sample {
        public final double latitude;
        public final double longitude;
        public final double elevationMeters;
        public final String datum;

        public Sample(double latitude, double longitude, double elevationMeters, String datum) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.elevationMeters = elevationMeters;
            this.datum = datum == null ? "unknown" : datum;
        }
    }

    public static final class Node {
        public final int row;
        public final int column;
        public final double xMeters;
        public final double yMeters;
        public final Double elevationMeters;

        Node(int row, int column, double x, double y, Double z) {
            this.row = row; this.column = column; this.xMeters = x; this.yMeters = y; this.elevationMeters = z;
        }
        public boolean isValid() { return elevationMeters != null && Double.isFinite(elevationMeters); }
    }

    public static final class Cell {
        public final int row;
        public final int column;
        public final Node nw;
        public final Node ne;
        public final Node sw;
        public final Node se;

        Cell(int row, int column, Node nw, Node ne, Node sw, Node se) {
            this.row = row; this.column = column; this.nw = nw; this.ne = ne; this.sw = sw; this.se = se;
        }
        public boolean isValid() { return nw.isValid() && ne.isValid() && sw.isValid() && se.isValid(); }
    }

    public static final class Segment {
        public final double x1;
        public final double y1;
        public final double x2;
        public final double y2;
        public final double elevationMeters;

        Segment(double x1, double y1, double x2, double y2, double z) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.elevationMeters = z;
        }
    }

    public static final class Result {
        public final List<Sample> samples;
        /** Contains only fully supported cells. Missing cells are not included. */
        public final List<Node> nodes;
        public final List<Cell> cells;
        public final List<Segment> contours;
        public final int rows;
        public final int columns;
        public final int distinctSampleCount;
        public final int validNodeCount;
        public final int validCellCount;
        public final int totalCellCount;
        public final int largestConnectedCellCount;
        public final double coveragePercent;
        public final boolean shouldRenderSurface;
        public final double contourIntervalMeters;
        public final double minElevation;
        public final double maxElevation;
        public final double originLatitude;
        public final double originLongitude;
        public final double longitudeScale;
        public final String datumGroup;
        public final String status;

        Result(List<Sample> samples, List<Node> nodes, List<Cell> cells, List<Segment> contours,
               int rows, int columns, int distinct, int valid, double coverage, double interval,
               double min, double max, double originLat, double originLon, double lonScale,
               String group, String status) {
            this.samples = samples; this.nodes = nodes; this.cells = cells; this.contours = contours;
            this.rows = rows; this.columns = columns; this.distinctSampleCount = distinct;
            this.validNodeCount = valid; this.coveragePercent = coverage; this.contourIntervalMeters = interval;
            this.minElevation = min; this.maxElevation = max; this.originLatitude = originLat;
            this.originLongitude = originLon; this.longitudeScale = lonScale; this.datumGroup = group; this.status = status;
            this.validCellCount = cells.size();
            this.totalCellCount = Math.max(0, (rows - 1) * (columns - 1));
            this.largestConnectedCellCount = TerrainEngine.largestConnectedCellCount(cells, rows, columns);
            this.shouldRenderSurface = canRenderSurface(validCellCount, coverage, largestConnectedCellCount);
        }
        public boolean hasSurface() { return !cells.isEmpty(); }
    }

    private static final class PointXY {
        final double x;
        final double y;
        final double z;
        PointXY(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }
    private static final class Accumulator {
        double x, y, z;
        int count;
        void add(PointXY p) { x += p.x; y += p.y; z += p.z; count++; }
        PointXY mean() { return new PointXY(x / count, y / count, z / count); }
    }
    private static final class Neighbor {
        final PointXY point;
        final double distance;
        Neighbor(PointXY point, double distance) { this.point = point; this.distance = distance; }
    }

    private TerrainEngine() {}

    /** Maps only well-recognized references together; all other labels remain isolated. */
    public static String datumGroup(String datum) {
        String d = datum == null ? "" : datum.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        if (d.contains("wgs84") || d.contains("wgs 84") || d.contains("ellipsoid") || d.contains("android")) return "WGS84_ELLIPSOID";
        if (d.contains("egm96")) return "EGM96";
        if (d.contains("egm2008") || d.contains("mean sea level") || d.contains("msl") || d.contains("orthometric")
                || d.contains("core location") || d.contains("ios")) return "EGM2008_MSL";
        return "UNKNOWN:" + (d.isEmpty() ? "unknown" : d);
    }

    public static boolean compatibleDatum(String a, String b) {
        String ga = datumGroup(a), gb = datumGroup(b);
        return ga.equals(gb) && !ga.startsWith("UNKNOWN:");
    }

    public static Result build(List<Sample> input, String requestedDatum, double contourIntervalMeters) {
        String group = datumGroup(requestedDatum);
        List<Sample> accepted = new ArrayList<>();
        for (Sample sample : input == null ? Collections.<Sample>emptyList() : input) {
            if (sample != null && valid(sample) && datumGroup(sample.datum).equals(group)) accepted.add(sample);
        }
        if (accepted.isEmpty()) return empty(accepted, group, 0, "Belum ada sampel elevasi lokal dengan datum terpilih.", 0, 0, 1);

        double latitude = 0, longitude = 0;
        for (Sample sample : accepted) { latitude += sample.latitude; longitude += sample.longitude; }
        latitude /= accepted.size(); longitude /= accepted.size();
        double longitudeScale = Math.max(0.01, Math.cos(Math.toRadians(latitude)));
        List<PointXY> projected = new ArrayList<>(accepted.size());
        Set<Long> distinctPositions = new HashSet<>();
        for (Sample sample : accepted) {
            double x = (sample.longitude - longitude) * 111_320.0 * longitudeScale;
            double y = (sample.latitude - latitude) * 111_320.0;
            projected.add(new PointXY(x, y, sample.elevationMeters));
            distinctPositions.add(key((int)Math.round(x), (int)Math.round(y)));
        }
        int distinctCount = distinctPositions.size();
        if (distinctCount < MIN_DISTINCT_SAMPLES)
            return empty(accepted, group, distinctCount, "Coverage belum cukup: diperlukan minimal 12 posisi elevasi unik.", latitude, longitude, longitudeScale);

        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (PointXY point : projected) {
            minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y);
        }
        double spanX = maxX - minX, spanY = maxY - minY;
        if (spanX < MIN_SPAN_METERS || spanY < MIN_SPAN_METERS || Math.max(spanX, spanY) / Math.max(MIN_SPAN_METERS, Math.min(spanX, spanY)) >= 50.0)
            return empty(accepted, group, distinctCount, "Coverage belum cukup: batas hampir satu dimensi; satu track tidak dibuat menjadi medan area.", latitude, longitude, longitudeScale);

        double aspect = spanX / spanY;
        double target = Math.max(4.0, distinctCount * 1.4);
        int columns = Math.min(MAX_NODES, Math.max(4, (int)Math.ceil(Math.sqrt(target * aspect))));
        int rows = Math.min(MAX_NODES, Math.max(4, (int)Math.ceil(Math.sqrt(target / aspect))));
        double dx = spanX / (columns - 1), dy = spanY / (rows - 1);

        // Collapse dense repeated observations into 5 m bins for bounded mobile work.
        Map<Long, Accumulator> aggregate = new HashMap<>();
        for (PointXY point : projected) {
            int bx = (int)Math.floor(point.x / AGGREGATE_CELL_METERS);
            int by = (int)Math.floor(point.y / AGGREGATE_CELL_METERS);
            aggregate.computeIfAbsent(key(bx, by), ignored -> new Accumulator()).add(point);
        }
        Map<Long, List<PointXY>> spatial = new HashMap<>();
        for (Map.Entry<Long, Accumulator> entry : aggregate.entrySet()) {
            PointXY point = entry.getValue().mean();
            int bx = (int)Math.floor(point.x / MAX_RADIUS_METERS);
            int by = (int)Math.floor(point.y / MAX_RADIUS_METERS);
            spatial.computeIfAbsent(key(bx, by), ignored -> new ArrayList<>()).add(point);
        }

        List<Node> nodes = new ArrayList<>(rows * columns);
        int validNodes = 0;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                double x = minX + column * dx, y = minY + row * dy;
                Double elevation = estimate(x, y, spatial);
                Node node = new Node(row, column, x, y, elevation);
                nodes.add(node);
                if (elevation != null) { validNodes++; minZ = Math.min(minZ, elevation); maxZ = Math.max(maxZ, elevation); }
            }
        }

        List<Cell> cells = new ArrayList<>();
        for (int row = 0; row < rows - 1; row++) {
            for (int column = 0; column < columns - 1; column++) {
                Cell cell = new Cell(row, column,
                        nodes.get(row * columns + column), nodes.get(row * columns + column + 1),
                        nodes.get((row + 1) * columns + column), nodes.get((row + 1) * columns + column + 1));
                if (cell.isValid()) cells.add(cell);
            }
        }
        if (validNodes == 0) { minZ = Double.NaN; maxZ = Double.NaN; }
        double interval = contourIntervalMeters > 0 ? contourIntervalMeters : chooseInterval(minZ, maxZ);
        List<Segment> contours = extractContours(cells, interval, minZ, maxZ);
        double coverage = 100.0 * cells.size() / Math.max(1, (rows - 1) * (columns - 1));
        int totalCells = Math.max(1, (rows - 1) * (columns - 1));
        int largestComponent = largestConnectedCellCount(cells, rows, columns);
        boolean showSurface = canRenderSurface(cells.size(), coverage, largestComponent);
        String status = cells.isEmpty()
                ? "Belum ada sel yang didukung di sekeliling sampel; blank = belum ada coverage. Tambahkan lintasan silang/sampel area."
                : showSurface
                ? "Permukaan ditampilkan hanya pada sel cukup rapat dan terhubung; area kosong tidak diisi."
                : "Data mendukung " + Math.round(coverage) + "% area (" + cells.size() + "/" + totalCells
                    + " sel valid), tetapi masih jarang/terpecah. Permukaan disembunyikan; hanya titik ukur asli ditampilkan.";
        return new Result(accepted, nodes, cells, contours, rows, columns, distinctCount, validNodes,
                coverage, interval, minZ, maxZ, latitude, longitude, longitudeScale, group, status);
    }

    private static Double estimate(double x, double y, Map<Long, List<PointXY>> spatial) {
        int bx = (int)Math.floor(x / MAX_RADIUS_METERS), by = (int)Math.floor(y / MAX_RADIUS_METERS);
        List<Neighbor> neighbors = new ArrayList<>();
        boolean[] quadrants = new boolean[4];
        for (int yy = by - 1; yy <= by + 1; yy++) {
            for (int xx = bx - 1; xx <= bx + 1; xx++) {
                List<PointXY> bucket = spatial.get(key(xx, yy));
                if (bucket == null) continue;
                for (PointXY point : bucket) {
                    double distance = Math.hypot(point.x - x, point.y - y);
                    if (distance > MAX_RADIUS_METERS) continue;
                    neighbors.add(new Neighbor(point, distance));
                    int quadrant = (point.x >= x ? 1 : 0) + (point.y >= y ? 2 : 0);
                    quadrants[quadrant] = true;
                }
            }
        }
        if (neighbors.isEmpty()) return null;
        neighbors.sort(Comparator.comparingDouble(n -> n.distance));
        if (neighbors.get(0).distance <= 0.5) return neighbors.get(0).point.z;
        if (neighbors.size() < 4 || !(quadrants[0] && quadrants[1] && quadrants[2] && quadrants[3])) return null;
        int used = Math.min(MAX_NEIGHBORS, neighbors.size());
        double weighted = 0, total = 0;
        for (int i = 0; i < used; i++) {
            Neighbor neighbor = neighbors.get(i);
            double weight = 1.0 / (neighbor.distance * neighbor.distance);
            weighted += neighbor.point.z * weight; total += weight;
        }
        return total > 0 ? weighted / total : null;
    }

    private static Result empty(List<Sample> samples, String group, int distinct, String status,
                                double latitude, double longitude, double longitudeScale) {
        return new Result(samples, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                0, 0, distinct, 0, 0, 10, Double.NaN, Double.NaN,
                latitude, longitude, longitudeScale, group, status);
    }

    private static boolean canRenderSurface(int validCells, double coverage, int largestComponent) {
        return validCells >= MIN_RENDER_CELLS && coverage >= MIN_RENDER_COVERAGE_PERCENT
                && largestComponent >= Math.ceil(validCells * MIN_CONNECTED_FRACTION);
    }

    private static int largestConnectedCellCount(List<Cell> cells, int rows, int columns) {
        int cellRows = rows - 1, cellColumns = columns - 1;
        if (cellRows <= 0 || cellColumns <= 0 || cells.isEmpty()) return 0;
        int total = cellRows * cellColumns;
        boolean[] valid = new boolean[total];
        for (Cell cell : cells) valid[cell.row * cellColumns + cell.column] = true;
        boolean[] visited = new boolean[total];
        int[] queue = new int[total];
        int largest = 0;
        for (int start = 0; start < total; start++) {
            if (!valid[start] || visited[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start; visited[start] = true;
            while (head < tail) {
                int index = queue[head++], row = index / cellColumns, column = index % cellColumns;
                int left = column > 0 ? index - 1 : -1;
                int right = column + 1 < cellColumns ? index + 1 : -1;
                int up = row > 0 ? index - cellColumns : -1;
                int down = row + 1 < cellRows ? index + cellColumns : -1;
                int[] neighbors = {left, right, up, down};
                for (int neighbor : neighbors) if (neighbor >= 0 && valid[neighbor] && !visited[neighbor]) {
                    visited[neighbor] = true; queue[tail++] = neighbor;
                }
            }
            largest = Math.max(largest, tail);
        }
        return largest;
    }

    private static List<Segment> extractContours(List<Cell> cells, double step, double min, double max) {
        List<Segment> out = new ArrayList<>();
        if (cells.isEmpty() || !Double.isFinite(step) || step <= 0 || !Double.isFinite(min) || !Double.isFinite(max)) return out;
        double level = (Math.floor(min / step) + 1) * step;
        for (; level < max; level += step) {
            for (Cell cell : cells) {
                List<double[]> hits = new ArrayList<>(4);
                edge(cell.nw, cell.ne, level, hits);
                edge(cell.ne, cell.se, level, hits);
                edge(cell.se, cell.sw, level, hits);
                edge(cell.sw, cell.nw, level, hits);
                deduplicate(hits);
                if (hits.size() == 2) out.add(segment(hits.get(0), hits.get(1), level));
                else if (hits.size() == 4) {
                    double center = (cell.nw.elevationMeters + cell.ne.elevationMeters + cell.sw.elevationMeters + cell.se.elevationMeters) / 4.0;
                    if (center > level) {
                        out.add(segment(hits.get(0), hits.get(1), level));
                        out.add(segment(hits.get(2), hits.get(3), level));
                    } else {
                        out.add(segment(hits.get(0), hits.get(3), level));
                        out.add(segment(hits.get(1), hits.get(2), level));
                    }
                }
            }
        }
        return out;
    }

    private static void edge(Node a, Node b, double z, List<double[]> out) {
        double za = a.elevationMeters, zb = b.elevationMeters;
        if (!((za <= z && zb > z) || (zb <= z && za > z))) return;
        double t = (z - za) / (zb - za);
        out.add(new double[]{a.xMeters + (b.xMeters - a.xMeters) * t, a.yMeters + (b.yMeters - a.yMeters) * t});
    }

    private static void deduplicate(List<double[]> points) {
        for (int i = points.size() - 1; i >= 0; i--) {
            for (int j = 0; j < i; j++) {
                if (Math.hypot(points.get(i)[0] - points.get(j)[0], points.get(i)[1] - points.get(j)[1]) < 0.001) {
                    points.remove(i); break;
                }
            }
        }
    }

    private static Segment segment(double[] a, double[] b, double level) { return new Segment(a[0], a[1], b[0], b[1], level); }

    private static double chooseInterval(double min, double max) {
        double span = max - min;
        if (!Double.isFinite(span) || span <= 0) return 1;
        double raw = span / 8.0;
        double power = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1))));
        double normalized = raw / power;
        return (normalized >= 5 ? 5 : normalized >= 2 ? 2 : 1) * power;
    }

    private static boolean valid(Sample sample) {
        return Double.isFinite(sample.latitude) && Double.isFinite(sample.longitude) && Double.isFinite(sample.elevationMeters)
                && sample.latitude >= -90 && sample.latitude <= 90 && sample.longitude >= -180 && sample.longitude <= 180;
    }

    private static long key(int x, int y) { return (((long)x) << 32) ^ (y & 0xffffffffL); }
}
