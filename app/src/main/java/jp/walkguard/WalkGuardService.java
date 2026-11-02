package jp.walkguard;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

/**
 * 常駐して歩行を検知し、画面を使いながら歩いていたら警告を出し続ける。
 * 警告1回（歩き始め〜止まる・画面を消す）ごとに記録する。
 */
public class WalkGuardService extends Service implements SensorEventListener {

    public static final String EXTRA_FROM_BOOT = "from_boot";

    private static final String CHANNEL_ID = "guard";
    private static final int NOTIF_ID = 1;

    // 判定のパラメータ（固定のもの。調整できるものは Prefs / 設定画面）
    private static final long MAX_STEP_GAP_MS = 2500;   // 1歩と1歩の間がこれ以上あいたら「歩行が途切れた」
    private static final int MIN_STEPS_AFTER_OPEN = 3;  // 画面を開いてから最低これだけ歩いていること
    private static final long GAP_THRESHOLD_MS = 5 * 60 * 1000; // これ以上止まっていたら「見守り停止」と記録
    private static final long HEARTBEAT_MS = 30000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Long> stepTimes = new ArrayDeque<>();
    private long walkRunStart, lastStepTime; // いま続いている歩行の開始時刻と最後の1歩

    private SensorManager sensorManager;
    private Sensor stepSensor, accelSensor;
    private boolean accelRegistered;
    private PowerManager powerManager;
    private KeyguardManager keyguardManager;
    private AudioManager audioManager;
    private Vibrator vibrator;
    private WindowManager windowManager;

    // 警告の状態
    private boolean warning;
    private long warnStart, lastCondition, lastVibrate, lastHeartbeat;
    private int warnSteps, todayCount;
    private boolean fullScreen;
    private boolean wasScreenInUse;
    private long screenInUseSince;

    // オーバーレイ
    private LinearLayout overlay;
    private TextView overlayCounter;
    private ObjectAnimator blink;

    // 加速度センサーでの簡易歩数検出（歩数センサーが無い機種用）
    private float smoothMag = SensorManager.GRAVITY_EARTH;
    private boolean above;
    private long lastAccelStep;

    private boolean gapChecked;

