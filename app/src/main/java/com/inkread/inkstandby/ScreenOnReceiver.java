package com.inkread.inkstandby;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 亮屏（退出待机）时更新一次。 */
public class ScreenOnReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.enabled(context)) return;
        if (!Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                && !Intent.ACTION_USER_PRESENT.equals(intent.getAction())) return;
        Log.i("InkStandby", "manifest screen-on: " + intent.getAction());
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                WallpaperApplier.applySync(context);
            } catch (Throwable t) {
                Log.w("InkStandby", "screen-on apply failed: " + t);
            } finally {
                pr.finish();
            }
        }, "inkstandby-screenon").start();
    }
}
