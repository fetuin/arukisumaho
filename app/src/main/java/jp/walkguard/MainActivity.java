package jp.walkguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 設定画面：許可の確認、見守りの開始・停止、履歴への入口。 */
public class MainActivity extends Activity {

    private static final int REQ_ACTIVITY = 1;
    private static final int REQ_NOTIF = 2;

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(245, 245, 245));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p * 2);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 見守りONなのに止まっていたら再開する
        if (Prefs.isEnabled(this)) WalkGuardService.start(this, false);
        render();
    }

    private boolean hasActivityPerm() {
        return Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasOverlayPerm() {
        return Settings.canDrawOverlays(this);
    }

    private boolean ignoresBatteryOpt() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean hasNotifPerm() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void render() {
        root.removeAllViews();
        boolean enabled = Prefs.isEnabled(this);

        // ---- 状態 ----
        LinearLayout status = Ui.card(this);
        status.addView(Ui.text(this, enabled ? "● 見守り中" : "○ 停止中", 24, true, enabled ? Ui.GREEN : Ui.GRAY));
        status.addView(Ui.text(this, enabled
                ? "歩きながら画面を使うと、警告とバイブでしつこく知らせます。"
                : "下の許可をすべて済ませてから「見守りを開始」を押してください。", 14, false, Ui.GRAY));

        Db db = Db.get(this);
        long today = Db.startOfToday().getTimeInMillis();
        int nToday = db.countSince(today);
        int n7 = db.countSince(today - 6L * 86400000L);
        TextView counts = Ui.text(this,
                "警告回数　今日 " + nToday + " 回 ／ 7日間 " + n7 + " 回 ／ 累計 " + db.countAll() + " 回",
                15, true, Color.BLACK);
        counts.setPadding(0, Ui.dp(this, 10), 0, 0);
        status.addView(counts);

        Button history = Ui.button(this, "警告の履歴を見る", Color.rgb(25, 118, 210));
        history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        status.addView(history);
        root.addView(status);

        // ---- 許可 ----
        LinearLayout perms = Ui.card(this);
        perms.addView(Ui.text(this, "必要な許可", 18, true, Color.BLACK));

        addPermRow(perms, "① 身体活動（歩数の検出）", hasActivityPerm(), () -> {
            if (Build.VERSION.SDK_INT >= 29) {
                requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQ_ACTIVITY);
            }
        });
        addPermRow(perms, "② 他のアプリの上に重ねて表示（警告の表示）", hasOverlayPerm(), () ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        if (!hasOverlayPerm() && Build.VERSION.SDK_INT >= 33) addRestrictedHelp(perms);
        addPermRow(perms, "③ 電池の最適化をしない（裏で止められないように）", ignoresBatteryOpt(), () -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        if (Build.VERSION.SDK_INT >= 33) {
            addPermRow(perms, "④ 通知", hasNotifPerm(), () ->
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
        }
        root.addView(perms);

        // ---- 開始・停止 ----
        LinearLayout ctl = Ui.card(this);
        if (!enabled) {
            Button start = Ui.button(this, "見守りを開始", Ui.GREEN);
            start.setOnClickListener(v -> onStartPressed());
            ctl.addView(start);
        } else {
            Button stop = Ui.button(this, "見守りを停止（暗証番号）", Ui.RED);
            stop.setOnClickListener(v -> Ui.askPin(this, "見守りを停止", () -> {
                Prefs.setEnabled(this, false);
                Prefs.setParentStopTime(this, System.currentTimeMillis());
                stopService(new Intent(this, WalkGuardService.class));
                render();
            }));
            ctl.addView(stop);
        }
        if (Prefs.hasPin(this)) {
            Button change = Ui.button(this, "暗証番号を変更", Ui.GRAY);
            change.setOnClickListener(v -> Ui.askPin(this, "今の暗証番号", () -> Ui.setPin(this, null)));
            ctl.addView(change);
        }
        root.addView(ctl);

        // ---- 判定の設定 ----
        LinearLayout tune = Ui.card(this);
        tune.addView(Ui.text(this, "警告の判定", 18, true, Color.BLACK));
        tune.addView(Ui.text(this, Prefs.hasPin(this)
                ? "変更には暗証番号が必要です。" : "暗証番号を設定すると、変更に暗証番号が必要になります。",
                12, false, Ui.GRAY));
        addSetting(tune, "警告を出すまで（画面を見ながら歩き続けた時間）",
                Prefs.warnAfterSec(this) + "秒",
                new int[]{3, 5, 8, 10, 15, 20}, v -> v + "秒",
                Prefs.warnAfterSec(this), v -> Prefs.setWarnAfterSec(this, v));
        addSetting(tune, "警告を消すまで（立ち止まってから）",
                Prefs.stopAfterSec(this) + "秒",
                new int[]{2, 3, 5, 8}, v -> v + "秒",
                Prefs.stopAfterSec(this), v -> Prefs.setStopAfterSec(this, v));
        addSetting(tune, "警告中のバイブ",
                vibLabel(Prefs.vibrateSec(this)),
                new int[]{0, 2, 3, 5, 10}, this::vibLabel,
                Prefs.vibrateSec(this), v -> Prefs.setVibrateSec(this, v));
        addSetting(tune, "警告を全画面に広げる",
                fullLabel(Prefs.fullScreenSec(this)),
                new int[]{0, 5, 10, 20, 30}, this::fullLabel,
                Prefs.fullScreenSec(this), v -> Prefs.setFullScreenSec(this, v));
        Button reset = Ui.button(this, "判定を初期設定に戻す", Ui.GRAY);
        reset.setOnClickListener(v -> withPinIfSet("初期設定に戻す", () -> {
            Prefs.setWarnAfterSec(this, 5);
            Prefs.setStopAfterSec(this, 3);
            Prefs.setVibrateSec(this, 3);
            Prefs.setFullScreenSec(this, 10);
            render();
        }));
        tune.addView(reset);
        root.addView(tune);

        // ---- 端末情報 ----
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        boolean hasStep = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null;
        TextView info = Ui.text(this, hasStep
                ? "この端末は歩数センサーで歩行を検出します。"
                : "この端末には歩数センサーが無いため、加速度センサーで歩行を推定します（精度はやや落ちます）。",
                13, false, Ui.GRAY);
        info.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), 0, 0);
        root.addView(info);

        // ---- バージョン ----
        String ver = "?";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        TextView version = Ui.text(this, getString(R.string.app_name) + "　バージョン " + ver, 13, false, Ui.GRAY);
        version.setGravity(android.view.Gravity.CENTER);
        version.setPadding(0, Ui.dp(this, 16), 0, 0);
        root.addView(version);
    }

    interface IntLabel { String of(int v); }
    interface IntSetter { void set(int v); }

    private String vibLabel(int v) { return v == 0 ? "なし" : v + "秒ごと"; }
    private String fullLabel(int v) { return v == 0 ? "広げない" : "警告から" + v + "秒後"; }

    private void withPinIfSet(String title, Runnable r) {
        if (Prefs.hasPin(this)) Ui.askPin(this, title, r);
        else r.run();
    }

    /** 設定1項目（ラベル・今の値・変更ボタン）。 */
    private void addSetting(LinearLayout parent, String label, String current,
                            int[] choices, IntLabel lab, int now, IntSetter setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 12), 0, 0);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        texts.addView(Ui.text(this, label, 14, false, Ui.GRAY));
        texts.addView(Ui.text(this, current, 17, true, Color.BLACK));
        row.addView(texts);

        Button b = Ui.button(this, "変更", Color.rgb(25, 118, 210));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> withPinIfSet(label, () -> {
            String[] items = new String[choices.length];
            int checked = -1;
            for (int i = 0; i < choices.length; i++) {
                items[i] = lab.of(choices[i]);
                if (choices[i] == now) checked = i;
            }
            new AlertDialog.Builder(this)
                    .setTitle(label)
                    .setSingleChoiceItems(items, checked, (d, which) -> {
                        setter.set(choices[which]);
                        d.dismiss();
                        render();
                    })
                    .setNegativeButton("キャンセル", null)
                    .show();
        }));
        row.addView(b);
        parent.addView(row);
    }

    private void addPermRow(LinearLayout parent, String label, boolean ok, Runnable onFix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        row.addView(Ui.text(this, (ok ? "✅ " : "⚠️ ") + label, 15, false, ok ? Color.BLACK : Ui.RED));
        if (!ok) {
            Button b = Ui.button(this, "許可する", Color.rgb(255, 143, 0));
            b.setOnClickListener(v -> onFix.run());
            row.addView(b);
        }
        parent.addView(row);
    }

    /**
     * Android 13以降は、ストアを通さずに入れたアプリの「重ねて表示」が
     * 「制限付き設定」で止められていることがあるので、解除の手順を出す。
     */
    private void addRestrictedHelp(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);
        box.setPadding(p, p, p, p);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(255, 248, 225));
        bg.setCornerRadius(Ui.dp(this, 8));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        box.setLayoutParams(lp);

        box.addView(Ui.text(this, "許可のスイッチが押せないとき", 15, true, Color.BLACK));
        box.addView(Ui.text(this,
                "Android 13以降では、ストア以外から入れたアプリのこの許可が「制限付き設定」で止められています。次の手順で解除してください。\n\n"
                        + "1. 上の「許可する」を一度押し、許可できない画面を出してから戻る\n"
                        + "2. 下の「アプリ情報を開く」を押す\n"
                        + "3. 右上の「︙」→「制限付き設定を許可」を押し、指紋や画面ロックで確認\n"
                        + "4. このアプリに戻り、もう一度「許可する」を押す\n\n"
                        + "※「︙」に項目が無いときは、手順1をもう一度行ってください。機種によって表示が少し違うことがあります。",
                14, false, Color.rgb(66, 66, 66)));

        Button open = Ui.button(this, "アプリ情報を開く", Ui.GRAY);
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        box.addView(open);
        parent.addView(box);
    }

    private void onStartPressed() {
        if (!hasActivityPerm() || !hasOverlayPerm()) {
            new AlertDialog.Builder(this)
                    .setTitle("許可が足りません")
                    .setMessage("①と②の許可が無いと、歩行の検出や警告の表示がうまくできません。先に許可してください。")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        Runnable go = () -> {
            Prefs.setEnabled(this, true);
            WalkGuardService.start(this, false);
            render();
        };
        if (!Prefs.hasPin(this)) Ui.setPin(this, go);
        else go.run();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_ACTIVITY && hasActivityPerm() && Prefs.isEnabled(this)) {
            // 歩数センサーを使えるようにサービスを入れ直す
            stopService(new Intent(this, WalkGuardService.class));
            WalkGuardService.start(this, false);
        }
        render();
    }
}