    public static void start(Context c, boolean fromBoot) {
        Intent i = new Intent(c, WalkGuardService.class);
        i.putExtra(EXTRA_FROM_BOOT, fromBoot);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        createChannel();
        startForeground(NOTIF_ID, buildNotification());

        // 歩数センサーは「身体活動」の許可が必要。無ければ加速度センサーで代用する
        boolean activityOk = Build.VERSION.SDK_INT < 29 || checkSelfPermission(
                android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        stepSensor = activityOk ? sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) : null;
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(screenReceiver, f);
        updateAccelRegistration();

        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!gapChecked) {
            gapChecked = true;
            boolean fromBoot = intent != null && intent.getBooleanExtra(EXTRA_FROM_BOOT, false);
            recordGapIfAny(fromBoot);
        }
        Prefs.setLastAlive(this, System.currentTimeMillis());
        return START_STICKY;
    }

    /** 前回の稼働から間が空いていたら「見守りが止まっていた期間」として記録する。 */
    private void recordGapIfAny(boolean fromBoot) {
        long now = System.currentTimeMillis();
        long parentStop = Prefs.parentStopTime(this);
        long last = Prefs.lastAlive(this);
        if (parentStop > 0) {
            Db.get(this).addGap(parentStop, now, "保護者が暗証番号で停止");
            Prefs.setParentStopTime(this, 0);
        } else if (last > 0 && now - last > GAP_THRESHOLD_MS) {
            // 正常に電源オフされたなら、直前にシャットダウンの時刻が記録されている
            long shutdownAt = Prefs.shutdownAt(this);
            boolean poweredOff = shutdownAt > 0 && shutdownAt >= last - 60000;
            String reason = poweredOff ? "電源オフ・再起動" : "アプリが止まっていた（強制停止など）";
            Db.get(this).addGap(last, now, reason);
        }
        Prefs.setShutdownAt(this, 0);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (warning) endWarning(System.currentTimeMillis());
        sensorManager.unregisterListener(this);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        hideOverlay();
        if (Prefs.isEnabled(this)) Prefs.setLastAlive(this, System.currentTimeMillis());
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------- センサー ----------------

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = System.currentTimeMillis();
        if (e.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            // 歩数センサーは数秒まとめて遅れて届くことがあるので、
            // 届いた時刻ではなく実際に歩いた時刻（イベントの時刻）を使う
            long ageMs = (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1000000L;
            if (ageMs < 0 || ageMs > 60000) ageMs = 0; // 時刻がおかしい機種は届いた時刻で代用
            onStep(now - ageMs);
        } else if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            float mag = (float) Math.sqrt(x * x + y * y + z * z);
            smoothMag = smoothMag * 0.8f + mag * 0.2f;
            if (!above && smoothMag > SensorManager.GRAVITY_EARTH + 1.5f) {
                above = true;
                if (now - lastAccelStep > 300) {
                    lastAccelStep = now;
                    onStep(now);
                }
            } else if (above && smoothMag < SensorManager.GRAVITY_EARTH + 0.4f) {
                above = false;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void onStep(long t) {
        if (lastStepTime == 0 || t - lastStepTime > MAX_STEP_GAP_MS) walkRunStart = t; // 新しい歩行の始まり
        if (t > lastStepTime) lastStepTime = t;
        stepTimes.addLast(t);
        if (warning) warnSteps++;
    }

    /** 歩数センサーが無い機種では、画面がついている間だけ加速度センサーを使う（電池節約）。 */
    private void updateAccelRegistration() {
        boolean want = stepSensor == null && accelSensor != null && powerManager.isInteractive();
        if (want && !accelRegistered) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
            accelRegistered = true;
        } else if (!want && accelRegistered) {
            sensorManager.unregisterListener(this, accelSensor);
            accelRegistered = false;
        }
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                long now = System.currentTimeMillis();
                if (warning) endWarning(now);
                Prefs.setShutdownAt(context, now);
                Prefs.setLastAlive(context, now);
                return;
            }
            updateAccelRegistration();
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && warning) {
                endWarning(System.currentTimeMillis());
            }
        }
    };

    // ---------------- 判定ループ ----------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            while (!stepTimes.isEmpty() && now - stepTimes.peekFirst() > 60000) {
                stepTimes.pollFirst();
            }
            Context ctx = WalkGuardService.this;
            long warnAfterMs = Prefs.warnAfterSec(ctx) * 1000L;
            long stopAfterMs = Prefs.stopAfterSec(ctx) * 1000L;
            long vibrateMs = Prefs.vibrateSec(ctx) * 1000L;
            long fullMs = Prefs.fullScreenSec(ctx) * 1000L;

            boolean screenInUse = powerManager.isInteractive() && !keyguardManager.isKeyguardLocked();
            if (screenInUse && !wasScreenInUse) screenInUseSince = now;
            wasScreenInUse = screenInUse;
            int mode = audioManager.getMode();
            boolean inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;

            // 「歩いている」＝ 最後の1歩から間があいていない
            boolean walking = lastStepTime > 0 && now - lastStepTime <= MAX_STEP_GAP_MS;
            // 画面を開いたまま歩き続けている時間（歩き出し or 画面を開いた時刻の遅いほうから数える）。
            // 歩いて→立ち止まって→画面を開いた、では歩行が途切れるので数えない
            long usingWhileWalkingMs = 0;
            int stepsAfterOpen = 0;
            if (walking && screenInUse) {
                long from = Math.max(walkRunStart, screenInUseSince);
                usingWhileWalkingMs = now - from;
                for (long t : stepTimes) if (t >= from) stepsAfterOpen++;
            }
            boolean canStart = usingWhileWalkingMs >= warnAfterMs && stepsAfterOpen >= MIN_STEPS_AFTER_OPEN;

            if (walking && screenInUse && !inCall && (warning || canStart)) {
                lastCondition = now;
                if (!warning) startWarning(now, stepsAfterOpen);
            }

            if (warning) {
                if (!screenInUse || inCall) {
                    endWarning(now);
                } else if (now - lastCondition > stopAfterMs) {
                    endWarning(lastCondition);
                } else {
                    if (vibrateMs > 0 && now - lastVibrate >= vibrateMs) {
                        vibrate();
                        lastVibrate = now;
                    }
                    if (fullMs > 0 && !fullScreen && now - warnStart >= fullMs) setFullScreen(true);
                    if (overlayCounter != null) {
                        overlayCounter.setText("今日 " + todayCount + " 回目　・　" + ((now - warnStart) / 1000) + " 秒");
                    }
                }
            }

            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                lastHeartbeat = now;
                Prefs.setLastAlive(WalkGuardService.this, now);
            }
            handler.postDelayed(this, 500);
        }
    };

    private void startWarning(long now, int stepsSoFar) {
        warning = true;
        warnStart = now;
        warnSteps = stepsSoFar;
        fullScreen = false;
        todayCount = Db.get(this).countSince(Db.startOfToday().getTimeInMillis()) + 1;
        showOverlay();
        if (Prefs.vibrateSec(this) > 0) vibrate();
        lastVibrate = now;
    }

    private void endWarning(long end) {
        if (!warning) return;
        warning = false;
        if (end < warnStart) end = warnStart;
        Db.get(this).addWarning(warnStart, end, warnSteps);
        hideOverlay();
    }

    private void vibrate() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = {0, 350, 150, 350};
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    // ---------------- 警告表示 ----------------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private WindowManager.LayoutParams overlayParams(boolean full) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                full ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;
        p.alpha = 0.8f; // 新しいAndroidで、下のアプリへのタッチが通るようにするため
        return p;
    }

    private void showOverlay() {
        if (overlay != null) return;
        if (!Settings.canDrawOverlays(this)) return;

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        int pad = dp(24);
        overlay.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 211, 47, 47));
        bg.setCornerRadius(dp(20));
        overlay.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("⚠ 歩きスマホ中！");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        TextView sub = new TextView(this);
        sub.setText("立ち止まってから使おう");
        sub.setTextColor(Color.WHITE);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(8), 0, dp(8));

        overlayCounter = new TextView(this);
        overlayCounter.setTextColor(Color.argb(230, 255, 255, 255));
        overlayCounter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        overlayCounter.setGravity(Gravity.CENTER);
        overlayCounter.setText("今日 " + todayCount + " 回目");

        overlay.addView(title);
        overlay.addView(sub);
        overlay.addView(overlayCounter);

        blink = ObjectAnimator.ofFloat(title, View.ALPHA, 1f, 0.25f);
        blink.setDuration(450);
        blink.setRepeatCount(ValueAnimator.INFINITE);
        blink.setRepeatMode(ValueAnimator.REVERSE);
        blink.start();

        try {
            windowManager.addView(overlay, overlayParams(false));
        } catch (Exception e) {
            overlay = null;
            overlayCounter = null;
        }
    }

    private void setFullScreen(boolean full) {
        fullScreen = full;
        if (overlay == null) return;
        try {
            windowManager.updateViewLayout(overlay, overlayParams(full));
        } catch (Exception ignored) { }
    }

    private void hideOverlay() {
        if (blink != null) { blink.cancel(); blink = null; }
        if (overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) { }
            overlay = null;
            overlayCounter = null;
        }
    }

    // ---------------- 通知 ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "見守り中", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("歩きスマホ見守り中")
                .setContentText("歩きながら画面を使うと警告します")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
