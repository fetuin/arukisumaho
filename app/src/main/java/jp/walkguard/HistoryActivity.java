package jp.walkguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 警告の履歴：回数のまとめ、日別・時間帯別グラフ、一覧、見守り停止の記録。 */
public class HistoryActivity extends Activity {

    private static final int DAYS = 30;
    private static final long DAY_MS = 86400000L;

    private LinearLayout root;
    private final SimpleDateFormat fDate = new SimpleDateFormat("M/d(E)", Locale.JAPAN);
    private final SimpleDateFormat fDateTime = new SimpleDateFormat("M/d(E) HH:mm", Locale.JAPAN);
    private final SimpleDateFormat fFull = new SimpleDateFormat("yyyy/M/d", Locale.JAPAN);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(245, 245, 245));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, 0, p, p * 2);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        root.removeAllViews();
        Db db = Db.get(this);
        long today = Db.startOfToday().getTimeInMillis();

        // ---- まとめ ----
        LinearLayout sum = Ui.card(this);
        sum.addView(Ui.text(this, "警告の回数", 18, true, Color.BLACK));
        sum.addView(row("今日", db.countSince(today) + " 回"));
        sum.addView(row("7日間", db.countSince(today - 6 * DAY_MS) + " 回"));
        sum.addView(row("30日間", db.countSince(today - (DAYS - 1) * DAY_MS) + " 回"));
        sum.addView(row("累計", db.countAll() + " 回"));
        long first = db.firstWarningTime();
        if (first > 0) {
            long days = Math.max(1, (today - startOfDay(first)) / DAY_MS + 1);
            sum.addView(row("1日あたり平均", String.format(Locale.JAPAN, "%.1f 回（%s から）",
                    db.countAll() / (double) days, fFull.format(new Date(first)))));
        }
        root.addView(sum);

        // ---- 日別 ----
        LinearLayout daily = Ui.card(this);
        daily.addView(Ui.text(this, "日別（最近30日）", 18, true, Color.BLACK));
        long[] starts = new long[DAYS];
        int[] dc = db.dailyCounts(DAYS, starts);
        String[] dl = new String[DAYS];
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < DAYS; i++) {
            cal.setTimeInMillis(starts[i]);
            int d = cal.get(Calendar.DAY_OF_MONTH);
            dl[i] = (i == DAYS - 1 || (DAYS - 1 - i) % 5 == 0) ? (cal.get(Calendar.MONTH) + 1) + "/" + d : "";
        }
        BarChartView dChart = new BarChartView(this);
        dChart.setData(dc, dl, DAYS - 1);
        daily.addView(dChart);
        daily.addView(Ui.text(this, "濃い棒が今日です", 12, false, Ui.GRAY));
        root.addView(daily);

        // ---- 時間帯別 ----
        LinearLayout hourly = Ui.card(this);
        hourly.addView(Ui.text(this, "時間帯別（最近30日）", 18, true, Color.BLACK));
        int[] hc = db.hourlyCounts(today - (DAYS - 1) * DAY_MS);
        String[] hl = new String[24];
        int maxH = -1, maxV = 0;
        for (int i = 0; i < 24; i++) {
            hl[i] = (i % 3 == 0) ? i + "時" : "";
            if (hc[i] > maxV) { maxV = hc[i]; maxH = i; }
        }
        BarChartView hChart = new BarChartView(this);
        hChart.setData(hc, hl, maxH);
        hourly.addView(hChart);
        if (maxH >= 0) {
            hourly.addView(Ui.text(this, "いちばん多いのは " + maxH + "時台（" + maxV + "回）", 13, false, Ui.GRAY));
        }
        root.addView(hourly);

        // ---- 最近の警告 ----
        LinearLayout list = Ui.card(this);
        list.addView(Ui.text(this, "最近の警告（新しい順・最大200件）", 18, true, Color.BLACK));
        List<Db.Warning> ws = db.warningsSince(0, 200);
        if (ws.isEmpty()) {
            list.addView(Ui.text(this, "まだ記録はありません。", 14, false, Ui.GRAY));
        } else {
            String lastDay = "";
            for (Db.Warning w : ws) {
                String day = fDate.format(new Date(w.start));
                if (!day.equals(lastDay)) {
                    TextView h = Ui.text(this, day, 14, true, Ui.RED);
                    h.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
                    list.addView(h);
                    lastDay = day;
                }
                long sec = Math.max(1, (w.end - w.start) / 1000);
                list.addView(Ui.text(this, String.format(Locale.JAPAN, "　%tR　%s・%d歩",
                        new Date(w.start), duration(sec), w.steps), 14, false, Color.BLACK));
            }
        }
        root.addView(list);

        // ---- 見守りが止まっていた期間 ----
        LinearLayout gaps = Ui.card(this);
        gaps.addView(Ui.text(this, "見守りが止まっていた期間", 18, true, Color.BLACK));
        gaps.addView(Ui.text(this, "5分以上アプリが動いていなかったときに記録されます。", 12, false, Ui.GRAY));
        List<Db.Gap> gs = db.gaps(50);
        if (gs.isEmpty()) {
            gaps.addView(Ui.text(this, "ありません。", 14, false, Ui.GRAY));
        } else {
            for (Db.Gap g : gs) {
                long sec = (g.end - g.start) / 1000;
                TextView t = Ui.text(this, fDateTime.format(new Date(g.start)) + " 〜 " + fDateTime.format(new Date(g.end))
                        + "（" + duration(sec) + "）\n　" + g.reason, 14, false,
                        g.reason.startsWith("アプリが止まって") ? Ui.RED : Color.BLACK);
                t.setPadding(0, Ui.dp(this, 6), 0, 0);
                gaps.addView(t);
            }
        }
        root.addView(gaps);

        // ---- 操作 ----
        LinearLayout ops = Ui.card(this);
        Button share = Ui.button(this, "履歴をCSVで共有・保存", Color.rgb(25, 118, 210));
        share.setOnClickListener(v -> shareCsv());
        ops.addView(share);
        Button clear = Ui.button(this, "履歴をすべて消去（暗証番号）", Ui.GRAY);
        clear.setOnClickListener(v -> {
            if (!Prefs.hasPin(this)) {
                Toast.makeText(this, "先に暗証番号を設定してください", Toast.LENGTH_SHORT).show();
                return;
            }
            Ui.askPin(this, "履歴を消去", () -> new AlertDialog.Builder(this)
                    .setMessage("警告の履歴と見守り停止の記録をすべて消します。元に戻せません。")
                    .setPositiveButton("消去する", (d, w) -> { Db.get(this).clearAll(); render(); })
                    .setNegativeButton("やめる", null)
                    .show());
        });
        ops.addView(clear);
        root.addView(ops);
    }

    private LinearLayout row(String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, Ui.dp(this, 6), 0, 0);
        TextView a = Ui.text(this, k, 15, false, Ui.GRAY);
        a.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView b = Ui.text(this, v, 15, true, Color.BLACK);
        r.addView(a);
        r.addView(b);
        return r;
    }

    private static String duration(long sec) {
        if (sec < 60) return sec + "秒";
        if (sec < 3600) return (sec / 60) + "分" + (sec % 60 > 0 ? (sec % 60) + "秒" : "");
        if (sec < 86400) return (sec / 3600) + "時間" + ((sec % 3600) / 60) + "分";
        return (sec / 86400) + "日" + ((sec % 86400) / 3600) + "時間";
    }

    private static long startOfDay(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void shareCsv() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "歩きスマホ警告の履歴");
        i.putExtra(Intent.EXTRA_TEXT, Db.get(this).exportCsv());
        startActivity(Intent.createChooser(i, "履歴を共有"));
    }
}
