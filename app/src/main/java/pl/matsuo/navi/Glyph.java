package pl.matsuo.navi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Calm line icons drawn in code; no colourful artwork, no brand logos. */
public class Glyph extends View {
    public static final int NAV = 0;
    public static final int WEATHER = 1;
    public static final int APPS = 2;
    public static final int WARN = 3;
    public static final int MUSIC = 4;
    public static final int ROUTE = 5;

    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public Glyph(Context c, int kind, int color) {
        super(c);
        this.kind = kind;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float ox = (getWidth() - s) / 2f, oy = (getHeight() - s) / 2f;
        c.save();
        c.translate(ox, oy);
        c.scale(s / 100f, s / 100f);
        p.setStrokeWidth(6f);
        path.reset();
        switch (kind) {
            case NAV:
                path.moveTo(50, 10);
                path.lineTo(82, 88);
                path.lineTo(50, 70);
                path.lineTo(18, 88);
                path.close();
                c.drawPath(path, p);
                break;
            case WEATHER:
                // Sun behind a cloud.
                c.drawCircle(36, 36, 16, p);
                for (int i = 0; i < 6; i++) {
                    double a = Math.PI + Math.PI * 1.1 * i / 5 - 0.3;
                    c.drawLine(
                            36 + (float) Math.cos(a) * 24,
                            36 + (float) Math.sin(a) * 24,
                            36 + (float) Math.cos(a) * 31,
                            36 + (float) Math.sin(a) * 31,
                            p);
                }
                path.moveTo(30, 82);
                path.lineTo(78, 82);
                path.arcTo(new RectF(64, 54, 92, 82), 90, -180, false);
                path.arcTo(new RectF(40, 38, 76, 74), -10, -150, false);
                path.arcTo(new RectF(18, 58, 42, 82), -90, -180, false);
                path.close();
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setColor(0xFF0C0C0C);
                c.drawPath(path, fill);
                c.drawPath(path, p);
                break;
            case WARN:
                path.moveTo(50, 10);
                path.lineTo(92, 86);
                path.lineTo(8, 86);
                path.close();
                c.drawPath(path, p);
                p.setStrokeWidth(8f);
                c.drawLine(50, 38, 50, 60, p);
                c.drawPoint(50, 73, p);
                break;
            case MUSIC:
                // A music note: two quavers joined by a beam.
                c.drawCircle(30, 76, 12, p);
                c.drawCircle(72, 68, 12, p);
                c.drawLine(42, 76, 42, 20, p);
                c.drawLine(84, 68, 84, 12, p);
                p.setStrokeWidth(9f);
                c.drawLine(42, 22, 84, 14, p);
                break;
            case ROUTE:
                // A winding road with a marker at the end.
                path.moveTo(16, 84);
                path.cubicTo(44, 72, 20, 46, 48, 38);
                path.cubicTo(72, 31, 70, 22, 84, 16);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(84, 16, 8, p);
                p.setStyle(Paint.Style.STROKE);
                break;
            case APPS:
                p.setStyle(Paint.Style.FILL);
                for (int r = 0; r < 3; r++) {
                    for (int k = 0; k < 3; k++) {
                        c.drawCircle(22 + k * 28, 22 + r * 28, 7, p);
                    }
                }
                p.setStyle(Paint.Style.STROKE);
                break;
        }
        c.restore();
    }
}
