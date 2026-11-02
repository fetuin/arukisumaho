package jp.walkguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 設定（見守りON/OFF・暗証番号・最終稼働時刻）。 */
public class Prefs {

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("walkguard", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) { return sp(c).getBoolean("enabled", false); }
    public static void setEnabled(Context c, boolean v) { sp(c).edit().putBoolean("enabled", v).apply(); }

    public static long lastAlive(Context c) { return sp(c).getLong("last_alive", 0); }
    public static void setLastAlive(Context c, long t) { sp(c).edit().putLong("last_alive", t).apply(); }

    // ---- 判定の調整（設定画面で変更） ----

    /** 画面を開いたまま何秒歩き続けたら警告するか。 */
    public static int warnAfterSec(Context c) { return sp(c).getInt("warn_after", 5); }
    public static void setWarnAfterSec(Context c, int v) { sp(c).edit().putInt("warn_after", v).apply(); }

    /** 立ち止まってから何秒で警告を消すか。 */
    public static int stopAfterSec(Context c) { return sp(c).getInt("stop_after", 3); }
    public static void setStopAfterSec(Context c, int v) { sp(c).edit().putInt("stop_after", v).apply(); }

    /** 警告中のバイブ間隔（秒、0 = バイブなし）。 */
    public static int vibrateSec(Context c) { return sp(c).getInt("vibrate", 3); }
    public static void setVibrateSec(Context c, int v) { sp(c).edit().putInt("vibrate", v).apply(); }

    /** 警告が出てから何秒で全画面に広げるか（0 = 広げない）。 */
    public static int fullScreenSec(Context c) { return sp(c).getInt("full_screen", 10); }
    public static void setFullScreenSec(Context c, int v) { sp(c).edit().putInt("full_screen", v).apply(); }

    public static long shutdownAt(Context c) { return sp(c).getLong("shutdown_at", 0); }
    public static void setShutdownAt(Context c, long t) { sp(c).edit().putLong("shutdown_at", t).commit(); }

    /** 保護者が暗証番号で停止した時刻（0 = 停止していない）。 */
    public static long parentStopTime(Context c) { return sp(c).getLong("parent_stop", 0); }
    public static void setParentStopTime(Context c, long t) { sp(c).edit().putLong("parent_stop", t).apply(); }

    public static boolean hasPin(Context c) { return sp(c).contains("pin_hash"); }

    public static void setPin(Context c, String pin) {
        sp(c).edit().putString("pin_hash", hash(pin)).apply();
    }

    public static boolean checkPin(Context c, String pin) {
        String h = sp(c).getString("pin_hash", null);
        return h != null && h.equals(hash(pin));
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(("walkguard:" + s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }
}
