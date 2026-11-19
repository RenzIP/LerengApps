package id.bagas.lerengoffline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Peta kontur gaya topografi: kertas krem, garis kontur menerus yang halus,
 * garis indeks lebih tebal, label elevasi di sepanjang garis, dan skala jarak.
 *
 * <p>Bila sel area cukup, yang digambar adalah peta kontur area 2D/3D. Bila
 * tidak (mis. satu jalur lurus), yang digambar adalah profil kontur sepanjang
 * jalur dengan pita elevasi dan garis setiap interval terpilih.</p>
 */
public final class TerrainView extends View {
    private TerrainEngine.Result result;
    private TerrainEngine.Preview preview;
    private RouteProfileEngine.Profile profile;
    private boolean surface3d;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path clip = new Path();
    private final Path paperPath = new Path();
    private final Path arrowPath = new Path();
    private final RectF plot = new RectF();
    private final RectF paperRect = new RectF();
    private final float[] p1 = new float[2], p2 = new float[2], p3 = new float[2], p4 = new float[2];

    private double minX, maxX, minY, maxY;
    private double zMin, zMax, zRange, reliefMeters;
    private float chartScale, chartPad, chartHeight, mapLeft, mapBottom;

    private TerrainEngine.Result tracedResult;
    private List<TerrainEngine.Polyline> tracedLines = Collections.emptyList();
    private TerrainEngine.Preview previewScreenSource;
    private List<ScreenLine> previewScreenLines = Collections.emptyList();

    private final DashPathEffect emptyDash =
            new DashPathEffect(new float[]{dp(5), dp(6)}, 0);
    private final DashPathEffect softDash =
            new DashPathEffect(new float[]{dp(3.5f), dp(4.5f)}, 0);

    /** Satu kontur yang sudah diproyeksikan ke layar. */
    private static final class ScreenLine {
        final double elevation;
        boolean closed;
        final List<float[]> points = new ArrayList<>();
        float length;

        ScreenLine(double elevation, boolean closed) {
            this.elevation = elevation;
            this.closed = closed;
        }
    }

