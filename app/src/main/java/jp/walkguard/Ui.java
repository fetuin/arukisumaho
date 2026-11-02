package jp.walkguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 画面部品の共通処理（XMLレイアウトを使わずコードで組み立てる）。 */
public class Ui {

    public static final int RED = Color.rgb(211, 47, 47);
    public static final int GREEN = Color.rgb(46, 125, 50);
    public static final int GRAY = Color.rgb(97, 97, 97);

    public static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    public static TextView text(Activity a, String s, float sp, boolean bold, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    public static Button button(Activity a, String s, int color) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(a, 10));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 10);
        b.setLayoutParams(lp);
        return b;
    }

    public static LinearLayout card(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 14);
        l.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(a, 12));
        bg.setStroke(dp(a, 1), Color.rgb(224, 224, 224));
        l.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 12);
        l.setLayoutParams(lp);
        return l;
    }

    private static EditText pinField(Activity a, String hint) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setGravity(Gravity.CENTER);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        return e;
    }

    /** 暗証番号を確認してから onOk を実行する。 */
    public static void askPin(Activity a, String title, Runnable onOk) {
        EditText e = pinField(a, "暗証番号");
        LinearLayout box = new LinearLayout(a);
        int p = dp(a, 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    if (Prefs.checkPin(a, e.getText().toString())) {
                        onOk.run();
                    } else {
                        Toast.makeText(a, "暗証番号が違います", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    /** 保護者用の暗証番号を設定する（2回入力）。 */
    public static void setPin(Activity a, Runnable onDone) {
        EditText e1 = pinField(a, "新しい暗証番号（4桁以上）");
        EditText e2 = pinField(a, "もう一度");
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(text(a, "見守りの停止や履歴の消去に使います。お子さんに知られないものにしてください。", 14, false, GRAY));
        box.addView(e1);
        box.addView(e2);
        new AlertDialog.Builder(a)
                .setTitle("保護者用の暗証番号")
                .setView(box)
                .setPositiveButton("設定", (d, w) -> {
                    String s1 = e1.getText().toString(), s2 = e2.getText().toString();
                    if (s1.length() < 4) {
                        Toast.makeText(a, "4桁以上にしてください", Toast.LENGTH_SHORT).show();
                    } else if (!s1.equals(s2)) {
                        Toast.makeText(a, "2回の入力が一致しません", Toast.LENGTH_SHORT).show();
                    } else {
                        Prefs.setPin(a, s1);
                        Toast.makeText(a, "暗証番号を設定しました", Toast.LENGTH_SHORT).show();
                        if (onDone != null) onDone.run();
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }
}
