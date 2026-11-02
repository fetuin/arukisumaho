package jp.walkguard;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** 警告の記録と「見守りが止まっていた期間」の記録を端末内に長期保存する。 */
public class Db extends SQLiteOpenHelper {

    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "walkguard.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE warnings (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, steps INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_warn_start ON warnings(start_ms)");
        db.execSQL("CREATE TABLE gaps (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, reason TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    // ---- 書き込み ----

    public void addWarning(long startMs, long endMs, int steps) {
        ContentValues v = new ContentValues();
        v.put("start_ms", startMs);
        v.put("end_ms", endMs);
        v.put("steps", steps);
        getWritableDatabase().insert("warnings", null, v);
    }

    public void addGap(long startMs, long endMs, String reason) {
        ContentValues v = new ContentValues();
        v.put("start_ms", startMs);
        v.put("end_ms", endMs);
        v.put("reason", reason);
        getWritableDatabase().insert("gaps", null, v);
    }

    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("warnings", null, null);
        db.delete("gaps", null, null);
    }

    // ---- 読み出し ----

    public static class Warning {
        public long start, end;
        public int steps;
    }

    public static class Gap {
        public long start, end;
        public String reason;
    }

    public int countSince(long sinceMs) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM warnings WHERE start_ms >= ?", new String[]{String.valueOf(sinceMs)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int countAll() {
        return countSince(0);
    }

    public long firstWarningTime() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MIN(start_ms) FROM warnings", null)) {
            return (c.moveToFirst() && !c.isNull(0)) ? c.getLong(0) : 0;
        }
    }

    public List<Warning> warningsSince(long sinceMs, int limit) {
        List<Warning> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ms, end_ms, steps FROM warnings WHERE start_ms >= ? ORDER BY start_ms DESC LIMIT " + limit,
                new String[]{String.valueOf(sinceMs)})) {
            while (c.moveToNext()) {
                Warning w = new Warning();
                w.start = c.getLong(0);
                w.end = c.getLong(1);
                w.steps = c.getInt(2);
                list.add(w);
            }
        }
        return list;
    }

    public List<Gap> gaps(int limit) {
        List<Gap> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ms, end_ms, reason FROM gaps ORDER BY start_ms DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                Gap g = new Gap();
                g.start = c.getLong(0);
                g.end = c.getLong(1);
                g.reason = c.getString(2);
                list.add(g);
            }
        }
        return list;
    }

    /** 今日を最後とする days 日分の [回数] 配列（古い順）。 */
    public int[] dailyCounts(int days, long[] dayStartsOut) {
        int[] counts = new int[days];
        Calendar cal = startOfToday();
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1));
        long[] starts = new long[days + 1];
        for (int i = 0; i <= days; i++) {
            starts[i] = cal.getTimeInMillis();
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        if (dayStartsOut != null) System.arraycopy(starts, 0, dayStartsOut, 0, Math.min(days, dayStartsOut.length));
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ms FROM warnings WHERE start_ms >= ?", new String[]{String.valueOf(starts[0])})) {
            while (c.moveToNext()) {
                long t = c.getLong(0);
                for (int i = 0; i < days; i++) {
                    if (t >= starts[i] && t < starts[i + 1]) { counts[i]++; break; }
                }
            }
        }
        return counts;
    }

    /** sinceMs 以降の、時間帯（0〜23時）ごとの回数。 */
    public int[] hourlyCounts(long sinceMs) {
        int[] counts = new int[24];
        Calendar cal = Calendar.getInstance();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ms FROM warnings WHERE start_ms >= ?", new String[]{String.valueOf(sinceMs)})) {
            while (c.moveToNext()) {
                cal.setTimeInMillis(c.getLong(0));
                counts[cal.get(Calendar.HOUR_OF_DAY)]++;
            }
        }
        return counts;
    }

    /** CSV（全件）。 */
    public String exportCsv() {
        StringBuilder sb = new StringBuilder();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.JAPAN);
        sb.append("種類,開始,終了,秒数,歩数/理由\n");
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ms, end_ms, steps FROM warnings ORDER BY start_ms", null)) {
            while (c.moveToNext()) {
                long s = c.getLong(0), e = c.getLong(1);
                sb.append("警告,").append(f.format(s)).append(',').append(f.format(e)).append(',')
                        .append((e - s) / 1000).append(',').append(c.getInt(2)).append('\n');
            }
        }
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ms, end_ms, reason FROM gaps ORDER BY start_ms", null)) {
            while (c.moveToNext()) {
                long s = c.getLong(0), e = c.getLong(1);
                sb.append("見守り停止,").append(f.format(s)).append(',').append(f.format(e)).append(',')
                        .append((e - s) / 1000).append(',').append(c.getString(2)).append('\n');
            }
        }
        return sb.toString();
    }

    public static Calendar startOfToday() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal;
    }
}
