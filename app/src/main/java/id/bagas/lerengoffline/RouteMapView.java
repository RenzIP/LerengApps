package id.bagas.lerengoffline;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline route map: a north-up Canvas projection of stored points, waypoints and (when the local
 * elevation coverage allows it) interpolated contour lines. There are no tiles, no network calls,
 * and no basemap — anything not measured locally stays blank.
 */
public final class RouteMapView extends View {
    private static final double METERS_PER_DEGREE = 111_320.0;
    private static final float MAX_USER_SCALE = 14f;

    private final List<SurveyDatabase.Point> points = new ArrayList<>();
    private TerrainEngine.Result terrain;
    private long surveyId = -1;
    private boolean hasProjection;

    private double originLatitude, originLongitude, longitudeScale = 1;
    private double minX, minY, maxX, maxY;
    private double centerX, centerY;
    private float fitScale = 1f;
    private float userScale = 1f;
    private float panX, panY;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trackPath = new Path();
    private final float[] projected = new float[2];
    private final RectF panel = new RectF();
    private final RectF labelBox = new RectF();
    private final DashPathEffect emptyDash =
            new DashPathEffect(new float[]{dp(5), dp(6)}, 0);

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public RouteMapView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        label.setStyle(Paint.Style.FILL);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        if (!hasContent()) return false;
                        float previous = totalScale();
                        userScale = clamp(userScale * detector.getScaleFactor(), 1f, MAX_USER_SCALE);
                        float current = totalScale();
                        if (previous <= 0 || current <= 0) return true;
                        float ratio = current / previous;
                        float cx = getWidth() / 2f, cy = getHeight() / 2f;
                        panX = detector.getFocusX() - cx - (detector.getFocusX() - cx - panX) * ratio;
                        panY = detector.getFocusY() - cy - (detector.getFocusY() - cy - panY) * ratio;
                        clampPan();
                        invalidate();
                        return true;
                    }
                });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onScroll(MotionEvent from, MotionEvent to, float dx, float dy) {
                if (scaleDetector.isInProgress()) return false;
                panX -= dx;
                panY -= dy;
                clampPan();
                invalidate();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                resetView();
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                performClick();
                return true;
            }
        });
        setClickable(true);
        setFocusable(true);
    }

    /** Sets the route shown on the map; a different project drops the previous terrain result. */
    public void setRoute(List<SurveyDatabase.Point> input, long id) {
        boolean projectChanged = id != surveyId;
        surveyId = id;
        points.clear();
        if (input != null) {
            for (SurveyDatabase.Point point : input) {
                if (point == null) continue;
                if (!Double.isFinite(point.lat) || !Double.isFinite(point.lon)) continue;
                points.add(point);
            }
        }
        if (projectChanged && terrain != null) terrain = null;
        recomputeProjection();
        if (projectChanged || !hasProjection) resetView();
        else {
            refreshFitScale();
            clampPan();
            invalidate();
        }
        updateContentDescription();
    }

    public void setTerrain(TerrainEngine.Result result) {
        terrain = result;
        recomputeProjection();
        refreshFitScale();
        clampPan();
        invalidate();
        updateContentDescription();
    }

    public void resetView() {
        userScale = 1f;
        panX = 0f;
        panY = 0f;
        refreshFitScale();
        hasProjection = true;
        invalidate();
        performClick();
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (!scaleDetector.isInProgress()) gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        refreshFitScale();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        panel.set(0, 0, width, height);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color(R.color.surface_sunken));
        canvas.drawRoundRect(panel, dp(14), dp(14), fill);

        if (!hasContent()) {
            drawEmptyState(canvas, width, height);
            return;
        }

        drawGrid(canvas, width, height);
        if (terrain != null && terrain.shouldRenderSurface && !terrain.contours.isEmpty())
            drawContours(canvas);
        drawTrack(canvas);
        drawWaypoints(canvas);
        if (trackCount() > 0) drawMarker(canvas, firstTrackPoint(), true);
        if (trackCount() > 1) drawMarker(canvas, lastTrackPoint(), false);
        drawNorthArrow(canvas, width);
        drawScaleBar(canvas, width, height);
        drawBadge(canvas);
    }

    // ------------------------------------------------------------------ data

    private boolean hasContent() {
        return trackCount() > 0 || waypointCount() > 0
                || (terrain != null && !terrain.samples.isEmpty());
    }

    private int trackCount() {
        int n = 0;
        for (SurveyDatabase.Point point : points) if (!point.waypoint) n++;
        return n;
    }

    private int waypointCount() {
        return points.size() - trackCount();
    }

    private SurveyDatabase.Point firstTrackPoint() {
        for (SurveyDatabase.Point point : points) if (!point.waypoint) return point;
        return points.isEmpty() ? null : points.get(0);
    }

    private SurveyDatabase.Point lastTrackPoint() {
        for (int i = points.size() - 1; i >= 0; i--) if (!points.get(i).waypoint) return points.get(i);
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    private void recomputeProjection() {
        if (terrain != null && Double.isFinite(terrain.originLatitude)
                && Double.isFinite(terrain.originLongitude)) {
            originLatitude = terrain.originLatitude;
            originLongitude = terrain.originLongitude;
            longitudeScale = terrain.longitudeScale > 0 ? terrain.longitudeScale : 1;
        } else if (!points.isEmpty()) {
            originLatitude = points.get(0).lat;
            originLongitude = points.get(0).lon;
            longitudeScale = Math.max(0.01, Math.cos(Math.toRadians(originLatitude)));
        }

        minX = Double.POSITIVE_INFINITY;
        minY = Double.POSITIVE_INFINITY;
        maxX = Double.NEGATIVE_INFINITY;
        maxY = Double.NEGATIVE_INFINITY;
        for (SurveyDatabase.Point point : points) {
            double x = xMeters(point.lat, point.lon), y = yMeters(point.lat);
            include(x, y);
        }
        if (terrain != null && terrain.shouldRenderSurface) {
            for (TerrainEngine.Segment segment : terrain.contours) {
                include(segment.x1, segment.y1);
                include(segment.x2, segment.y2);
            }
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY)) {
            minX = minY = -30;
            maxX = maxY = 30;
        }
        centerX = (minX + maxX) / 2;
        centerY = (minY + maxY) / 2;
    }

    private void include(double x, double y) {
        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
    }

    private double xMeters(double latitude, double longitude) {
        return (longitude - originLongitude) * METERS_PER_DEGREE * longitudeScale;
    }

    private double yMeters(double latitude) {
        return (latitude - originLatitude) * METERS_PER_DEGREE;
    }

    private void refreshFitScale() {
        float width = getWidth() - 2 * dp(26);
        float height = getHeight() - 2 * dp(30);
        if (width <= 0 || height <= 0) return;
        double spanX = Math.max(60, maxX - minX);
        double spanY = Math.max(60, maxY - minY);
        fitScale = (float) Math.min(width / spanX, height / spanY);
        if (!Float.isFinite(fitScale) || fitScale <= 0) fitScale = 1f;
    }

    private float totalScale() {
        return fitScale * userScale;
    }

    private void clampPan() {
        float limitX = getWidth() * (0.5f + userScale * 0.5f);
        float limitY = getHeight() * (0.5f + userScale * 0.5f);
        panX = clamp(panX, -limitX, limitX);
        panY = clamp(panY, -limitY, limitY);
    }

    private boolean project(double x, double y) {
        float scale = totalScale();
        projected[0] = getWidth() / 2f + (float) ((x - centerX) * scale) + panX;
        projected[1] = getHeight() / 2f - (float) ((y - centerY) * scale) + panY;
        return projected[0] > -dp(40) && projected[0] < getWidth() + dp(40)
                && projected[1] > -dp(40) && projected[1] < getHeight() + dp(40);
    }

    // ------------------------------------------------------------------ drawing

    private void drawGrid(Canvas canvas, float width, float height) {
        double metersPerPixel = 1.0 / Math.max(0.000001, totalScale());
        double step = niceStep(metersPerPixel * dp(52));
        if (!Double.isFinite(step) || step <= 0) return;
        double stepPx = step * totalScale();
        if (stepPx < dp(18)) return;

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(0.7f));
        stroke.setColor(color(R.color.chart_grid));
        stroke.setAlpha(140);
        stroke.setPathEffect(null);

        float startX = (float) ((width / 2f + panX) % stepPx);
        if (startX < 0) startX += stepPx;
        for (float x = startX; x < width; x += (float) stepPx)
            canvas.drawLine(x, 0, x, height, stroke);
        float startY = (float) ((height / 2f + panY) % stepPx);
        if (startY < 0) startY += stepPx;
        for (float y = startY; y < height; y += (float) stepPx)
            canvas.drawLine(0, y, width, y, stroke);
    }

    private void drawContours(Canvas canvas) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(0.9f));
        stroke.setColor(color(R.color.terrain_outline));
        stroke.setAlpha(92);
        stroke.setPathEffect(null);
        for (TerrainEngine.Segment segment : terrain.contours) {
            project(segment.x1, segment.y1);
            float x1 = projected[0], y1 = projected[1];
            project(segment.x2, segment.y2);
            canvas.drawLine(x1, y1, projected[0], projected[1], stroke);
        }
    }

    private void drawTrack(Canvas canvas) {
        List<SurveyDatabase.Point> track = new ArrayList<>();
        for (SurveyDatabase.Point point : points) if (!point.waypoint) track.add(point);
        if (track.isEmpty()) return;

        trackPath.reset();
        boolean first = true;
        SurveyDatabase.Point previous = null;
        for (SurveyDatabase.Point point : track) {
            if (!project(xMeters(point.lat, point.lon), yMeters(point.lat))) continue;
            float x = projected[0], y = projected[1];
            boolean breakLine = previous != null
                    && (RouteProfileEngine.distanceMeters(previous.lat, previous.lon, point.lat, point.lon) > 2000
                    || Math.abs(point.time - previous.time) > 6L * 60L * 60L * 1000L);
            if (first || breakLine) trackPath.moveTo(x, y); else trackPath.lineTo(x, y);
            first = false;
            previous = point;
        }
        if (first) return;

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(6));
        stroke.setColor(color(R.color.surface));
        stroke.setAlpha(225);
        stroke.setPathEffect(null);
        canvas.drawPath(trackPath, stroke);

        stroke.setStrokeWidth(dp(3));
        stroke.setColor(color(R.color.primary));
        stroke.setAlpha(255);
        canvas.drawPath(trackPath, stroke);
    }

    private void drawWaypoints(Canvas canvas) {
        int waypoints = waypointCount();
        boolean showLabels = waypoints <= 12;
        int drawn = 0;
        for (SurveyDatabase.Point point : points) {
            if (!point.waypoint) continue;
            if (!project(xMeters(point.lat, point.lon), yMeters(point.lat))) continue;
            float x = projected[0], y = projected[1];

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color(R.color.surface));
            canvas.drawCircle(x, y, dp(6.5f), fill);
            fill.setColor(color(R.color.accent));
            canvas.drawCircle(x, y, dp(4.4f), fill);

            String text = point.label == null ? "" : point.label.trim();
            if (showLabels && !text.isEmpty() && drawn < 12) {
                drawLabel(canvas, text, x, y - dp(10));
                drawn++;
            }
        }
    }

    private void drawLabel(Canvas canvas, String value, float x, float baseline) {
        label.setTextSize(sp(10));
        label.setTextAlign(Paint.Align.LEFT);
        String text = value.length() > 22 ? value.substring(0, 21) + "…" : value;
        float textWidth = label.measureText(text);
        float padH = dp(4), padV = dp(2.5f);
        float height = sp(10) + padV * 2;
        float left = x - textWidth / 2 - padH;
        left = clamp(left, dp(2), Math.max(dp(2), getWidth() - textWidth - padH * 2 - dp(2)));
        float top = baseline - sp(10) - padV;
        top = clamp(top, dp(2), Math.max(dp(2), getHeight() - height - dp(2)));
        labelBox.set(left, top, left + textWidth + padH * 2, top + height);

        fill.setColor(color(R.color.surface));
        fill.setAlpha(235);
        canvas.drawRoundRect(labelBox, dp(4), dp(4), fill);
        fill.setAlpha(255);
        label.setColor(color(R.color.text));
        canvas.drawText(text, left + padH, top + sp(10) + dp(0.5f), label);
    }

    private void drawMarker(Canvas canvas, SurveyDatabase.Point point, boolean start) {
        if (point == null) return;
        if (!project(xMeters(point.lat, point.lon), yMeters(point.lat))) return;
        float x = projected[0], y = projected[1];
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color(R.color.surface));
        canvas.drawCircle(x, y, dp(7), fill);
        fill.setColor(color(start ? R.color.primary : R.color.danger));
        canvas.drawCircle(x, y, dp(4.6f), fill);
        fill.setColor(color(R.color.surface));
        canvas.drawCircle(x, y, dp(1.6f), fill);
    }

    private void drawNorthArrow(Canvas canvas, float width) {
        float x = width - dp(22), y = dp(20);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.6f));
        stroke.setColor(color(R.color.text_muted));
        stroke.setPathEffect(null);
        canvas.drawLine(x, y + dp(10), x, y - dp(6), stroke);

        Path arrow = new Path();
        arrow.moveTo(x, y - dp(11));
        arrow.lineTo(x - dp(4.5f), y - dp(3));
        arrow.lineTo(x + dp(4.5f), y - dp(3));
        arrow.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color(R.color.text_muted));
        canvas.drawPath(arrow, fill);

        label.setTextSize(sp(9));
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(color(R.color.text_muted));
        canvas.drawText("U", x, y + dp(17), label);
    }

    private void drawScaleBar(Canvas canvas, float width, float height) {
        double metersPerPixel = 1.0 / Math.max(0.000001, totalScale());
        double step = niceStep(metersPerPixel * dp(78));
        float pixels = (float) (step * totalScale());
        if (pixels <= 0 || pixels > width * 0.6f) return;
        float left = dp(16), bottom = height - dp(18);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.4f));
        stroke.setColor(color(R.color.text_muted));
        stroke.setPathEffect(null);
        canvas.drawLine(left, bottom, left + pixels, bottom, stroke);
        canvas.drawLine(left, bottom - dp(4), left, bottom + dp(4), stroke);
        canvas.drawLine(left + pixels, bottom - dp(4), left + pixels, bottom + dp(4), stroke);

        label.setTextSize(sp(9.5f));
        label.setTextAlign(Paint.Align.LEFT);
        label.setColor(color(R.color.text_muted));
        canvas.drawText(formatDistance(step), left, bottom - dp(7), label);
    }

    private void drawBadge(Canvas canvas) {
        String text = "PETA LOKAL • TANPA UBIN";
        label.setTextSize(sp(9));
        label.setTextAlign(Paint.Align.LEFT);
        float textWidth = label.measureText(text);
        float height = sp(9) + dp(8);
        labelBox.set(dp(12), dp(12), dp(12) + textWidth + dp(16), dp(12) + height);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color(R.color.terrain_badge_bg));
        fill.setAlpha(235);
        canvas.drawRoundRect(labelBox, dp(8), dp(8), fill);
        fill.setAlpha(255);
        label.setColor(color(R.color.terrain_badge_text));
        canvas.drawText(text, dp(20), dp(12) + height / 2 + sp(3.2f), label);
    }

    private void drawEmptyState(Canvas canvas, float width, float height) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.2f));
        stroke.setColor(color(R.color.outline_strong));
        stroke.setPathEffect(emptyDash);
        panel.set(dp(14), dp(14), width - dp(14), height - dp(14));
        canvas.drawRoundRect(panel, dp(12), dp(12), stroke);
        stroke.setPathEffect(null);

        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(color(R.color.text_muted));
        label.setTextSize(sp(13));
        canvas.drawText("Belum ada jalur", width / 2, height / 2 - sp(2), label);
        label.setColor(color(R.color.text_faint));
        label.setTextSize(sp(11));
        canvas.drawText("Rekam atau impor titik untuk melihat peta lokal",
                width / 2, height / 2 + sp(18), label);
    }

    // ------------------------------------------------------------------ helpers

    private void updateContentDescription() {
        double distance = 0;
        SurveyDatabase.Point previous = null;
        for (SurveyDatabase.Point point : points) {
            if (point.waypoint) continue;
            if (previous != null)
                distance += RouteProfileEngine.distanceMeters(previous.lat, previous.lon, point.lat, point.lon);
            previous = point;
        }
        String description = String.format(Locale.getDefault(),
                "Peta jalur offline. %d titik jalur, %d waypoint, %.2f kilometer. Tanpa ubin daring. Cubit untuk zoom, ketuk dua kali untuk mengatur ulang.",
                trackCount(), waypointCount(), distance / 1000.0);
        setContentDescription(description);
    }

    private double niceStep(double target) {
        if (!Double.isFinite(target) || target <= 0) return 1;
        double exponent = Math.floor(Math.log10(target));
        double base = Math.pow(10, exponent);
        double normalized = target / base;
        double nice = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
        return nice * base;
    }

    private String formatDistance(double meters) {
        if (meters >= 1000) return String.format(Locale.getDefault(), "%.1f km", meters / 1000.0);
        return String.format(Locale.getDefault(), "%.0f m", meters);
    }

    private int color(int colorRes) { return UiKit.color(getContext(), colorRes); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
