package pl.matsuo.navi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.List;

/**
 * The shape of a driven route and nothing else: no map, no labels, no scale.
 *
 * <p>The projection itself lives in {@link Geo.Projection}, which is plain Java and unit tested;
 * this class only paints it.
 */
public class RouteView extends View {

    /** Share of the shorter side kept free around the route. */
    static final float PAD = 0.08f;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private List<double[]> points;

    public RouteView(Context c, int color, int accent, float stroke) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(stroke);
        line.setColor(color);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(accent);
    }

    public void set(List<double[]> points) {
        this.points = points;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (points == null || points.size() < 2) {
            return;
        }
        Geo.Projection p =
                new Geo.Projection(
                        points, getWidth(), getHeight(), Math.min(getWidth(), getHeight()) * PAD);

        path.reset();
        float lastX = 0;
        float lastY = 0;
        for (int i = 0; i < points.size(); i++) {
            float x = p.x(points.get(i)[1]);
            float y = p.y(points.get(i)[0]);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        c.drawPath(path, line);
        // Where the car is now.
        c.drawCircle(lastX, lastY, line.getStrokeWidth() * 1.8f, dot);
    }
}
