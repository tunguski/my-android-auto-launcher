package pl.matsuo.navi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Line weather icon driven by the WMO code (Open-Meteo) and the time of day. */
public class WxIcon extends View {
    private int code = 3;
    private boolean day = true;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public WxIcon(Context c, int color, int background) {
        super(c);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        bg.setColor(background);
    }

    public void set(int code, boolean day) {
        this.code = code;
        this.day = day;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        c.save();
        c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
        c.scale(s / 100f, s / 100f);
        p.setStrokeWidth(5.5f);

        if (code <= 1) { // clear
            if (day) sun(c, 50, 50, 20, true);
            else moon(c, 50, 50, 26);
        } else if (code == 2) { // partly cloudy
            if (day) sun(c, 36, 34, 15, true);
            else moon(c, 36, 34, 18);
            cloud(c, 12, 30);
        } else if (code == 45 || code == 48) { // fog
            cloud(c, 0, 10);
            for (int i = 0; i < 3; i++)
                c.drawLine(14 + i * 6, 84 + i * 7, 86 - (2 - i) * 6, 84 + i * 7, p);
        } else {
            boolean precip = code >= 51;
            cloud(c, 0, precip ? 10 : 22);
            if (code >= 95) { // thunderstorm
                path.reset();
                path.moveTo(54, 72);
                path.lineTo(44, 85);
                path.lineTo(56, 85);
                path.lineTo(46, 98);
                c.drawPath(path, p);
            } else if ((code >= 71 && code <= 77) || code == 85 || code == 86) { // snow
                for (int i = 0; i < 3; i++) {
                    float x = 30 + i * 20, y = i == 1 ? 94 : 86;
                    c.drawLine(x - 5, y, x + 5, y, p);
                    c.drawLine(x - 3, y - 4, x + 3, y + 4, p);
                    c.drawLine(x - 3, y + 4, x + 3, y - 4, p);
                }
            } else if (precip) { // rain or drizzle
                for (int i = 0; i < 3; i++) {
                    float x = 34 + i * 18;
                    c.drawLine(x, 82, x - 5, 96, p);
                }
            }
        }
        c.restore();
    }

    private void sun(Canvas c, float x, float y, float r, boolean rays) {
        c.drawCircle(x, y, r, p);
        if (!rays) return;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            c.drawLine(
                    x + (float) Math.cos(a) * (r + 7),
                    y + (float) Math.sin(a) * (r + 7),
                    x + (float) Math.cos(a) * (r + 14),
                    y + (float) Math.sin(a) * (r + 14),
                    p);
        }
    }

    private void moon(Canvas c, float x, float y, float r) {
        path.reset();
        path.addArc(new RectF(x - r, y - r, x + r, y + r), -60, 280);
        path.arcTo(
                new RectF(x - r * 0.45f, y - r * 1.05f, x + r * 1.15f, y + r * 0.55f),
                125,
                -235,
                false);
        c.drawPath(path, p);
    }

    /** Cloud: dx shifts right, dy shifts down; the base shape spans ~8..92 x 30..70. */
    private void cloud(Canvas c, float dx, float dy) {
        path.reset();
        float b = 70 + dy * 0.6f, l = 10 + dx, r = 90 + dx * 0.2f;
        float t = dy * 0.6f;
        path.moveTo(l + 16, b);
        path.lineTo(r - 14, b);
        path.arcTo(new RectF(r - 28, b - 28, r, b), 90, -180, false);
        path.arcTo(new RectF(l + 22, 22 + t, l + 64, 64 + t), -20, -150, false);
        path.arcTo(new RectF(l, b - 32, l + 32, b), -90, -180, false);
        path.close();
        c.drawPath(path, bg);
        c.drawPath(path, p);
    }
}
