package id.bagas.lerengoffline;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

public final class RouteProfileView extends View {
    private static final int GRID_STEPS = 4;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path fill = new Path();
    private final RectF plot = new RectF();
    private android.graphics.DashPathEffect dashEffect;
    private android.graphics.DashPathEffect emptyDashEffect;
    private Shader fillShader;

    private RouteProfileEngine.Profile profile;
    private String placeholder = "Belum ada track\nRekam atau impor CSV peserta";
    private float reveal = 1f;
    private ValueAnimator revealAnimator;

    public RouteProfileView(Context context) { super(context); init(); }
    public RouteProfileView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        dashEffect = new android.graphics.DashPathEffect(new float[]{dp(3), dp(4)}, 0);
        emptyDashEffect = new android.graphics.DashPathEffect(new float[]{dp(4), dp(5)}, 0);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(color(R.color.chart_grid));
        labelPaint.setTextSize(sp(10));
        labelPaint.setColor(color(R.color.chart_axis));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.4f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(color(R.color.chart_line));
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(color(R.color.chart_marker));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2));
        ringPaint.setColor(color(R.color.terrain_point_ring));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fillShader = new LinearGradient(0, dp(22), 0, Math.max(dp(23), h - dp(30)),
                color(R.color.chart_fill_top), color(R.color.chart_fill_bottom), Shader.TileMode.CLAMP);
    }

    public void setProfile(RouteProfileEngine.Profile profile) {
        boolean hadProfile = this.profile != null && this.profile.stations.size() >= 2;
        this.profile = profile;
        if (revealAnimator != null) revealAnimator.cancel();
        if (profile == null || profile.stations.size() < 2) {
            reveal = 1f;
            invalidate();
            return;
        }
        // Only animate the first time real data appears; periodic refreshes should not flicker.
        if (hadProfile) {
            reveal = 1f;
            invalidate();
            return;
        }
        revealAnimator = ValueAnimator.ofFloat(0f, 1f);
        revealAnimator.setDuration(420);
        revealAnimator.addUpdateListener(a -> {
            reveal = (float) a.getAnimatedValue();
            invalidate();
        });
        revealAnimator.start();
    }

    public void setPlaceholder(String text) {
        placeholder = text;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        plot.set(dp(44), dp(22), w - dp(14), h - dp(30));

        if (profile == null || profile.stations.size() < 2) {
            drawEmptyState(canvas, w, h);
            return;
        }

        double minZ = profile.minElevationMeters, maxZ = profile.maxElevationMeters;
        if (maxZ - minZ < 1) { minZ -= 0.5; maxZ += 0.5; }
        double maxDistance = Math.max(1, profile.distanceMeters);

        drawGrid(canvas, minZ, maxZ);

        // Area fill with vertical gradient
        line.reset();
        fill.reset();
        for (int i = 0; i < profile.stations.size(); i++) {
            RouteProfileEngine.Station s = profile.stations.get(i);
            float x = plot.left + (plot.width()) * (float) (s.distanceMeters / maxDistance);
            float y = plot.bottom - plot.height() * (float) ((s.elevationMeters - minZ) / (maxZ - minZ));
            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, plot.bottom);
                fill.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }
        fill.lineTo(plot.left + plot.width() * reveal, plot.bottom);
        fill.close();

        fillPaint.setShader(fillShader);
        canvas.drawPath(fill, fillPaint);

        // Reveal the line progressively on first draw
        canvas.save();
        canvas.clipRect(plot.left, 0, plot.left + plot.width() * reveal, h);
        canvas.drawPath(line, linePaint);
        canvas.restore();

        drawWaypoints(canvas, maxDistance);

        // Elevation extremes
        drawExtreme(canvas, maxDistance, minZ, maxZ, true);
        drawExtreme(canvas, maxDistance, minZ, maxZ, false);

        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("elevasi (m)", dp(6), dp(14), labelPaint);
    }

    private void drawGrid(Canvas canvas, double minZ, double maxZ) {
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= GRID_STEPS; i++) {
            float y = plot.top + plot.height() * i / GRID_STEPS;
            gridPaint.setPathEffect(i == GRID_STEPS ? null : dashEffect);
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint);
            double z = maxZ - (maxZ - minZ) * i / (double) GRID_STEPS;
            canvas.drawText(String.format(Locale.getDefault(), "%.0f", z), plot.left - dp(7), y + dp(3.5f), labelPaint);
        }
        gridPaint.setPathEffect(null);

        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("0", plot.left, plot.bottom + dp(16), labelPaint);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.getDefault(), "%.2f km", profile.distanceMeters / 1000.0),
                plot.right, plot.bottom + dp(16), labelPaint);
    }

    private void drawWaypoints(Canvas canvas, double maxDistance) {
        for (RouteProfileEngine.RouteWaypoint wp : profile.waypoints) {
            float x = plot.left + plot.width() * (float) (wp.distanceMeters / maxDistance);
            if (x > plot.left + plot.width() * reveal) continue;
            gridPaint.setPathEffect(null);
            gridPaint.setColor(color(R.color.chart_marker));
            gridPaint.setAlpha(90);
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint);
            gridPaint.setColor(color(R.color.chart_grid));
            gridPaint.setAlpha(255);

            canvas.drawCircle(x, plot.top, dp(5), ringPaint);
            canvas.drawCircle(x, plot.top, dp(3.2f), markerPaint);
        }
    }

    private void drawExtreme(Canvas canvas, double maxDistance, double minZ, double maxZ, boolean highest) {
        int count = profile.stations.size();
        int index = 0;
        for (int i = 1; i < count; i++) {
            double current = profile.stations.get(i).elevationMeters;
            double best = profile.stations.get(index).elevationMeters;
            if (highest ? current > best : current < best) index = i;
        }
        RouteProfileEngine.Station s = profile.stations.get(index);
        if (s.distanceMeters / maxDistance > reveal) return;
        float x = plot.left + plot.width() * (float) (s.distanceMeters / maxDistance);
        float y = plot.bottom - plot.height() * (float) ((s.elevationMeters - minZ) / (maxZ - minZ));
        canvas.drawCircle(x, y, dp(4.6f), ringPaint);
        markerPaint.setColor(color(highest ? R.color.chart_line : R.color.chart_axis));
        canvas.drawCircle(x, y, dp(2.8f), markerPaint);
        markerPaint.setColor(color(R.color.chart_marker));

        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(color(R.color.text_muted));
        float labelY = highest ? y - dp(8) : y + dp(15);
        canvas.drawText(String.format(Locale.getDefault(), "%.0f m", s.elevationMeters), x, labelY, labelPaint);
        labelPaint.setColor(color(R.color.chart_axis));
    }

    private void drawEmptyState(Canvas canvas, float w, float h) {
        // Dashed baseline hint
        gridPaint.setPathEffect(emptyDashEffect);
        canvas.drawLine(dp(20), h / 2f, w - dp(20), h / 2f, gridPaint);
        gridPaint.setPathEffect(null);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(color(R.color.text_faint));
        labelPaint.setTextSize(sp(12.5f));
        String[] lines = placeholder.split("\n");
        float lineHeight = dp(18);
        float startY = h / 2f - (lines.length - 1) * lineHeight / 2f - dp(6);
        for (int i = 0; i < lines.length; i++)
            canvas.drawText(lines[i], w / 2f, startY + i * lineHeight, labelPaint);
        labelPaint.setTextSize(sp(10));
        labelPaint.setColor(color(R.color.chart_axis));
    }

    @Override protected void onDetachedFromWindow() {
        if (revealAnimator != null) revealAnimator.cancel();
        super.onDetachedFromWindow();
    }

    private int color(int res) { return getContext().getColor(res); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
}