    public TerrainView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(dp(4));
        halo.setStrokeJoin(Paint.Join.ROUND);
        halo.setStrokeCap(Paint.Cap.ROUND);
        halo.setTextAlign(Paint.Align.CENTER);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.8f));
    }

    public void setResult(TerrainEngine.Result value) {
        setResult(value, null);
    }

    public void setResult(TerrainEngine.Result value, TerrainEngine.Preview previewValue) {
        result = value;
        preview = value != null ? previewValue : null;
        tracedResult = null;
        tracedLines = Collections.emptyList();
        previewScreenSource = null;
        previewScreenLines = Collections.emptyList();
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
        boolean hasArea = result != null
                && (result.hasSurface() || (preview != null && !preview.isEmpty()));
        if (hasArea) {
            drawArea(canvas);
        } else if (hasProfile()) {
            drawProfile(canvas);
        } else if (result != null && !result.samples.isEmpty()) {
            drawScatter(canvas);
        } else {
            drawEmptyState(canvas);
        }
    }

    // ------------------------------------------------------------ area contours

    private void drawArea(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (!computeBounds()) {
            drawEmptyState(canvas);
            return;
        }
        float width = getWidth(), height = getHeight();
        drawPaper(canvas);
        canvas.save();
        canvas.clipPath(paperPath);

        zMin = Double.isFinite(result.minElevation) ? result.minElevation : minSampleElevation(result);
        zMax = Double.isFinite(result.maxElevation) ? result.maxElevation : maxSampleElevation(result);
        zRange = Math.max(1, zMax - zMin);

        double spanX = Math.max(1, maxX - minX), spanY = Math.max(1, maxY - minY);
        if (surface3d) {
            chartPad = dp(22);
            chartHeight = height;
            reliefMeters = Math.max(5, Math.max(spanX, spanY) * 0.22);
            double projectionWidth = spanX + spanY * 0.35;
            double projectionHeight = spanX * 0.22 + spanY * 0.55 + reliefMeters;
            chartScale = (float) Math.min((width - 2 * chartPad) / Math.max(1, projectionWidth),
                    (height - 2 * chartPad) / Math.max(1, projectionHeight));
            drawRelief(canvas);
        } else {
            chartPad = dp(10);
            chartHeight = height;
            chartScale = (float) Math.min((width - 2 * chartPad) / spanX,
                    (height - 2 * chartPad) / spanY);
            mapLeft = (width - (float) (spanX * chartScale)) / 2f;
            mapBottom = (height + (float) (spanY * chartScale)) / 2f;
        }

        if (!surface3d) {
            drawGrid(canvas);
            drawPreview(canvas);
            drawSamplePoints(canvas);
        }

        List<ScreenLine> lines = buildScreenLines(surface3d);
        drawContourLines(canvas, lines);
        if (!lines.isEmpty()) drawAreaLabels(canvas, lines);
        if (!surface3d) {
            drawScaleBar(canvas);
            drawNorthArrow(canvas);
        }
        drawLegend(canvas, lines);
        canvas.restore();
        drawPaperBorder(canvas);
    }

    /** Kisi 5×5 tipis seperti peta topografi cetak versi awal. */
    private void drawGrid(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        gridPaint.setPathEffect(null);
        gridPaint.setColor(color(R.color.terrain_paper_edge));
        gridPaint.setAlpha(170);
        float width = getWidth(), height = getHeight();
        for (int i = 1; i < 5; i++) {
            float x = width * i / 5f, y = height * i / 5f;
            canvas.drawLine(x, 0, x, height, gridPaint);
            canvas.drawLine(0, y, width, y, gridPaint);
        }
    }

    /**
     * Kontur perkiraan (IDW global) di luar area terukur. Garisnya putus-putus
     * dan pucat supaya tidak pernah terbaca sebagai kontur asli.
     */
    private void drawPreview(Canvas canvas) {
        if (preview == null || preview.isEmpty() || surface3d) return;
        List<ScreenLine> lines = previewScreenLines();
        stroke.setPathEffect(softDash);
        stroke.setColor(color(R.color.terrain_contour));
        stroke.setAlpha(96);
        stroke.setStrokeWidth(dp(1.0f));
        for (ScreenLine line : lines) {
            buildSmoothPath(path, line);
            canvas.drawPath(path, stroke);
        }
        stroke.setPathEffect(null);
        stroke.setAlpha(255);
    }

    /** Titik ukur: inti hijau dengan cincin kertas, seperti versi awal. */
    private void drawSamplePoints(Canvas canvas) {
        if (preview == null || preview.points.isEmpty() || surface3d) return;
        fillPaint.setStyle(Paint.Style.FILL);
        for (double[] point : preview.points) {
            project2d(point[0], point[1], p1);
            fillPaint.setColor(color(R.color.terrain_paper));
            fillPaint.setAlpha(210);
            canvas.drawCircle(p1[0], p1[1], dp(2.8f), fillPaint);
            fillPaint.setColor(color(R.color.terrain_point));
            fillPaint.setAlpha(190);
            canvas.drawCircle(p1[0], p1[1], dp(1.55f), fillPaint);
        }
        fillPaint.setAlpha(255);
    }

    private void drawNorthArrow(Canvas canvas) {
        float x = getWidth() - dp(23);
        float top = dp(17), shaftBottom = dp(33);
        stroke.setPathEffect(null);
        stroke.setColor(color(R.color.terrain_scale));
        stroke.setAlpha(220);
        stroke.setStrokeWidth(dp(1.2f));
        canvas.drawLine(x, shaftBottom, x, top + dp(9), stroke);
        arrowPath.reset();
        arrowPath.moveTo(x, top);
        arrowPath.lineTo(x - dp(4.6f), top + dp(9.5f));
        arrowPath.lineTo(x + dp(4.6f), top + dp(9.5f));
        arrowPath.close();
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(color(R.color.terrain_scale));
        fillPaint.setAlpha(235);
        canvas.drawPath(arrowPath, fillPaint);
        fillPaint.setAlpha(255);

        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setTextSize(sp(9.5f));
        text.setColor(color(R.color.terrain_scale));
        halo.setColor(color(R.color.terrain_paper));
        halo.setTextSize(sp(9.5f));
        halo.setFakeBoldText(true);
        halo.setTextAlign(Paint.Align.CENTER);
        halo.setStrokeWidth(dp(4));
        canvas.drawText("N", x, top - dp(3.5f), halo);
        canvas.drawText("N", x, top - dp(3.5f), text);
        text.setFakeBoldText(false);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawRelief(Canvas canvas) {
        fillPaint.setStyle(Paint.Style.FILL);
        for (TerrainEngine.Cell cell : result.cells) {
            project3d(cell.nw.xMeters, cell.nw.yMeters, cell.nw.elevationMeters, p1);
            project3d(cell.ne.xMeters, cell.ne.yMeters, cell.ne.elevationMeters, p2);
            project3d(cell.se.xMeters, cell.se.yMeters, cell.se.elevationMeters, p3);
            project3d(cell.sw.xMeters, cell.sw.yMeters, cell.sw.elevationMeters, p4);
            path.reset();
            path.moveTo(p1[0], p1[1]);
            path.lineTo(p2[0], p2[1]);
            path.lineTo(p3[0], p3[1]);
            path.lineTo(p4[0], p4[1]);
            path.close();
            double z = (cell.nw.elevationMeters + cell.ne.elevationMeters
                    + cell.sw.elevationMeters + cell.se.elevationMeters) / 4.0;
            fillPaint.setColor(shaded(reliefColor(z), cell));
            canvas.drawPath(path, fillPaint);
        }
    }

    private void drawContourLines(Canvas canvas, List<ScreenLine> lines) {
        for (int pass = 0; pass < 2; pass++) {
            boolean indexPass = pass == 1;
            stroke.setPathEffect(null);
            for (ScreenLine line : lines) {
                if (isIndexLevel(line.elevation) != indexPass) continue;
                buildSmoothPath(path, line);
                stroke.setColor(color(indexPass ? R.color.terrain_contour_index : R.color.terrain_contour));
                stroke.setAlpha(indexPass ? 255 : 225);
                stroke.setStrokeWidth(dp(indexPass ? 1.9f : 1.15f));
                canvas.drawPath(path, stroke);
            }
        }
    }

    /**
     * Catmull-Rom ke kurva Bézier. Garis marching squares yang kaku menjadi
     * kurva yang mengalir seperti peta kontur cetak.
     */
    private void buildSmoothPath(Path out, ScreenLine line) {
        List<float[]> points = line.points;
        int count = points.size();
        if (count < 2) return;
        out.reset();
        if (line.closed) {
            out.moveTo(points.get(0)[0], points.get(0)[1]);
            for (int i = 0; i < count; i++) {
                float[] p0 = points.get((i - 1 + count) % count);
                float[] p1 = points.get(i);
                float[] p2 = points.get((i + 1) % count);
                float[] p3 = points.get((i + 2) % count);
                out.cubicTo(
                        p1[0] + (p2[0] - p0[0]) / 6f, p1[1] + (p2[1] - p0[1]) / 6f,
                        p2[0] - (p3[0] - p1[0]) / 6f, p2[1] - (p3[1] - p1[1]) / 6f,
                        p2[0], p2[1]);
            }
            out.close();
        } else {
            out.moveTo(points.get(0)[0], points.get(0)[1]);
            for (int i = 0; i < count - 1; i++) {
                float[] p0 = points.get(Math.max(0, i - 1));
                float[] p1 = points.get(i);
                float[] p2 = points.get(i + 1);
                float[] p3 = points.get(Math.min(count - 1, i + 2));
                out.cubicTo(
                        p1[0] + (p2[0] - p0[0]) / 6f, p1[1] + (p2[1] - p0[1]) / 6f,
                        p2[0] - (p3[0] - p1[0]) / 6f, p2[1] - (p3[1] - p1[1]) / 6f,
                        p2[0], p2[1]);
            }
        }
    }

    private List<ScreenLine> buildScreenLines(boolean is3d) {
        return buildScreenLines(polylines(), is3d);
    }

    private List<ScreenLine> buildScreenLines(List<TerrainEngine.Polyline> source, boolean is3d) {
        List<ScreenLine> out = new ArrayList<>(source.size());
        for (TerrainEngine.Polyline polyline : source) {
            ScreenLine line = new ScreenLine(polyline.elevationMeters, polyline.closed);
            float[] previous = null;
            float[] projected = new float[2];
            for (double[] point : polyline.points) {
                if (is3d) project3d(point[0], point[1], polyline.elevationMeters, projected);
                else project2d(point[0], point[1], projected);
                float[] screen = new float[]{projected[0], projected[1]};
                if (previous != null && Math.hypot(screen[0] - previous[0], screen[1] - previous[1]) < dp(0.9f)) {
                    continue;
                }
                line.points.add(screen);
                previous = screen;
            }
            // Garis tertutup menyimpan titik awal dua kali (awal dan akhir).
            // Buang duplikatnya supaya kurva tidak punya segmen nol panjang.
            if (line.closed && line.points.size() > 2) {
                float[] first = line.points.get(0);
                float[] last = line.points.get(line.points.size() - 1);
                if (Math.hypot(last[0] - first[0], last[1] - first[1]) < dp(0.9f)) {
                    line.points.remove(line.points.size() - 1);
                }
            }
            if (line.points.size() < 2) continue;
            if (line.closed && line.points.size() < 3) line.closed = false;
            smoothScreen(line);
            float length = 0;
            for (int i = 1; i < line.points.size(); i++) {
                float[] a = line.points.get(i - 1), b = line.points.get(i);
                length += (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
            if (line.closed && line.points.size() > 2) {
                float[] a = line.points.get(line.points.size() - 1), b = line.points.get(0);
                length += (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
            line.length = length;
            if (length < dp(6)) continue;
            out.add(line);
        }
        return out;
    }

    /**
     * Penghalusan ringan di ruang layar. Marching squares menghasilkan titik
     * tiap sel sehingga garis terlihat bergerigi; rata-rata 1-2-1 membuatnya
     * mengalir seperti kontur peta cetak tanpa mengubah bentuk global.
     */
    private void smoothScreen(ScreenLine line) {
        int count = line.points.size();
        if (count < 3) return;
        for (int pass = 0; pass < 2; pass++) {
            List<float[]> source = new ArrayList<>(line.points);
            for (int i = 0; i < count; i++) {
                if (!line.closed && (i == 0 || i == count - 1)) continue;
                float[] previous = source.get(line.closed ? (i - 1 + count) % count : i - 1);
                float[] current = source.get(i);
                float[] next = source.get(line.closed ? (i + 1) % count : i + 1);
                line.points.set(i, new float[]{
                        previous[0] * 0.25f + current[0] * 0.5f + next[0] * 0.25f,
                        previous[1] * 0.25f + current[1] * 0.5f + next[1] * 0.25f});
            }
        }
    }

    private List<TerrainEngine.Polyline> polylines() {        if (result == null) return Collections.emptyList();
        if (tracedResult != result) {
            tracedResult = result;
            tracedLines = TerrainEngine.trace(result.contours);
        }
        return tracedLines;
    }

    private List<ScreenLine> previewScreenLines() {
        if (preview == null) return Collections.emptyList();
        if (previewScreenSource != preview) {
            previewScreenSource = preview;
            previewScreenLines = buildScreenLines(preview.lines, false);
        }
        return previewScreenLines;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Proyeksi garis perkiraan bergantung ukuran kanvas.
        previewScreenSource = null;
        previewScreenLines = Collections.emptyList();
    }

    /** Label ditempel di sepanjang garis, dengan halo kertas sebagai pemutus garis. */
    private void drawAreaLabels(Canvas canvas, List<ScreenLine> lines) {
        TreeMap<Long, ScreenLine> longest = new TreeMap<>();
        ScreenLine highestClosed = null;
        for (ScreenLine line : lines) {
            long key = Math.round(line.elevation * 1000.0);
            ScreenLine existing = longest.get(key);
            if (existing == null || line.length > existing.length) longest.put(key, line);
            if (line.closed && (highestClosed == null || line.elevation > highestClosed.elevation)) {
                highestClosed = line;
            }
        }
        if (longest.isEmpty()) return;

        List<ScreenLine> ordered = new ArrayList<>(longest.values());
        List<float[]> placed = new ArrayList<>();
        final int maxLabels = 5;

        // Seperti peta topografi cetak: label utama pada garis indeks.
        for (ScreenLine line : ordered) {
            if (placed.size() >= maxLabels) break;
            if (isIndexLevel(line.elevation)) placeLabel(canvas, line, placed);
        }
        // Bila rentang elevasi sempit sehingga hampir tak ada garis indeks,
        // sebarkan label pada garis terpanjang supaya peta tidak kosong.
        if (placed.size() < 2) {
            int stride = Math.max(1, (int) Math.ceil(ordered.size() / 4.0));
            for (int i = 0; i < ordered.size() && placed.size() < maxLabels; i += stride) {
                ScreenLine line = ordered.get(i);
                if (isIndexLevel(line.elevation)) continue;
                placeLabel(canvas, line, placed);
            }
        }
        if (highestClosed != null) placeLabel(canvas, highestClosed, placed);
    }

    private boolean placeLabel(Canvas canvas, ScreenLine line, List<float[]> placed) {
        int count = line.points.size();
        if (count < 2 || line.length < dp(56)) return false;

        int index;
        if (line.closed) {
            index = 0;
            for (int i = 1; i < count; i++) {
                if (line.points.get(i)[1] < line.points.get(index)[1]) index = i;
            }
        } else {
            float target = line.length * 0.5f, walked = 0;
            index = count / 2;
            for (int i = 1; i < count; i++) {
                float[] a = line.points.get(i - 1), b = line.points.get(i);
                walked += (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
                if (walked >= target) { index = i; break; }
            }
        }

        float[] point = line.points.get(index);
        float[] before = line.points.get(Math.max(0, index - 2));
        float[] after = line.points.get(Math.min(count - 1, index + 2));
        float angle = (float) Math.toDegrees(Math.atan2(after[1] - before[1], after[0] - before[0]));
        if (angle > 90f) angle -= 180f;
        else if (angle < -90f) angle += 180f;

        String label = formatLevel(line.elevation);
        text.setTextSize(sp(10));
        text.setFakeBoldText(true);
        float labelWidth = text.measureText(label);
        float radius = Math.max(labelWidth, sp(10)) * 0.62f;
        if (point[0] < dp(26) || point[0] > getWidth() - dp(26)
                || point[1] < dp(30) || point[1] > getHeight() - dp(30)) return false;
        for (float[] other : placed) {
            if (Math.hypot(point[0] - other[0], point[1] - other[1]) < radius + other[2]) return false;
        }
        placed.add(new float[]{point[0], point[1], radius});

        float baseline = point[1] + sp(3.4f);
        halo.setColor(color(R.color.terrain_paper));
        halo.setTextSize(sp(10));
        halo.setFakeBoldText(true);
        halo.setTextAlign(Paint.Align.CENTER);
        halo.setStrokeWidth(dp(5.5f));
        text.setColor(color(R.color.terrain_contour_text));
        canvas.save();
        canvas.rotate(angle, point[0], point[1]);
        canvas.drawText(label, point[0], baseline, halo);
        canvas.drawText(label, point[0], baseline, text);
        canvas.restore();
        text.setFakeBoldText(false);
        return true;
    }

    private void drawLegend(Canvas canvas, List<ScreenLine> lines) {
        String title = "INTERVAL KONTUR " + intervalText().toUpperCase(Locale.getDefault());
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(sp(9.5f));
        text.setFakeBoldText(true);
        text.setColor(color(R.color.terrain_scale));
        halo.setColor(color(R.color.terrain_paper));
        halo.setTextSize(sp(9.5f));
        halo.setFakeBoldText(true);
        halo.setTextAlign(Paint.Align.LEFT);
        halo.setStrokeWidth(dp(4));
        float x = dp(14), y = dp(18);
        canvas.drawText(title, x, y, halo);
        canvas.drawText(title, x, y, text);
        text.setFakeBoldText(false);

        boolean hasPreview = preview != null && !preview.isEmpty();
        if (hasPreview) {
            String sub = "garis putus-putus = perkiraan di luar area terukur";
            text.setTextSize(sp(8.5f));
            text.setColor(color(R.color.terrain_scale));
            text.setAlpha(215);
            halo.setTextSize(sp(8.5f));
            halo.setAlpha(215);
            halo.setStrokeWidth(dp(3.5f));
            canvas.drawText(sub, x, y + dp(12), halo);
            canvas.drawText(sub, x, y + dp(12), text);
            text.setAlpha(255);
            halo.setAlpha(255);
        }

        if (lines.isEmpty() && !hasPreview) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(color(R.color.terrain_contour_text));
            text.setTextSize(sp(13));
            canvas.drawText("Belum ada kontur pada interval ini", getWidth() / 2f,
                    getHeight() / 2f - dp(6), text);
            text.setTextSize(sp(11));
            text.setColor(color(R.color.terrain_scale));
            canvas.drawText("Coba interval lebih kecil atau tambah sampel elevasi", getWidth() / 2f,
                    getHeight() / 2f + dp(14), text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawScaleBar(Canvas canvas) {
        if (!(chartScale > 0) || !Double.isFinite(chartScale)) return;
        double maxMeters = (getWidth() * 0.30) / chartScale;
        double[] candidates = {5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 5000};
        double meters = 0;
        for (double candidate : candidates) if (candidate <= maxMeters) meters = candidate;
        if (meters <= 0) return;
        float length = (float) (meters * chartScale);
        if (length < dp(24)) return;

        float x0 = dp(16), y0 = getHeight() - dp(16);
        stroke.setPathEffect(null);
        stroke.setColor(color(R.color.terrain_scale));
        stroke.setAlpha(230);
        stroke.setStrokeWidth(dp(1.4f));
        canvas.drawLine(x0, y0, x0 + length, y0, stroke);
        canvas.drawLine(x0, y0 - dp(4), x0, y0 + dp(1), stroke);
        canvas.drawLine(x0 + length, y0 - dp(4), x0 + length, y0 + dp(1), stroke);

        String label = formatLevel(meters) + " m";
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(sp(9.5f));
        text.setFakeBoldText(true);
        text.setColor(color(R.color.terrain_scale));
        halo.setColor(color(R.color.terrain_paper));
        halo.setTextSize(sp(9.5f));
        halo.setFakeBoldText(true);
        halo.setTextAlign(Paint.Align.CENTER);
        halo.setStrokeWidth(dp(4));
        float cx = x0 + length / 2f, cy = y0 - dp(7);
        canvas.drawText(label, cx, cy, halo);
        canvas.drawText(label, cx, cy, text);
        text.setFakeBoldText(false);
        text.setTextAlign(Paint.Align.LEFT);
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

        drawPaper(canvas);
        canvas.save();
        canvas.clipPath(paperPath);

        double interval = result != null && result.contourIntervalMeters > 0
                ? result.contourIntervalMeters : TerrainEngine.DEFAULT_INTERVAL_METERS;
        double maxDistance = Math.max(1, profile.distanceMeters);
        List<Double> levels = TerrainEngine.levels(interval, minZ, maxZ);

        clip.reset();
        clip.moveTo(plot.left, plot.bottom);
        for (RouteProfileEngine.Station station : stations) {
            float x = plot.left + plot.width() * (float) (station.distanceMeters / maxDistance);
            float y = yFor(station.elevationMeters, minZ, maxZ);
            clip.lineTo(x, y);
        }
        clip.lineTo(plot.left + plot.width(), plot.bottom);
        clip.close();

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

        float levelSpacing = (float) (plot.height() * interval / (maxZ - minZ));
        int stride = Math.max(1, (int) Math.ceil(dp(24) / Math.max(1f, levelSpacing)));
        text.setTextSize(sp(9));
        text.setFakeBoldText(false);
        halo.setColor(color(R.color.terrain_paper));
        halo.setTextSize(sp(9));
        halo.setFakeBoldText(false);
        halo.setTextAlign(Paint.Align.LEFT);
        halo.setStrokeWidth(dp(4));
        for (int i = 0; i < levels.size(); i++) {
            double level = levels.get(i);
            float y = yFor(level, minZ, maxZ);
            boolean index = isIndexLevel(level);
            stroke.setPathEffect(index ? null : softDash);
            stroke.setColor(color(index ? R.color.terrain_contour_index : R.color.terrain_contour));
            stroke.setAlpha(index ? 210 : 150);
            stroke.setStrokeWidth(dp(index ? 1.3f : 0.9f));
            canvas.drawLine(plot.left, y, plot.right, y, stroke);
            if (i % stride != 0) continue;
            String label = formatLevel(level);
            text.setColor(color(R.color.terrain_contour_text));
            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(label, plot.left - dp(6), y + dp(3.2f), text);
            text.setTextAlign(Paint.Align.LEFT);
            float labelRightX = plot.right - text.measureText(label) - dp(4);
            canvas.drawText(label, labelRightX, y - dp(3), halo);
            text.setColor(color(R.color.terrain_contour_text));
            canvas.drawText(label, labelRightX, y - dp(3), text);
        }
        stroke.setPathEffect(null);

        path.reset();
        boolean first = true;
        for (RouteProfileEngine.Station station : stations) {
            float x = plot.left + plot.width() * (float) (station.distanceMeters / maxDistance);
            float y = yFor(station.elevationMeters, minZ, maxZ);
            if (first) { path.moveTo(x, y); first = false; }
            else path.lineTo(x, y);
        }
        stroke.setColor(color(R.color.terrain_contour_index));
        stroke.setAlpha(255);
        stroke.setStrokeWidth(dp(2.4f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(path, stroke);

        drawProfileExtremes(canvas, stations, minZ, maxZ, maxDistance);

        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(color(R.color.terrain_scale));
        text.setTextSize(sp(9.5f));
        text.setFakeBoldText(false);
        canvas.drawText("0", plot.left, plot.bottom + dp(15), text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.getDefault(), "%.2f km", profile.distanceMeters / 1000.0),
                plot.right, plot.bottom + dp(15), text);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("jarak jalur", plot.left, plot.bottom + dp(27), text);

        String title = "PROFIL KONTUR • " + intervalText().toUpperCase(Locale.getDefault());
        text.setTextSize(sp(9.5f));
        text.setFakeBoldText(true);
        text.setColor(color(R.color.terrain_scale));
        halo.setColor(color(R.color.terrain_paper));
        halo.setTextSize(sp(9.5f));
        halo.setFakeBoldText(true);
        halo.setTextAlign(Paint.Align.LEFT);
        halo.setStrokeWidth(dp(4));
        canvas.drawText(title, dp(12), dp(17), halo);
        canvas.drawText(title, dp(12), dp(17), text);
        text.setFakeBoldText(false);
        text.setTextAlign(Paint.Align.LEFT);

        canvas.restore();
        drawPaperBorder(canvas);
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
        text.setColor(color(R.color.terrain_contour_text));
        halo.setColor(color(R.color.terrain_paper));
        halo.setTextSize(sp(9.5f));
        halo.setFakeBoldText(true);
        halo.setTextAlign(Paint.Align.CENTER);
        halo.setStrokeWidth(dp(4));
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
        drawPaper(canvas);
        canvas.save();
        canvas.clipPath(paperPath);

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
            canvas.restore();
            drawEmptyState(canvas);
            return;
        }
        chartPad = dp(20);
        chartHeight = height;
        double spanX = Math.max(1, maxSampleX - minSampleX), spanY = Math.max(1, maxSampleY - minSampleY);
        chartScale = (float) Math.min((width - 2 * chartPad) / spanX, (height - 2 * chartPad) / spanY);
        minX = minSampleX; minY = minSampleY;
        mapLeft = (width - (float) (spanX * chartScale)) / 2f;
        mapBottom = (height + (float) (spanY * chartScale)) / 2f;
        zMin = minSampleElevation(result);
        zMax = maxSampleElevation(result);
        zRange = Math.max(1, zMax - zMin);

        fillPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < result.samples.size(); i += stride) {
            TerrainEngine.Sample sample = result.samples.get(i);
            double x = (sample.longitude - centerLon) * 111_320.0 * longitudeScale;
            double y = (sample.latitude - centerLat) * 111_320.0;
            project2d(x, y, p1);
            float t = (float) Math.max(0, Math.min(1, (sample.elevationMeters - zMin) / zRange));
            fillPaint.setColor(color(R.color.terrain_paper));
            canvas.drawCircle(p1[0], p1[1], dp(4.4f), fillPaint);
            fillPaint.setColor(rampColor(t));
            canvas.drawCircle(p1[0], p1[1], dp(2.9f), fillPaint);
        }

        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(color(R.color.terrain_scale));
        text.setTextSize(sp(9.5f));
        text.setFakeBoldText(true);
        halo.setColor(color(R.color.terrain_paper));
        halo.setTextSize(sp(9.5f));
        halo.setFakeBoldText(true);
        halo.setTextAlign(Paint.Align.LEFT);
        halo.setStrokeWidth(dp(4));
        String note = "TITIK UKUR • KONTUR AREA BUTUH LINTASAN SILANG";
        canvas.drawText(note, dp(12), dp(17), halo);
        canvas.drawText(note, dp(12), dp(17), text);
        text.setFakeBoldText(false);
        text.setTextAlign(Paint.Align.LEFT);

        canvas.restore();
        drawPaperBorder(canvas);
    }

    private void drawEmptyState(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        drawPaper(canvas);
        canvas.save();
        canvas.clipPath(paperPath);

        stroke.setColor(color(R.color.terrain_contour));
        stroke.setAlpha(200);
        stroke.setStrokeWidth(dp(1.2f));
        stroke.setPathEffect(emptyDash);
        canvas.drawLine(dp(24), h / 2f, w - dp(24), h / 2f, stroke);
        stroke.setPathEffect(null);

        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(color(R.color.terrain_contour_text));
        text.setTextSize(sp(13));
        canvas.drawText("Belum ada elevasi untuk digambar", w / 2f, h / 2f - dp(12), text);
        text.setTextSize(sp(11));
        text.setColor(color(R.color.terrain_scale));
        canvas.drawText("Rekam jalur ber-elevasi atau impor CSV/GPX", w / 2f, h / 2f + dp(10), text);
        text.setTextAlign(Paint.Align.LEFT);

        canvas.restore();
        drawPaperBorder(canvas);
    }

    // -------------------------------------------------------------------- helpers

    private boolean computeBounds() {
        // Bingkai 2D mengikuti lapisan perkiraan supaya jalur panjang tidak
        // menjadi strip sempit di tengah kertas.
        if (!surface3d && preview != null) {
            minX = preview.minX; maxX = preview.maxX;
            minY = preview.minY; maxY = preview.maxY;
            return true;
        }
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

    private void project2d(double x, double y, float[] out) {
        out[0] = mapLeft + (float) ((x - minX) * chartScale);
        out[1] = mapBottom - (float) ((y - minY) * chartScale);
    }

    private void project3d(double x, double y, double z, float[] out) {
        double localX = x - minX, localY = y - minY;
        double normalizedZ = Math.max(0, Math.min(1, (z - zMin) / zRange));
        out[0] = chartPad + (float) ((localX + localY * 0.35) * chartScale);
        out[1] = chartHeight - chartPad - (float) ((localX * 0.22 + localY * 0.55 + normalizedZ * reliefMeters) * chartScale);
    }

    private void drawPaper(Canvas canvas) {
        paperRect.set(0, 0, getWidth(), getHeight());
        paperPath.reset();
        paperPath.addRoundRect(paperRect, dp(12), dp(12), Path.Direction.CW);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(color(R.color.terrain_paper));
        canvas.drawPath(paperPath, fillPaint);
    }

    private void drawPaperBorder(Canvas canvas) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setPathEffect(null);
        stroke.setColor(color(R.color.terrain_paper_edge));
        stroke.setStrokeWidth(dp(0.9f));
        stroke.setAlpha(255);
        canvas.drawPath(paperPath, stroke);
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
        return formatLevel(interval) + " m";
    }

    private String formatLevel(double value) {
        return Math.abs(value - Math.rint(value)) < 1e-6
                ? String.format(Locale.getDefault(), "%.0f", value)
                : String.format(Locale.getDefault(), "%.1f", value);
    }

    /** Warna pita profil: ramp elevasi yang dilembutkan ke warna kertas. */
    private int bandColor(double elevation) {
        double span = Math.max(1e-6, zMax - zMin);
        float t = (float) Math.max(0, Math.min(1, (elevation - zMin) / span));
        return mix(rampColor(t), color(R.color.terrain_paper), 0.45f);
    }

    private int reliefColor(double elevation) {
        double span = Math.max(1e-6, zMax - zMin);
        float t = (float) Math.max(0, Math.min(1, (elevation - zMin) / span));
        return mix(rampColor(t), color(R.color.terrain_paper), 0.35f);
    }

    /** Cahaya arah sederhana supaya relief 3D terbaca sebagai permukaan. */
    private int shaded(int base, TerrainEngine.Cell cell) {
        double east = (cell.ne.elevationMeters + cell.se.elevationMeters) / 2.0;
        double west = (cell.nw.elevationMeters + cell.sw.elevationMeters) / 2.0;
        double south = (cell.sw.elevationMeters + cell.se.elevationMeters) / 2.0;
        double north = (cell.nw.elevationMeters + cell.ne.elevationMeters) / 2.0;
        double slope = ((east - west) - (south - north)) / zRange;
        float factor = (float) Math.max(0.78, Math.min(1.16, 1.0 + slope * 0.55));
        return Color.rgb(
                clampChannel(Color.red(base) * factor),
                clampChannel(Color.green(base) * factor),
                clampChannel(Color.blue(base) * factor));
    }

    private static int clampChannel(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    /** Ramp elevasi: lembah hijau → rumput → pasir → puncak berbatu. */
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
