package id.bagas.lerengoffline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Lightweight Canvas renderer; polygons are drawn only from fully supported terrain cells. */
public final class TerrainView extends View {
    private TerrainEngine.Result result;
    private boolean surface3d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] p1 = new float[2], p2 = new float[2], p3 = new float[2], p4 = new float[2];
    private double minX, minY, zMin, zRange, reliefMeters;
    private float chartScale, chartPad, chartHeight;
    private final android.graphics.DashPathEffect emptyDash =
            new android.graphics.DashPathEffect(new float[]{dp(4), dp(5)}, 0);

    public TerrainView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(0.8f));
        text.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setResult(TerrainEngine.Result value) { result = value; invalidate(); }
    public void setSurface3d(boolean value) { surface3d = value; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (result == null || (result.nodes.isEmpty() && result.samples.isEmpty())) {
            drawEmptyState(canvas);
            return;
        }

        double centerLat = result.originLatitude, centerLon = result.originLongitude, longitudeScale = result.longitudeScale;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double minSampleY = Double.POSITIVE_INFINITY;
        if (!result.nodes.isEmpty()) {
            minX = Double.POSITIVE_INFINITY; minSampleY = Double.POSITIVE_INFINITY;
            for (TerrainEngine.Node node : result.nodes) {
                minX = Math.min(minX, node.xMeters); maxX = Math.max(maxX, node.xMeters);
                minSampleY = Math.min(minSampleY, node.yMeters); maxY = Math.max(maxY, node.yMeters);
            }
            minY = minSampleY;
        } else {
            minX = Double.POSITIVE_INFINITY; minY = Double.POSITIVE_INFINITY;
            int stride = Math.max(1, (int)Math.ceil(result.samples.size() / 5000.0));
            for (int i = 0; i < result.samples.size(); i += stride) {
                TerrainEngine.Sample sample = result.samples.get(i);
                double x = (sample.longitude - centerLon) * 111_320.0 * longitudeScale;
                double y = (sample.latitude - centerLat) * 111_320.0;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX) || !Double.isFinite(maxY)) {
            drawEmptyState(canvas);
            return;
        }

        float width = getWidth(), height = getHeight();
        boolean showSurface = result.shouldRenderSurface;
        chartPad = dp(22); chartHeight = height;
        double spanX = Math.max(1, maxX - minX), spanY = Math.max(1, maxY - minY);
        zMin = showSurface && Double.isFinite(result.minElevation) ? result.minElevation : minSampleElevation(result);
        double maxZ = showSurface && Double.isFinite(result.maxElevation) ? result.maxElevation : maxSampleElevation(result);
        zRange = Math.max(1, maxZ - zMin);
        reliefMeters = Math.max(5, Math.max(spanX, spanY) * 0.22);
        double projectionWidth = surface3d ? spanX + spanY * 0.35 : spanX;
        double projectionHeight = surface3d ? spanX * 0.22 + spanY * 0.55 + reliefMeters : spanY;
        chartScale = (float)Math.min((width - 2 * chartPad) / Math.max(1, projectionWidth),
                (height - 2 * chartPad) / Math.max(1, projectionHeight));

        if (showSurface && surface3d) {
            paint.setStyle(Paint.Style.FILL);
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
                float t = (float)Math.max(0, Math.min(1, (z - zMin) / zRange));
                paint.setColor(shaded(rampColor(t), cell));
                canvas.drawPath(path, paint);

                stroke.setColor(color(R.color.terrain_outline));
                stroke.setAlpha(46);
                stroke.setStrokeWidth(dp(0.7f));
                canvas.drawPath(path, stroke);
            }

            // Iso-lines on top of the shaded surface
            stroke.setColor(color(R.color.terrain_outline));
            stroke.setAlpha(70);
            stroke.setStrokeWidth(dp(0.9f));
            for (TerrainEngine.Segment segment : result.contours) {
                project(segment.x1, segment.y1, segment.elevationMeters, p1);
                project(segment.x2, segment.y2, segment.elevationMeters, p2);
                canvas.drawLine(p1[0], p1[1], p2[0], p2[1], stroke);
            }
        }

        if (showSurface && !surface3d) {
            stroke.setColor(color(R.color.terrain_outline));
            stroke.setAlpha(150);
            stroke.setStrokeWidth(dp(1.2f));
            for (TerrainEngine.Segment segment : result.contours) {
                project(segment.x1, segment.y1, segment.elevationMeters, p1);
                project(segment.x2, segment.y2, segment.elevationMeters, p2);
                canvas.drawLine(p1[0], p1[1], p2[0], p2[1], stroke);
            }
        }

        // Sample markers
        paint.setStyle(Paint.Style.FILL);
        int stride = Math.max(1, (int)Math.ceil(result.samples.size() / 5000.0));
        float outer = dp(surface3d ? 4.0f : 4.4f);
        float inner = dp(surface3d ? 2.6f : 3.0f);
        for (int i = 0; i < result.samples.size(); i += stride) {
            TerrainEngine.Sample sample = result.samples.get(i);
            double x = (sample.longitude - centerLon) * 111_320.0 * longitudeScale;
            double y = (sample.latitude - centerLat) * 111_320.0;
            project(x, y, sample.elevationMeters, p1);
            float t = (float)Math.max(0, Math.min(1, (sample.elevationMeters - zMin) / zRange));
            paint.setColor(color(R.color.terrain_point_ring));
            canvas.drawCircle(p1[0], p1[1], outer, paint);
            paint.setColor(rampColor(t));
            canvas.drawCircle(p1[0], p1[1], inner, paint);
        }

        if (!showSurface) drawBadge(canvas, width, result.cells.isEmpty()
                ? "TITIK UKUR SAJA • BELUM ADA COVERAGE"
                : "TITIK UKUR SAJA • PERMUKAAN PARSIAL DISEMBUNYIKAN");
    }

    /** 2D drawn badge shown when only points exist. */
    private void drawBadge(Canvas canvas, float width, String badge) {
        float left = dp(10), top = dp(9), right = width - dp(10), bottom = dp(37);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color(R.color.terrain_badge_bg));
        canvas.drawRoundRect(left, top, right, bottom, dp(11), dp(11), paint);

        text.setTextSize(sp(10));
        text.setFakeBoldText(true);
        text.setColor(color(R.color.terrain_badge_text));
        // Label panjang tidak boleh terpotong di layar sempit; pendekkan dengan elipsis.
        canvas.drawText(ellipsize(badge, right - left - dp(16)), width / 2f, top + dp(18), text);
        text.setFakeBoldText(false);
    }

    private String ellipsize(String value, float maxWidth) {
        if (maxWidth <= 0 || text.measureText(value) <= maxWidth) return value;
        String suffix = "…";
        int end = value.length();
        while (end > 0 && text.measureText(value.substring(0, end) + suffix) > maxWidth) end--;
        return value.substring(0, Math.max(0, end)).trim() + suffix;
    }

    private void drawEmptyState(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        stroke.setColor(color(R.color.outline));
        stroke.setAlpha(255);
        stroke.setStrokeWidth(dp(1));
        stroke.setPathEffect(emptyDash);
        canvas.drawLine(dp(24), h / 2f, w - dp(24), h / 2f, stroke);
        stroke.setPathEffect(null);

        text.setColor(color(R.color.text_faint));
        text.setTextSize(sp(13));
        canvas.drawText("Belum ada permukaan terrain", w / 2f, h / 2f - dp(12), text);
        text.setTextSize(sp(11));
        canvas.drawText("Rekam titik elevasi atau impor dataset CSV", w / 2f, h / 2f + dp(10), text);
    }

    private void project(double x, double y, double z, float[] out) {
        double localX = x - minX, localY = y - minY;
        if (!surface3d) {
            out[0] = chartPad + (float)(localX * chartScale);
            out[1] = chartHeight - chartPad - (float)(localY * chartScale);
            return;
        }
        double normalizedZ = Math.max(0, Math.min(1, (z - zMin) / zRange));
        out[0] = chartPad + (float)((localX + localY * 0.35) * chartScale);
        out[1] = chartHeight - chartPad - (float)((localX * 0.22 + localY * 0.55 + normalizedZ * reliefMeters) * chartScale);
    }

    /** Simple directional light so 3D cells read as a surface rather than a flat mesh. */
    private int shaded(int base, TerrainEngine.Cell cell) {
        double east = (cell.ne.elevationMeters + cell.se.elevationMeters) / 2.0;
        double west = (cell.nw.elevationMeters + cell.sw.elevationMeters) / 2.0;
        double south = (cell.sw.elevationMeters + cell.se.elevationMeters) / 2.0;
        double north = (cell.nw.elevationMeters + cell.ne.elevationMeters) / 2.0;
        double slope = ((east - west) - (south - north)) / zRange;
        float factor = (float)Math.max(0.74, Math.min(1.20, 1.0 + slope * 0.55));
        return Color.rgb(
                clampChannel(Color.red(base) * factor),
                clampChannel(Color.green(base) * factor),
                clampChannel(Color.blue(base) * factor));
    }

    private static int clampChannel(float value) { return Math.max(0, Math.min(255, Math.round(value))); }

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
    private double minSampleElevation(TerrainEngine.Result value) { double z = Double.POSITIVE_INFINITY; for (TerrainEngine.Sample s : value.samples) z = Math.min(z, s.elevationMeters); return Double.isFinite(z) ? z : 0; }
    private double maxSampleElevation(TerrainEngine.Result value) { double z = Double.NEGATIVE_INFINITY; for (TerrainEngine.Sample s : value.samples) z = Math.max(z, s.elevationMeters); return Double.isFinite(z) ? z : 1; }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
}
