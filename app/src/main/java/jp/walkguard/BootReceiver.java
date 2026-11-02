package jp.walkguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 再起動・アプリ更新のあと、見守りを自動で再開する。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.isEnabled(context)) return;
        boolean fromBoot = Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction());
        try {
            WalkGuardService.start(context, fromBoot);
        } catch (Exception ignored) { }
    }
}
