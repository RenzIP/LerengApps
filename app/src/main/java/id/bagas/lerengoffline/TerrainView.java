package id.bagas.lerengoffline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kontur terrain. Bila sel area cukup, yang digambar adalah peta kontur 2D/3D.
 * Bila tidak (mis. satu jalur lurus), yang digambar adalah profil kontur
 * sepanjang jalur dengan pita elevasi dan garis setiap interval terpilih.
 */
public final class TerrainView extends View {
    private TerrainEngine.Result result;
    private RouteProfileEngine.Profile profile;
    private boolean surface3d;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path clip = new Path();
    private final RectF plot = new RectF();
    private final float[] p1 = new float[2], p2 = new float[2], p3 = new float[2], p4 = new float[2];

    private double minX, minY, zMin, zMax, zRange, reliefMeters;
    private float chartScale, chartPad, chartHeight;

    private final DashPathEffect emptyDash =
            new DashPathEffect(new float[]{dp(4), dp(5)}, 0);
    private final DashPathEffect softDash =
            new DashPathEffect(new float[]{dp(3), dp(4)}, 0);

    public TerrainView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(0.8f));
        text.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(dp(3f));
        halo.setColor(Color.WHITE);
    }

    public void setResult(TerrainEngine.Result value) {
        result = value;
        invalidate();
    }

    public void setProfile(RouteProfileEngine.Profile value) {
        profile = value;
        invalidate();
    }

    public void setSurface3d(boolean value) {
        surface3d = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean hasArea = result != null && result.hasSurface();
        boolean hasProfile = hasProfile();

        if (hasArea && surface3d) {
            drawArea3d(canvas);
        } else if (hasArea) {
            drawArea2d(canvas);
        } else if (hasProfile) {
            drawProfile(canvas);
        } else if (result != null && !result.samples.isEmpty()) {
            drawScatter(canvas);
        } else {
            drawEmptyState(canvas);
        }
    }

    // ------------------------------------------------------------ area contours

    private void drawArea2d(Canvas canvas) {
        if (!computeBounds()) {
            drawEmptyState(canvas);
            return;
        }
        float width = getWidth(), height = getHeight();
        double spanX = Math.max(1, maxX - minX), spanY = Math.max(1, maxY - minY);
        chartPad = dp(20);
        chartHeight = height;
        chartScale = (float) Math.min((width - 2 * chartPad) / spanX,
                (height - 2 * chartPad) / spanY);

        zMin = Double.isFinite(result.minElevation) ? result.minElevation : minSampleElevation(result);
        double maxZ = Double.isFinite(result.maxElevation) ? result.maxElevation : maxSampleElevation(result);
        zMax = maxZ;
        zRange = Math.max(1, maxZ - zMin);

        fillPaint.setStyle(Paint.Style.FILL);
        for (TerrainEngine.Cell cell : result.cells) {
            project(cell.nw.xMeters, cell.nw.yMeters, cell.nw.elevationMeters, p1);
            project(cell.ne.xMeters, cell.ne.yMeters, cell.ne.elevationMeters, p2);
            project(cell.se.xMeters, cell.se.yMeters, cell.se.elevationMeters, p3);
            project(cell.sw.xMeters, cell.sw.yMeters, cell.sw.elevationMeters, p4);
            path.reset();
            path.moveTo(p1[0], p1[1]);
            path.lineTo(p2[0], p2[1]);
            path.lineTo(p3[0], p3[1]);
            path.lineTo(p4[0], p4[1]);
            path.close();
            double z = (cell.nw.elevationMeters + cell.ne.elevationMeters
                    + cell.sw.elevationMeters + cell.se.elevationMeters) / 4.0;
            fillPaint.setColor(bandColor(z));
            canvas.drawPath(path, fillPaint);
        }

        drawContourSegments(canvas, true);
        drawSampleDots(canvas, false);

        // Label elevasi pada kontur indeks agar peta bisa dibaca tanpa legenda.
        drawAreaLabels(canvas);

        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(color(R.color.text_faint));
        text.setTextSize(sp(9.5f));
        text.setFakeBoldText(false);
        canvas.drawText("interval kontur " + intervalText() + " • peta area terukur",
                dp(8), dp(13), text);
    }

    private void drawArea3d(Canvas canvas) {
        if (!computeBounds()) {
            drawEmptyState(canvas);
            return;
        }
        float width = getWidth(), height = getHeight();
        double spanX = Math.max(1, maxX - minX), spanY = Math.max(1, maxY - minY);
        chartPad = dp(20);
        chartHeight = height;
        zMin = Double.isFinite(result.minElevation) ? result.minElevation : minSampleElevation(result);
        double maxZ = Double.isFinite(result.maxElevation) ? result.maxElevation : maxSampleElevation(result);
        zMax = maxZ;
        zRange = Math.max(1, maxZ - zMin);
        reliefMeters = Math.max(5, Math.max(spanX, spanY) * 0.22);
        double projectionWidth = spanX + spanY * 0.35;
        double projectionHeight = spanX * 0.22 + spanY * 0.55 + reliefMeters;
        chartScale = (float) Math.min((width - 2 * chartPad) / Math.max(1, projectionWidth),
                (height - 2 * chartPad) / Math.max(1, projectionHeight));

        fillPaint.setStyle(Paint.Style.FILL);
        for (TerrainEngine.Cell cell : result.cells) {
            project(cell.nw.xMeters, cell.nw.yMeters, cell.nw.elevationMeters, p1);
            project(cell.ne.xMeters, cell.ne.yMeters, cell.ne.elevationMeters, p2);
            project(cell.se.xMeters, cell.se.yMeters, cell.se.elevationMeters, p3);
            project(cell.sw.xMeters, cell.sw.yMeters, cell.sw.elevationMeters, p4);
            path.reset();
            path.moveTo(p1[0], p1[1]);
            path.lineTo(p2[0], p2[1]);
            path.lineTo(p3[0], p3[1]);
            path.lineTo(p4[0], p4[1]);
            path.close();
            double z = (cell.nw.elevationMeters + cell.ne.elevationMeters
                    + cell.sw.elevationMeters + cell.se.elevationMeters) / 4.0;
            fillPaint.setColor(shaded(bandColor(z), cell));
            canvas.drawPath(path, fillPaint);

            stroke.setColor(color(R.color.terrain_outline));
            stroke.setAlpha(36);
            stroke.setStrokeWidth(dp(0.7f));
            stroke.setPathEffect(null);
            canvas.drawPath(path, stroke);
        }

        stroke.setColor(color(R.color.terrain_outline));
        stroke.setAlpha(80);
        stroke.setStrokeWidth(dp(0.9f));
        stroke.setPathEffect(null);
        for (TerrainEngine.Segment segment : result.contours) {
            project(segment.x1, segment.y1, segment.elevationMeters, p1);
            project(segment.x2, segment.y2, segment.elevationMeters, p2);
            canvas.drawLine(p1[0], p1[1], p2[0], p2[1], stroke);
        }
        drawSampleDots(canvas, true);
    }

    private void drawContourSegments(Canvas canvas, boolean labelIndex) {
        stroke.setPathEffect(null);
        for (TerrainEngine.Segment segment : result.contours) {
            project(segment.x1, segment.y1, segment.elevationMeters, p1);
            project(segment.x2, segment.y2, segment.elevationMeters, p2);
            boolean index = isIndexLevel(segment.elevationMeters);
            stroke.setColor(color(R.color.terrain_outline));
            stroke.setAlpha(index ? 190 : 120);
            stroke.setStrokeWidth(dp(index ? 1.7f : 1.0f));
            canvas.drawLine(p1[0], p1[1], p2[0], p2[1], stroke);
        }
    }

    /** Satu label per level, diletakkan di segmen terpanjang supaya tidak menumpuk. */
    private void drawAreaLabels(Canvas canvas) {
        double interval = result.contourIntervalMeters;
        if (!(interval > 0)) return;
        java.util.Map<Long, TerrainEngine.Segment> longest = new java.util.HashMap<>();
        java.util.Map<Long, Double> lengths = new java.util.HashMap<>();
        for (TerrainEngine.Segment segment : result.contours) {
            long key = Math.round(segment.elevationMeters * 1000.0);
            double length = Math.hypot(segment.x2 - segment.x1, segment.y2 - segment.y1);
            Double previous = lengths.get(key);
            if (previous == null || length > previous) {
                lengths.put(key, length);
                longest.put(key, segment);
            }
        }
        text.setTextSize(sp(9));
        text.setFakeBoldText(false);
        text.setTextAlign(Paint.Align.CENTER);
        for (TerrainEngine.Segment segment : longest.values()) {
            if (!isIndexLevel(segment.elevationMeters)) continue;
            project(segment.x1, segment.y1, segment.elevationMeters, p1);
            project(segment.x2, segment.y2, segment.elevationMeters, p2);
            float cx = (p1[0] + p2[0]) / 2f, cy = (p1[1] + p2[1]) / 2f;
            if (cx < dp(24) || cx > getWidth() - dp(24) || cy < dp(20) || cy > getHeight() - dp(10)) continue;
            float angle = (float) Math.toDegrees(Math.atan2(p2[1] - p1[1], p2[0] - p1[0]));
            if (angle > 90 || angle < -90) angle += 180;
            String label = String.format(Locale.getDefault(), "%.0f", segment.elevationMeters);
            canvas.save();
            canvas.rotate(angle, cx, cy);
            canvas.drawText(label, cx, cy + dp(3), halo);
            text.setColor(color(R.color.terrain_outline));
            canvas.drawText(label, cx, cy + dp(3), text);
            canvas.restore();
        }
    }

    // ----------------------------------------------------------- contour profile

    private boolean hasProfile() {
        if (profile == null) return false;
        int count = 0;
        for (RouteProfileEngine.Station station : profile.stations) {
            if (Double.isFinite(station.elevationMeters)) count++;
        }
        return count >= 2;
    }

    private void drawProfile(Canvas canvas) {
        float width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;

        List<RouteProfileEngine.Station> stations = new ArrayList<>();
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (RouteProfileEngine.Station station : profile.stations) {
            if (!Double.isFinite(station.elevationMeters)) continue;
            stations.add(station);
            minZ = Math.min(minZ, station.elevationMeters);
            maxZ = Math.max(maxZ, station.elevationMeters);
        }
        if (stations.size() < 2) {
            drawEmptyState(canvas);
            return;
        }

        plot.set(dp(46), dp(28), width - dp(14), height - dp(34));
        if (plot.height() < dp(40) || plot.width() < dp(40)) return;
        if (maxZ - minZ < 1) { minZ -= 0.5; maxZ += 0.5; }
        zMin = minZ;
        zMax = maxZ;
        zRange = Math.max(1e-6, maxZ - minZ);

        double interval = result != null && result.contourIntervalMeters > 0
                ? result.contourIntervalMeters : TerrainEngine.DEFAULT_INTERVAL_METERS;
        double maxDistance = Math.max(1, profile.distanceMeters);
        List<Double> levels = TerrainEngine.levels(interval, minZ, maxZ);

        // Polyline profil
        clip.reset();
        clip.moveTo(plot.left, plot.bottom);
        for (RouteProfileEngine.Station station : stations) {
            float x = plot.left + plot.width() * (float) (station.distanceMeters / maxDistance);
            float y = yFor(station.elevationMeters, minZ, maxZ);
            clip.lineTo(x, y);
        }
        clip.lineTo(plot.left + plot.width(), plot.bottom);
        clip.close();

        // Pita elevasi: setiap selang interval mendapat satu warna.
        canvas.save();
        canvas.clipPath(clip);
        for (int i = 0; i < levels.size(); i++) {
            double lower = i == 0 ? Math.min(minZ, levels.get(0)) : levels.get(i);
            double upper = i == levels.size() - 1 ? Math.max(maxZ, levels.get(i)) : levels.get(i + 1);
            float top = yFor(upper, minZ, maxZ);
            float bottom = yFor(lower, minZ, maxZ);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(bandColor((lower + upper) / 2.0));
            canvas.drawRect(plot.left, top, plot.right, bottom, fillPaint);
        }
        canvas.restore();

        // Garis kontur horizontal + label berkala.
        float levelSpacing = (float) (plot.height() * interval / (maxZ - minZ));
        int stride = Math.max(1, (int) Math.ceil(dp(24) / Math.max(1f, levelSpacing)));
        text.setTextSize(sp(9));
        text.setFakeBoldText(false);
        for (int i = 0; i < levels.size(); i++) {
            double level = levels.get(i);
            float y = yFor(level, minZ, maxZ);
            boolean index = isIndexLevel(level);
            stroke.setPathEffect(index ? null : softDash);
            stroke.setColor(color(R.color.terrain_outline));
            stroke.setAlpha(index ? 150 : 90);
            stroke.setStrokeWidth(dp(index ? 1.2f : 0.8f));
            canvas.drawLine(plot.left, y, plot.right, y, stroke);
            if (i % stride != 0) continue;
            String label = String.format(Locale.getDefault(), "%.0f", level);
            text.setColor(color(R.color.text_faint));
            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(label, plot.left - dp(6), y + dp(3.2f), text);
            text.setTextAlign(Paint.Align.LEFT);
            float labelRightX = plot.right - text.measureText(label) - dp(4);
            canvas.drawText(label, labelRightX, y - dp(3), halo);
            text.setColor(color(R.color.terrain_outline));
            canvas.drawText(label, labelRightX, y - dp(3), text);
        }
        stroke.setPathEffect(null);

        // Garis profil
        path.reset();
        boolean first = true;
        for (RouteProfileEngine.Station station : stations) {
            float x = plot.left + plot.width() * (float) (station.distanceMeters / maxDistance);
            float y = yFor(station.elevationMeters, minZ, maxZ);
            if (first) { path.moveTo(x, y); first = false; }
            else path.lineTo(x, y);
        }
        stroke.setColor(color(R.color.chart_line));
        stroke.setAlpha(255);
        stroke.setStrokeWidth(dp(2.2f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(path, stroke);

        drawProfileExtremes(canvas, stations, minZ, maxZ, maxDistance);

        // Sumbu jarak
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(color(R.color.text_faint));
        text.setTextSize(sp(9.5f));
        canvas.drawText("0", plot.left, plot.bottom + dp(15), text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.getDefault(), "%.2f km", profile.distanceMeters / 1000.0),
                plot.right, plot.bottom + dp(15), text);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("jarak jalur", plot.left, plot.bottom + dp(27), text);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(sp(9.5f));
        canvas.drawText("profil kontur • interval " + intervalText(), dp(8), dp(13), text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private void drawProfileExtremes(Canvas canvas, List<RouteProfileEngine.Station> stations,
                                     double minZ, double maxZ, double maxDistance) {
        RouteProfileEngine.Station lowest = stations.get(0), highest = stations.get(0);
        for (RouteProfileEngine.Station station : stations) {
            if (station.elevationMeters < lowest.elevationMeters) lowest = station;
            if (station.elevationMeters > highest.elevationMeters) highest = station;
        }
        text.setTextSize(sp(9.5f));
        text.setFakeBoldText(true);
        text.setColor(color(R.color.terrain_outline));
        drawExtremeLabel(canvas, highest, minZ, maxZ, maxDistance, true);
        drawExtremeLabel(canvas, lowest, minZ, maxZ, maxDistance, false);
        text.setFakeBoldText(false);
    }

    private void drawExtremeLabel(Canvas canvas, RouteProfileEngine.Station station, double minZ,
                                  double maxZ, double maxDistance, boolean above) {
        float x = plot.left + plot.width() * (float) (station.distanceMeters / maxDistance);
        float y = yFor(station.elevationMeters, minZ, maxZ);
        String label = String.format(Locale.getDefault(), "%.0f m", station.elevationMeters);
        float half = text.measureText(label) / 2f;
        float labelX = Math.max(plot.left + half, Math.min(plot.right - half, x));
        float labelY = Math.max(plot.top + sp(10), Math.min(plot.bottom - dp(4), above ? y - dp(7) : y + dp(14)));
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, labelX, labelY, halo);
        canvas.drawText(label, labelX, labelY, text);
    }

    private float yFor(double elevation, double minZ, double maxZ) {
        double span = Math.max(1e-6, maxZ - minZ);
        return plot.bottom - plot.height() * (float) ((elevation - minZ) / span);
    }

    // ---------------------------------------------------------------- fallbacks

    private void drawScatter(Canvas canvas) {
        float width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        double centerLat = result.originLatitude, centerLon = result.originLongitude;
        double longitudeScale = result.longitudeScale;
        double minSampleX = Double.POSITIVE_INFINITY, maxSampleX = Double.NEGATIVE_INFINITY;
        double minSampleY = Double.POSITIVE_INFINITY, maxSampleY = Double.NEGATIVE_INFINITY;
        int stride = Math.max(1, (int) Math.ceil(result.samples.size() / 5000.0));
        for (int i = 0; i < result.samples.size(); i += stride) {
            TerrainEngine.Sample sample = result.samples.get(i);
            double x = (sample.longitude - centerLon) * 111_320.0 * longitudeScale;
            double y = (sample.latitude - centerLat) * 111_320.0;
            minSampleX = Math.min(minSampleX, x); maxSampleX = Math.max(maxSampleX, x);
            minSampleY = Math.min(minSampleY, y); maxSampleY = Math.max(maxSampleY, y);
        }
        if (!Double.isFinite(minSampleX) || !Double.isFinite(minSampleY)) {
            drawEmptyState(canvas);
            return;
        }
        chartPad = dp(20);
        chartHeight = height;
        double spanX = Math.max(1, maxSampleX - minSampleX), spanY = Math.max(1, maxSampleY - minSampleY);
        chartScale = (float) Math.min((width - 2 * chartPad) / spanX, (height - 2 * chartPad) / spanY);
        minX = minSampleX; minY = minSampleY;
        zMin = minSampleElevation(result);
        zMax = maxSampleElevation(result);
        zRange = Math.max(1, zMax - zMin);

        fillPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < result.samples.size(); i += stride) {
            TerrainEngine.Sample sample = result.samples.get(i);
            double x = (sample.longitude - centerLon) * 111_320.0 * longitudeScale;
            double y = (sample.latitude - centerLat) * 111_320.0;
            project(x, y, sample.elevationMeters, p1);
            float t = (float) Math.max(0, Math.min(1, (sample.elevationMeters - zMin) / zRange));
            fillPaint.setColor(color(R.color.terrain_point_ring));
            canvas.drawCircle(p1[0], p1[1], dp(4.2f), fillPaint);
            fillPaint.setColor(rampColor(t));
            canvas.drawCircle(p1[0], p1[1], dp(2.8f), fillPaint);
        }

        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(color(R.color.text_faint));
        text.setTextSize(sp(9.5f));
        text.setFakeBoldText(false);
        canvas.drawText("titik ukur • kontur area butuh lintasan silang", dp(8), dp(13), text);
    }

    private void drawEmptyState(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        stroke.setColor(color(R.color.outline));
        stroke.setAlpha(255);
        stroke.setStrokeWidth(dp(1));
        stroke.setPathEffect(emptyDash);
        canvas.drawLine(dp(24), h / 2f, w - dp(24), h / 2f, stroke);
        stroke.setPathEffect(null);

        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(color(R.color.text_faint));
        text.setTextSize(sp(13));
        canvas.drawText("Belum ada elevasi untuk digambar", w / 2f, h / 2f - dp(12), text);
        text.setTextSize(sp(11));
        canvas.drawText("Rekam jalur ber-elevasi atau impor CSV/GPX", w / 2f, h / 2f + dp(10), text);
    }

    private void drawSampleDots(Canvas canvas, boolean small) {
        if (result.samples.isEmpty()) return;
        int stride = Math.max(1, (int) Math.ceil(result.samples.size() / 5000.0));
        float outer = dp(small ? 3.0f : 3.6f);
        float inner = dp(small ? 1.8f : 2.4f);
        fillPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < result.samples.size(); i += stride) {
            TerrainEngine.Sample sample = result.samples.get(i);
            double x = (sample.longitude - result.originLongitude) * 111_320.0 * result.longitudeScale;
            double y = (sample.latitude - result.originLatitude) * 111_320.0;
            project(x, y, sample.elevationMeters, p1);
            fillPaint.setColor(color(R.color.terrain_point_ring));
            canvas.drawCircle(p1[0], p1[1], outer, fillPaint);
            fillPaint.setColor(bandColor(sample.elevationMeters));
            canvas.drawCircle(p1[0], p1[1], inner, fillPaint);
        }
    }

    // -------------------------------------------------------------------- helpers

    private double maxX, maxY;

    /** Bounds are needed by the area renderers; scatter computes its own. */
    private boolean computeBounds() {
        if (result == null || result.nodes.isEmpty()) return false;
        maxX = Double.NEGATIVE_INFINITY;
        maxY = Double.NEGATIVE_INFINITY;
        minX = Double.POSITIVE_INFINITY;
        minY = Double.POSITIVE_INFINITY;
        for (TerrainEngine.Node node : result.nodes) {
            minX = Math.min(minX, node.xMeters); maxX = Math.max(maxX, node.xMeters);
            minY = Math.min(minY, node.yMeters); maxY = Math.max(maxY, node.yMeters);
        }
        return Double.isFinite(minX) && Double.isFinite(minY);
    }

    private void project(double x, double y, double z, float[] out) {
        double localX = x - minX, localY = y - minY;
        if (!surface3d) {
            out[0] = chartPad + (float) (localX * chartScale);
            out[1] = chartHeight - chartPad - (float) (localY * chartScale);
            return;
        }
        double normalizedZ = Math.max(0, Math.min(1, (z - zMin) / zRange));
        out[0] = chartPad + (float) ((localX + localY * 0.35) * chartScale);
        out[1] = chartHeight - chartPad - (float) ((localX * 0.22 + localY * 0.55 + normalizedZ * reliefMeters) * chartScale);
    }

    private boolean isIndexLevel(double level) {
        double interval = result != null && result.contourIntervalMeters > 0
                ? result.contourIntervalMeters : TerrainEngine.DEFAULT_INTERVAL_METERS;
        if (!(interval > 0)) return false;
        return Math.abs(Math.IEEEremainder(level, interval * 5)) < interval * 0.01;
    }

    private String intervalText() {
        double interval = result != null && result.contourIntervalMeters > 0
                ? result.contourIntervalMeters : TerrainEngine.DEFAULT_INTERVAL_METERS;
        return String.format(Locale.getDefault(), interval == Math.rint(interval) ? "%.0f m" : "%.1f m", interval);
    }

    /** Warna pita datar per interval, bukan gradasi halus. */
    private int bandColor(double elevation) {
        double interval = result != null && result.contourIntervalMeters > 0
                ? result.contourIntervalMeters : TerrainEngine.DEFAULT_INTERVAL_METERS;
        if (!(interval > 0)) interval = TerrainEngine.DEFAULT_INTERVAL_METERS;
        double top = Double.isFinite(zMax) ? zMax : elevation;
        double base = Math.floor(zMin / interval) * interval;
        int bands = Math.max(1, (int) Math.ceil((top - base) / interval));
        int band = (int) Math.floor((elevation - base) / interval);
        band = Math.max(0, Math.min(bands - 1, band));
        float t = bands <= 1 ? 0.5f : band / (float) (bands - 1);
        return mix(rampColor(t), Color.WHITE, 0.28f);
    }

    /** Simple directional light so 3D cells read as a surface rather than a flat mesh. */
    private int shaded(int base, TerrainEngine.Cell cell) {
        double east = (cell.ne.elevationMeters + cell.se.elevationMeters) / 2.0;
        double west = (cell.nw.elevationMeters + cell.sw.elevationMeters) / 2.0;
        double south = (cell.sw.elevationMeters + cell.se.elevationMeters) / 2.0;
        double north = (cell.nw.elevationMeters + cell.ne.elevationMeters) / 2.0;
        double slope = ((east - west) - (south - north)) / zRange;
        float factor = (float) Math.max(0.74, Math.min(1.20, 1.0 + slope * 0.55));
        return Color.rgb(
                clampChannel(Color.red(base) * factor),
                clampChannel(Color.green(base) * factor),
                clampChannel(Color.blue(base) * factor));
    }

    private static int clampChannel(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    /** Elevation ramp: low valley green → grass → sand → rocky peak. */
    private int rampColor(float t) {
        t = Math.max(0f, Math.min(1f, t));
        int low = color(R.color.terrain_low);
        int mid = color(R.color.terrain_mid);
        int high = color(R.color.terrain_high);
        int peak = color(R.color.terrain_peak);
        if (t < 0.42f) return mix(low, mid, t / 0.42f);
        if (t < 0.74f) return mix(mid, high, (t - 0.42f) / 0.32f);
        return mix(high, peak, (t - 0.74f) / 0.26f);
    }

    private static int mix(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    private int color(int res) { return getContext().getColor(res); }

    private double minSampleElevation(TerrainEngine.Result value) {
        double z = Double.POSITIVE_INFINITY;
        if (value != null) for (TerrainEngine.Sample s : value.samples) z = Math.min(z, s.elevationMeters);
        return Double.isFinite(z) ? z : 0;
    }

    private double maxSampleElevation(TerrainEngine.Result value) {
        double z = Double.NEGATIVE_INFINITY;
        if (value != null) for (TerrainEngine.Sample s : value.samples) z = Math.max(z, s.elevationMeters);
        return Double.isFinite(z) ? z : 1;
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
}
