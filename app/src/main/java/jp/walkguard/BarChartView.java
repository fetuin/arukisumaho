package jp.walkguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/** シンプルな棒グラフ（日別・時間帯別の回数表示用）。 */
public class BarChartView extends View {

    private int[] values = new int[0];
    private String[] labels = new String[0];
    private int highlight = -1;

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barHi = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint();
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public BarChartView(Context c) {
        super(c);
        bar.setColor(Color.rgb(239, 154, 154));
        barHi.setColor(Ui.RED);
        base.setColor(Color.rgb(189, 189, 189));
        label.setColor(Ui.GRAY);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(sp(10));
        value.setColor(Color.BLACK);
        value.setTextAlign(Paint.Align.CENTER);
        value.setTextSize(sp(10));
        value.setFakeBoldText(true);
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    /** labels の空文字は表示しない。highlight は濃い色にする棒（-1で無し）。 */
    public void setData(int[] values, String[] labels, int highlight) {
        this.values = values;
        this.labels = labels;
        this.highlight = highlight;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 170, getResources().getDisplayMetrics());
        setMeasuredDimension(MeasureSpec.getSize(w), height);
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = values.length;
        if (n == 0) return;
        float w = getWidth(), h = getHeight();
        float labelH = sp(16), valueH = sp(14);
        float chartTop = valueH, chartBottom = h - labelH;
        int max = 1;
        for (int v : values) max = Math.max(max, v);

        float slot = w / n;
        float bw = Math.max(2f, slot * 0.7f);
        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2f;
            int v = values[i];
            if (v > 0) {
                float top = chartBottom - (chartBottom - chartTop) * v / max;
                r.set(cx - bw / 2f, top, cx + bw / 2f, chartBottom);
                c.drawRoundRect(r, 3, 3, i == highlight ? barHi : bar);
                if (n <= 31) c.drawText(String.valueOf(v), cx, top - sp(3), value);
            }
            if (labels != null && i < labels.length && labels[i] != null && !labels[i].isEmpty()) {
                c.drawText(labels[i], cx, h - sp(3), label);
            }
        }
        c.drawRect(0, chartBottom, w, chartBottom + 2, base);
    }
}
